# ADR-029: Ambiguous Outcome Reconciliation & Remote State Verification (Phase 11G)

## Status
**ACCEPTED / IMPLEMENTED**

## Context

Following the completion of Phase 11F (Failure Recovery & Partial Rollback), migration executions maintain durable records and audit trails in PostgreSQL (`migration_executions`, `execution_track_records`, `execution_rollback_records`). Phase 11F strictly defined that any ambiguous mutation outcome (such as a dropped TCP socket, network timeout, or HTTP 500/503 during insertion or deletion) transitions the track record to status `UNCERTAIN` and the execution to `RECOVERY_REQUIRED`.

Prior to Phase 11G, the system had no mechanism to verify remote destination state or resolve these `UNCERTAIN` records:
- **Insertion Uncertainty**: If YouTube accepted a track insertion but the HTTP response timed out or was dropped, the local record was marked `UNCERTAIN`. Blindly retrying insertion risks inserting duplicate tracks into the user's destination playlist. Blindly marking it failed or skipping it risks missing songs.
- **Deletion Uncertainty**: If a compensating deletion request timed out, the local record was marked `UNCERTAIN`. Blindly repeating DELETE may fail with 404 or leave inconsistent counts.
- **Abandoned / Stale In-Flight States**: If an application instance or JVM crashed while a track was in `ADDING` or `ROLLING_BACK`, the track could remain indefinitely stuck in-flight.
- **Remote State Disagreement**: Discrepancies between local database records and the actual YouTube playlist could not be inspected or reconciled.

Phase 11G introduces a production-grade, evidence-driven, auditable reconciliation subsystem that verifies actual remote playlist state against local execution records without guesswork.

### Core Engineering Principle
> **Reconciliation is evidence-driven state resolution, not retry logic.**
> Never infer that an insertion succeeded simply because an item with the same video ID exists.
> Never infer that a mutation failed because an API request timed out.
> Never interpret an incomplete remote playlist response as proof of absence.
> When evidence is insufficient, preserve `UNCERTAIN` and require further investigation.
> The system must prefer an honest unresolved state over a potentially destructive incorrect decision.

---

## Architectural Decisions

### 1. Remote Playlist Inspection Architecture (`MusicDestination` & `YouTubePlaylistService`)

To verify remote playlist state, the system extends `MusicDestination` with an inspection capability:
`DestinationPlaylistInspection inspectPlaylist(UUID userId, String playlistId)`

#### Provider Inspection Capabilities:
- **Provider Protocol**: Calls YouTube Data API v3 endpoint:
  `GET https://www.googleapis.com/youtube/v3/playlistItems?part=snippet,contentDetails&playlistId={playlistId}&maxResults=50&pageToken={pageToken}`
- **Pagination Completeness**: YouTube returns at most 50 items per page. The service sequentially traverses pagination tokens (`nextPageToken`).
- **Defensive Safeguards**:
  - **Loop / Cycle Detection**: Tracks visited `pageToken` tokens in a `Set<String>` to prevent infinite loops from malformed or cyclic provider responses.
  - **Bounded Traversal**: Restricts traversal to a maximum of 100 pages (5,000 items), preventing out-of-memory or unbounded HTTP requests on abnormally large playlists.
  - **All-or-Nothing Completeness Rule**: If an error (e.g. timeout, 401, 403, 429, 500) occurs on *any* page (even page 5 of 6), the inspection is marked `complete = false`, failure details are captured, and the item list is discarded. **An incomplete inspection must never be treated as a complete snapshot of the playlist.** Proof of absence requires a 100% complete remote snapshot.
- **Item Identity Distinction**:
  - `destinationPlaylistItemId`: The unique ID of the item inside the playlist (e.g. `UEx...`).
  - `destinationTrackId`: The underlying YouTube Video ID (e.g. `dQw4w9WgXcQ`).
  These identifiers are strictly separated. Video identity is not equivalent to playlist item identity.

---

### 2. Evidence-Driven Insertion Reconciliation

When reconciling an `UNCERTAIN` or stale `ADDING` track record:

#### Evidence Evaluated:
1. Destination Playlist ID (`destinationPlaylistId`).
2. Expected YouTube Video ID (`destinationTrackId`).
3. Execution Attempt Timestamp (`startedAt` on the track record).
4. Item Publication Timestamp (`publishedAt` from YouTube snippet).
5. Canonical Position (`canonicalTargetOrder`).
6. Unassigned Items: Only remote items not already claimed by an existing `ADDED` track record in the execution are considered.

#### Decision Rules:
1. **Confirmed Insertion (`CONFIRMED_INSERTED`)**:
   - Exactly one unassigned remote item exists with matching `destinationTrackId`.
   - The remote item's `publishedAt` is at or after `track.startedAt` (allowing up to a 30-second window for provider clock skew).
   - **Action**: Track transitions `UNCERTAIN` $\to$ `ADDED`, stores verified `destinationPlaylistItemId`, updates counters, and records `CONFIRMED_INSERTED` audit outcome.
