# Phase 14A — Architecture & Existing-System Audit Report
## Spotify-to-YouTube Migrator

**Date:** 2026-11-17  
**Author:** AI Engineering Agent  
**Status:** COMPLETE  
**Target Subphases:** Phase 14A (Architecture Audit) & Phase 14B (Persistent Migration Records)

---

## 1. Executive Summary

This audit report thoroughly inspects the existing architecture of the Spotify-to-YouTube Migrator across Phases 9 through 13 to establish a persistent, reliable migration-history foundation for Phase 14B.

The audit confirms that the codebase possesses a mature, highly resilient execution aggregate (`MigrationExecution` and `ExecutionTrackRecord`), backed by PostgreSQL, Flyway migrations (V1 through V14), generational fencing, durable leases, API idempotency, and rollback audit retention.

Rather than introducing redundant entities, competing state machines, or disjoint history recording pathways, Phase 14B builds directly on the established `MigrationExecution` and `ExecutionTrackRecord` domain model. This audit identifies the exact missing history fields (source track count, match count, top-level failure summary, track-level match score, decision provenance, and candidate metadata) and defines the minimal, backward-compatible persistence and lifecycle changes required to complete Phase 14B.

---

## 2. Existing Migration-Related Entities & Responsibilities

| Entity | Table | Responsibilities | Key Fields |
| :--- | :--- | :--- | :--- |
| **`MigrationExecution`** | `migration_executions` | Primary aggregate root for approved migrations (Phases 11–13). Enforces lifecycle state, concurrency, worker leases, optimistic locking, and counter conservation. | `id`, `user_id`, `review_session_id`, `plan_id`, `source_playlist_id`, `source_playlist_title`, `destination_platform`, `destination_playlist_id`, `destination_playlist_url`, `status`, `total_approved_tracks`, `successful_insertions`, `failed_insertions`, `uncertain_insertions`, `skipped_insertions`, `rolled_back_insertions`, `started_at`, `completed_at`, `lease_owner`, `lease_until`, `lease_generation`, `playlist_creation_operation_id`, `rollback_requested_at`, `version` |
| **`ExecutionTrackRecord`** | `execution_track_records` | Child entity persisting the outcome of an individual approved track's insertion into the destination playlist. | `id`, `execution_id`, `target_position`, `source_position`, `source_track_id`, `source_title`, `source_artist`, `destination_track_id`, `destination_playlist_item_id`, `status`, `error_message`, `attempt_count`, `max_attempts`, `next_attempt_at`, `started_at`, `completed_at`, `version` |
| **`ReviewSession`** | `review_sessions` | Human review session (Phase 10) binding user, source playlist, and pre-flight plan. Enforces completion invariants. | `id`, `user_id`, `source_playlist_id`, `source_playlist_title`, `plan_id`, `status`, `total_tracks`, `completed_at` |
| **`ReviewTrack`** | `review_tracks` | Stores human review decisions while preserving original automatic matching provenance. | `id`, `session_id`, `position`, `source_track_id`, `source_title`, `source_artist`, `original_classification`, `original_candidate_id`, `original_candidate_title`, `original_candidate_channel`, `original_candidate_duration_ms`, `original_match_score`, `original_calibrated_probability`, `original_match_decision`, `original_match_reasons`, `review_status`, `decision_source`, `selected_candidate_id`, `selected_candidate_title`, `selected_candidate_channel`, `is_excluded`, `has_conflict` |
| **`ExecutionRollbackRecord`** | `execution_rollback_records` | Audit log for compensating deletions from destination playlists (Phase 11F). Protected by `ON DELETE RESTRICT` (V8). | `id`, `execution_id`, `track_record_id`, `target_position`, `destination_track_id`, `destination_playlist_item_id`, `status`, `error_message`, `attempted_at`, `completed_at` |
| **`ExecutionReconciliationRecord`**| `execution_reconciliation_records` | Audit log for remote destination state inspections (Phase 11G). Protected by `ON DELETE RESTRICT` (V9). | `id`, `execution_id`, `track_record_id`, `reconciliation_type`, `previous_track_status`, `resolved_track_status`, `reconciliation_outcome`, `evidence_summary`, `failure_code` |
| **`MigrationIdempotencyRecord`** | `migration_idempotency_keys` | Manages API idempotency tokens, request fingerprints, worker claim stages, and cached responses (Phase 13E). | `id`, `user_id`, `idempotency_key`, `request_fingerprint`, `execution_id`, `status`, `claim_stage`, `claim_owner`, `claim_generation`, `response_status`, `response_payload`, `expires_at` |
| **`MigrationJob` / `MigrationTask`** | `migration_jobs`, `migration_tasks` | Early legacy entities (Phase 1/2/8). Retained solely for backwards compatibility with legacy direct migration endpoints. Bypassed by modern execution engine. | `id`, `user_id`, `source_playlist_id`, `status`, `total_tracks`, `processed_tracks`, `failed_tracks` |

