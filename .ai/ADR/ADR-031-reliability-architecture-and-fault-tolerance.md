# ADR-031: Phase 13 Reliability Engineering, Fault Tolerance & Idempotency Architecture (Phase 13A)

## Status
**ACCEPTED / ARCHITECTURE APPROVED**

## Context

Following the completion and freezing of Phase 12 (Asynchronous Migration & Progress Tracking), the system operates an asynchronous migration execution pipeline backed by PostgreSQL and Spring's bounded `ThreadPoolTaskExecutor`.

An architectural audit of Phase 12 identified four critical reliability vulnerabilities under real-world operational conditions:
1. **Transient Provider Fault Sensitivity**: Network timeouts or transient HTTP 5xx responses during external mutations cause immediate abortion of migration execution, skipping all remaining tracks without retry attempts.
2. **In-Memory Queue Volatility**: In-memory task queues in the JVM heap are volatile. Process restarts or crashes drop unstarted `QUEUED` tasks, leaving them orphaned in PostgreSQL without automated resume or retry mechanisms.
3. **Ambiguous External Mutation Hazards**: YouTube Data API v3 does not support client idempotency tokens on `POST /playlists` or `POST /playlistItems`. Blindly retrying timed-out mutations produces duplicate destination playlists and duplicate playlist items.
4. **Lack of Formal API Idempotency**: Callers resubmitting migration requests during network disconnects or client retries risk either race conditions (concurrent duplicate playlist creation) or unexpected HTTP 409 Conflict rejections.

Phase 13 introduces end-to-end reliability engineering across failure classification, retry policies, durable scheduling, API idempotency, and ambiguous outcome reconciliation.

---

## Architectural Decisions

### 1. Two-Tier Retry Architecture: In-Flight Transient vs. Durable Scheduled

We reject both unconstrained in-memory retry loops (which hold threads and risk memory exhaustion) and heavyweight distributed message queues like Kafka or RabbitMQ (which violate the project's modular monolith and operational simplicity principles in ADR-001, ADR-003, and ADR-007).

Instead, Phase 13 establishes a **Two-Tier Retry Architecture**:

#### Tier 1 — In-Flight Transient Retry (Local Worker Thread):
- Applied strictly to **Confirmed Retryable** failures (e.g., HTTP 429 rate limit with `Retry-After`, transient network read blips).
- Bounded to maximum 3 attempts and 45 seconds total elapsed duration per track.
- Uses exponential backoff with AWS Full Jitter:
  $$\text{delay} = \text{random}(0, \min(M, B \times 2^{\text{attempt}}))$$
  where $B = 1.0\text{ s}$, $M = 30.0\text{ s}$.
- Upstream `Retry-After` headers are explicitly respected: $\text{delay} = \max(\text{headerDelay}, \text{jitterDelay})$.
- Strictly **zero database connections or transactions** are held during backoff sleep.

#### Tier 2 — Durable Scheduled Retry (PostgreSQL-Backed):
- Applied to prolonged rate limits, worker pool saturation (`TaskRejectedException`), and recovery across application restarts.
- Persistent state in `migration_executions`: `retry_count`, `max_retries` (default: 3), `next_retry_at`, and `last_failure_reason`.
- Polled using PostgreSQL `FOR UPDATE SKIP LOCKED` by a lightweight scheduled sweeper:
  ```sql
  SELECT id FROM migration_executions
  WHERE status = 'QUEUED' AND (next_retry_at IS NULL OR next_retry_at <= NOW())
  ORDER BY next_retry_at ASC NULLS FIRST, created_at ASC
  FOR UPDATE SKIP LOCKED
  LIMIT :batchSize;
  ```
- Guarantees zero lost migrations on JVM crash or pod termination without requiring Redis or Kafka.

---

### 2. Strict Distinction Between Confirmed Rejections and Ambiguous Mutations

We mandate that **no mutation operation that times out or returns HTTP 5xx may ever be blindly retried**.

- **Read Operations (Spotify / YouTube Search)**: Naturally idempotent. Re-attempting a GET request on timeout or 5xx is safe.
- **Write Mutations (Create Playlist / Add Track)**: Non-idempotent upstream. Socket timeout or 503 means the state on YouTube is **UNCERTAIN**.
- **Action**: Ambiguous mutations MUST undergo Phase 11G remote state verification (`MusicDestination.inspectPlaylist`) before any retry is permitted:
  - If verified **present** on YouTube: Mark settled as `ADDED` with verified `destinationPlaylistItemId`. Proceed to next track.
  - If verified **absent** from YouTube: Revert to `PENDING` and safely re-attempt insertion.
  - If indeterminate or multiple matching items exist: Transition to `UNCERTAIN` and halt execution for human review.

---

### 3. Safe Destination Playlist Creation Pattern

To prevent duplicate destination playlists when `POST /playlists` times out:
1. The backend records the migration execution intent prior to external creation.
2. If `createPlaylist` times out, the retry mechanism queries the user's channel via `GET /playlists?mine=true` (costs 1 quota unit, vs 50 for creation).
3. It inspects recent playlists for matching title and description created within the creation attempt window.
4. If discovered, it links the existing playlist without re-creating. If confirmed absent, it safely creates the playlist.

---

### 4. API Idempotency Contract (`Idempotency-Key`)

1. **Header**: Endpoints `POST /api/migrations` and `POST /api/migrations/submit` evaluate the standard `Idempotency-Key` header.
2. **User-Scoped**: Unique constraint on `(user_id, idempotency_key)`. Different users may supply the identical key independently.
3. **Request Fingerprint**: SHA-256 hash of canonicalized request parameters (`reviewSessionId`, `title`, `description`, `privacy`).
4. **Semantics**:
   - Same user + same key + identical payload: returns existing execution (202 Accepted if queued/running, 200 OK if completed).
   - Same user + same key + mismatched payload: rejects with `HTTP 409 Conflict`.
   - Concurrent submissions: exactly one initializes the execution; the concurrent caller receives the initialized execution.

---

### 5. Preservation of Phase 12 Progress Invariants

The 8-status mathematical count conservation invariant established in Phase 12F is strictly preserved:
$$\text{totalTracks} = \text{pending} + \text{adding} + \text{added} + \text{failed} + \text{uncertain} + \text{skipped} + \text{rollingBack} + \text{rolledBack}$$

We explicitly reject adding a `RETRYING` track status to the database. Between in-flight retries, tracks remain in status `ADDING` or `PENDING` with an incremented `attempt_count`, ensuring zero regression on database check constraints or progress reporting formulas.

---

## Consequences

### Positive:
- **Resilience**: Transient network glitches and 429 rate limits are automatically resolved without user intervention or skipped tracks.
- **Durable Crash Recovery**: JVM restarts automatically resume interrupted migrations from PostgreSQL without lost tasks.
- **Zero Duplicate Playlists**: Network drops during playlist creation cannot produce orphan/duplicate playlists on YouTube.
- **Zero Duplicate Tracks**: Ambiguous track insertions are resolved through evidence-based inspection before retrying.
- **Zero Infrastructure Bloat**: Retains the PostgreSQL modular monolith without adding Redis, Kafka, or RabbitMQ.

### Negative / Trade-offs:
- Upstream YouTube playlist inspection adds latency (100–500ms) when an ambiguous timeout occurs.
- Database table `migration_idempotency_keys` requires a periodic purge sweeper for records older than 72 hours.
- Bounded retry attempts slightly increase maximum job completion time under sustained provider degradation.