2. **Confirmed Not Inserted (`CONFIRMED_NOT_INSERTED`)**:
   - The remote playlist inspection completed successfully and fully (`complete == true`).
   - Zero remote playlist items exist matching the expected `destinationTrackId`.
   - **Action**: Track transitions `UNCERTAIN` $\to$ `FAILED` (with reason "Reconciliation confirmed item was not inserted"), updates counters, and records `CONFIRMED_NOT_INSERTED` audit outcome. This safely enables explicit Phase 11F recovery during subsequent workflow steps.
3. **Ambiguous Insertion (`AMBIGUOUS`)**:
   - Remote matching item exists, but its `publishedAt` timestamp predates `track.startedAt` (meaning the track was already in the playlist before this execution attempt).
   - Or, multiple matching remote items exist that cannot be uniquely correlated with this specific execution attempt.
   - **Action**: Preserve `UNCERTAIN`, keep execution in `RECOVERY_REQUIRED`, record explicit ambiguity reason in audit log.
4. **Inspection Failure (`INSPECTION_FAILED`)**:
   - Provider returned an error (timeout, 401, 403, 429, 500) or incomplete pagination.
   - **Action**: Preserve `UNCERTAIN`, preserve `RECOVERY_REQUIRED`, log sanitized error.

---

### 3. Evidence-Driven Deletion / Rollback Reconciliation

When reconciling an `UNCERTAIN` or stale `ROLLING_BACK` track record:

#### Primary Identity:
For deletion, `destinationPlaylistItemId` is the definitive, authoritative resource identifier.

#### Decision Rules:
1. **Confirmed Deleted (`CONFIRMED_DELETED`)**:
   - The inspection completed 100% successfully (`complete == true`).
   - The exact `destinationPlaylistItemId` is **absent** from the playlist snapshot.
   - **Action**: Track transitions `UNCERTAIN` $\to$ `ROLLED_BACK`, updates rollback counters, and records `CONFIRMED_DELETED` audit outcome.
2. **Confirmed Present (`CONFIRMED_PRESENT`)**:
   - The exact `destinationPlaylistItemId` is still **present** in the remote playlist snapshot.
   - **Action**: Track transitions `UNCERTAIN` $\to$ `ADDED`, restores consistent execution counters, and records `CONFIRMED_PRESENT` audit outcome. The user can now safely issue a new explicit rollback request.
3. **Ambiguous Deletion (`AMBIGUOUS`)**:
   - `destinationPlaylistItemId` was null/missing, or remote state could not establish identity.
   - **Action**: Preserve `UNCERTAIN`, preserve `RECOVERY_REQUIRED`.
4. **Inspection Failure (`INSPECTION_FAILED`)**:
   - Complete remote snapshot unavailable.
   - **Action**: Preserve `UNCERTAIN`, preserve `RECOVERY_REQUIRED`.

---

### 4. Stale In-Flight State Reconciliation

Phase 11F established persisted in-flight states (`ADDING` and `ROLLING_BACK`). Phase 11G integrates lease-aware evaluation:
- **Active Operations Protected**: Any track in `ADDING` or `ROLLING_BACK` whose `startedAt` is within the active lease window (default 5 minutes) is considered active and is **never** touched by reconciliation.
- **Abandoned Operations Reconciled**: Tracks in `ADDING` or `ROLLING_BACK` whose `startedAt` is older than 5 minutes are evaluated directly against the remote playlist:
  - Stale `ADDING` $\to$ Evaluated via Insertion Reconciliation rules.
  - Stale `ROLLING_BACK` $\to$ Evaluated via Deletion Reconciliation rules.
- **No Background Workers**: Triggered strictly on-demand via the explicit reconciliation API or sync recovery workflows.

---

### 5. Reconciliation State Machine

Explicit legal state transitions implemented in `ExecutionTrackRecord`:

```text
[INSERTION]
                 +---> CONFIRMED_INSERTED ----> ADDED
                 |
UNCERTAIN /      +---> CONFIRMED_NOT_INSERTED -> FAILED (eligible for 11F recovery)
stale ADDING ----+
                 +---> AMBIGUOUS -------------> UNCERTAIN (retains RECOVERY_REQUIRED)
                 |
                 +---> INSPECTION_FAILED -----> UNCERTAIN (retains RECOVERY_REQUIRED)

[DELETION]
                 +---> CONFIRMED_DELETED -----> ROLLED_BACK
                 |
UNCERTAIN /      +---> CONFIRMED_PRESENT -----> ADDED (eligible for 11F rollback)
stale ROLLING ---+
BACK             +---> AMBIGUOUS -------------> UNCERTAIN (retains RECOVERY_REQUIRED)
                 |
                 +---> INSPECTION_FAILED -----> UNCERTAIN (retains RECOVERY_REQUIRED)
```

Direct illegal transitions (e.g. attempting to mark `ADDED` tracks as confirmed deleted without an uncertain rollback, or changing completed tracks) are strictly rejected with `IllegalStateException`.

---

### 6. Concurrency & Distributed Safety Architecture

Reconciliation operations can run in multi-instance environments and must never race with concurrent recovery, rollback, resume, or another reconciliation request.

