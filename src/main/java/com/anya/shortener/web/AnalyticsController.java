package com.anya.shortener.web;

import com.anya.shortener.domain.DailyRollup;
import com.anya.shortener.domain.ShortLink;
import com.anya.shortener.service.AnalyticsService;
import com.anya.shortener.service.ShortLinkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** Click analytics for a link. */
@RestController
@RequestMapping("/api/v1/links/{slug}/analytics")
@Tag(name = "Analytics", description = "Click statistics and breakdowns")
public class AnalyticsController {

    /** Bounds how much of the click collection a single request can scan. */
    private static final int MAX_RANGE_DAYS = 365;

    private final AnalyticsService analyticsService;
    private final ShortLinkService linkService;

    public AnalyticsController(AnalyticsService analyticsService, ShortLinkService linkService) {
        this.analyticsService = analyticsService;
        this.linkService = linkService;
    }

    /**
     * Full report over a time range, defaulting to the last 30 days.
     *
     * <p>Reads raw {@link com.anya.shortener.domain.ClickEvent} documents, so it
     * can only answer within the raw-event retention window. Use
     * {@link #history} for older periods.
     */
    @GetMapping
    @Operation(summary = "Click report with timeline and breakdowns")
    public AnalyticsService.AnalyticsReport report(
            @PathVariable String slug,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {

        // Resolving metadata first means an unknown slug returns 404 rather than
        // an empty, misleading zero-filled report.
        ShortLink link = linkService.getMetadata(slug);

        Instant end = to == null ? Instant.now() : to;
        Instant start = from == null ? end.minus(30, ChronoUnit.DAYS) : from;

        if (start.isAfter(end)) {
            throw new IllegalArgumentException("'from' must be before 'to'");
        }
        if (ChronoUnit.DAYS.between(start, end) > MAX_RANGE_DAYS) {
            throw new IllegalArgumentException(
                    "Range exceeds the " + MAX_RANGE_DAYS + "-day maximum");
        }
        return analyticsService.report(link, start, end);
    }

    /**
     * Pre-aggregated daily history.
     *
     * <p>Served from rollups, which are never expired, so this answers for
     * periods whose raw events have already been reclaimed.
     */
    @GetMapping("/daily")
    @Operation(summary = "Daily rollups, retained beyond the raw-event window")
    public List<DailyRollup> history(
            @PathVariable String slug,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        linkService.getMetadata(slug);

        LocalDate end = to == null ? LocalDate.now() : to;
        LocalDate start = from == null ? end.minusDays(30) : from;
        return analyticsService.history(slug, start, end);
    }
}
