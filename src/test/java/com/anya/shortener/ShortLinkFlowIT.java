package com.anya.shortener;

import com.anya.shortener.domain.ShortLink;
import com.anya.shortener.repository.ClickEventRepository;
import com.anya.shortener.repository.ShortLinkRepository;
import com.anya.shortener.service.ClickCounterBuffer;
import com.anya.shortener.service.LinkCacheService;
import com.anya.shortener.service.ShortLinkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage against real MongoDB and Redis containers.
 *
 * <p>Tagged {@code integration} and excluded from {@code mvn test}, because it
 * needs a Docker daemon. Run with {@code mvn verify}.
 *
 * <p>These assertions are the ones that unit tests with mocked Redis cannot
 * make: that the cache actually absorbs the second read, that negative caching
 * spares MongoDB, and that buffered counters survive the round trip into a
 * document.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class ShortLinkFlowIT {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:7.0"));

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void wireContainers(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        // Off so the tests can drive the flush explicitly instead of racing it.
        registry.add("app.analytics.flush-interval", () -> "1h");
        registry.add("app.rate-limit.enabled", () -> "false");
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ShortLinkService linkService;
    @Autowired
    private ShortLinkRepository linkRepository;
    @Autowired
    private ClickEventRepository clickRepository;
    @Autowired
    private ClickCounterBuffer counterBuffer;
    @Autowired
    private LinkCacheService cache;
    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void reset() {
        linkRepository.deleteAll();
        clickRepository.deleteAll();
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @Test
    @DisplayName("creating a link returns 201 with a resolvable short URL")
    void createsLink() throws Exception {
        mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/some/page\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slug").isNotEmpty())
                .andExpect(jsonPath("$.longUrl").value("https://example.com/some/page"));
    }

    @Test
    @DisplayName("redirect returns 302, never 301, so clicks keep reaching the service")
    void redirectsWithFound() throws Exception {
        ShortLink link = linkService.create("https://example.com/target", null, null, "anonymous");

        mockMvc.perform(get("/" + link.getSlug()))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/target"))
                .andExpect(header().string("Cache-Control",
                        org.hamcrest.Matchers.containsString("no-store")));
    }

    @Test
    @DisplayName("the second resolve is served from Redis, not MongoDB")
    void secondResolveHitsCache() {
        ShortLink link = linkService.create("https://example.com/cached", null, null, "anonymous");
        long hitsBefore = cache.stats().hits();

        linkService.resolve(link.getSlug());
        linkService.resolve(link.getSlug());

        assertThat(cache.stats().hits()).isGreaterThan(hitsBefore);
        assertThat(redisTemplate.opsForValue().get("shortener:link:" + link.getSlug()))
                .isEqualTo("https://example.com/cached");
    }

    @Test
    @DisplayName("an unknown slug is negatively cached so repeats never reach MongoDB")
    void cachesNegativeLookups() {
        assertThat(cache.lookup("nosuch").cached()).isFalse();

        mockMvcQuietly("/nosuch");

        assertThat(redisTemplate.opsForValue().get("shortener:link:nosuch"))
                .isEqualTo(LinkCacheService.NOT_FOUND_MARKER);
        assertThat(cache.lookup("nosuch").absent()).isTrue();
    }

    @Test
    @DisplayName("shortening the same URL twice returns the same slug")
    void deduplicatesIdenticalUrls() {
        ShortLink first = linkService.create("https://example.com/dup", null, null, "anonymous");
        // Differs only by case and default port, which normalisation collapses.
        ShortLink second = linkService.create("HTTPS://Example.com:443/dup", null, null, "anonymous");

        assertThat(second.getSlug()).isEqualTo(first.getSlug());
        assertThat(linkRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("a taken alias is rejected with 409 rather than overwriting")
    void rejectsDuplicateAlias() throws Exception {
        linkService.create("https://example.com/a", "myalias", null, "anonymous");

        mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/b\",\"alias\":\"myalias\"}"))
                .andExpect(status().isConflict());

        assertThat(linkService.resolve("myalias")).isEqualTo("https://example.com/a");
    }

    @Test
    @DisplayName("buffered click counts land in MongoDB on flush")
    void flushesBufferedClicks() throws Exception {
        ShortLink link = linkService.create("https://example.com/counted", null, null, "anonymous");

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/" + link.getSlug())).andExpect(status().isFound());
        }

        // Ingestion is async, so wait for the buffer rather than asserting
        // immediately.
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(counterBuffer.pending(link.getSlug())).isEqualTo(5L));

        // Nothing has touched the document yet — that is the whole point of
        // buffering.
        assertThat(linkRepository.findById(link.getSlug()).orElseThrow().getTotalClicks()).isZero();

        counterBuffer.flush();

        assertThat(linkRepository.findById(link.getSlug()).orElseThrow().getTotalClicks())
                .isEqualTo(5L);
    }

    @Test
    @DisplayName("click events are recorded with a hashed visitor, never a raw IP")
    void recordsClickEventsWithoutRawIp() throws Exception {
        ShortLink link = linkService.create("https://example.com/tracked", null, null, "anonymous");

        mockMvc.perform(get("/" + link.getSlug())
                        .header("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 17_1 like Mac OS X)")
                        .header("Referer", "https://news.example.com/article"))
                .andExpect(status().isFound());

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(clickRepository.count()).isEqualTo(1));

        var event = clickRepository.findAll().get(0);
        assertThat(event.getSlug()).isEqualTo(link.getSlug());
        assertThat(event.getDeviceType()).isEqualTo("mobile");
        assertThat(event.getReferrerDomain()).isEqualTo("news.example.com");
        assertThat(event.getVisitorHash()).isNotNull().doesNotContain("127.0.0.1");
    }

    @Test
    @DisplayName("a deactivated link stops redirecting and is evicted from cache")
    void deactivationEvictsCache() throws Exception {
        ShortLink link = linkService.create("https://example.com/gone", null, null, "anonymous");
        linkService.resolve(link.getSlug());

        linkService.deactivate(link.getSlug());

        assertThat(redisTemplate.opsForValue().get("shortener:link:" + link.getSlug())).isNull();
        mockMvc.perform(get("/" + link.getSlug())).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("an expired link is not resolvable even though the document still exists")
    void expiredLinksStopResolving() throws Exception {
        ShortLink link = linkService.create(
                "https://example.com/temp", null, Duration.ofMillis(1), "anonymous");

        Thread.sleep(50);
        cache.evict(link.getSlug());

        mockMvc.perform(get("/" + link.getSlug())).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("analytics reports the buffered count, not the stale stored one")
    void analyticsIncludesUnflushedClicks() throws Exception {
        ShortLink link = linkService.create("https://example.com/live", null, null, "anonymous");

        mockMvc.perform(get("/" + link.getSlug())).andExpect(status().isFound());
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(counterBuffer.pending(link.getSlug())).isEqualTo(1L));

        mockMvc.perform(get("/api/v1/links/" + link.getSlug() + "/analytics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalClicks").value(1));
    }

    @Test
    @DisplayName("the root forwards to the web UI's index page")
    void rootForwardsToWebUi() throws Exception {
        // Spring's welcome-page support serves "/" by forwarding to
        // /index.html. MockMvc does not follow forwards, so the body is empty
        // here by design — the forward target is what there is to assert.
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("index.html"));
    }

    @Test
    @DisplayName("the web UI page itself is served with its content")
    void servesWebUi() throws Exception {
        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Shorten a link")));
    }

    @Test
    @DisplayName("static assets are not swallowed by the slug route")
    void staticAssetsAreNotTreatedAsSlugs() throws Exception {
        // The redirect route is /{slug:[0-9A-Za-z]{1,32}}, which excludes the
        // dot in an asset filename. If that pattern is ever widened, these
        // would start 404ing as unknown slugs instead of serving the UI.
        mockMvc.perform(get("/styles.css")).andExpect(status().isOk());
        mockMvc.perform(get("/app.js")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("adding the UI did not break slug resolution")
    void slugRouteStillResolvesAlongsideStaticFiles() throws Exception {
        ShortLink link = linkService.create("https://example.com/coexist", null, null, "anonymous");

        mockMvc.perform(get("/" + link.getSlug()))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/coexist"));
    }

    @Test
    @DisplayName("a javascript: URL is refused at creation")
    void refusesDangerousScheme() throws Exception {
        mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"javascript:alert(1)\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_url"));
    }

    private void mockMvcQuietly(String path) {
        try {
            mockMvc.perform(get(path)).andExpect(status().isNotFound());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
