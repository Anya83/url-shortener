package com.anya.shortener.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Background jobs: draining click counters and building daily rollups.
 *
 * <p>The two jobs coordinate across instances differently, on purpose.
 *
 * <p>The <b>flush</b> needs no lock. It claims work with {@code SPOP} on a shared
 * Redis set, so each slug is handed to exactly one instance and the work shards
 * itself — more replicas simply drain the backlog faster.
 *
 * <p>The <b>rollup</b> does take a lock. It aggregates a whole day in one pass,
 * so running it on every replica would multiply the load for an identical
 * result. The lock is advisory: the job is idempotent, so the worst case if it
 * expires mid-run is duplicated effort, never corrupted data.
 */
@Component
public class AnalyticsScheduler {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsScheduler.class);

    private static final String ROLLUP_LOCK_KEY = "shortener:lock:rollup";
    private static final Duration ROLLUP_LOCK_TTL = Duration.ofMinutes(10);

    private final ClickCounterBuffer counterBuffer;
    private final AnalyticsService analyticsService;
    private final StringRedisTemplate redis;

    /** Identifies this instance so it only ever releases a lock it owns. */
    private final String instanceId = UUID.randomUUID().toString();

    @Value("${app.analytics.enabled:true}")
    private boolean analyticsEnabled;

    public AnalyticsScheduler(ClickCounterBuffer counterBuffer,
                              AnalyticsService analyticsService,
                              StringRedisTemplate redis) {
        this.counterBuffer = counterBuffer;
        this.analyticsService = analyticsService;
        this.redis = redis;
    }

    /**
     * Drains buffered click counts into Mongo.
     *
     * <p>{@code fixedDelay} rather than {@code fixedRate}: the delay is measured
     * from the end of the previous run, so a slow flush cannot cause runs to
     * overlap and contend on the same documents.
     */
    @Scheduled(fixedDelayString = "${app.analytics.flush-interval:30s}")
    public void flushClickCounters() {
        if (!analyticsEnabled) {
            return;
        }
        try {
            int flushed = counterBuffer.flush();
            if (flushed > 0) {
                log.debug("Click flush wrote {} slug(s)", flushed);
            }
        } catch (Exception e) {
            // Never propagate: an escaping exception cancels all future runs of
            // a scheduled task, silently stopping the flush forever.
            log.error("Click flush cycle failed", e);
        }
    }

    /** Rolls up the previous UTC day, shortly after midnight. */
    @Scheduled(cron = "${app.analytics.rollup-cron:0 10 0 * * *}", zone = "UTC")
    public void buildDailyRollups() {
        if (!analyticsEnabled) {
            return;
        }
        LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        if (!acquireRollupLock()) {
            log.debug("Another instance holds the rollup lock; skipping {}", yesterday);
            return;
        }
        try {
            int written = analyticsService.buildRollupsFor(yesterday);
            log.info("Built {} daily rollup(s) for {}", written, yesterday);
        } catch (Exception e) {
            log.error("Daily rollup for {} failed", yesterday, e);
        } finally {
            releaseRollupLock();
        }
    }

    /**
     * Flushes whatever is still buffered during a graceful shutdown, so a normal
     * deploy does not discard the last interval's clicks.
     */
    @PreDestroy
    public void flushOnShutdown() {
        if (!analyticsEnabled) {
            return;
        }
        try {
            int flushed = counterBuffer.flush();
            log.info("Shutdown flush wrote {} slug(s)", flushed);
        } catch (Exception e) {
            log.warn("Shutdown flush failed: {}", e.getMessage());
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logSchedulerState() {
        log.info("Analytics scheduler active (instance {})", instanceId);
    }

    private boolean acquireRollupLock() {
        try {
            Boolean acquired = redis.opsForValue()
                    .setIfAbsent(ROLLUP_LOCK_KEY, instanceId, ROLLUP_LOCK_TTL);
            return Boolean.TRUE.equals(acquired);
        } catch (DataAccessException e) {
            log.warn("Could not acquire rollup lock: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Releases the lock only if this instance still owns it. A blind {@code DEL}
     * could delete a lock another instance acquired after ours expired.
     */
    private void releaseRollupLock() {
        try {
            String owner = redis.opsForValue().get(ROLLUP_LOCK_KEY);
            if (instanceId.equals(owner)) {
                redis.delete(ROLLUP_LOCK_KEY);
            }
        } catch (DataAccessException e) {
            log.warn("Could not release rollup lock: {}", e.getMessage());
        }
    }
}
