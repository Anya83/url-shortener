package com.anya.shortener.web.dto;

import com.anya.shortener.domain.ShortLink;

import java.time.Instant;

public record LinkResponse(
        String slug,
        String shortUrl,
        String longUrl,
        Instant createdAt,
        Instant expiresAt,
        long totalClicks,
        boolean active
) {
    public static LinkResponse from(ShortLink link, String baseUrl) {
        return new LinkResponse(
                link.getSlug(),
                baseUrl + "/" + link.getSlug(),
                link.getLongUrl(),
                link.getCreatedAt(),
                link.getExpiresAt(),
                link.getTotalClicks(),
                link.isActive());
    }
}
