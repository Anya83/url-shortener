package com.anya.shortener.service;

import com.anya.shortener.config.AppProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CounterSlugGeneratorTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOps;

    private static AppProperties props(int blockSize) {
        return new AppProperties(
                "http://localhost:8080",
                new AppProperties.Slug(7, "counter", blockSize, List.of("api")),
                new AppProperties.Cache(Duration.ofHours(24), Duration.ofMinutes(2), true),
                new AppProperties.Analytics(Duration.ofDays(90), Duration.ofSeconds(30), true),
                new AppProperties.RateLimit(true, 20, 600),
                new AppProperties.Privacy("test-salt"));
    }

    /** Emulates Redis INCRBY over an in-memory counter. */
    private void stubRedisCounter(int blockSize) {
        AtomicLong counter = new AtomicLong(0);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString(), anyLong()))
                .thenAnswer(inv -> counter.addAndGet(inv.getArgument(1, Long.class)));
    }

    @Test
    @DisplayName("generated slugs are the configured width")
    void slugsHaveConfiguredLength() {
        stubRedisCounter(512);
        CounterSlugGenerator generator = new CounterSlugGenerator(redis, props(512));

        for (int i = 0; i < 100; i++) {
            assertThat(generator.next()).hasSize(7);
        }
    }

    @Test
    @DisplayName("a block is reserved once and served from memory, not per slug")
    void reservesBlocksRatherThanIncrementingPerSlug() {
        stubRedisCounter(512);
        CounterSlugGenerator generator = new CounterSlugGenerator(redis, props(512));

        for (int i = 0; i < 512; i++) {
            generator.next();
        }

        // 512 slugs from one block means exactly one Redis round trip.
        verify(valueOps, atMost(1)).increment(anyString(), anyLong());
    }

    @Test
    @DisplayName("the counter-to-slug mapping is a bijection, so slugs never collide")
    void producesNoCollisions() {
        stubRedisCounter(1000);
        CounterSlugGenerator generator = new CounterSlugGenerator(redis, props(1000));

        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 20_000; i++) {
            assertThat(seen.add(generator.next())).as("collision at %d", i).isTrue();
        }
    }

    @Test
    @DisplayName("consecutive slugs are not adjacent, so links are not enumerable")
    void scramblesSequentialCounters() {
        stubRedisCounter(512);
        CounterSlugGenerator generator = new CounterSlugGenerator(redis, props(512));

        String first = generator.next();
        String second = generator.next();
        String third = generator.next();

        assertThat(Base62Codec.decode(second) - Base62Codec.decode(first)).isNotEqualTo(1L);
        assertThat(Base62Codec.decode(third) - Base62Codec.decode(second)).isNotEqualTo(1L);
    }

    @Test
    @DisplayName("concurrent callers never receive the same slug")
    void isThreadSafe() throws Exception {
        stubRedisCounter(64);
        CounterSlugGenerator generator = new CounterSlugGenerator(redis, props(64));

        int threads = 8;
        int perThread = 500;
        Set<String> seen = Collections.synchronizedSet(new HashSet<>());
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        seen.add(generator.next());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(seen).hasSize(threads * perThread);
    }
}
