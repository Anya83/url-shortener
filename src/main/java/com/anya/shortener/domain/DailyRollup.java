package com.anya.shortener.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDate;

/**
 * Pre-aggregated daily totals for one slug.
 *
 * <p>Raw {@link ClickEvent} documents expire after the retention window; these
 * rollups do not. That split keeps the storage bill flat while still allowing
 * "clicks per day since launch" to be answered years later.
 *
 * <p>The {@code _id} is the deterministic composite {@code slug|date}. Making the
 * key derivable means the rollup job can issue idempotent upserts — re-running it
 * over the same day overwrites rather than duplicates, so a failed run is safe to
 * retry.
 */
@Document(collection = "daily_rollups")
@CompoundIndex(name = "idx_slug_date", def = "{'slug': 1, 'date': -1}")
public class DailyRollup {

    @Id
    private String id;

    private String slug;
    private LocalDate date;
    private long clicks;
    private long uniqueVisitors;

    public DailyRollup() {
    }

    public DailyRollup(String slug, LocalDate date, long clicks, long uniqueVisitors) {
        this.id = buildId(slug, date);
        this.slug = slug;
        this.date = date;
        this.clicks = clicks;
        this.uniqueVisitors = uniqueVisitors;
    }

    public static String buildId(String slug, LocalDate date) {
        return slug + "|" + date;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public long getClicks() {
        return clicks;
    }

    public void setClicks(long clicks) {
        this.clicks = clicks;
    }

    public long getUniqueVisitors() {
        return uniqueVisitors;
    }

    public void setUniqueVisitors(long uniqueVisitors) {
        this.uniqueVisitors = uniqueVisitors;
    }
}
