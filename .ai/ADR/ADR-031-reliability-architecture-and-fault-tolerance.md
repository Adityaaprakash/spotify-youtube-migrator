# ADR-031: Phase 13 Reliability Engineering, Fault Tolerance & Idempotency Architecture (Phase 13A — Revised)

## Status
**ACCEPTED / ARCHITECTURE APPROVED (REVISED)**

## Context

Following the completion and freezing of Phase 12 (Asynchronous Migration & Progress Tracking), the system operates an asynchronous migration execution pipeline backed by PostgreSQL and Spring's bounded `ThreadPoolTaskExecutor`.

An architectural review of the initial Phase 13A design identified critical revisions required for production safety:
1. **Retry Metadata Ownership**: A migration execution aggregate contains multiple distinct, independently retryable operations (destination playlist creation, individual track insertions, and upstream read queries). Retry counters must be owned at the operation level rather than grouped as a single counter on the execution root.
2. **External Playlist Creation Safety**: Relying on playlist title and creation timestamp matching cannot guarantee ownership. A deterministic correlation marker in the playlist description, combined with pre-allocated creation intent, is required.
3. **Durable Worker Leases & Fencing**: Phase 12 relies on a static 5-minute threshold without worker identity, heartbeats, or fencing. A zombie worker waking from a pause could overwrite state.
4. **RFC 9110 `Retry-After` Semantics**: The original retry formula erroneously shortened provider `Retry-After` instructions when they exceeded local maximum delays.
5. **Worker Thread Starvation**: Blocking worker threads during exponential backoff starves the bounded pool (2–4 threads). Delays $> 2\text{ s}$ must release the thread and be durably scheduled.
6. **Atomic Scheduling Protocol**: `SELECT ... FOR UPDATE SKIP LOCKED` must be integrated into a complete two-phase claiming protocol with fencing generation and post-commit dispatch failure rollbacks.
7. **Track Insertion Reconciliation**: YouTube's eventual consistency (read-after-write replication lag), intentional duplicate source tracks, and user modifications require multi-stage evidence reconciliation rather than a single immediate read.
8. **YouTube Error Classification**: YouTube returns both transient user rate limits (`userRateLimitExceeded`) and permanent daily quota exhaustion (`quotaExceeded`) under HTTP 403; these require completely distinct handling.
9. **Idempotency Retention Lifecycle**: Idempotency keys must never expire while an execution is active or recoverable. The 72-hour TTL applies strictly after reaching a terminal state.

---

## Architectural Decisions

### 1. Granular Operation-Level Retry Metadata Ownership

We reject placing a single `retry_count` and `max_retries` on `migration_executions`.

Instead, retry metadata is strictly owned by the entity corresponding to each logical operation:
- **Individual Track Insertions**: Owned by `ExecutionTrackRecord`.
  - Fields added: `max_attempts` (default: 3), `next_attempt_at`, `last_failure_classification`, `retry_backoff_ms`.
  - Existing fields reused: `attempt_count`, `started_at`, `completed_at`, `error_message`, `status`.
- **Destination Playlist Creation**: Owned by `MigrationExecution`.
  - Fields added: `playlist_creation_operation_id` (UUID), `playlist_creation_attempt_count`, `playlist_creation_max_attempts`, `playlist_creation_next_attempt_at`, `playlist_creation_failure_code`.
- **Upstream Read Queries**: Managed in-flight by transient `RetryContext` on the worker thread.

---

### 2. Destination Playlist Creation Safety & Description Correlation

To prevent duplicate destination playlists when `POST /playlists` encounters an ambiguous timeout or 503:
1. **Pre-Allocation Intent**: `MigrationExecution` is persisted with `status = 'ALLOCATING_DESTINATION'` and a generated `playlist_creation_operation_id` prior to remote invocation.
2. **Correlation Marker in Description**: A deterministic, machine-readable signature is appended to the YouTube playlist description:
   ```text
   [ref: migrator-op:<playlist_creation_operation_id>]
   ```
3. **Reconciliation Rules**:
   - On timeout, the worker queries user playlists via `GET /playlists?mine=true` (costs 1 quota unit, vs 50 for creation).
   - If a playlist with `[ref: migrator-op:<id>]` is discovered: Conclusively proven created. The backend links the playlist and transitions to `QUEUED`.
   - If no playlist matches the marker and no title collision exists: Safely re-attempts creation.
   - If an unverified playlist with the same title exists without the marker: Transitions to `RECOVERY_REQUIRED` and pauses for user confirmation.

---

### 3. Durable Worker Ownership, Heartbeats & Fencing Tokens

To eliminate zombie-worker race conditions:
1. **Worker Identity & Lease**:
   - `migration_executions` maintains `lease_owner` (worker node/thread ID), `lease_until` (timestamp), and `heartbeat_at` (timestamp).
   - Lease duration is **60 seconds**, refreshed via an asynchronous worker heartbeat every **15 seconds**.
2. **Generational Fencing Tokens (`lease_generation`)**:
   - Every claim increments `lease_generation` by 1.
   - All track mutation updates verify the caller's fencing token matches `migration_executions.lease_generation`.
   - Zombie workers waking from pauses fail the fencing check with zero rows updated, preventing state corruption.
3. **Graceful Shutdown**:
   - On SIGTERM, active workers cooperatively release the lease, setting `status = 'QUEUED'` and `lease_owner = NULL`.

---

