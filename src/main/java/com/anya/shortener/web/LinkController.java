package com.anya.shortener.web;

import com.anya.shortener.config.AppProperties;
import com.anya.shortener.domain.ShortLink;
import com.anya.shortener.service.LinkCacheService;
import com.anya.shortener.service.RateLimiterService;
import com.anya.shortener.service.ShortLinkService;
import com.anya.shortener.web.dto.CreateLinkRequest;
import com.anya.shortener.web.dto.LinkResponse;
import com.anya.shortener.web.error.RateLimitExceededException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Duration;
import java.util.List;

/** Link management API. */
@RestController
@RequestMapping("/api/v1/links")
@Tag(name = "Links", description = "Create and manage short links")
public class LinkController {

    private final ShortLinkService linkService;
    private final RateLimiterService rateLimiter;
    private final LinkCacheService cache;
    private final ClientIpResolver ipResolver;
    private final AppProperties props;

    public LinkController(ShortLinkService linkService,
                          RateLimiterService rateLimiter,
                          LinkCacheService cache,
                          ClientIpResolver ipResolver,
                          AppProperties props) {
        this.linkService = linkService;
        this.rateLimiter = rateLimiter;
        this.cache = cache;
        this.ipResolver = ipResolver;
        this.props = props;
    }

    /**
     * Creates a short link.
     *
     * <p>{@code X-Api-Key} is an ownership scope, not authentication — it
     * partitions links and de-duplication between callers. A real deployment
     * would put Spring Security in front of this; it is left out so the project
     * stays focused on the caching and analytics design.
     */
    @PostMapping
    @Operation(summary = "Create a short link")
    public ResponseEntity<LinkResponse> create(
            @Valid @RequestBody CreateLinkRequest request,
            @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
            HttpServletRequest httpRequest) {

        String ownerKey = apiKey == null || apiKey.isBlank() ? "anonymous" : apiKey;

        // Limited by IP rather than by owner key, since the key is
        // self-asserted and a caller could otherwise mint a new one per request.
        RateLimiterService.Decision decision = rateLimiter.checkCreate(ipResolver.resolve(httpRequest));
        if (!decision.allowed()) {
            throw new RateLimitExceededException(decision.retryAfterSeconds());
        }

        Duration ttl = request.ttlSeconds() == null ? null : Duration.ofSeconds(request.ttlSeconds());
        ShortLink link = linkService.create(request.url(), request.alias(), ttl, ownerKey);
        LinkResponse body = LinkResponse.from(link, props.baseUrl());

        return ResponseEntity.created(URI.create(body.shortUrl()))
                .header("X-RateLimit-Remaining", String.valueOf(decision.remaining()))
                .body(body);
    }

    @GetMapping("/{slug}")
    @Operation(summary = "Fetch a link's metadata")
    public LinkResponse get(@PathVariable String slug) {
        return LinkResponse.from(linkService.getMetadata(slug), props.baseUrl());
    }

    @GetMapping
    @Operation(summary = "List links belonging to the calling API key")
    public List<LinkResponse> list(
            @RequestHeader(value = "X-Api-Key", required = false) String apiKey) {
        String ownerKey = apiKey == null || apiKey.isBlank() ? "anonymous" : apiKey;
        return linkService.listByOwner(ownerKey).stream()
                .map(link -> LinkResponse.from(link, props.baseUrl()))
                .toList();
    }

    /**
     * Removes a link.
     *
     * <p>Defaults to a soft delete so analytics survive. {@code ?purge=true}
     * hard-deletes the link and every click record attached to it, which is what
     * a data-deletion request needs.
     */
    @DeleteMapping("/{slug}")
    @Operation(summary = "Deactivate a link, or purge it with its analytics")
    public ResponseEntity<Void> delete(@PathVariable String slug,
                                       @RequestParam(defaultValue = "false") boolean purge) {
        if (purge) {
            linkService.delete(slug);
        } else {
            linkService.deactivate(slug);
        }
        return ResponseEntity.noContent().build();
    }

    /** Cache hit/miss counters, for demonstrating the caching layer's effect. */
    @GetMapping("/_cache-stats")
    @Operation(summary = "Cache hit ratio and counters")
    public LinkCacheService.CacheStats cacheStats() {
        return cache.stats();
    }
}