---

## 3. Existing Migration-Track Persistence & Outcomes

Track outcomes are recorded in `ExecutionTrackRecord` across a deterministic lifecycle:

```
           [PENDING]
               │
               ▼
           [ADDING] ──────────────┐ (interrupted / lease expired)
          ╱        ╲              ▼
         ▼          ▼        [UNCERTAIN]
      [ADDED]    [FAILED]         │ (reconciliation / recovery)
         │          │             ▼
         │          └───────► [PENDING] (retry attempt)
         ▼
   [ROLLING_BACK]
         │
         ▼
   [ROLLED_BACK]
```

- **`PENDING`**: Initial state waiting for sequential worker dispatch.
- **`ADDING`**: In-flight state recorded in a short database transaction immediately before invoking the destination provider.
- **`ADDED`**: Confirmed remote playlist insertion. The destination provider item ID (`destinationPlaylistItemId`) is recorded and never overwritten.
- **`FAILED`**: Confirmed provider rejection or exhausted retry attempts. Accompanied by sanitized `errorMessage`.
- **`UNCERTAIN`**: Ambiguous remote outcome (e.g., connection reset, HTTP 500–504, or unconfirmed worker crash). Requires reconciliation before retry.
- **`SKIPPED`**: Tracks remaining after an unrecoverable failure, marked skipped to preserve playlist ordering integrity.
- **`ROLLING_BACK` / `ROLLED_BACK`**: Compensating mutation lifecycle for partial or complete rollback.

---

## 4. Existing Migration Lifecycle & Status Transitions

The authoritative migration state machine is defined in `MigrationExecutionStatus`:

```
               [ALLOCATING_DESTINATION] (Phase 13F)
                          │
                          ▼
                    [INITIALIZED]
                          │
                          ▼
                       [QUEUED] (Phase 12B/13D)
                          │
                          ▼
                       [RUNNING]
                     ╱    │    ╲
                    ▼     ▼     ▼
         [COMPLETED] [PARTIALLY_COMPLETED] [FAILED]
                          │
                          ▼
                 [RECOVERY_REQUIRED]
                          │
                          ▼
                 [PARTIALLY_ROLLED_BACK]
                          │
                          ▼
                    [ROLLED_BACK]
```

- **Transitions**:
  - `INITIALIZED` / `QUEUED` $\to$ `RUNNING` via conditional versioned claim (`claimExecutionRunningWithVersion`).
  - `RUNNING` $\to$ `COMPLETED` when all approved tracks are `ADDED`.
  - `RUNNING` $\to$ `PARTIALLY_COMPLETED` when some tracks are `ADDED` and remaining tracks are `FAILED` or `SKIPPED`.
  - `RUNNING` $\to$ `FAILED` when 0 tracks are `ADDED` and attempts failed.
  - `RUNNING` $\to$ `RECOVERY_REQUIRED` when `UNCERTAIN` tracks exist or crash recovery detected stale `ADDING` tracks.
  - `RUNNING` $\to$ `QUEUED` when track-level retry backoff is scheduled via cooperative lease release.

---

## 5. Existing Retry & Idempotency Mechanisms

1. **Provider Failure Classification (Phase 13B)**:
   - `ProviderFailureClassifier` maps exceptions into categorized failures (`TRANSIENT_NETWORK`, `RATE_LIMITED`, `AUTHENTICATION`, `QUOTA_EXHAUSTED`, `RESOURCE_NOT_FOUND`, `INVALID_REQUEST`, `SERVER_ERROR`, `UNKNOWN`).
2. **Retry Policy & Backoff (Phase 13C)**:
   - `DefaultRetryPolicy` evaluates retry budgets and schedules in-flight or deferred retries using exponential backoff with full jitter and `Retry-After` header parsing.
3. **Durable Worker Leases & Generational Fencing (Phase 13D)**:
   - `MigrationExecutionRepository.claimNextEligibleExecution` uses atomic row locking (`FOR UPDATE SKIP LOCKED`).
   - Every mutation checks worker identity, fencing generation, and database wall-clock lease validity (`lease_until > clock_timestamp()`).
