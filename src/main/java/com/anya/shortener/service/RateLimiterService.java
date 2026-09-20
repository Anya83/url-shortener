package com.anya.shortener.service;

import com.anya.shortener.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Per-caller rate limiting backed by Redis.
 *
 * <p>Redis rather than in-process counters because the limit must hold across
 * replicas: a 20-per-minute cap enforced locally becomes 20 x N behind a load
 * balancer, which is not a limit at all.
 *
 * <p>Failures are open, not closed. If Redis is unreachable the request is
 * allowed through — a limiter outage should degrade protection, not take down
 * the service it is protecting. For a limiter guarding billing or authentication
 * the opposite choice would be right.
 */
@Service
public class RateLimiterService {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterService.class);
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final StringRedisTemplate redis;
    private final RedisScript<Long> rateLimitScript;
    private final AppProperties props;

    public RateLimiterService(StringRedisTemplate redis,
                              RedisScript<Long> rateLimitScript,
                              AppProperties props) {
        this.redis = redis;
        this.rateLimitScript = rateLimitScript;
        this.props = props;
    }

    public record Decision(boolean allowed, long remaining, long retryAfterSeconds) {
    }

    private static final Decision ALLOWED_UNLIMITED = new Decision(true, Long.MAX_VALUE, 0);

    public Decision checkCreate(String callerId) {
        return check("create", callerId, props.rateLimit().createPerMinute());
    }

    public Decision checkRedirect(String callerId) {
        return check("redirect", callerId, props.rateLimit().redirectPerMinute());
    }

    private Decision check(String bucket, String callerId, int limit) {
        if (!props.rateLimit().enabled()) {
            return ALLOWED_UNLIMITED;
        }
        // The window start is folded into the key, so each window gets a fresh
        // key and expired windows are reclaimed by Redis without a sweep job.
        long windowIndex = Instant.now().getEpochSecond() / WINDOW.toSeconds();
        String key = "shortener:rl:" + bucket + ":" + callerId + ":" + windowIndex;

        try {
            Long count = redis.execute(rateLimitScript, List.of(key),
                    String.valueOf(WINDOW.toMillis()));
            if (count == null) {
                return ALLOWED_UNLIMITED;
            }
            if (count > limit) {
                long secondsIntoWindow = Instant.now().getEpochSecond() % WINDOW.toSeconds();
                return new Decision(false, 0, WINDOW.toSeconds() - secondsIntoWindow);
            }
            return new Decision(true, Math.max(0, limit - count), 0);
        } catch (DataAccessException e) {
            log.warn("Rate limit check failed for '{}', allowing request: {}", callerId, e.getMessage());
            return ALLOWED_UNLIMITED;
        }
    }
}
