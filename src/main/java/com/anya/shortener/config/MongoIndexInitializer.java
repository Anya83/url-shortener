package com.anya.shortener.config;

import com.anya.shortener.domain.ClickEvent;
import com.anya.shortener.domain.DailyRollup;
import com.anya.shortener.domain.ShortLink;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Creates every index explicitly at startup.
 *
 * <p>Spring Data's {@code auto-index-creation} is left off (see
 * {@code application.yml}). Deriving indexes from annotations is convenient but
 * poor practice in production: index creation is triggered implicitly by the
 * first repository call, against whichever node the app connects to, with no
 * control over ordering or failure handling. Declaring them here makes the full
 * set reviewable in one file and startup failures explicit.
 *
 * <p>Index creation is idempotent — MongoDB ignores a create for an index that
 * already matches — so this runs safely on every boot.
 */
@Component
public class MongoIndexInitializer {

    private static final Logger log = LoggerFactory.getLogger(MongoIndexInitializer.class);

    private final MongoTemplate mongo;
    private final AppProperties props;

    public MongoIndexInitializer(MongoTemplate mongo, AppProperties props) {
        this.mongo = mongo;
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void createIndexes() {
        createShortLinkIndexes();
        createClickEventIndexes();
        createRollupIndexes();
        log.info("MongoDB indexes verified");
    }

    private void createShortLinkIndexes() {
        IndexOperations ops = mongo.indexOps(ShortLink.class);
        // Supports "same URL returns the same slug".
        ops.ensureIndex(new Index().on("urlHash", Sort.Direction.ASC).named("idx_url_hash"));
        ops.ensureIndex(new Index().on("ownerKey", Sort.Direction.ASC).named("idx_owner"));

        // expireAfterSeconds(0) means "expire at the instant in this field",
        // rather than zero seconds after it. Documents with a null expiresAt are
        // never touched by the TTL monitor, which is how permanent links work.
        ops.ensureIndex(new Index()
                .on("expiresAt", Sort.Direction.ASC)
                .named("idx_expires_at_ttl")
                .expire(Duration.ZERO));
    }

    private void createClickEventIndexes() {
        IndexOperations ops = mongo.indexOps(ClickEvent.class);

        // Matches every analytics query: equality on slug, then a range on
        // timestamp, descending for recent-first reads.
        ops.ensureIndex(new Index()
                .on("slug", Sort.Direction.ASC)
                .on("timestamp", Sort.Direction.DESC)
                .named("idx_slug_timestamp"));

        Duration retention = props.analytics().rawEventRetention();
        try {
            ops.ensureIndex(new Index()
                    .on("timestamp", Sort.Direction.ASC)
                    .named("idx_timestamp_ttl")
                    .expire(retention));
        } catch (Exception e) {
            // MongoDB rejects a create that changes an existing index's TTL.
            // Changing retention on a live deployment therefore needs a collMod,
            // which is a deliberate operation rather than something to do
            // silently at boot.
            log.warn("Could not apply raw-event TTL of {}. An index with a different "
                            + "expireAfterSeconds likely exists; change it with collMod. Cause: {}",
                    retention, e.getMessage());
        }
    }

    private void createRollupIndexes() {
        mongo.indexOps(DailyRollup.class).ensureIndex(new Index()
                .on("slug", Sort.Direction.ASC)
                .on("date", Sort.Direction.DESC)
                .named("idx_slug_date"));
    }

    /** Index metadata, useful when demonstrating the schema design. */
    public Document describeIndexes() {
        Document doc = new Document();
        doc.put("short_links", mongo.indexOps(ShortLink.class).getIndexInfo().toString());
        doc.put("click_events", mongo.indexOps(ClickEvent.class).getIndexInfo().toString());
        doc.put("daily_rollups", mongo.indexOps(DailyRollup.class).getIndexInfo().toString());
        return doc;
    }
}