4. **API Idempotency Contract (Phase 13E)**:
   - `ApiIdempotencyFilter` and `MigrationIdempotencyService` enforce atomic CAS claiming across request fingerprints, caching terminal responses for 72 hours.
5. **External Operation Idempotency (Phase 13F)**:
   - Destination playlist creation generates a UUID `playlist_creation_operation_id` to correlate and reconcile remote creation before allocating track records.
6. **Rollback Intent & Crash Recovery (Phase 13G)**:
   - Durable rollback intent (`rollback_requested_at`, `rollback_target_positions`) survives node crashes and resumes compensations safely.

---

## 6. Existing Database Schema & Relationships

The Flyway migration history consists of 14 applied migrations:

- `V1__baseline.sql` & `V2__init_schema.sql`: Base tables (`users`, `playlists`, `tracks`, `migration_jobs`, `migration_tasks`).
- `V3__phase2_auth_oauth_schema.sql` & `V4__phase2_oauth_connection_status.sql`: OAuth tokens and connection state.
- `V5__phase10_review_session_schema.sql`: `review_sessions` and `review_tracks`.
- `V6__phase11e_durable_execution_schema.sql`: `migration_executions` and `execution_track_records`.
- `V7__phase11f_failure_recovery_and_rollback_schema.sql`: `rolled_back_insertions` column and `execution_rollback_records` table.
- `V8__phase11f_rollback_audit_retention.sql`: `ON DELETE RESTRICT` constraint on rollback records.
- `V9__phase11g_reconciliation_schema.sql`: `execution_reconciliation_records` table with `ON DELETE RESTRICT`.
- `V10__phase13d_durable_leases_and_retry_scheduling.sql`: Leases, fencing tokens, and track retry metadata.
- `V11__phase13e_api_idempotency_keys.sql`: `migration_idempotency_keys` table.
- `V12__phase13e_idempotency_claim_protocol.sql`: Atomic idempotency claim protocol columns.
- `V13__phase13f_external_operation_safety.sql`: Relaxed NOT NULL on destination playlist ID, `playlist_creation_operation_id`.
- `V14__phase13g_recovery_and_rollback_intent.sql`: Rollback intent tracking columns and partial index.

Foreign key relationships:
- `migration_executions.user_id` $\to$ `users.id` (`ON DELETE CASCADE`)
- `migration_executions.review_session_id` $\to$ `review_sessions.id` (`ON DELETE CASCADE`, `UNIQUE`)
- `execution_track_records.execution_id` $\to$ `migration_executions.id` (`ON DELETE CASCADE`)
- `execution_rollback_records.execution_id` $\to$ `migration_executions.id` (`ON DELETE RESTRICT`)
- `execution_reconciliation_records.execution_id` $\to$ `migration_executions.id` (`ON DELETE RESTRICT`)
- `migration_idempotency_keys.execution_id` $\to$ `migration_executions.id` (`ON DELETE RESTRICT`)

---

## 7. Existing APIs Exposing Migration Information

- **`MigrationExecutionController`**:
  - `GET /api/migrations/execution/{executionId}`: Full durable execution summary with all track records.
  - `GET /api/migrations/execution/session/{sessionId}`: Execution lookup by review session ID.
  - `GET /api/migrations/execution/{executionId}/rollbacks`: Audit log of rollback attempts.
  - `GET /api/migrations/execution/{executionId}/reconciliations`: Audit log of reconciliation inspections.
  - `GET /api/migrations/execution/{executionId}/reconciliation-status`: Uncertain tracks requiring resolution.
- **`MigrationController`**:
  - `GET /api/migrations/{executionId}/progress`: Polling progress snapshot (`MigrationProgressSnapshot`).
  - `GET /api/migrations/review-sessions/{reviewSessionId}/progress`: Progress snapshot by review session ID.
  - Legacy endpoints: `GET /api/migrations/{id}`, `GET /api/migrations/{id}/tasks`.

---

## 8. Missing History Capabilities (Phase 14B Gaps)

1. **Top-Level Migration Summary Gaps in `MigrationExecution`**:
   - `total_source_tracks`: Count of original source playlist tracks is evaluated during review (`ReviewSession.totalTracks` and `ApprovedMigrationContext.totalSourceTracks`) but not persisted on `MigrationExecution`.
   - `matched_tracks`: Count of tracks that received approved matches is not explicitly persisted on `MigrationExecution`.
   - `failure_summary`: When an execution ends in `FAILED`, `PARTIALLY_COMPLETED`, or `RECOVERY_REQUIRED`, no top-level explanation is persisted on `migration_executions`.
