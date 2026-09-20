package com.anya.shortener.service;

import com.anya.shortener.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Collision-free slugs from a shared Redis counter, handed out in blocks.
 *
 * <p>Two problems are solved here.
 *
 * <p><b>Redis round trips.</b> Calling {@code INCR} per link would put Redis on
 * the critical path of every create. Instead each instance reserves a block of
 * {@code counterBlockSize} IDs with one {@code INCRBY} and serves them from
 * memory, cutting Redis traffic by that factor. The cost is that unused IDs in a
 * block are lost when an instance restarts — harmless, given the address space.
 *
 * <p><b>Enumerability.</b> A raw counter produces {@code 0000001}, {@code 0000002},
 * … letting anyone walk every link in the system. Each ID is therefore mapped
 * through {@code (id * MULTIPLIER) mod 62^7}. Because the multiplier is coprime
 * to 62^7, the mapping is a bijection — still collision-free, but consecutive
 * counter values land far apart.
 *
 * <p>This is obfuscation, not secrecy: the multiplier is in the source, so the
 * mapping is invertible by anyone who reads it. Links that must be unguessable
 * should use {@link RandomSlugGenerator}.
 */
public class CounterSlugGenerator implements SlugGenerator {

    private static final Logger log = LoggerFactory.getLogger(CounterSlugGenerator.class);
    private static final String COUNTER_KEY = "shortener:slug:counter";

    /** Coprime to 62^7 = 2^7 * 31^7 (it is odd and not a multiple of 31). */
    private static final long MULTIPLIER = 1_500_450_271L;

    private final StringRedisTemplate redis;
    private final int slugLength;
    private final int blockSize;
    private final long space;

    /** Next ID to hand out from the reserved block. */
    private final AtomicLong cursor = new AtomicLong(0);
    /** Exclusive upper bound of the reserved block. */
    private volatile long blockEnd = 0;

    public CounterSlugGenerator(StringRedisTemplate redis, AppProperties props) {
        this.redis = redis;
        this.slugLength = props.slug().length();
        this.blockSize = props.slug().counterBlockSize();
        this.space = Base62Codec.capacity(slugLength);
    }

    @Override
    public String next() {
        long id = nextId();
        long scrambled = Math.floorMod(id * MULTIPLIER, space);
        return Base62Codec.encode(scrambled, slugLength);
    }

    private long nextId() {
        while (true) {
            long candidate = cursor.getAndIncrement();
            if (candidate < blockEnd) {
                return candidate;
            }
            reserveBlock();
        }
    }

    /**
     * Reserves the next block from Redis. Synchronized so that a burst of threads
     * arriving at an exhausted block produces one {@code INCRBY}, not one per
     * thread; the re-check inside the lock lets late arrivals use the block the
     * winner just reserved.
     */
    private synchronized void reserveBlock() {
        if (cursor.get() < blockEnd) {
            return;
        }
        Long end = redis.opsForValue().increment(COUNTER_KEY, blockSize);
        if (end == null) {
            throw new IllegalStateException("Redis returned no value for slug counter increment");
        }
        long start = end - blockSize;
        cursor.set(start);
        blockEnd = end;
        log.debug("Reserved slug block [{}, {})", start, end);
    }

    @Override
    public String strategy() {
        return "counter";
    }
}
