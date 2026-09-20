package com.anya.shortener.repository;

import com.anya.shortener.domain.DailyRollup;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.LocalDate;
import java.util.List;

public interface DailyRollupRepository extends MongoRepository<DailyRollup, String> {

    List<DailyRollup> findBySlugAndDateBetweenOrderByDateAsc(String slug, LocalDate from, LocalDate to);

    void deleteBySlug(String slug);
}