2. **Track-Level Provenance & Outcome Gaps in `ExecutionTrackRecord`**:
   - `destination_title`: Destination YouTube video title is available in `ApprovedTrack` and `ReviewTrack`, but dropped when constructing `ExecutionTrackRecord`.
   - `destination_channel`: Destination YouTube channel/uploader name is dropped.
   - `destination_duration_ms`: Destination track duration is dropped.
   - `decision_source`: Provenance (`AUTOMATIC` vs `USER_SELECTED`) is available in `ApprovedTrack` and `ReviewTrack`, but not stored on `ExecutionTrackRecord`.
   - `match_score`: Original match confidence score ($0.0 - 1.0$) is not stored on `ExecutionTrackRecord`.
   - `match_decision`: Match classification decision (`DEFINITE_MATCH`, etc.) is not stored on `ExecutionTrackRecord`.
3. **Database Indexing Gaps for Historical Querying**:
   - No composite index on `(user_id, created_at DESC)` on `migration_executions` for fast pagination of user history.
4. **Repository Query Gaps**:
   - `MigrationExecutionRepository` lacks `Pageable` support for reverse-chronological user history queries.

---

## 9. Reusable Components

- **Aggregate Entities**: `MigrationExecution` and `ExecutionTrackRecord` are completely reusable and require only additive extension.
- **Lifecycle & State Machine**: `MigrationExecutionStatus` and `ExecutionTrackStatus` already define all necessary lifecycle states.
- **Service Layer**: `DefaultMigrationExecutionStateService`, `ApprovedMigrationOrchestrator`, and `DefaultApprovedPlanValidator` already own the transaction and validation boundaries.
- **Transaction Discipline**: Existing short-transaction pattern around external network calls ensures high performance and prevents lock starvation.

---

## 10. Potential Data-Integrity Risks & Mitigations

1. **Risk:** Counter drift between stored counters and child track records.  
   **Mitigation:** `MigrationExecution.reconcileCounts()` dynamically derives all counters directly from child track records upon every lifecycle settlement.
2. **Risk:** User-selected match overwritten by automatic match on retry.  
   **Mitigation:** `decision_source` is durably persisted on `ExecutionTrackRecord`. When a migration resumes or retries, existing track records are loaded from the database without re-evaluating matching.
3. **Risk:** Completed tracks re-inserted during resume or recovery.  
   **Mitigation:** The resume and recovery workflows filter strictly for `PENDING` tracks; `ADDED` tracks are immutable.
4. **Risk:** Database migration failure on existing databases.  
   **Mitigation:** `V15` Flyway migration adds nullable or defaulted columns, with safe backfilling from existing columns.

---

## 11. Proposed Architecture Decisions for Phase 14B

1. **Add Additive Columns to `migration_executions` via Flyway `V15`**:
   - `total_source_tracks INT NOT NULL DEFAULT 0`
   - `matched_tracks INT NOT NULL DEFAULT 0`
   - `failure_summary TEXT`
   - Composite index: `idx_migrationexecutions_user_created ON migration_executions(user_id, created_at DESC)`
2. **Add Additive Columns to `execution_track_records` via Flyway `V15`**:
   - `destination_title VARCHAR(500)`
   - `destination_channel VARCHAR(500)`
   - `destination_duration_ms BIGINT`
   - `decision_source VARCHAR(50)`
   - `match_score DOUBLE PRECISION`
   - `match_decision VARCHAR(50)`
3. **Lifecycle Integration**:
   - `DefaultApprovedPlanValidator`: Include `matchScore` and `matchDecision` in `ApprovedTrack`.
   - `MigrationExecution`: Populate `totalSourceTracks` and `matchedTracks` from `ApprovedMigrationContext`. Populate `failureSummary` when destination playlist creation fails or upon final count reconciliation.
   - `ExecutionTrackRecord`: Copy destination metadata, provenance, and match score from `ApprovedTrack`.
4. **DTO & Presentation Alignment**:
   - Update `ExecutionTrackRecordDto`, `ExecutionTrackRecordResponse`, `MigrationExecutionSummary`, and `MigrationExecutionResponse` with backward-compatible constructors.
5. **Repository Enhancements**:
   - Add `findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable)` to `MigrationExecutionRepository`.
