package com.anya.shortener.repository;

import com.anya.shortener.domain.ClickEvent;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;

public interface ClickEventRepository extends MongoRepository<ClickEvent, String> {

    long countBySlugAndTimestampBetween(String slug, Instant from, Instant to);

    void deleteBySlug(String slug);
}
