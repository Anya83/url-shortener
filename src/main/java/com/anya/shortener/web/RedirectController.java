package com.anya.shortener.web;

import com.anya.shortener.service.ClickIngestService;
import com.anya.shortener.service.RateLimiterService;
import com.anya.shortener.service.ShortLinkService;
import com.anya.shortener.web.error.RateLimitExceededException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * The redirect path — the only endpoint that sees production traffic volumes.
 *
 * <p>Everything here is arranged so the response leaves as early as possible:
 * resolution is a single Redis read on a cache hit, and analytics are dispatched
 * to a background executor after the response entity is built, never before.
 */
@RestController
@Tag(name = "Redirect", description = "Public short-link resolution")
public class RedirectController {

    private final ShortLinkService linkService;
    private final ClickIngestService clickIngest;
    private final RateLimiterService rateLimiter;
    private final ClientIpResolver ipResolver;

    public RedirectController(ShortLinkService linkService,
                              ClickIngestService clickIngest,
                              RateLimiterService rateLimiter,
                              ClientIpResolver ipResolver) {
        this.linkService = linkService;
        this.clickIngest = clickIngest;
        this.rateLimiter = rateLimiter;
        this.ipResolver = ipResolver;
    }

    /**
     * Resolves a slug and redirects.
     *
     * <p>Returns <b>302 Found</b>, not 301. A 301 is cached by browsers
     * indefinitely, so every click after the first would never reach this service
     * — the link could not be retargeted or revoked, and analytics would record
     * one click per browser for all time. 302 keeps the service in the loop on
     * every click, which is the entire point of a shortener with analytics.
     *
     * <p>{@code Cache-Control: no-store} is set for the same reason, since some
     * intermediaries cache 302s absent an explicit directive.
     */
    @GetMapping("/{slug:[0-9A-Za-z]{1,32}}")
    @Operation(summary = "Resolve a short link and redirect to its destination")
    public ResponseEntity<Void> redirect(@PathVariable String slug, HttpServletRequest request) {
        String clientIp = ipResolver.resolve(request);

        RateLimiterService.Decision decision = rateLimiter.checkRedirect(clientIp);
        if (!decision.allowed()) {
            throw new RateLimitExceededException(decision.retryAfterSeconds());
        }

        String longUrl = linkService.resolve(slug);

        ResponseEntity<Void> response = ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(longUrl))
                .header(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate")
                .build();

        // Header reads happen here, on the request thread: the servlet request is
        // recycled once the response commits, so the async task must be handed
        // plain values rather than the request object.
        clickIngest.record(new ClickIngestService.ClickContext(
                slug,
                request.getHeader(HttpHeaders.REFERER),
                request.getHeader(HttpHeaders.USER_AGENT),
                clientIp,
                request.getHeader("CF-IPCountry")));

        return response;
    }
}
