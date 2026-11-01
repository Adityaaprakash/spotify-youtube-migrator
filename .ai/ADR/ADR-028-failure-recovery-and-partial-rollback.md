# ADR-028: Failure Recovery and Partial Rollback for Destination Execution (Phase 11F)

## Status
**ACCEPTED / IMPLEMENTED**

## Context

Following the completion of Phase 11E (Durable Execution State, Concurrency Protection, and Safe Resume), migration executions maintain durable records in PostgreSQL (`migration_executions` and `execution_track_records`). 

However, two operational capabilities remained unaddressed prior to Phase 11F:

1. **Controlled Failure Recovery**:
   When an execution encountered a confirmed failure on one or more tracks (e.g. temporary YouTube rate limits or transient network stalls during insertion), the execution halted in `PARTIALLY_COMPLETED` or `FAILED`. Resuming via Phase 11E resumed only unattempted `PENDING` tracks; it did not allow re-attempting confirmed `FAILED` tracks without corrupting state or requiring complete re-execution from scratch. Furthermore, attempts to blindly retry without failure classification risked infinite retry loops on non-retryable provider rejections.

2. **Partial Rollback (Compensating Mutations)**:
   If an execution partially failed or if a user aborted or requested undo after partial completion, the application had no safe mechanism to remove inserted tracks from the destination playlist. Without a verified rollback mechanism, partial playlists were left littered with orphaned tracks.

### Relationship to Prior Architectural Decisions
- **ADR-022**: Migration plans remain transient in-memory previews; execution state is durably modeled.
- **ADR-023**: Review sessions and user decisions are immutable once approved.
- **ADR-024 & ADR-025**: Playlist creation strictly isolates playlist generation from track insertion.
- **ADR-026 & ADR-027**: Canonical 0-indexed destination ordering and durable execution tracking are preserved.

---

## Architectural Decisions

### 1. Failure Classification Engine (`ExecutionFailureClassifier`)
Phase 11F introduces a dedicated failure classification engine that inspects provider and local exceptions to distinguish the underlying operational failure mode.

The failure taxonomy comprises 7 explicit classifications:
1. **`CONFIRMED_RETRYABLE_PROVIDER_REJECTION`**:
   Provider explicitly rejected an idempotent operation prior to mutation processing with a transient status (e.g. HTTP 429 Too Many Requests, or HTTP 503 / network timeout during idempotent READ operations). Safe for retry.
2. **`CONFIRMED_NON_RETRYABLE_PROVIDER_REJECTION`**:
   Provider rejected the mutation deterministically (HTTP 400 Bad Request, playlist track quota exceeded, duplicate item rejected). Automatic retries will fail; requires user intervention or alternative candidate selection.
3. **`AUTHENTICATION_OR_AUTHORIZATION_FAILURE`**:
   OAuth2 token expired, revoked, or insufficient scope (HTTP 401 Unauthorized, HTTP 403 Forbidden without quota). Requires re-authentication before recovery can proceed.
4. **`INVALID_DESTINATION_RESOURCE`**:
   Target playlist or video does not exist or was deleted on YouTube (HTTP 404 Not Found, `ResourceNotFoundException`).
5. **`LOCAL_PERSISTENCE_FAILURE`**:
   Database constraint violation, transaction timeout, or optimistic lock failure (`OptimisticLockingFailureException`, `CannotAcquireLockException`).
6. **`CONFIRMED_PROVIDER_OPERATION_FAILURE`**:
   Provider returned an explicit business failure payload.
7. **`MANUAL_INTERVENTION_REQUIRED` (UNCERTAIN Mutation Outcomes)**:
   Any remote mutation (track insertion or compensating deletion) that fails due to a network timeout (`ResourceAccessException`) or server error (HTTP 500/503) where the remote state is ambiguous is strictly classified as `MANUAL_INTERVENTION_REQUIRED` (`retryable = false`, `requiresManualIntervention = true`).

> **CRITICAL BOUNDARY RULE:** Ambiguous mutation outcomes (where a socket dropped or 500/503 was returned during POST/DELETE and remote state is unknown) are marked `UNCERTAIN` and strictly preserved for Phase 11G dedicated reconciliation. Controlled recovery **never** blindly treats `UNCERTAIN` tracks as confirmed failures, eliminating duplicate remote mutations.

