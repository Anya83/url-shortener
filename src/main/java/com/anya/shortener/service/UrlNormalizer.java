package com.anya.shortener.service;

import com.anya.shortener.web.error.InvalidUrlException;
import org.springframework.stereotype.Component;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

/**
 * Validates and canonicalises submitted URLs.
 *
 * <p>Normalisation exists so that de-duplication actually works: without it
 * {@code HTTP://Example.com} and {@code http://example.com/} would hash
 * differently and produce two slugs for one destination.
 *
 * <p>It is also the security boundary for the create endpoint. Only {@code http}
 * and {@code https} are accepted — {@code javascript:}, {@code data:} and
 * {@code file:} destinations would otherwise turn every short link into a stored
 * XSS or local-file vector the moment a browser follows the redirect.
 */
@Component
public class UrlNormalizer {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");
    private static final int MAX_URL_LENGTH = 2_048;

    /**
     * Parses, validates and canonicalises {@code raw}.
     *
     * @throws InvalidUrlException if the input is unparseable or uses a scheme
     *                             that is unsafe to redirect to.
     */
    public String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidUrlException("URL must not be empty");
        }
        String trimmed = raw.trim();
        if (trimmed.length() > MAX_URL_LENGTH) {
            throw new InvalidUrlException("URL exceeds " + MAX_URL_LENGTH + " characters");
        }

        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            throw new InvalidUrlException("Malformed URL: " + e.getReason());
        }

        if (!uri.isAbsolute()) {
            throw new InvalidUrlException(
                    "URL must be absolute and include a scheme, e.g. https://example.com");
        }

        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!ALLOWED_SCHEMES.contains(scheme)) {
            throw new InvalidUrlException(
                    "Only http and https URLs can be shortened, got '" + scheme + "'");
        }

        // Credentials embedded in a URL are the classic phishing construction:
        // in "https://apple.com@evil.com/" everything before the @ is userinfo,
        // so the browser goes to evil.com while the string reads as Apple.
        // Rejected outright rather than stripped, because silently dropping it
        // would change where the link actually points.
        if (uri.getRawUserInfo() != null || rawAuthorityHasUserInfo(uri)) {
            throw new InvalidUrlException("URLs containing embedded credentials are not accepted");
        }

        int port = uri.getPort();
        String host = uri.getHost();
        if (host == null) {
            // URI treats a non-ASCII authority as non-server-based and returns a
            // null host, which would reject every internationalised domain. The
            // authority still holds the value, so it is parsed directly.
            HostAndPort parsed = parseAuthority(uri.getAuthority());
            host = parsed.host();
            if (parsed.port() != -1) {
                port = parsed.port();
            }
        }
        if (host.isBlank()) {
            throw new InvalidUrlException("URL must include a host");
        }

        // Punycode so that visually identical internationalised hosts collapse
        // to one canonical form.
        try {
            host = IDN.toASCII(host).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            throw new InvalidUrlException("Host is not a valid domain name: " + host);
        }
        if (isDefaultPort(scheme, port)) {
            port = -1;
        }

        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }

        StringBuilder sb = new StringBuilder(scheme).append("://").append(host);
        if (port != -1) {
            sb.append(':').append(port);
        }
        sb.append(path);
        if (uri.getRawQuery() != null) {
            sb.append('?').append(uri.getRawQuery());
        }
        // The fragment is deliberately dropped: it never reaches the server, so
        // two URLs differing only by fragment are the same destination to us.
        return sb.toString();
    }

    private boolean isDefaultPort(String scheme, int port) {
        return ("http".equals(scheme) && port == 80)
                || ("https".equals(scheme) && port == 443);
    }

    private record HostAndPort(String host, int port) {
    }

    /**
     * Splits an authority that {@link URI} refused to parse into host and port.
     *
     * <p>Only reached for internationalised hosts. IPv6 literals are always
     * ASCII, so {@link URI} parses them itself and they never arrive here; the
     * bracket check below is defensive.
     */
    private HostAndPort parseAuthority(String authority) {
        if (authority == null || authority.isBlank()) {
            throw new InvalidUrlException("URL must include a host");
        }
        String hostPort = authority;
        int closingBracket = hostPort.lastIndexOf(']');
        int colon = hostPort.lastIndexOf(':');

        if (colon > closingBracket && colon >= 0) {
            String portPart = hostPort.substring(colon + 1);
            try {
                int parsedPort = Integer.parseInt(portPart);
                return new HostAndPort(hostPort.substring(0, colon), parsedPort);
            } catch (NumberFormatException e) {
                throw new InvalidUrlException("Invalid port in URL: " + portPart);
            }
        }
        return new HostAndPort(hostPort, -1);
    }

    /**
     * Catches credentials in an authority that {@link URI} could not parse, where
     * {@code getRawUserInfo()} returns null even though an {@code @} is present.
     */
    private boolean rawAuthorityHasUserInfo(URI uri) {
        String authority = uri.getRawAuthority();
        return authority != null && authority.indexOf('@') >= 0;
    }

    /**
     * SHA-256 of the normalised URL, scoped by owner.
     *
     * <p>The URL itself is not indexed because Mongo caps index keys at 1024
     * bytes and URLs can exceed that. A fixed-width hash sidesteps the limit and
     * keeps the index small.
     *
     * <p>The owner is length-prefixed rather than joined by a separator, so an
     * owner key that happens to contain the separator cannot be crafted to
     * collide with a different owner/URL pair.
     */
    public String hash(String normalizedUrl, String ownerKey) {
        String owner = ownerKey == null ? "" : ownerKey;
        String payload = owner.length() + ":" + owner + normalizedUrl;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(
                    "SHA-256 is required by the JDK spec but unavailable", e);
        }
    }
}
