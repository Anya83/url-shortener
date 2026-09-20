package com.anya.shortener.service;

import com.anya.shortener.domain.ShortLink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Write coalescing for click counts.
 *
 * <p>Incrementing {@code totalClicks} in Mongo on every redirect would put a
 * write on the hottest read path in the system, and a popular link would
 * serialise all its traffic behind contention on a single document.
 *
 * <p>Instead Redis absorbs the increments — one {@code INCR}, in memory, no disk
 * — and a scheduled flush folds them into Mongo in one bulk operation. A link
 * taking 10,000 clicks between flushes costs 10,000 Redis increments and exactly
 * one Mongo write instead of 10,000.
 *
 * <p>The trade is a bounded window of durability: counts buffered since the last
 * flush are lost if Redis dies. That is acceptable for an analytics counter and
 * is why the authoritative per-click record is written separately by
 * {@link ClickIngestService}.
 */
@Service
public class ClickCounterBuffer {

    private static final Logger log = LoggerFactory.getLogger(ClickCounterBuffer.class);

    private static final String COUNTER_PREFIX = "shortener:clicks:";
    /** Slugs with unflushed counts, so the flush never scans the keyspace. */
    private static final String DIRTY_SET = "shortener:clicks:dirty";
    /** Caps one flush cycle so a backlog cannot stall the scheduler thread. */
    private static final int FLUSH_BATCH_SIZE = 500;

    private final StringRedisTemplate redis;
    private final MongoTemplate mongo;
    private final RedisScript<Long> drainScript;

    public ClickCounterBuffer(StringRedisTemplate redis,
                              MongoTemplate mongo,
                              RedisScript<Long> drainCounterScript) {
        this.redis = redis;
        this.mongo = mongo;
        this.drainScript = drainCounterScript;
    }

    /** Records one click. Never throws — a counter failure must not fail a redirect. */
    public void increment(String slug) {
        try {
            redis.opsForValue().increment(COUNTER_PREFIX + slug);
            redis.opsForSet().add(DIRTY_SET, slug);
        } catch (DataAccessException e) {
            log.warn("Could not buffer click for '{}': {}", slug, e.getMessage());
        }
    }

    /**
     * Drains buffered counters into Mongo.
     *
     * @return number of slugs flushed
     */
    public int flush() {
        List<String> slugs;
        try {
            slugs = redis.opsForSet().pop(DIRTY_SET, FLUSH_BATCH_SIZE);
        } catch (DataAccessException e) {
            log.warn("Could not read dirty click set: {}", e.getMessage());
            return 0;
        }
        if (slugs == null || slugs.isEmpty()) {
            return 0;
        }

        BulkOperations bulk = mongo.bulkOps(BulkOperations.BulkMode.UNORDERED, ShortLink.class);
        Instant now = Instant.now();
        int flushed = 0;

        for (String slug : slugs) {
            long delta = drain(slug);
            if (delta <= 0) {
                continue;
            }
            bulk.updateOne(
                    Query.query(Criteria.where("_id").is(slug)),
                    new Update().inc("totalClicks", delta).set("lastAccessedAt", now));
            flushed++;
        }

        if (flushed == 0) {
            return 0;
        }
        try {
            bulk.execute();
            log.debug("Flushed click counts for {} slug(s)", flushed);
        } catch (DataAccessException e) {
            log.error("Bulk click flush failed for {} slug(s): {}", flushed, e.getMessage());
        }
        return flushed;
    }

    /**
     * Atomically reads and clears a slug's counter.
     *
     * <p>A separate GET then DEL would drop any increment landing between the
     * two, so both run inside one Lua call.
     */
    private long drain(String slug) {
        try {
            Long value = redis.execute(drainScript, List.of(COUNTER_PREFIX + slug));
            return value == null ? 0L : value;
        } catch (DataAccessException e) {
            log.warn("Could not drain counter for '{}': {}", slug, e.getMessage());
            return 0L;
        }
    }

    /** Buffered-but-not-yet-flushed count, for reads that want live numbers. */
    public long pending(String slug) {
        try {
            String value = redis.opsForValue().get(COUNTER_PREFIX + slug);
            return value == null ? 0L : Long.parseLong(value);
        } catch (DataAccessException | NumberFormatException e) {
            return 0L;
        }
    }
}
