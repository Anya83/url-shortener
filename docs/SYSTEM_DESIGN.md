# System Design

## The problem

A URL shortener is read-dominated to an extreme degree. A link is created once and resolved thousands of times. Published ratios for real shorteners sit somewhere near 100:1 reads to writes, and the reads are latency-critical in a way the writes are not — a user waiting on a redirect is staring at a blank page.

The naive implementation does this per redirect:

1. Query MongoDB for the slug
2. Increment a click counter in MongoDB
3. Insert a click event in MongoDB
4. Return 302

Three database operations, two of them writes, on the hottest path in the system. This design removes all three from the request path.

---

## Request flow

```
GET /aB3xK9p
     │
     ├─ rate limit check ──────────────► Redis  (INCR + PEXPIRE, one Lua call)
     │
     ├─ resolve slug ─────────────────► Redis  (GET shortener:link:aB3xK9p)
     │       │
     │       ├─ hit ─────────────────► return URL
     │       ├─ sentinel ───────────► 404, MongoDB never touched
     │       └─ miss ───────────────► MongoDB findById(_id)
     │                                    │
     │                                    ├─ found ─► cache it, return
     │                                    └─ absent ► cache sentinel, 404
     │
     ├─ 302 response sent ◄──────────── user is now redirected
     │
     └─ dispatch to analytics executor  (after the response is built)
             ├─ Redis INCR  shortener:clicks:aB3xK9p
             ├─ Redis SADD  shortener:clicks:dirty
             └─ MongoDB insert ClickEvent
```

On a cache hit the user-visible path is **two Redis round trips and zero database queries**.

---

## Data model

### `short_links`

The slug is the `_id`. This is the single most consequential schema decision in the project.

Every redirect looks up by slug. MongoDB's `_id` index is always present and is the one index guaranteed to be in memory on an active collection. Using a separate `slug` field with a unique secondary index would mean an index traversal *plus* a document fetch on every resolve. Making the slug the `_id` collapses that into one lookup.

| Field | Purpose |
|---|---|
| `_id` | the slug |
| `longUrl` | destination |
| `urlHash` | SHA-256 of normalised URL + owner, indexed — backs de-duplication |
| `ownerKey` | tenant scope |
| `expiresAt` | TTL index, `expireAfterSeconds: 0` |
| `active` | soft delete |
| `totalClicks` | denormalised, written only by the flush job |
| `version` | optimistic lock |

**Why `urlHash` instead of indexing `longUrl`:** MongoDB caps index keys at 1024 bytes. URLs legitimately exceed that. A fixed-width hash sidesteps the limit and keeps the index small enough to stay resident.

**Why `expireAfterSeconds: 0`:** it means "expire at the instant stored in this field," not "zero seconds after it." Documents with a null `expiresAt` are ignored by the TTL monitor entirely, which is how permanent links coexist with expiring ones in one collection.

### `click_events`

The high-volume collection — it grows with traffic, not with link count.

Compound index on `{slug: 1, timestamp: -1}` matches the analytics access pattern exactly: equality on slug, then a range on time, recent first. Every aggregation pipeline opens with a `$match` on that shape, so the pipelines are index-backed rather than collection scans. Placing a `$group` before the `$match` would defeat the index entirely — pipeline stage order is a correctness-of-performance issue, not a style preference.

TTL index on `timestamp` reclaims raw events after 90 days.

**The client IP is never stored.** Only a salted SHA-256, truncated to 128 bits. The salt is what makes this meaningful: an *unsalted* hash of an IPv4 address is trivially reversible — there are only 2³² possibilities and hashing all of them takes minutes. An unsalted digest would be personal data wearing a disguise.

### `daily_rollups`

Pre-aggregated per slug per day. Tiny, and never expires.

This is what makes the TTL on raw events safe. Storage stays proportional to *links × days* rather than *clicks*, while "clicks per day since launch" still answers years later.

The `_id` is the deterministic composite `slug|date`. Because the key is derivable, the rollup job issues idempotent upserts — re-running it over a day that was already processed overwrites rather than duplicates, so a failed run is safe to retry blindly.

---

## Caching

### Cache-aside, not write-through

