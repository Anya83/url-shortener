package com.anya.shortener.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Redis wiring.
 *
 * <p>Spring Boot already auto-configures {@code StringRedisTemplate}, and every
 * value this service caches is a plain string, so no custom serializer is
 * registered. That is intentional: JDK-serialized values in Redis are opaque to
 * {@code redis-cli}, brittle across refactors, and a deserialization risk.
 */
@Configuration
public class RedisConfig {

    /**
     * Fixed-window rate limiter, as a single atomic script.
     *
     * <p>{@code INCR} and {@code PEXPIRE} must not be two round trips: between
     * them a crash or a race can leave the key with no TTL, which would pin a
     * caller at their limit forever. Running both inside one Lua invocation makes
     * the pair atomic.
     *
     * <p>This is a fixed window, not a sliding one. It admits up to 2x the limit
     * across a window boundary, which is an accepted trade for O(1) memory per
     * caller — a sliding log would store every request timestamp.
     */
    @Bean
    public RedisScript<Long> rateLimitScript() {
        String lua = """
                local current = redis.call('INCR', KEYS[1])
                if current == 1 then
                  redis.call('PEXPIRE', KEYS[1], ARGV[1])
                end
                return current
                """;
        return new DefaultRedisScript<>(lua, Long.class);
    }

    /**
     * Atomically drains a slug's buffered click count.
     *
     * <p>Reading then deleting as two calls would lose any increment that landed
     * in between. GETDEL semantics via Lua keep the flush lossless under
     * concurrent traffic.
     */
    @Bean
    public RedisScript<Long> drainCounterScript() {
        String lua = """
                local value = redis.call('GET', KEYS[1])
                if value == false then
                  return 0
                end
                redis.call('DEL', KEYS[1])
                return tonumber(value)
                """;
        return new DefaultRedisScript<>(lua, Long.class);
    }
}