---

### 2. Controlled Recovery Architecture

Controlled recovery allows an execution containing confirmed `FAILED` tracks to safely re-attempt insertion.

#### Invariants Enforced During Recovery:
1. **Strict Preservation of Confirmed Work**: Tracks already in status `ADDED` are **never** re-inserted.
2. **Canonical Ordering**: Tracks are executed strictly in their canonical 0-indexed destination position (`canonicalTargetOrder`).
3. **Uncertain Protection**: If an execution contains any tracks in status `UNCERTAIN` (or `uncertainInsertions > 0`), recovery is **strictly rejected** (HTTP 409 Conflict). Ambiguous outcomes must be reconciled in Phase 11G before recovery can proceed.
4. **Completed Protection**: Executions with status `COMPLETED` cannot be recovered (HTTP 409 Conflict).
5. **Cross-Instance Atomic Concurrency Lock**: Recovery claims the execution using `MigrationExecutionRepository.claimExecutionRunningWithVersion` within a short transaction. The query executes a conditional SQL update checking that the execution is in an eligible status (`PARTIALLY_COMPLETED`, `FAILED`, `RECOVERY_REQUIRED`, `PARTIALLY_ROLLED_BACK`) and that its version matches `expectedVersion`. If another instance claimed or modified the execution concurrently, 0 rows are updated, throwing `InvalidStateException` (HTTP 409 Conflict). `@Modifying(clearAutomatically = true, flushAutomatically = true)` clears the persistence context to prevent stale in-memory state.
6. **Lease-Aware Crash Reconciliation**: Stale in-flight states (`ADDING` or `ROLLING_BACK`) are evaluated against a defined 5-minute lease threshold (`DEFAULT_STALE_THRESHOLD`). Active, slow operations whose `startedAt` is within the lease window are preserved and not marked uncertain. Only operations exceeding 5 minutes are classified as abandoned and transitioned to `UNCERTAIN`.
7. **Isolated Provider Calls**: External provider calls execute strictly **outside** active database transactions to prevent long-running locks or connection starvation.
8. **Accurate Aggregate Reconciliation**: Upon recovery completion, aggregate counters (`successfulInsertions`, `failedInsertions`, `uncertainInsertions`, `skippedInsertions`, `rolledBackInsertions`) and execution status are transactionally reconciled directly from persisted track records.

---

### 3. Safe Partial Rollback Architecture

Partial rollback provides safe compensating deletion of items previously inserted into the destination playlist.

#### Provider Deletion Identity:
- A YouTube Video ID (e.g. `dQw4w9WgXcQ`) is **not** a playlist item resource.
- YouTube Data API v3 requires the unique `playlistItemId` returned upon insertion: `DELETE https://www.googleapis.com/youtube/v3/playlistItems?id={playlistItemId}`.
- Phase 11E already persists `destinationPlaylistItemId` in `execution_track_records`.