Links are immutable in practice. Write-through would add complexity and a consistency surface for a write pattern that barely exists.

### Negative caching

The pattern most cache designs miss. Caching what exists is obvious; the dangerous traffic is requests for slugs that *don't*.

Scanners, expired links, and typos never populate a plain cache, so every one of those requests falls through to the database. This is **cache penetration** — the cache provides no protection precisely when the traffic is most hostile.

Misses are therefore cached as a sentinel (`__NOT_FOUND__`) with a deliberately short TTL. It only needs to outlive a burst.

*Alternative considered:* a Bloom filter over all known slugs would answer "definitely absent" in memory with no Redis round trip at all. Rejected here because it complicates deletion (standard Bloom filters can't remove entries) and negative caching already collapses a burst to a single database read. At a scale where the sentinel keys themselves became a memory problem, the Bloom filter would be the next step.

### TTL jitter

Entries written in the same burst would otherwise expire in the same burst, dumping the whole load onto MongoDB simultaneously — a **cache avalanche**. Every TTL is spread by up to ±10%.

### Expiry cap

An expiring link is cached for no longer than its own remaining lifetime. Without that cap, a link could keep redirecting from cache for hours after expiry: MongoDB's TTL monitor deletes the document but has no way to reach into Redis.

### Degradation

Every Redis call is wrapped. Redis unreachable means redirects fall back to MongoDB — slower, but correct. **A cache outage must not become a site outage.**

Eviction policy is `allkeys-lru` (see `docker-compose.yml`), which matters more than it looks: everything Redis holds here is derived data that can be rebuilt from MongoDB, so evicting the coldest keys under memory pressure is correct. The default `noeviction` would instead start *refusing writes* at `maxmemory`, breaking click buffering while the cache still appears healthy.

---

## Slug generation

Two strategies behind one interface.

### Counter (default)

`INCRBY` on a shared Redis counter, reserved in blocks of 512.

Calling `INCR` per link would put Redis on the critical path of every create. Reserving a block amortises that to one round trip per 512 links. Unused IDs are lost on restart — irrelevant against 62⁷ ≈ 3.5 × 10¹² available slugs, roughly 9,600 years of headroom at a million links per day.

Raw counter values produce `0000001`, `0000002`, … — every link in the system enumerable by anyone who can count. Each ID is therefore mapped through:

```
slug = base62((id × 1_500_450_271) mod 62⁷)
```

The multiplier is odd and not a multiple of 31, so it is coprime to 62⁷ = 2⁷ × 31⁷. That makes the mapping a **bijection**: still collision-free, but consecutive counter values land far apart.

This is obfuscation, not secrecy — the multiplier is in the source, so the mapping is invertible by anyone who reads it. Honest framing matters here.

### Random

`SecureRandom` over the base62 alphabet, for links that must be genuinely unguessable.

No uniqueness guarantee, so the caller treats a duplicate-key error as a retry signal. In practice collisions stay below 1% probability until roughly 8.4 million links exist, and the unique `_id` index turns any collision into a caught error rather than a silent overwrite.

**`insert` is used rather than `save`** in both paths. `save` upserts — a collision would silently overwrite someone else's link instead of raising `DuplicateKeyException`. That distinction is the difference between a retry and a data-loss bug.

---

## Click analytics

### Write coalescing

Incrementing `totalClicks` per redirect would put a write on the hottest read path, and a popular link would serialise all its traffic behind contention on a single document.

Redis absorbs the increments; a scheduled flush folds them into MongoDB via one unordered bulk operation. 10,000 clicks between flushes cost 10,000 in-memory increments and one database write.

**The trade is explicit:** counts buffered since the last flush are lost if Redis dies. Bounded by the flush interval, acceptable for an analytics counter, and the reason the authoritative per-click record is written separately.

Draining uses a Lua script rather than `GET` then `DEL`, because an increment landing between those two calls would be lost. Atomicity here is the difference between approximate and correct counts.

### Async ingestion

Analytics run on a **bounded** executor with a discard policy.

Analytics are lossy-tolerant; redirects are not. If ingestion falls behind, the correct failure mode is to drop click records — never to block or fail a user's redirect. An unbounded queue would instead convert a MongoDB stall into an out-of-memory crash that takes redirects down with it.

Request headers are read on the request thread and passed as plain values. The servlet request is recycled once the response commits, so touching it from the async thread would be a use-after-free.

### Coordination across instances

The two background jobs coordinate differently, on purpose.

**Flush needs no lock.** It claims work with `SPOP` on a shared Redis set, so each slug goes to exactly one instance and the work shards itself — more replicas drain the backlog faster.

**Rollup takes a lock.** It aggregates a whole day in one pass, so running it on every replica multiplies load for an identical result. The lock is advisory; the job is idempotent, so a lock expiring mid-run costs duplicated effort, never corrupted data. Release is guarded by an owner check — a blind `DEL` could delete a lock another instance acquired after ours expired.

### Unique visitors

Counted with a two-stage `$group` — first by `(slug, visitor)` to deduplicate, then by slug to count — rather than collecting with `$addToSet`. A set of every visitor hash for a busy link would push the pipeline past MongoDB's 16 MB document limit.

---

## Rate limiting

Fixed window, in Redis, via one Lua script.

**Why Redis and not in-process:** the limit must hold across replicas. A 20-per-minute cap enforced locally becomes 20 × N behind a load balancer, which is not a limit.

**Why one Lua call:** `INCR` and `PEXPIRE` as two round trips can leave a key with no TTL if the process dies between them, pinning a caller at their limit forever.

**Why fixed and not sliding:** a fixed window admits up to 2× the limit across a boundary. Accepted in exchange for O(1) memory per caller — a sliding log stores every request timestamp. The window start is folded into the key, so expired windows are reclaimed by Redis without a sweep job.

**Why it fails open:** a limiter outage should degrade protection, not take down the service it protects. For a limiter guarding billing or authentication the opposite choice would be correct.

Creates are limited by IP rather than by API key, since the key is self-asserted and a caller could otherwise mint a new one per request.

---

## Security

| Concern | Handling |
|---|---|
| `javascript:` / `data:` / `file:` destinations | Scheme allowlist — `http` and `https` only. Without it every short link is a stored-XSS vector the moment a browser follows the redirect. |
| Embedded credentials | Rejected. `https://apple.com@evil.example/` reads as Apple but resolves to evil.example. Rejected rather than stripped, since stripping silently changes where the link points. |
| IP retention | Never stored raw. Salted, truncated hash only. |
| Referrer retention | Domain only — full referrers routinely carry session tokens and search terms. |
| Reserved paths | `api`, `actuator`, `docs`, … cannot be claimed as aliases. |
| Error responses | Stack traces and driver messages never returned to clients. |
| Container | Runs as an unprivileged user. |

**Not implemented:** authentication. `X-Api-Key` is an ownership scope, not a credential. A real deployment needs Spring Security in front of the management endpoints; it's omitted so the project stays focused on the caching and analytics design rather than re-implementing a login flow.

---

## Scaling path

The application is stateless — every piece of mutable state lives in Redis or MongoDB — so horizontal scaling is just adding replicas.

The order things would break, and what to do:

1. **Redis memory.** Shorten `link-ttl`; `allkeys-lru` already sheds the coldest keys correctly. Then Redis Cluster, keyed by slug.
2. **MongoDB read load on cache misses.** Add replica-set secondaries and read from them — link documents are immutable after creation, so replication lag is harmless for resolves.
3. **`click_events` write volume.** This is the first genuine wall. Put Kafka between the redirect and ingestion, and let a consumer group batch inserts.
4. **`click_events` size.** Shard on `{slug: hashed}`. Slug is in every analytics query, so it's a natural shard key with even distribution.
5. **Global latency.** The 302 itself is the bottleneck at that point — push resolution to an edge KV store (Cloudflare Workers KV, or similar) and keep the origin for analytics and writes.

### What this design does *not* do

- **No multi-region writes.** Single-primary MongoDB. Multi-region would need conflict resolution on slug allocation.
- **No exactly-once click counting.** Counts are approximate under Redis failure, by design.
- **No real-time analytics.** Reports reflect data up to one flush interval old.
- **No custom domains.** One `base-url` per deployment.

Each of these is a deliberate scope boundary rather than an oversight — they're the natural next features, and each would change the data model.
