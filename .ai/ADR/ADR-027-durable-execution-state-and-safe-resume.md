# ADR-027: Durable Execution State, Concurrency Protection, and Safe Resume (Phase 11E)

## Status
**ACCEPTED / IMPLEMENTED**

## Context

Following the completion of Phase 11A (Execution Architecture & Contracts), Phase 11B (Approved Plan Validation), Phase 11C (Destination Playlist Creation), and Phase 11D (Ordered Track Insertion), the application requires durable execution state and safe recovery capabilities.

### 1. The Persistence & Recovery Gap in Phases 11C & 11D
In Phase 11C and Phase 11D:
- `DestinationPlaylistCreationResult` returned the confirmed YouTube playlist ID in memory, but this identity was not persisted in PostgreSQL.
- `DefaultOrderedTrackInsertionService` performed ordered insertions and returned `PlaylistTrackInsertionSummary`, but per-track outcomes (`SUCCESS`, `FAILED`, `UNCERTAIN`, `SKIPPED`) existed solely in memory on the calling thread.
- If the application process restarted, crashed, or encountered a container redeployment:
  1. All knowledge of which tracks were inserted was erased from application memory.
  2. The association between the review session and the confirmed destination playlist was lost.
  3. A client re-submitting an insertion request risked blindly duplicating tracks or creating multiple empty playlists.
  4. Two concurrent HTTP requests targeting the same review session could interleave track insertions without coordination.

### 2. Reconciliation with Prior ADRs
- **ADR-022**: Pre-flight `MigrationPlan` instances remain transient and in-memory.
- **ADR-023**: Human review decisions and source track provenance are durably persisted in `review_sessions` and `review_tracks`.
- **ADR-024**: Approved execution contexts are validated strictly and immutably from completed `ReviewSession` records.
- **ADR-025 & ADR-026**: Playlist creation and track insertion operate against the validated execution context.

To close the persistence and recovery gap without violating ADR-022 or polluting operational job tables (`migration_jobs` / `migration_tasks` from Phase 8), Phase 11E introduces dedicated execution persistence: `migration_executions` and `execution_track_records`.

---

## Architectural Decisions

### 1. Dedicated Durable Execution Model
Phase 11E introduces two domain entities extending `BaseEntity`:
1. **`MigrationExecution` (`migration_executions` table)**:
   - `id`: UUID primary key.
   - `user_id`: Foreign key to `users(id)`, establishing ownership.
   - `review_session_id`: Foreign key to `review_sessions(id)` with a `UNIQUE` constraint, enforcing that each review session has at most one durable execution.
   - `plan_id`: Optional UUID linking back to the transient plan.
   - `source_playlist_id`, `source_playlist_title`: Source playlist metadata.
   - `destination_platform`: Destination platform enum (e.g. `YOUTUBE`).
   - `destination_playlist_id`: Confirmed platform identifier for the playlist (non-blank).
   - `destination_playlist_url`: Web URL for the playlist.
   - `status`: Lifecycle enum `MigrationExecutionStatus` (`INITIALIZED`, `RUNNING`, `PARTIALLY_COMPLETED`, `COMPLETED`, `FAILED`, `RECOVERY_REQUIRED`).
   - Summary counters: `total_approved_tracks`, `successful_insertions`, `failed_insertions`, `uncertain_insertions`, `skipped_insertions`.
   - Timestamps: `started_at`, `completed_at`, `created_at`, `updated_at`.
   - `version`: `@Version` column for optimistic concurrency protection.

2. **`ExecutionTrackRecord` (`execution_track_records` table)**:
   - `id`: UUID primary key.
   - `execution_id`: Foreign key to `migration_executions(id)` with `ON DELETE CASCADE`.
   - `target_position`: 0-indexed canonical destination position ($0 \le \text{targetPosition} < M$).
   - `source_position`: Original 0-indexed position in the source playlist.
   - `source_track_id`: Spotify track ID.
   - `source_title`, `source_artist`: Track metadata.
   - `destination_track_id`: Target video ID.
   - `destination_playlist_item_id`: Provider item ID returned on confirmed insertion.
   - `status`: Lifecycle enum `ExecutionTrackStatus` (`PENDING`, `ADDING`, `ADDED`, `FAILED`, `UNCERTAIN`, `SKIPPED`).
   - `error_message`: Sanitized failure or timeout diagnostic message.
   - `attempt_count`: Number of insertion attempts.
   - Timestamps: `started_at`, `completed_at`.
   - `version`: `@Version` column for optimistic concurrency.
   - Unique constraint: `UNIQUE (execution_id, target_position)`.

### 2. State Machine & Lifecycle Transitions

#### Execution Status (`MigrationExecutionStatus`):
- `INITIALIZED`: Execution record created; track records populated as `PENDING`.
- `RUNNING`: Insertion currently claimed and executing on a thread.
- `PARTIALLY_COMPLETED`: Execution halted with partial successes and skipped/failed tracks.
- `COMPLETED`: Terminal state; all approved tracks successfully inserted (`successful_insertions == total_approved_tracks`).
- `FAILED`: Terminal failure; zero tracks succeeded and insertion aborted.
- `RECOVERY_REQUIRED`: Interrupted execution detected (e.g. stale `ADDING` or `UNCERTAIN` track) requiring operator/user review.

