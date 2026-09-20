package com.anya.shortener.service;

import com.anya.shortener.config.AppProperties;
import com.anya.shortener.domain.ShortLink;
import com.anya.shortener.repository.ClickEventRepository;
import com.anya.shortener.repository.DailyRollupRepository;
import com.anya.shortener.repository.ShortLinkRepository;
import com.anya.shortener.web.error.SlugAlreadyExistsException;
import com.anya.shortener.web.error.SlugNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Create, resolve and manage short links. */
@Service
public class ShortLinkService {

    private static final Logger log = LoggerFactory.getLogger(ShortLinkService.class);

    /** Bounded because each attempt costs a Mongo round trip. */
    private static final int MAX_SLUG_ATTEMPTS = 5;

    private final ShortLinkRepository linkRepository;
    private final ClickEventRepository clickRepository;
    private final DailyRollupRepository rollupRepository;
    private final LinkCacheService cache;
    private final SlugGenerator slugGenerator;
    private final UrlNormalizer normalizer;
    private final AppProperties props;
    private final Set<String> reservedSlugs;

    public ShortLinkService(ShortLinkRepository linkRepository,
                            ClickEventRepository clickRepository,
                            DailyRollupRepository rollupRepository,
                            LinkCacheService cache,
                            SlugGenerator slugGenerator,
                            UrlNormalizer normalizer,
                            AppProperties props) {
        this.linkRepository = linkRepository;
        this.clickRepository = clickRepository;
        this.rollupRepository = rollupRepository;
        this.cache = cache;
        this.slugGenerator = slugGenerator;
        this.normalizer = normalizer;
        this.props = props;
        this.reservedSlugs = Set.copyOf(
                props.slug().reserved().stream().map(s -> s.toLowerCase(Locale.ROOT)).toList());
    }

    /**
     * Creates a short link, or returns the existing one for an identical URL.
     *
     * <p>De-duplication only applies to generated slugs. A caller who asked for a
     * specific alias gets that alias even if the destination is already
     * shortened, because the alias is the point of the request.
     */
    public ShortLink create(String rawUrl, String customAlias, Duration ttl, String ownerKey) {
        String normalized = normalizer.normalize(rawUrl);
        String urlHash = normalizer.hash(normalized, ownerKey);
        Instant now = Instant.now();
        Instant expiresAt = ttl == null ? null : now.plus(ttl);

        if (customAlias != null && !customAlias.isBlank()) {
            return createWithAlias(customAlias.trim(), normalized, urlHash, ownerKey, now, expiresAt);
        }

        Optional<ShortLink> existing = linkRepository.findByUrlHash(urlHash);
        if (existing.isPresent() && existing.get().isResolvable(now)) {
            log.debug("Reusing existing slug '{}' for identical URL", existing.get().getSlug());
            return existing.get();
        }

        return createWithGeneratedSlug(normalized, urlHash, ownerKey, now, expiresAt);
    }

    private ShortLink createWithAlias(String alias, String normalized, String urlHash,
                                      String ownerKey, Instant now, Instant expiresAt) {
        validateAlias(alias);
        if (linkRepository.existsById(alias)) {
            throw new SlugAlreadyExistsException(alias);
        }
        ShortLink link = new ShortLink(alias, normalized, urlHash, ownerKey, now, expiresAt);
        try {
            ShortLink saved = linkRepository.insert(link);
            cache.put(saved.getSlug(), saved.getLongUrl(), remaining(saved, now));
            return saved;
        } catch (DuplicateKeyException e) {
            // existsById above is not atomic with the insert; a concurrent
            // request can claim the alias in between. The unique _id index is
            // what actually enforces it.
            throw new SlugAlreadyExistsException(alias);
        }
    }

