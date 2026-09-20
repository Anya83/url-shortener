package com.anya.shortener.service;

import com.anya.shortener.domain.ClickEvent;
import com.anya.shortener.domain.DailyRollup;
import com.anya.shortener.domain.ShortLink;
import com.anya.shortener.repository.DailyRollupRepository;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.aggregation.DateOperators;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-side analytics, answered with MongoDB aggregation pipelines.
 *
 * <p>Every pipeline opens with a {@code $match} on {@code slug} plus a timestamp
 * range, which is exactly the shape of the {@code idx_slug_timestamp} compound
 * index. That ordering is what keeps these queries index-backed instead of
 * collection scans — a {@code $group} placed before the {@code $match} would
 * defeat the index entirely.
 */
@Service
public class AnalyticsService {

    private static final int TOP_N = 10;

    private final MongoTemplate mongo;
    private final DailyRollupRepository rollupRepository;
    private final ClickCounterBuffer counterBuffer;

    public AnalyticsService(MongoTemplate mongo,
                            DailyRollupRepository rollupRepository,
                            ClickCounterBuffer counterBuffer) {
        this.mongo = mongo;
        this.rollupRepository = rollupRepository;
        this.counterBuffer = counterBuffer;
    }

    public record Bucket(String key, long count) {
    }

    public record TimePoint(String date, long clicks) {
    }

    public record AnalyticsReport(
            String slug,
            long totalClicks,
            long clicksInRange,
            long uniqueVisitors,
            Instant from,
            Instant to,
            List<TimePoint> timeline,
            List<Bucket> topReferrers,
            List<Bucket> devices,
            List<Bucket> browsers,
            List<Bucket> countries
    ) {
    }

    /**
     * Builds the full report for one slug over a time range.
     *
     * <p>{@code totalClicks} adds the counts still buffered in Redis to the value
     * stored on the document. Without that the number would visibly lag by up to
     * one flush interval, which looks like a bug to anyone clicking their own
     * link and refreshing.
     */
    public AnalyticsReport report(ShortLink link, Instant from, Instant to) {
        String slug = link.getSlug();
        Criteria range = Criteria.where("slug").is(slug)
                .and("timestamp").gte(from).lt(to);

        return new AnalyticsReport(
                slug,
                link.getTotalClicks() + counterBuffer.pending(slug),
                countInRange(range),
                uniqueVisitors(range),
                from,
                to,
                timeline(range),
                topBuckets(range, "referrerDomain"),
                topBuckets(range, "deviceType"),
                topBuckets(range, "browser"),
                topBuckets(range, "countryCode")
        );
    }

    private long countInRange(Criteria range) {
        return mongo.count(new org.springframework.data.mongodb.core.query.Query(range), ClickEvent.class);
    }

    /**
     * Counts distinct visitors.
     *
     * <p>Grouped then counted rather than collected with {@code $addToSet},
     * because a set of every visitor hash for a busy link would push the pipeline
     * past MongoDB's 16 MB document limit.
     */
    private long uniqueVisitors(Criteria range) {
        Aggregation agg = Aggregation.newAggregation(
                Aggregation.match(range),
                Aggregation.group("visitorHash"),
                Aggregation.count().as("total"));
        AggregationResults<Document> results =
                mongo.aggregate(agg, ClickEvent.class, Document.class);
        Document first = results.getUniqueMappedResult();
        return first == null ? 0L : ((Number) first.get("total")).longValue();
    }

    private List<TimePoint> timeline(Criteria range) {
        Aggregation agg = Aggregation.newAggregation(
                Aggregation.match(range),
                Aggregation.project()
                        .and(DateOperators.DateToString.dateOf("timestamp")
                                .toString("%Y-%m-%d")).as("day"),
                Aggregation.group("day").count().as("clicks"),
                Aggregation.sort(Sort.Direction.ASC, "_id"));

        AggregationResults<Document> results =
                mongo.aggregate(agg, ClickEvent.class, Document.class);
        List<TimePoint> points = new ArrayList<>();
        for (Document doc : results.getMappedResults()) {
            points.add(new TimePoint(doc.getString("_id"), ((Number) doc.get("clicks")).longValue()));
        }
        return points;
    }

    private List<Bucket> topBuckets(Criteria range, String field) {
        Aggregation agg = Aggregation.newAggregation(
                Aggregation.match(range),
                Aggregation.group(field).count().as("count"),
                Aggregation.sort(Sort.Direction.DESC, "count"),
                Aggregation.limit(TOP_N));

        AggregationResults<Document> results =
                mongo.aggregate(agg, ClickEvent.class, Document.class);
        List<Bucket> buckets = new ArrayList<>();
        for (Document doc : results.getMappedResults()) {
            Object key = doc.get("_id");
            buckets.add(new Bucket(key == null ? "unknown" : key.toString(),
                    ((Number) doc.get("count")).longValue()));
        }
        return buckets;
    }

    /**
     * Collapses one day of raw events into {@link DailyRollup} documents.
     *
     * <p>This is what lets raw events expire while history survives. Unique
     * visitors are counted with a two-stage group — first by (slug, visitor) to
     * deduplicate, then by slug to count — for the same 16 MB reason as above.
     *
     * @return how many rollup documents were written
     */
    public int buildRollupsFor(LocalDate day) {
        Instant start = day.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant end = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Criteria range = Criteria.where("timestamp").gte(start).lt(end);

        Aggregation agg = Aggregation.newAggregation(
                Aggregation.match(range),
                Aggregation.group("slug", "visitorHash").count().as("hits"),
                Aggregation.group("_id.slug")
                        .sum("hits").as("clicks")
                        .count().as("uniqueVisitors"));

        AggregationResults<Document> results =
                mongo.aggregate(agg, ClickEvent.class, Document.class);

        List<DailyRollup> rollups = new ArrayList<>();
        for (Document doc : results.getMappedResults()) {
            String slug = doc.getString("_id");
            if (slug == null) {
                continue;
            }
            rollups.add(new DailyRollup(
                    slug,
                    day,
                    ((Number) doc.get("clicks")).longValue(),
                    ((Number) doc.get("uniqueVisitors")).longValue()));
        }
        if (rollups.isEmpty()) {
            return 0;
        }
        // saveAll upserts on the deterministic slug|date id, so re-running this
        // for a day that was already processed overwrites rather than duplicates.
        rollupRepository.saveAll(rollups);
        return rollups.size();
    }

    public List<DailyRollup> history(String slug, LocalDate from, LocalDate to) {
        return rollupRepository.findBySlugAndDateBetweenOrderByDateAsc(slug, from, to);
    }
}