#### Track Status (`ExecutionTrackStatus`):
- `PENDING` $\to$ `ADDING`: Claimed immediately before dispatching `MusicDestination.addTrackToPlaylist`.
- `ADDING` $\to$ `ADDED`: Provider confirmed success with non-null playlist item ID.
- `ADDING` $\to$ `FAILED`: Provider confirmed rejection (e.g. video unavailable, private).
- `ADDING` $\to$ `UNCERTAIN`: Ambiguous network timeout or socket reset (`ResourceAccessException`).
- `PENDING` $\to$ `SKIPPED`: Aborted due to a prior failure or uncertain outcome to preserve playlist order.

### 3. Concurrency Protection & Anti-Duplicate Guarantees
- **Execution-Level Claim**: Before inserting tracks, the execution must transition from `INITIALIZED` or `PARTIALLY_COMPLETED` to `RUNNING`.
- **Concurrent Conflict Guard**: If another request attempts to execute or resume an execution currently in `RUNNING` status, it fails fast with `InvalidStateException` (HTTP 409 Conflict: `"Migration execution is currently in progress"`).
- **Optimistic Locking**: Both `MigrationExecution` and `ExecutionTrackRecord` use JPA `@Version` columns. Conflicting concurrent updates trigger `OptimisticLockException`.
- **Unique Review Session Constraint**: Enforces that only one `MigrationExecution` can exist per `ReviewSession`. Repeated initialization requests return the existing execution idempotently.

### 4. Safe Resume Semantics
- **No Blind Reinsertion**: Tracks in `ADDED` status are **never** reinserted.
- **Interrupted `ADDING` Safety**: If the server crashes while a track is in `ADDING` status, a subsequent resume detects the stale `ADDING` state. Because the remote mutation may have succeeded on YouTube before the crash, the track is **not** reset to `PENDING` (which would cause duplicates). Instead, it is transitioned to `UNCERTAIN`, and the execution is marked `RECOVERY_REQUIRED`.
- **Eligible Tracks for Resume**: Only tracks with status `PENDING` (or explicit retries authorized by policy) are eligible for insertion.
- **Order Invariant**: Resumed insertions strictly maintain ascending `targetPosition` order.

### 5. Short Transaction Boundaries
- Remote YouTube API calls (`MusicDestination.addTrackToPlaylist`) are **never** wrapped in a single long-lived database transaction.
- Individual track transitions are committed in discrete transactions:
  1. Track marked `ADDING` $\to$ committed.
  2. Remote call executed.
  3. Track marked `ADDED`, `FAILED`, or `UNCERTAIN` $\to$ committed.
- This prevents database connection pool exhaustion and deadlocks during external API latency.

### 6. Credential Sanitization
- Upstream error messages are scrubbed using `TOKEN_PATTERN` (`bearer`, `access_token=`, `api_key=`, `secret=`, etc.) before being persisted to `error_message` columns or logs.

---

## Contract Specifications

### Package: `com.spotifyyoutube.migrator.migration.execution.domain`
- `MigrationExecution`: Entity for `migration_executions`.
- `ExecutionTrackRecord`: Entity for `execution_track_records`.
- `MigrationExecutionStatus`: Enum (`INITIALIZED`, `RUNNING`, `PARTIALLY_COMPLETED`, `COMPLETED`, `FAILED`, `RECOVERY_REQUIRED`).
- `ExecutionTrackStatus`: Enum (`PENDING`, `ADDING`, `ADDED`, `FAILED`, `UNCERTAIN`, `SKIPPED`).

### Package: `com.spotifyyoutube.migrator.migration.execution.repository`
- `MigrationExecutionRepository`: Spring Data repository for `MigrationExecution`.
- `ExecutionTrackRecordRepository`: Spring Data repository for `ExecutionTrackRecord`.

### Package: `com.spotifyyoutube.migrator.migration.application.execution`
- `MigrationExecutionStateService`: Application service managing durable execution lifecycle, initialization, execution, and safe resume.
- `DefaultMigrationExecutionStateService`: Implementation coordinating transactional transitions and provider mutations.

### Package: `com.spotifyyoutube.migrator.migration.api`
- Endpoints in `MigrationExecutionController`:
  - `POST /api/migrations/execution/initialize`: Initialize durable execution.
  - `GET /api/migrations/execution/{executionId}`: Get execution details.
  - `GET /api/migrations/execution/session/{sessionId}`: Get execution by review session ID.
  - `POST /api/migrations/execution/{executionId}/resume`: Safe resume of eligible pending tracks.

---

## Consequences

### Positive
- Execution state survives process restarts and server crashes.
- Confirmed playlist identity is durably associated with the review session and user.
- Prevents duplicate track insertions on retries.
- Concurrency locks prevent simultaneous execution of the same migration.
- Safe resume preserves relative ordering and respects prior outcomes.

### Negative / Trade-offs
- Ambiguous timeouts require manual review or future Phase 11G remote reconciliation if YouTube committed the video.
- Additional database tables and migration overhead.