### 4. Correct `Retry-After` Semantics & Thread-Release Threshold

1. **RFC 9110 Compliance**:
   Upstream provider `Retry-After` headers (delta-seconds or HTTP-date) must **never be shortened** by local configuration:
   $$\text{effectiveDelay} = \begin{cases}
   \text{parsedRetryAfter}, & \text{if } \text{Retry-After header is present and valid} \\
   \text{random}(0, \min(M, B \times 2^{\text{attempt}})), & \text{otherwise (Full Jitter)}
   \end{cases}$$
2. **Thread-Release Threshold**:
   - If $\text{effectiveDelay} \le 2.0\text{ seconds}$: Allowed to perform a bounded in-flight micro-retry (max 1 attempt).
   - If $\text{effectiveDelay} > 2.0\text{ seconds}$: The worker thread **releases immediately**. The operation is durably scheduled via `next_attempt_at`, preventing worker thread pool starvation.
3. **Quota Window Deferral**:
   If YouTube returns `quotaExceeded`, retrying within the same quota window is prohibited. The execution is deferred until midnight Pacific Time (quota reset) or fast-failed with actionable user notification.

---

### 5. Atomic Durable Scheduling Protocol

We implement a complete two-phase claiming protocol using PostgreSQL:
1. **Phase 1: Atomic Claim (Short DB Transaction)**:
   ```sql
   WITH claimable AS (
       SELECT id FROM migration_executions
       WHERE status = 'QUEUED'
         AND (next_retry_at IS NULL OR next_retry_at <= NOW())
         AND (lease_until IS NULL OR lease_until < NOW())
       ORDER BY next_retry_at ASC NULLS FIRST, created_at ASC
       FOR UPDATE SKIP LOCKED
       LIMIT 1
   )
   UPDATE migration_executions m
   SET status = 'RUNNING',
       lease_owner = :instanceId,
       lease_generation = m.lease_generation + 1,
       lease_until = NOW() + INTERVAL '60 seconds',
       heartbeat_at = NOW(),
       updated_at = NOW()
   FROM claimable
   WHERE m.id = claimable.id
   RETURNING m.id, m.user_id, m.lease_generation;
   ```
2. **Phase 2: Post-Commit Worker Dispatch**:
   The transaction commits immediately before submitting the task to `ThreadPoolTaskExecutor`.
3. **Phase 3: Saturation Handling**:
   If `taskExecutor.execute()` rejects the task (`TaskRejectedException`), a separate short transaction reverts the execution to `QUEUED` with a 10-second backoff and clears the lease.

---

### 6. Track Insertion Reconciliation with Replication Lag & Duplicates

1. **Replication Lag Tolerance**:
   Absence in a single immediate read is not proof of non-insertion due to distributed eventual consistency on YouTube. Reconciliation enforces a 3-second delay, followed by a second read before concluding an item was not applied.
2. **Intentional Duplicate Tracks**:
   Deduplication must never be performed by YouTube video ID alone. The system tracks `assignedPlaylistItemIds` and maintains a strict 1-to-1 bijection between canonical `targetPosition` and remote `playlistItemId`.
3. **Ambiguity Halt**:
   If unassigned items cannot be unambiguously attributed via timestamp correlation, the track is marked `UNCERTAIN` and execution pauses in `RECOVERY_REQUIRED`.

---

### 7. Granular YouTube Provider Error Classification

The failure classifier inspects Google API `error.errors[].reason`:
- `quotaExceeded`: Daily quota exhausted. Non-retryable in current window.
- `userRateLimitExceeded` / `rateLimitExceeded`: Short-term QPS limit. Retryable with exponential backoff.
- `insufficientPermissions` / `forbidden`: Auth scope issue. Non-retryable.
- `invalidCredentials` / `tokenExpired`: Retryable once after token refresh.
- `videoNotFound`: Video missing. Non-retryable; track marked `FAILED`.

---

### 8. API Idempotency Contract & Lifecycle Retention

1. **Header**: `Idempotency-Key` (UUID / 1-128 ASCII chars) on submission endpoints.
2. **User-Scoped**: Unique constraint on `(user_id, idempotency_key)`.
3. **Canonical Fingerprint**: `v1:` prefix with SHA-256 hash using `\u001F` delimiter across `userId`, `reviewSessionId`, `title`, `description`, `privacy`.
4. **Lifecycle Retention**:
   - Active / Recoverable executions: `expires_at = NULL` (**NEVER EXPIRES** while running or awaiting recovery).
   - Terminal executions (`COMPLETED`, `FAILED`, `ROLLED_BACK`): `expires_at = NOW() + INTERVAL '72 hours'`.

---

## Consequences

### Positive:
- **Zero Thread Starvation**: Worker threads never block on long backoffs.
- **Zombie Worker Immunity**: Generational fencing tokens prevent stale workers from writing.
- **Deterministic Playlist Ownership**: Correlation markers eliminate duplicate playlist creation.
- **RFC 9110 Compliant**: Upstream `Retry-After` headers are strictly honored.
- **Eventual Consistency Safe**: Multi-stage inspection eliminates false-negative insertion retries.
- **Minimal Infrastructure**: Uses PostgreSQL MVCC and `SKIP LOCKED`. No Redis or Kafka required.

### Trade-offs:
- YouTube playlist descriptions contain a small signature (`[ref: migrator-op:<id>]`).
- Inspecting user playlists during creation timeouts consumes 1 quota unit.
