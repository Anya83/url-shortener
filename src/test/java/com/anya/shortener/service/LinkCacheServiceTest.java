package com.anya.shortener.service;

import com.anya.shortener.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LinkCacheServiceTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOps;

    private LinkCacheService cache;

    private static AppProperties defaults() {
        return new AppProperties(
                "http://localhost:8080",
                new AppProperties.Slug(7, "counter", 512, List.of("api")),
                new AppProperties.Cache(Duration.ofHours(24), Duration.ofMinutes(2), true),
                new AppProperties.Analytics(Duration.ofDays(90), Duration.ofSeconds(30), true),
                new AppProperties.RateLimit(true, 20, 600),
                new AppProperties.Privacy("test-salt"));
    }

    @BeforeEach
    void setUp() {
        cache = new LinkCacheService(redis, defaults());
    }

    @Test
    @DisplayName("a cached URL is returned as a hit")
    void returnsHit() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("shortener:link:abc1234")).thenReturn("https://example.com/");

        LinkCacheService.CacheLookup result = cache.lookup("abc1234");

        assertThat(result.cached()).isTrue();
        assertThat(result.absent()).isFalse();
        assertThat(result.longUrl()).isEqualTo("https://example.com/");
    }

    @Test
    @DisplayName("the sentinel is reported as known-absent, so Mongo is not queried")
    void returnsKnownAbsentForNegativeEntry() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(LinkCacheService.NOT_FOUND_MARKER);

        LinkCacheService.CacheLookup result = cache.lookup("missing");

        assertThat(result.cached()).isTrue();
        assertThat(result.absent()).isTrue();
        assertThat(result.longUrl()).isNull();
    }

    @Test
    @DisplayName("an empty cache reports a miss rather than an absence")
    void returnsMissWhenNotCached() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);

        LinkCacheService.CacheLookup result = cache.lookup("unknown");

        assertThat(result.cached()).isFalse();
        assertThat(result.absent()).isFalse();
    }

    @Test
    @DisplayName("a Redis failure degrades to a miss instead of propagating")
    void degradesOnRedisFailure() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenThrow(new QueryTimeoutException("redis down"));

        LinkCacheService.CacheLookup result = cache.lookup("abc1234");

        assertThat(result.cached()).isFalse();
        assertThat(result.absent()).isFalse();
        assertThat(cache.stats().errors()).isEqualTo(1);
    }

    @Test
    @DisplayName("a write failure is swallowed so a cache outage cannot fail a request")
    void swallowsWriteFailure() {
        when(redis.opsForValue()).thenReturn(valueOps);
        doThrow(new QueryTimeoutException("redis down"))
                .when(valueOps).set(anyString(), anyString(), any(Duration.class));

        cache.put("abc1234", "https://example.com/");

        assertThat(cache.stats().errors()).isEqualTo(1);
    }

    @Test
    @DisplayName("TTL is jittered within 10% so co-written entries do not expire together")
    void appliesJitter() {
        when(redis.opsForValue()).thenReturn(valueOps);
        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);

        cache.put("abc1234", "https://example.com/");

        verify(valueOps).set(eq("shortener:link:abc1234"), anyString(), ttl.capture());
        Duration base = Duration.ofHours(24);
        assertThat(ttl.getValue())
                .isBetween(base.minus(base.dividedBy(10)), base.plus(base.dividedBy(10)));
    }

    @Test
    @DisplayName("an expiring link is never cached past its own expiry")
    void capsTtlAtRemainingLifetime() {
        when(redis.opsForValue()).thenReturn(valueOps);
        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);

        cache.put("abc1234", "https://example.com/", Duration.ofMinutes(5));

        verify(valueOps).set(anyString(), anyString(), ttl.capture());
        assertThat(ttl.getValue()).isLessThanOrEqualTo(Duration.ofMinutes(6));
    }

    @Test
    @DisplayName("an already-expired link is not cached at all")
    void skipsCachingWhenAlreadyExpired() {
        cache.put("abc1234", "https://example.com/", Duration.ofSeconds(-1));

        verify(redis, never()).opsForValue();
    }

    @Test
    @DisplayName("hit ratio counts negative hits as hits, since both avoid Mongo")
    void hitRatioIncludesNegativeHits() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("shortener:link:a")).thenReturn("https://example.com/");
        when(valueOps.get("shortener:link:b")).thenReturn(LinkCacheService.NOT_FOUND_MARKER);
        when(valueOps.get("shortener:link:c")).thenReturn(null);

        cache.lookup("a");
        cache.lookup("b");
        cache.lookup("c");

        LinkCacheService.CacheStats stats = cache.stats();
        assertThat(stats.hits()).isEqualTo(1);
        assertThat(stats.negativeHits()).isEqualTo(1);
        assertThat(stats.misses()).isEqualTo(1);
        assertThat(stats.hitRatio()).isCloseTo(2.0 / 3.0, org.assertj.core.data.Offset.offset(0.001));
    }
}
