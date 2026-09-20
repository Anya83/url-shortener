package com.anya.shortener.service;

import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Minimal user-agent classification into device, browser and OS buckets.
 *
 * <p>Deliberately heuristic rather than a full UA database. Click analytics need
 * to answer "mobile or desktop, roughly which browser" — they do not need exact
 * version detection, and a bundled UA database would add megabytes and a
 * recurring update obligation for accuracy nobody looks at.
 *
 * <p>Order matters in each chain: Edge advertises itself as Chrome and Chrome
 * advertises itself as Safari, so the most specific token is tested first.
 */
@Component
public class UserAgentParser {

    public record Parsed(String deviceType, String browser, String os) {
    }

    private static final Parsed UNKNOWN = new Parsed("unknown", "unknown", "unknown");

    public Parsed parse(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return UNKNOWN;
        }
        String ua = userAgent.toLowerCase(Locale.ROOT);
        return new Parsed(deviceType(ua), browser(ua), os(ua));
    }

    private String deviceType(String ua) {
        if (ua.contains("bot") || ua.contains("crawler") || ua.contains("spider")
                || ua.contains("curl") || ua.contains("wget")) {
            return "bot";
        }
        // "ipad" and Android tablets both report "mobile"-adjacent tokens, so
        // tablets are matched before the generic mobile check.
        if (ua.contains("ipad") || ua.contains("tablet")
                || (ua.contains("android") && !ua.contains("mobile"))) {
            return "tablet";
        }
        if (ua.contains("mobi") || ua.contains("iphone") || ua.contains("ipod")
                || ua.contains("android")) {
            return "mobile";
        }
        return "desktop";
    }

    private String browser(String ua) {
        if (ua.contains("edg/") || ua.contains("edga/") || ua.contains("edgios/")) {
            return "Edge";
        }
        if (ua.contains("opr/") || ua.contains("opera")) {
            return "Opera";
        }
        if (ua.contains("firefox") || ua.contains("fxios")) {
            return "Firefox";
        }
        if (ua.contains("chrome") || ua.contains("crios")) {
            return "Chrome";
        }
        if (ua.contains("safari")) {
            return "Safari";
        }
        return "other";
    }

    private String os(String ua) {
        if (ua.contains("windows")) {
            return "Windows";
        }
        if (ua.contains("android")) {
            return "Android";
        }
        // Checked before "mac" because iOS UAs contain "like Mac OS X".
        if (ua.contains("iphone") || ua.contains("ipad") || ua.contains("ipod")) {
            return "iOS";
        }
        if (ua.contains("mac os")) {
            return "macOS";
        }
        if (ua.contains("linux")) {
            return "Linux";
        }
        return "other";
    }
}
