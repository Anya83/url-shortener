package com.anya.shortener.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

/**
 * A single short link.
 *
 * <p>The slug <em>is</em> the Mongo {@code _id}. That is deliberate: the redirect
 * path looks links up by slug on every request, and using it as the primary key
 * means those lookups hit the clustered {@code _id} index directly instead of a
 * secondary index, saving one index traversal on the hottest query in the system.
 *
 * <p>{@code totalClicks} is denormalised. It is not incremented on the redirect
 * path — Redis absorbs the increments and a scheduled flush folds them into this
 * field in batches. See {@code ClickCounterBuffer}.
 */
@Document(collection = "short_links")
public class ShortLink {

    @Id
    private String slug;

    @Field("longUrl")
    private String longUrl;

    /**
     * SHA-256 of the normalised long URL, scoped per owner. Backs the "same URL
     * returns the same slug" de-duplication without indexing the full URL, which
     * can exceed Mongo's 1024-byte index key limit.
     */
    @Indexed(name = "idx_url_hash")
    private String urlHash;

    /** Identifies who created the link. {@code "anonymous"} when unauthenticated. */
    @Indexed(name = "idx_owner")
    private String ownerKey;

    private Instant createdAt;

    /**
     * Optional expiry. The TTL index on this field lets Mongo reclaim expired
     * links on its own, so no cleanup job is needed. Documents with a null value
     * are never expired by the TTL monitor.
     */
    @Indexed(name = "idx_expires_at_ttl", expireAfterSeconds = 0)
    private Instant expiresAt;

    /** Soft delete. Disabled links stop redirecting but keep their analytics. */
    private boolean active = true;

    private long totalClicks;

    private Instant lastAccessedAt;

    /** Guards against lost updates when two flush cycles overlap. */
    @Version
    private Long version;

    public ShortLink() {
    }

    public ShortLink(String slug, String longUrl, String urlHash, String ownerKey,
                     Instant createdAt, Instant expiresAt) {
        this.slug = slug;
        this.longUrl = longUrl;
        this.urlHash = urlHash;
        this.ownerKey = ownerKey;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.active = true;
        this.totalClicks = 0L;
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && expiresAt.isBefore(now);
    }

    /** True when the link should still serve redirects. */
    public boolean isResolvable(Instant now) {
        return active && !isExpired(now);
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public String getLongUrl() {
        return longUrl;
    }

    public void setLongUrl(String longUrl) {
        this.longUrl = longUrl;
    }

    public String getUrlHash() {
        return urlHash;
    }

    public void setUrlHash(String urlHash) {
        this.urlHash = urlHash;
    }

    public String getOwnerKey() {
        return ownerKey;
    }

    public void setOwnerKey(String ownerKey) {
        this.ownerKey = ownerKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public long getTotalClicks() {
        return totalClicks;
    }

    public void setTotalClicks(long totalClicks) {
        this.totalClicks = totalClicks;
    }

    public Instant getLastAccessedAt() {
        return lastAccessedAt;
    }

    public void setLastAccessedAt(Instant lastAccessedAt) {
        this.lastAccessedAt = lastAccessedAt;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
