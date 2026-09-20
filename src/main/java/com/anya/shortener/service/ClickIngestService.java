package com.anya.shortener.service;

import com.anya.shortener.config.AppProperties;
import com.anya.shortener.domain.ClickEvent;
import com.anya.shortener.repository.ClickEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Records click analytics without delaying the redirect.
 *
 * <p>Everything here runs on the bounded {@code analyticsExecutor}. The redirect
 * response is already on its way to the browser by the time this executes, so a
 * slow Mongo write costs nothing on the user-visible path — and if the queue
 * saturates, the task is dropped rather than queued indefinitely.
 *
 * <p>Two writes happen per click, for different purposes: the counter buffer
 * gives fast aggregate totals, and the {@link ClickEvent} document supports
 * breakdowns by referrer, device and time.
 */
@Service
public class ClickIngestService {

    private static final Logger log = LoggerFactory.getLogger(ClickIngestService.class);

    private final ClickEventRepository clickRepository;
    private final ClickCounterBuffer counterBuffer;
    private final UserAgentParser userAgentParser;
    private final AppProperties props;

    public ClickIngestService(ClickEventRepository clickRepository,
                              ClickCounterBuffer counterBuffer,
                              UserAgentParser userAgentParser,
                              AppProperties props) {
        this.clickRepository = clickRepository;
        this.counterBuffer = counterBuffer;
        this.userAgentParser = userAgentParser;
        this.props = props;
    }

    /**
     * Captured from the request thread before dispatching, because the servlet
     * request is recycled once the response commits — reading headers from the
     * async thread would be a use-after-free.
     */
    public record ClickContext(String slug, String referrer, String userAgent,
                               String clientIp, String countryCode) {
    }

    @Async("analyticsExecutor")
    public void record(ClickContext ctx) {
        if (!props.analytics().enabled()) {
            return;
        }
        try {
            counterBuffer.increment(ctx.slug());

            ClickEvent event = new ClickEvent(ctx.slug(), Instant.now());
            event.setReferrerDomain(extractDomain(ctx.referrer()));

            UserAgentParser.Parsed parsed = userAgentParser.parse(ctx.userAgent());
            event.setDeviceType(parsed.deviceType());
            event.setBrowser(parsed.browser());
            event.setOs(parsed.os());

            event.setCountryCode(ctx.countryCode() == null ? "unknown" : ctx.countryCode());
            event.setVisitorHash(hashVisitor(ctx.clientIp()));

            clickRepository.save(event);
        } catch (Exception e) {
            // Analytics are best-effort. Swallowing here keeps a Mongo blip from
            // filling logs with stack traces on every click.
            log.warn("Failed to record click for '{}': {}", ctx.slug(), e.toString());
        }
    }

    /**
     * Reduces a referrer URL to its host.
     *
     * <p>Only the domain is kept: full referrer URLs routinely carry session
     * tokens and search terms in their query strings, which is data this service
     * has no reason to store.
     */
    private String extractDomain(String referrer) {
        if (referrer == null || referrer.isBlank()) {
            return "direct";
        }
        try {
            String host = new URI(referrer).getHost();
            return host == null ? "direct" : host.toLowerCase(java.util.Locale.ROOT);
        } catch (URISyntaxException e) {
            return "unknown";
        }
    }

    /**
     * Salted hash of the client IP, truncated to 128 bits.
     *
     * <p>The raw IP is never persisted. The salt is what makes this meaningful:
     * an unsalted hash of an IPv4 address is trivially reversible by hashing all
     * 2^32 possibilities, so an unsalted digest would be personal data wearing a
     * disguise.
     */
    private String hashVisitor(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) {
            return "unknown";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(props.privacy().ipSalt().getBytes(StandardCharsets.UTF_8));
            byte[] hash = digest.digest(clientIp.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
