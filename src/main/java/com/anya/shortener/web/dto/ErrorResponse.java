package com.anya.shortener.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

/** RFC-7807-flavoured error body used by every failing endpoint. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        int status,
        String error,
        String message,
        Instant timestamp,
        Map<String, String> details
) {
    public static ErrorResponse of(int status, String error, String message) {
        return new ErrorResponse(status, error, message, Instant.now(), null);
    }

    public static ErrorResponse of(int status, String error, String message,
                                   Map<String, String> details) {
        return new ErrorResponse(status, error, message, Instant.now(), details);
    }
}
