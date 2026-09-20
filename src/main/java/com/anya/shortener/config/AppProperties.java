package com.anya.shortener.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * Typed binding for the {@code app.*} configuration tree.
 *
 * <p>Everything tunable about the caching, ID generation and analytics behaviour
 * is surfaced here rather than hard-coded, so the same jar can run against local
 * Docker containers or managed Atlas/Redis Cloud instances without a rebuild.
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @DefaultValue("http://localhost:8080") String baseUrl,
        @DefaultValue Slug slug,
        @DefaultValue Cache cache,
        @DefaultValue Analytics analytics,
        @DefaultValue RateLimit rateLimit,
        @DefaultValue Privacy privacy
) {

    /**
     * @param strategy         {@code counter} for Redis-sequenced slugs, {@code random}
     *                         for unguessable ones.
     * @param counterBlockSize how many IDs an instance reserves from Redis per
     *                         round trip. Larger blocks mean fewer Redis calls but
     *                         more wasted IDs on restart.
     */
    public record Slug(
            @DefaultValue("7") int length,
            @DefaultValue("counter") String strategy,
            @DefaultValue("512") int counterBlockSize,
            @DefaultValue({"api", "actuator", "health", "docs", "swagger-ui", "v3", "favicon.ico"})
            List<String> reserved
    ) {
    }

    /**
     * @param linkTtl     how long a resolved link stays cached.
     * @param negativeTtl how long a "this slug does not exist" marker stays cached.
     *                    Short on purpose: it only needs to outlive a burst.
     */
    public record Cache(
            @DefaultValue("24h") Duration linkTtl,
            @DefaultValue("2m") Duration negativeTtl,
            @DefaultValue("true") boolean enabled
    ) {
    }

    /**
     * @param rawEventRetention how long individual click documents are kept before
     *                          the Mongo TTL monitor reclaims them.
     * @param flushInterval     how often buffered Redis click counters are folded
     *                          back into Mongo.
     */
    public record Analytics(
            @DefaultValue("90d") Duration rawEventRetention,
            @DefaultValue("30s") Duration flushInterval,
            @DefaultValue("true") boolean enabled
    ) {
    }

    public record RateLimit(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("20") int createPerMinute,
            @DefaultValue("600") int redirectPerMinute
    ) {
    }

    /**
     * @param ipSalt salt mixed into visitor hashes. Must be overridden in any real
     *               deployment — a known salt makes the hashes reversible by
     *               brute force over the IPv4 space.
     */
    public record Privacy(
            @DefaultValue("local-dev-salt-change-me") String ipSalt
    ) {
    }
}