#### Invariants Enforced During Rollback:
1. **Execution-Level Atomic Claim**: Rollback claims the execution using `MigrationExecutionRepository.claimExecutionRunningWithVersion` checking eligible statuses (`PARTIALLY_COMPLETED`, `COMPLETED`, `PARTIALLY_ROLLED_BACK`, `RECOVERY_REQUIRED`, `FAILED`) and expected version. Concurrent claims fail fast with HTTP 409 Conflict.
2. **Track-Level Atomic Claim (`ROLLING_BACK`)**: Before calling `MusicDestination.removeTrackFromPlaylist`, each track is atomically claimed via `ExecutionTrackRecordRepository.claimTrackRollingBack`. The query conditionally updates status from `ADDED` to `ROLLING_BACK` with row-level locking. If a concurrent rollback request or instance already claimed the track, 0 rows are updated, preventing duplicate remote DELETE calls.
3. **Scope Confinement**: Rollback requests can only target playlist items belonging to the verified execution. Attempting to delete items from other playlists or executions throws an error.
4. **Selective or Complete Rollback**: Callers may provide an explicit list of canonical `targetPositions`, or omit the list to roll back all confirmed `ADDED` tracks in the execution.
5. **Remote 404 Semantics**: If YouTube returns HTTP 404 Not Found on deletion, the item has already been deleted on the provider. The system treats this safely as a confirmed deletion and updates state to `ROLLED_BACK`.
6. **State-Level Idempotency**: Repeating a rollback request on an already `ROLLED_BACK` item bypasses remote provider calls and records an `ALREADY_ROLLED_BACK` audit outcome.
7. **Mutation Ambiguity Handling**: If remote deletion encounters a network timeout (`ResourceAccessException`) or HTTP 500/503 server error, the track status transitions to `UNCERTAIN` and an audit record with status `UNCERTAIN` is persisted. The execution transitions to `RECOVERY_REQUIRED`.
8. **Failure Reversion**: On confirmed provider rejection (e.g. HTTP 400, 401, 403), the track reverts from `ROLLING_BACK` back to `ADDED`, allowing future retry or inspection, and an audit record with status `FAILED` is saved.
9. **Audit Persistence & Retention**: Every rollback attempt (success, idempotent no-op, failure, or uncertain) is immutably recorded in the `execution_rollback_records` table with `ON DELETE RESTRICT` foreign keys.
10. **Lifecycle State Transitions & Aggregate Integrity**:
    - If `successfulInsertions == totalApprovedTracks`: execution status transitions to `COMPLETED`.
    - If `uncertainInsertions > 0 || hasStaleAdding()`: execution status transitions to `RECOVERY_REQUIRED`.
    - If `successfulInsertions == 0 && rolledBackInsertions > 0`: execution status transitions to `ROLLED_BACK` (terminal).
    - If `successfulInsertions > 0 && rolledBackInsertions > 0`: execution status transitions to `PARTIALLY_ROLLED_BACK`.
    - If `successfulInsertions > 0`: execution status transitions to `PARTIALLY_COMPLETED`.
    - All summary counters are derived directly from persisted track records in `reconcileCounts()`, preventing counter drift or double-incrementing.

---

### 4. Database Schema Changes (Flyway Migrations `V7` and `V8`)

1. **Flyway Migration `V7` (`V7__phase11f_failure_recovery_and_rollback_schema.sql`)**:
   - Column `rolled_back_insertions INT NOT NULL DEFAULT 0` on `migration_executions`.
   - Audit table `execution_rollback_records` with foreign keys referencing `migration_executions` and `execution_track_records`.
2. **Flyway Migration `V8` (`V8__phase11f_rollback_audit_retention.sql`)**:
   - Alters foreign key constraints `fk_rollback_execution` and `fk_rollback_track_record` from `ON DELETE CASCADE` to `ON DELETE RESTRICT`.
   - Enforces audit retention at the PostgreSQL database level: prevents silent loss of compensating mutation history if an execution or track record deletion is attempted.

---

### 5. API Endpoints

1. **`POST /api/migrations/execution/{executionId}/recover`**
   - Recovers eligible failed tracks and executes insertion.
   - Body (optional): `RecoverExecutionApiRequest` (`userId`, `reason`).
   - Returns: `MigrationExecutionResponse`.
2. **`POST /api/migrations/execution/{executionId}/rollback`**
   - Partially rolls back specified or all confirmed inserted items.
   - Body: `RollbackExecutionApiRequest` (`userId`, `playlistItemIds`, `reason`).
   - Returns: `RollbackExecutionResponse`.
3. **`GET /api/migrations/execution/{executionId}/rollbacks`** (alias: `/rollback-history`)
   - Returns immutable audit list of rollback operations for the execution.
   - Returns: `List<RollbackRecordResponse>`.

Security enforcement:
- Anti-impersonation: Caller-supplied `userId` must match the authenticated `Principal`.
- Anti-IDOR: Execution must belong to the authenticated user (returns 404 if not owned).

---

## Explicitly Excluded Out-of-Scope Responsibilities

1. **Phase 11G (Ambiguous Outcome Reconciliation)**:
   - Automatic reconciliation of uncertain YouTube mutations or network socket drops.
   - Destination playlist synchronization against remote YouTube state.
2. **Phase 12 (Asynchronous Execution Infrastructure)**:
   - Background worker threads, job message queues, retry schedulers, or SSE/WebSocket event streaming.
   - All Phase 11F recovery and rollback methods remain strictly synchronous and deterministic.
