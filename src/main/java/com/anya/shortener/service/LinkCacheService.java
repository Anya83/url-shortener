package com.anya.shortener.service;

import com.anya.shortener.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.LongAdder;

/**
 * Redis cache in front of the {@code short_links} collection.
 *
 * <p>The redirect path is overwhelmingly read-heavy and the value it needs is
 * tiny and immutable in practice, which is the textbook case for a cache-aside
 * read. Three details matter more than the basic pattern:
 *
 * <ul>
 *   <li><b>Negative caching.</b> A miss for a slug that does not exist is cached
 *       as a sentinel. Without it, traffic to nonexistent slugs — scanners,
 *       stale links, someone walking the keyspace — passes straight through to
 *       Mongo on every request, which is cache penetration: the cache provides
 *       no protection precisely when it is most needed.</li>
 *   <li><b>TTL jitter.</b> Entries written in the same burst would otherwise
 *       expire in the same burst, dumping the whole load onto Mongo at once. Each
 *       TTL is spread by up to ±10%.</li>
 *   <li><b>Degradation, not failure.</b> Every Redis call is wrapped. If Redis is
 *       unreachable the service keeps serving redirects from Mongo, slower but
 *       correct. A cache outage must not become a site outage.</li>
 * </ul>
 */
@Service
public class LinkCacheService {

    private static final Logger log = LoggerFactory.getLogger(LinkCacheService.class);

    private static final String KEY_PREFIX = "shortener:link:";

    /**
     * Marks a slug known not to exist. Safe as a sentinel because every cached
     * value is a normalised URL, which always begins with {@code http}.
     */
    public static final String NOT_FOUND_MARKER = "__NOT_FOUND__";

    private static final double JITTER_RATIO = 0.10;

    private final StringRedisTemplate redis;
    private final AppProperties props;

    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final LongAdder negativeHits = new LongAdder();
    private final LongAdder errors = new LongAdder();

    public LinkCacheService(StringRedisTemplate redis, AppProperties props) {
        this.redis = redis;
        this.props = props;
    }

    /**
     * Looks up a slug.
     *
     * @return {@link CacheLookup#hit(String)} for a cached URL,
     *         {@link CacheLookup#knownAbsent()} when the slug is cached as
     *         nonexistent, or {@link CacheLookup#miss()} when the cache cannot
     *         answer and the caller must consult Mongo.
     */
    public CacheLookup lookup(String slug) {
        if (!props.cache().enabled()) {
            return CacheLookup.miss();
        }
        try {
            String value = redis.opsForValue().get(key(slug));
            if (value == null) {
                misses.increment();
                return CacheLookup.miss();
            }
            if (NOT_FOUND_MARKER.equals(value)) {
                negativeHits.increment();
                return CacheLookup.knownAbsent();
            }
            hits.increment();
            return CacheLookup.hit(value);
        } catch (DataAccessException e) {
            // Falling through to Mongo is the correct response to a cache
            // failure, so this is logged and swallowed rather than propagated.
            errors.increment();
            log.warn("Redis lookup failed for slug '{}', falling back to Mongo: {}", slug, e.getMessage());
            return CacheLookup.miss();
        }
    }

    public void put(String slug, String longUrl) {
        put(slug, longUrl, null);
    }

    /**
     * Caches a link, never past {@code maxTtl}.
     *
     * <p>Expiring links pass their remaining lifetime here. Without that cap a
     * link could keep redirecting from cache for hours after its expiry, since
     * Mongo's TTL monitor deletes the document but cannot touch Redis.
     */
    public void put(String slug, String longUrl, Duration maxTtl) {
        if (!props.cache().enabled()) {
            return;
        }
        Duration ttl = props.cache().linkTtl();
        if (maxTtl != null && maxTtl.compareTo(ttl) < 0) {
            ttl = maxTtl;
        }
        if (ttl.isZero() || ttl.isNegative()) {
            return;
        }
        store(slug, longUrl, ttl);
    }

    public void putNegative(String slug) {
        if (!props.cache().enabled()) {
            return;
        }
        store(slug, NOT_FOUND_MARKER, props.cache().negativeTtl());
    }

    /**
     * Drops a slug from the cache.
     *
     * <p>Called on update and delete. Invalidating rather than rewriting avoids a
     * race where a slower writer's stale value overwrites a faster one's fresh
     * value; the next read simply repopulates from Mongo.
     */
    public void evict(String slug) {
        if (!props.cache().enabled()) {
            return;
        }
        try {
            redis.delete(key(slug));
        } catch (DataAccessException e) {
            errors.increment();
            log.warn("Redis evict failed for slug '{}': {}", slug, e.getMessage());
        }
    }

    private void store(String slug, String value, Duration baseTtl) {
        try {
            redis.opsForValue().set(key(slug), value, jitter(baseTtl));
        } catch (DataAccessException e) {
            errors.increment();
            log.warn("Redis write failed for slug '{}': {}", slug, e.getMessage());
        }
    }

    /** Spreads expiry by up to ±10% so co-written entries do not expire together. */
    private Duration jitter(Duration base) {
        long millis = base.toMillis();
        long spread = (long) (millis * JITTER_RATIO);
        if (spread <= 0) {
            return base;
        }
        return Duration.ofMillis(millis + ThreadLocalRandom.current().nextLong(-spread, spread + 1));
    }

    private String key(String slug) {
        return KEY_PREFIX + slug;
    }

    public CacheStats stats() {
        long h = hits.sum();
        long n = negativeHits.sum();
        long m = misses.sum();
        long total = h + n + m;
        double ratio = total == 0 ? 0.0 : (double) (h + n) / total;
        return new CacheStats(h, n, m, errors.sum(), ratio);
    }

    /** Outcome of a cache read. */
    public record CacheLookup(String longUrl, boolean cached, boolean absent) {

        public static CacheLookup hit(String longUrl) {
            return new CacheLookup(longUrl, true, false);
        }

        /** The slug is cached as known-nonexistent; do not query Mongo. */
        public static CacheLookup knownAbsent() {
            return new CacheLookup(null, true, true);
        }

        /** The cache could not answer; the caller must query Mongo. */
        public static CacheLookup miss() {
            return new CacheLookup(null, false, false);
        }

        public Optional<String> url() {
            return Optional.ofNullable(longUrl);
        }
    }

    public record CacheStats(long hits, long negativeHits, long misses, long errors, double hitRatio) {
    }
}
