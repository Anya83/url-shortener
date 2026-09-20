package com.anya.shortener.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * Determines the caller's IP, accounting for reverse proxies.
 *
 * <p>{@code X-Forwarded-For} accumulates left to right as a request crosses
 * proxies, so the original client is the <em>first</em> entry. Note that this
 * header is trivially spoofable by the client: it may only be trusted when the
 * service sits behind a proxy that overwrites it. Facing the internet directly,
 * {@code trustProxyHeaders} must stay false or any caller can forge an identity
 * and bypass rate limiting.
 */
@Component
public class ClientIpResolver {

    private final boolean trustProxyHeaders;

    public ClientIpResolver(
            @org.springframework.beans.factory.annotation.Value("${app.trust-proxy-headers:false}")
            boolean trustProxyHeaders) {
        this.trustProxyHeaders = trustProxyHeaders;
    }

    public String resolve(HttpServletRequest request) {
        if (trustProxyHeaders) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                int comma = forwarded.indexOf(',');
                return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
            }
            String realIp = request.getHeader("X-Real-IP");
            if (realIp != null && !realIp.isBlank()) {
                return realIp.trim();
            }
        }
        return request.getRemoteAddr();
    }
}
