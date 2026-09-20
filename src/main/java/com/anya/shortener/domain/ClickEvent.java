package com.anya.shortener.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * One raw click on a short link.
 *
 * <p>This is the high-volume collection: it grows with traffic, not with the
 * number of links. Two things keep it from becoming a liability:
 *
 * <ul>
 *   <li>A TTL index on {@code timestamp} expires raw events after a retention
 *       window (90 days by default). Long-term numbers live in {@link DailyRollup},
 *       which is tiny and never expires.</li>
 *   <li>The client IP is never stored. Only a salted hash is kept, which is
 *       enough to approximate unique visitors without retaining personal data.</li>
 * </ul>
 *
 * <p>The compound index matches the analytics access pattern exactly — always
 * "one slug, ordered by time, within a range" — so those queries are covered
 * rather than collection-scanned.
 */
@Document(collection = "click_events")
@CompoundIndex(name = "idx_slug_timestamp", def = "{'slug': 1, 'timestamp': -1}")
public class ClickEvent {

    @Id
    private String id;

    private String slug;

    /**
     * Doubles as the TTL anchor. {@code expireAfterSeconds} is overridden at
     * startup from {@code app.analytics.raw-event-retention} so the retention
     * window stays configurable.
     */
    @Indexed(name = "idx_timestamp_ttl", expireAfterSeconds = 7776000)
    private Instant timestamp;

    private String referrerDomain;
    private String deviceType;
    private String browser;
    private String os;
    private String countryCode;

    /** Salted SHA-256 of the client IP. Never the IP itself. */
    private String visitorHash;

    public ClickEvent() {
    }

    public ClickEvent(String slug, Instant timestamp) {
        this.slug = slug;
        this.timestamp = timestamp;
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

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public String getReferrerDomain() {
        return referrerDomain;
    }

    public void setReferrerDomain(String referrerDomain) {
        this.referrerDomain = referrerDomain;
    }

    public String getDeviceType() {
        return deviceType;
    }

    public void setDeviceType(String deviceType) {
        this.deviceType = deviceType;
    }

    public String getBrowser() {
        return browser;
    }

    public void setBrowser(String browser) {
        this.browser = browser;
    }

    public String getOs() {
        return os;
    }

    public void setOs(String os) {
        this.os = os;
    }

    public String getCountryCode() {
        return countryCode;
    }

    public void setCountryCode(String countryCode) {
        this.countryCode = countryCode;
    }

    public String getVisitorHash() {
        return visitorHash;
    }

    public void setVisitorHash(String visitorHash) {
        this.visitorHash = visitorHash;
    }
}
