package com.anya.shortener.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * @param url        destination to shorten. Validated properly in
 *                   {@code UrlNormalizer}; the annotation only catches an empty body.
 * @param alias      optional vanity slug. When absent one is generated.
 * @param ttlSeconds optional lifetime. When absent the link never expires.
 */
public record CreateLinkRequest(
        @NotBlank(message = "url is required")
        @Size(max = 2048, message = "url must be 2048 characters or fewer")
        String url,

        @Size(max = 32, message = "alias must be 32 characters or fewer")
        String alias,

        @Positive(message = "ttlSeconds must be positive")
        Long ttlSeconds
) {
}
