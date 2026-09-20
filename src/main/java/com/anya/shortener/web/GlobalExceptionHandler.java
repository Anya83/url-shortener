package com.anya.shortener.web;

import com.anya.shortener.web.dto.ErrorResponse;
import com.anya.shortener.web.error.InvalidUrlException;
import com.anya.shortener.web.error.RateLimitExceededException;
import com.anya.shortener.web.error.SlugAlreadyExistsException;
import com.anya.shortener.web.error.SlugNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

/** Maps domain exceptions to HTTP responses. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(SlugNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(SlugNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(404, "not_found", e.getMessage()));
    }

    @ExceptionHandler(SlugAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleConflict(SlugAlreadyExistsException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(409, "slug_taken", e.getMessage()));
    }

    @ExceptionHandler(InvalidUrlException.class)
    public ResponseEntity<ErrorResponse> handleInvalidUrl(InvalidUrlException e) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(400, "invalid_url", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(400, "bad_request", e.getMessage()));
    }

    /** 429 with {@code Retry-After}, so well-behaved clients back off correctly. */
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleRateLimit(RateLimitExceededException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.getRetryAfterSeconds()))
                .body(ErrorResponse.of(429, "rate_limited",
                        "Too many requests. Retry in " + e.getRetryAfterSeconds() + "s."));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        Map<String, String> fieldErrors = new HashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(err -> fieldErrors.put(err.getField(), err.getDefaultMessage()));
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(400, "validation_failed",
                        "Request body failed validation", fieldErrors));
    }

    /**
     * Catch-all. The exception is logged in full but never echoed to the client —
     * stack traces and driver messages leak internal structure.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("Unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(500, "internal_error", "An unexpected error occurred"));
    }
}
