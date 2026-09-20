# URL Shortener with Click Analytics

A short-link service built around a specific constraint: **the redirect path must not write to the database.**

Redirects are the only endpoint that sees real traffic volume, and the naive implementation — look up the link in MongoDB, increment a counter, return a 302 — puts a database read *and* a write on every single click. This project removes both. Redirects resolve from a Redis cache-aside read, and click counts accumulate in Redis before being folded into MongoDB in batches.

Java 17 · Spring Boot 3.4 · MongoDB 7 · Redis 7.4 · Docker Compose

---

## Quick start

```bash
docker compose up --build
```

Then:

| What | Where |
|---|---|
| API docs (Swagger UI) | http://localhost:8080/docs |
| Health | http://localhost:8080/actuator/health |
| Cache hit ratio | http://localhost:8080/api/v1/links/_cache-stats |

Create a link:

```bash
curl -X POST http://localhost:8080/api/v1/links -H "Content-Type: application/json" -d "{\"url\":\"https://example.com/a/very/long/path\"}"
```

Follow it (`-I` shows the 302 without following):

```bash
curl -I http://localhost:8080/YOUR_SLUG
```

Read its analytics:

```bash
curl http://localhost:8080/api/v1/links/YOUR_SLUG/analytics
```

---

## The design decisions worth reading

Full reasoning is in [docs/SYSTEM_DESIGN.md](docs/SYSTEM_DESIGN.md). The short version:

### The slug is the MongoDB `_id`

Every redirect looks a link up by slug. Making the slug the primary key means that lookup hits the clustered `_id` index directly instead of a secondary index, saving an index traversal on the hottest query in the system.

### Negative caching, because cache penetration is the real risk

Caching links that exist is obvious. The failure mode people miss is traffic for slugs that *don't* exist — scanners, expired links, someone walking the keyspace. A plain cache never holds those, so every one of those requests reaches MongoDB. The cache stops protecting you exactly when you need it most.

Misses are cached as a sentinel with a short TTL, so a burst against a nonexistent slug costs one MongoDB read total instead of one per request.

### Clicks are buffered in Redis, not written per click

`INCR` in Redis, then a scheduled job folds the accumulated counts into MongoDB with one bulk write. A link taking 10,000 clicks between flushes costs 10,000 in-memory increments and **one** MongoDB write.

The trade is explicit: counts buffered since the last flush are lost if Redis dies. That's acceptable for an analytics counter, which is why the authoritative per-click record is written separately and asynchronously.

### 302, never 301

A 301 is cached by browsers indefinitely. Every click after the first would never reach the service — the link could not be retargeted or revoked, and analytics would record exactly one click per browser, forever. For a shortener *with analytics*, a 301 defeats the entire purpose.

### Redis failures degrade, they don't cascade

Every Redis call is wrapped. If Redis is unreachable the service keeps serving redirects from MongoDB — slower, but correct. A cache outage must not become a site outage. The rate limiter fails **open** for the same reason; a limiter that takes down the service it protects has failed at its job.

### Sequential IDs without enumerable links

The counter strategy reserves blocks of IDs from Redis (one `INCRBY` per 512 links, not per link) and maps each through `(id × multiplier) mod 62⁷`. The multiplier is coprime to 62⁷, so the mapping is a bijection — collision-free, but consecutive links land far apart in the address space.

This is obfuscation, not secrecy: the multiplier is in the source. Links that must be genuinely unguessable use `APP_SLUG_STRATEGY=random`.

### Raw events expire, history doesn't

Click documents grow with traffic, so a TTL index reclaims them after 90 days. A nightly job rolls each day up into a small permanent document first. Storage stays flat; "clicks per day since launch" still answers years later.

---

## API

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/v1/links` | Create a link |
| `GET` | `/api/v1/links/{slug}` | Link metadata |
| `GET` | `/api/v1/links` | List links for the calling API key |
| `DELETE` | `/api/v1/links/{slug}` | Deactivate (`?purge=true` to hard-delete with analytics) |
| `GET` | `/api/v1/links/{slug}/analytics` | Timeline and breakdowns |
| `GET` | `/api/v1/links/{slug}/analytics/daily` | Pre-aggregated daily rollups |
| `GET` | `/{slug}` | **Resolve and redirect (302)** |

Create accepts an optional vanity `alias` and an optional `ttlSeconds`:

```json
{ "url": "https://example.com/page", "alias": "launch", "ttlSeconds": 86400 }
```

`X-Api-Key` is an **ownership scope, not authentication** — it partitions links and de-duplication between callers. A real deployment would put Spring Security in front of this; it's left out so the project stays focused on the caching and analytics design.

---

## Running the tests

Unit tests need no infrastructure:

```bash
mvn test
```

Integration tests run against real MongoDB and Redis containers via Testcontainers, so they need a running Docker daemon. They're tagged `integration` and excluded from `mvn test`:

```bash
mvn verify
```

They assert the things mocked tests can't: that the cache actually absorbs the second read, that negative caching spares MongoDB, and that buffered counters survive the round trip into a document.

---

## Configuration

Copy `.env.example` to `.env`. Everything is environment-driven, so the same jar runs against local containers or managed Atlas/Redis Cloud instances.

Two settings genuinely matter before any real deployment:

**`APP_IP_SALT`** — visitor hashes are only pseudonymous if this salt is secret. Without it, hashing all 2³² IPv4 addresses reverses every hash in the database. Generate one with `openssl rand -hex 32`.

**`APP_TRUST_PROXY`** — only enable behind a reverse proxy that *overwrites* `X-Forwarded-For`. If the app is internet-facing, leaving this true lets any caller forge their IP and bypass rate limiting entirely.

Other knobs: `app.cache.link-ttl`, `app.cache.negative-ttl`, `app.analytics.flush-interval`, `app.analytics.raw-event-retention`, `app.slug.strategy`, `app.rate-limit.*`. See `src/main/resources/application.yml`.

---

## Running without Docker

The app needs MongoDB and Redis reachable, not necessarily local. Point it at free managed tiers:

```bash
export MONGODB_URI="mongodb+srv://user:pass@cluster.mongodb.net/urlshortener"
export REDIS_HOST=your-instance.redis-cloud.com
export REDIS_PORT=6379
export REDIS_PASSWORD=your-password
mvn spring-boot:run
```

Integration tests still require Docker, since Testcontainers starts real containers.

---

## Project layout

```
src/main/java/com/anya/shortener/
├── config/      Properties, Redis scripts, async executor, index creation
├── domain/      ShortLink, ClickEvent, DailyRollup
├── repository/  Spring Data Mongo repositories
├── service/     Caching, slug generation, click ingestion, analytics
└── web/         Controllers, DTOs, error handling
```

## License

MIT