    /**
     * Inserts under a freshly generated slug, retrying on collision.
     *
     * <p>{@code insert} is used rather than {@code save} deliberately: {@code save}
     * upserts, so a collision would silently overwrite someone else's link
     * instead of raising {@link DuplicateKeyException}.
     */
    private ShortLink createWithGeneratedSlug(String normalized, String urlHash,
                                              String ownerKey, Instant now, Instant expiresAt) {
        for (int attempt = 1; attempt <= MAX_SLUG_ATTEMPTS; attempt++) {
            String slug = slugGenerator.next();
            if (reservedSlugs.contains(slug.toLowerCase(Locale.ROOT))) {
                continue;
            }
            ShortLink link = new ShortLink(slug, normalized, urlHash, ownerKey, now, expiresAt);
            try {
                ShortLink saved = linkRepository.insert(link);
                cache.put(saved.getSlug(), saved.getLongUrl(), remaining(saved, now));
                return saved;
            } catch (DuplicateKeyException e) {
                log.warn("Slug collision on '{}' (attempt {}/{}), retrying",
                        slug, attempt, MAX_SLUG_ATTEMPTS);
            }
        }
        throw new IllegalStateException(
                "Could not allocate a free slug after " + MAX_SLUG_ATTEMPTS + " attempts");
    }

    /**
     * Resolves a slug to its destination, reading through the cache.
     *
     * <p>This is the hot path. The ordering below is what keeps it cheap: a
     * negative cache hit returns without touching Mongo at all, and every Mongo
     * outcome — found or not — is written back so the next request is a cache hit
     * either way.
     */
    public String resolve(String slug) {
        LinkCacheService.CacheLookup cached = cache.lookup(slug);
        if (cached.absent()) {
            throw new SlugNotFoundException(slug);
        }
        if (cached.cached()) {
            return cached.longUrl();
        }

        Instant now = Instant.now();
        Optional<ShortLink> found = linkRepository.findById(slug);
        if (found.isEmpty() || !found.get().isResolvable(now)) {
            // Cached as absent so a burst against this slug costs one Mongo read,
            // not one per request.
            cache.putNegative(slug);
            throw new SlugNotFoundException(slug);
        }

        ShortLink link = found.get();
        cache.put(slug, link.getLongUrl(), remaining(link, now));
        return link.getLongUrl();
    }

    /** Fetches full metadata. Unlike {@link #resolve} this always reads Mongo. */
    public ShortLink getMetadata(String slug) {
        return linkRepository.findById(slug).orElseThrow(() -> new SlugNotFoundException(slug));
    }

    public List<ShortLink> listByOwner(String ownerKey) {
        return linkRepository.findByOwnerKeyOrderByCreatedAtDesc(ownerKey);
    }

    /** Soft-deletes a link, keeping its analytics history. */
    public void deactivate(String slug) {
        ShortLink link = getMetadata(slug);
        link.setActive(false);
        linkRepository.save(link);
        cache.evict(slug);
        log.info("Deactivated slug '{}'", slug);
    }

    /** Hard-deletes a link and every analytics record belonging to it. */
    public void delete(String slug) {
        if (!linkRepository.existsById(slug)) {
            throw new SlugNotFoundException(slug);
        }
        linkRepository.deleteById(slug);
        clickRepository.deleteBySlug(slug);
        rollupRepository.deleteBySlug(slug);
        cache.evict(slug);
        log.info("Deleted slug '{}' and its analytics", slug);
    }

    /** Remaining lifetime, or null for links that never expire. */
    private Duration remaining(ShortLink link, Instant now) {
        if (link.getExpiresAt() == null) {
            return null;
        }
        return Duration.between(now, link.getExpiresAt());
    }

    private void validateAlias(String alias) {
        if (!Base62Codec.isValid(alias)) {
            throw new com.anya.shortener.web.error.InvalidUrlException(
                    "Alias must contain only letters and digits, got '" + alias + "'");
        }
        if (alias.length() > 32) {
            throw new com.anya.shortener.web.error.InvalidUrlException(
                    "Alias must be 32 characters or fewer");
        }
        if (reservedSlugs.contains(alias.toLowerCase(Locale.ROOT))) {
            throw new SlugAlreadyExistsException(alias);
        }
    }

    public String slugStrategy() {
        return slugGenerator.strategy();
    }
}
