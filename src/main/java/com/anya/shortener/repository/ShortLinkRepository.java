package com.anya.shortener.repository;

import com.anya.shortener.domain.ShortLink;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface ShortLinkRepository extends MongoRepository<ShortLink, String> {

    /** Backs "shortening the same URL twice returns the same slug". */
    Optional<ShortLink> findByUrlHash(String urlHash);

    List<ShortLink> findByOwnerKeyOrderByCreatedAtDesc(String ownerKey);

    long countByOwnerKey(String ownerKey);
}