1. **Two-Phase Short Transaction Pattern**:
   - **Transaction 1 (Claiming)**:
     - Atomic execution claim via `claimExecutionRunningWithVersion(...)` checking version and eligible statuses (`RECOVERY_REQUIRED`, `PARTIALLY_COMPLETED`, `FAILED`, `PARTIALLY_ROLLED_BACK`).
     - Identification of eligible uncertain and stale tracks.
     - Commits immediately.
   - **External Inspection (Out-of-Transaction)**:
     - Provider inspection (`MusicDestination.inspectPlaylist`) executes strictly **outside** any active database transaction, eliminating database connection starvation, lock escalation, and transaction timeout vulnerabilities.
   - **Transaction 2 (Resolution & Audit)**:
     - Re-fetches claimed execution with optimistic locking check.
     - Evaluates evidence and applies domain state transitions.
     - Persists append-only `ExecutionReconciliationRecord` audit rows.
     - Reconciles aggregate execution counts and recalculates final execution status (`COMPLETED`, `RECOVERY_REQUIRED`, `PARTIALLY_COMPLETED`, `ROLLED_BACK`, `PARTIALLY_ROLLED_BACK`).
     - Releases execution claim via `releaseExecution()`.
2. **Track-Level Claim Protection**:
   - `ExecutionTrackRecordRepository.claimTrackReconciling(...)` conditionally updates uncertain/stale tracks to prevent concurrent workers from resolving the same track record.
3. **No Stale Overwrites**:
   - Optimistic locking (`@Version`) on `MigrationExecution` and `ExecutionTrackRecord` prevents stale updates if state was altered concurrently.

---

### 7. Durable Audit Persistence (`execution_reconciliation_records`)

Implemented via Flyway migration `V9__phase11g_reconciliation_schema.sql`:
- **Table**: `execution_reconciliation_records`
- **Fields**:
  - `id`: UUID (Primary Key)
  - `execution_id`: UUID (Foreign Key $\to$ `migration_executions(id)`, `ON DELETE RESTRICT`)
  - `execution_track_record_id`: UUID (Foreign Key $\to$ `execution_track_records(id)`, `ON DELETE RESTRICT`)
  - `reconciliation_type`: VARCHAR(32) (`INSERTION`, `DELETION`, `STALE_IN_FLIGHT_ADDING`, `STALE_IN_FLIGHT_ROLLING_BACK`)
  - `previous_track_status`: VARCHAR(32)
  - `resolved_track_status`: VARCHAR(32)
  - `reconciliation_outcome`: VARCHAR(32) (`CONFIRMED_INSERTED`, `CONFIRMED_NOT_INSERTED`, `CONFIRMED_DELETED`, `CONFIRMED_PRESENT`, `AMBIGUOUS`, `INSPECTION_FAILED`)
  - `destination_playlist_id`: VARCHAR(255)
  - `destination_playlist_item_id`: VARCHAR(255)
  - `destination_track_id`: VARCHAR(255)
  - `evidence_summary`: VARCHAR(1000)
  - `evidence_timestamp`: TIMESTAMP WITH TIME ZONE
  - `inspection_started_at`: TIMESTAMP WITH TIME ZONE
  - `inspection_completed_at`: TIMESTAMP WITH TIME ZONE
  - `failure_code`: VARCHAR(64)
  - `sanitized_error_message`: VARCHAR(1000)
  - `created_at`: TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
- **Audit Integrity**: `ON DELETE RESTRICT` guarantees parent deletion cannot silently erase reconciliation history. Credential sanitization guarantees tokens and secrets are never logged or stored.

---

### 8. REST Presentation & Security Architecture

Endpoints added to `MigrationExecutionController`:
1. `POST /api/migrations/execution/{executionId}/reconcile`: Explicitly triggers reconciliation for an execution or specific track.
2. `GET /api/migrations/execution/{executionId}/reconciliations` (and alias `/reconciliation-history`): Retrieves audit history.
3. `GET /api/migrations/execution/{executionId}/reconciliation-status`: Returns current count of uncertain tracks and reconciliation eligibility.

#### Security Enforcements:
- **Authentication**: All endpoints require authenticated principal (HTTP 401 on missing auth).
- **Anti-IDOR & Ownership**: Validates that `execution.userId` matches the authenticated `principal.getName()`. Unauthorized access returns HTTP 404 (preventing ID enumeration).
- **Anti-Impersonation**: If caller provides a `userId` in the request body, it must match the authenticated principal (HTTP 400 Bad Request on mismatch).
- **Sanitized Errors**: Raw provider error bodies and OAuth credentials are never returned in responses.

---

### 9. Out of Scope & Phase 12 Boundaries

Strictly excluded from Phase 11G:
- Asynchronous workers, `@Async`, or message queues (RabbitMQ, Kafka).
- Recurring scheduled reconciliation cron jobs.
- Automatic mutation retries during reconciliation (reconciliation is inspection-only).
- Automatic deletion of duplicate-looking tracks without authoritative identity.
- WebSockets or SSE progress streams.
- Frontend redesign.
These remain reserved for Phase 12 (Migration Observability, Performance & Orchestration).
