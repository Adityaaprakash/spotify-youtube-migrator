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
   Provider explicitly rejected the mutation with a transient status (HTTP 429 Too Many Requests, HTTP 503 Service Unavailable, HTTP 500 Internal Server Error, or network I/O timeout `ResourceAccessException`). Safe for controlled retry/recovery.
2. **`CONFIRMED_NON_RETRYABLE_PROVIDER_REJECTION`**:
   Provider rejected the mutation deterministically (HTTP 400 Bad Request, playlist track quota exceeded, duplicate item rejected). Automatic retries will fail; requires user intervention or alternative candidate selection.
3. **`AUTHENTICATION_OR_AUTHORIZATION_FAILURE`**:
   OAuth2 token expired, revoked, or insufficient scope (HTTP 401 Unauthorized, HTTP 403 Forbidden). Requires re-authentication before recovery can proceed.
4. **`INVALID_DESTINATION_RESOURCE`**:
   Target playlist or video does not exist or was deleted on YouTube (HTTP 404 Not Found, `ResourceNotFoundException`).
5. **`LOCAL_PERSISTENCE_FAILURE`**:
   Database constraint violation, transaction timeout, or optimistic lock failure (`OptimisticLockingFailureException`, `CannotAcquireLockException`).
6. **`CONFIRMED_PROVIDER_OPERATION_FAILURE`**:
   Provider returned an explicit business failure payload.
7. **`MANUAL_INTERVENTION_REQUIRED`**:
   Unexpected state anomaly or unclassified terminal error requiring operator inspection.

> **CRITICAL BOUNDARY RULE:** Ambiguous outcomes (where a socket timed out during the request and the remote state is unknown) are classified as `UNCERTAIN` and strictly handled under Phase 11G. Controlled recovery **never** blindly treats `UNCERTAIN` tracks as confirmed failures.

---

### 2. Controlled Recovery Architecture

Controlled recovery allows an execution containing confirmed `FAILED` tracks to safely re-attempt insertion.

#### Invariants Enforced During Recovery:
1. **Strict Preservation of Confirmed Work**: Tracks already in status `ADDED` are **never** re-inserted.
2. **Canonical Ordering**: Tracks are executed strictly in their canonical 0-indexed destination position (`canonicalTargetOrder`).
3. **Uncertain Protection**: If an execution contains any tracks in status `UNCERTAIN` (or `uncertainInsertions > 0`), recovery is **strictly rejected** (HTTP 409 Conflict). Ambiguous outcomes must be reconciled before recovery can proceed.
4. **Completed Protection**: Executions with status `COMPLETED` cannot be recovered (HTTP 409 Conflict).
5. **Concurrency Lock**: Recovery transitions the execution to `RUNNING`. If the execution is already `RUNNING`, concurrent recovery is rejected.
6. **Isolated Provider Calls**: External provider calls execute strictly **outside** active database transactions to prevent long-running locks or connection starvation.
7. **Accurate Aggregate Reconciliation**: Upon recovery completion, aggregate counters (`successfulInsertions`, `failedInsertions`, `pendingInsertions`) and execution status (`COMPLETED`, `PARTIALLY_COMPLETED`, or `FAILED`) are transactionally reconciled.

---

### 3. Safe Partial Rollback Architecture

Partial rollback provides safe compensating deletion of items previously inserted into the destination playlist.

#### Provider Deletion Identity:
- A YouTube Video ID (e.g. `dQw4w9WgXcQ`) is **not** a playlist item resource.
- YouTube Data API v3 requires the unique `playlistItemId` returned upon insertion: `DELETE https://www.googleapis.com/youtube/v3/playlistItems?id={playlistItemId}`.
- Phase 11E already persists `destinationPlaylistItemId` in `execution_track_records`.

#### Invariants Enforced During Rollback:
1. **Scope Confinement**: Rollback requests can only target playlist items belonging to the verified execution. Attempting to delete items from other playlists or executions throws an error.
2. **Selective or Complete Rollback**: Callers may provide an explicit list of `destinationPlaylistItemId` targets, or omit the list to roll back all confirmed `ADDED` tracks in the execution.
3. **Remote 404 Semantics**: If YouTube returns HTTP 404 Not Found on deletion, the item has already been deleted on the provider. The system treats this safely as a confirmed deletion and updates state to `ROLLED_BACK`.
4. **State-Level Idempotency**: Repeating a rollback request on an already `ROLLED_BACK` item bypasses remote provider calls and records an `ALREADY_ROLLED_BACK` audit outcome.
5. **Non-Atomic Multi-Item Execution**: Deletions across multiple items execute sequentially. If item $k$ fails due to a provider rejection, previous deletions remain confirmed and persisted, and the failed item is recorded as `FAILED`.
6. **Audit Persistence**: Every rollback attempt (success, idempotent no-op, or failure) is immutably recorded in the `execution_rollback_records` table.
7. **Lifecycle State Transitions**:
   - If all confirmed `ADDED` tracks are rolled back: execution status transitions to `ROLLED_BACK`.
   - If a subset of tracks is rolled back or some deletions fail: execution status transitions to `PARTIALLY_ROLLED_BACK`.
   - Confirmed rolled-back count is tracked in `migration_executions.rolled_back_insertions`.

---

### 4. Database Schema Changes (Flyway Migration `V7`)

Flyway migration `V7__phase11f_failure_recovery_and_rollback_schema.sql` introduces:

1. **Column Addition to `migration_executions`**:
   - `rolled_back_insertions INT NOT NULL DEFAULT 0`
2. **New Audit Table `execution_rollback_records`**:
   - `id UUID PRIMARY KEY`
   - `execution_id UUID NOT NULL REFERENCES migration_executions(id) ON DELETE CASCADE`
   - `execution_track_record_id UUID REFERENCES execution_track_records(id) ON DELETE SET NULL`
   - `destination_playlist_item_id VARCHAR(255) NOT NULL`
   - `status VARCHAR(50) NOT NULL` (`ROLLED_BACK`, `ALREADY_ROLLED_BACK`, `FAILED`)
   - `error_message TEXT`
   - `reason VARCHAR(500)`
   - `attempted_at TIMESTAMPTZ NOT NULL`
   - Indexes: `idx_exec_rollback_execution_id` on `execution_id`, `idx_exec_rollback_item_id` on `destination_playlist_item_id`.

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
