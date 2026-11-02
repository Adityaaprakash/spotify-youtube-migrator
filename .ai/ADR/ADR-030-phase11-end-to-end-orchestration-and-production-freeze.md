# ADR-030: Phase 11 End-to-End Migration Orchestration, Production Hardening & Subsystem Freeze (Phase 11H)

## Status
**ACCEPTED / IMPLEMENTED / FROZEN**

## Context

Phase 11 represents the complete destination playlist creation, execution, state tracking, failure recovery, partial rollback, and ambiguous outcome reconciliation capability for the `spotify-youtube-migrator` project.

Prior to Phase 11H, individual subphases implemented isolated parts of this lifecycle:
- **Phase 11A & 11B (ADR-024)**: Approved migration contracts, context preparation, and all-or-nothing validation boundary.
- **Phase 11C (ADR-025)**: YouTube playlist creation isolated from track insertions with privacy safeguards.
- **Phase 11D (ADR-026)**: Canonical 0-indexed sequential track insertion with conservative halting.
- **Phase 11E (ADR-027)**: Durable PostgreSQL execution state (`migration_executions`, `execution_track_records`), discrete short transactions, cross-instance execution claim locking (`status -> RUNNING`), optimistic locking (`@Version`), and safe resume.
- **Phase 11F (ADR-028)**: Failure recovery classification engine, controlled recovery for confirmed failed tracks, safe partial rollback using authoritative `destinationPlaylistItemId`, in-flight `ROLLING_BACK` claims, and immutable rollback audit logging (`execution_rollback_records` with `ON DELETE RESTRICT`).
- **Phase 11G (ADR-029)**: Evidence-driven destination playlist inspection (`MusicDestination.inspectPlaylist`), cycle detection, 100-page limit, all-or-nothing complete pagination, insertion attribution via timestamp/unassigned identity, deletion verification via playlist-item absence/presence, 5-minute lease-aware stale in-flight state handling, and immutable reconciliation audit logging (`execution_reconciliation_records` with `ON DELETE RESTRICT`).

Phase 11H integrates all these components into a single coherent orchestration workflow, verifies cross-subsystem contract consistency, audits the complete execution state machine, enforces security and capacity bounds, and formally freezes the entire Phase 11 subsystem.

---

## Architectural Decisions

### 1. End-to-End Orchestration Architecture (`ApprovedMigrationOrchestrator`)

To provide callers (REST controllers, CLI, or prospective UI clients) with a unified, safe entry point, Phase 11H introduces `ApprovedMigrationOrchestrator` and `DefaultApprovedMigrationOrchestrator`.

#### Architectural Characteristics:
- **Thin Facade**: The orchestrator coordinates existing specialized domain services (`ApprovedMigrationExecutionService`, `DestinationPlaylistCreationService`, `MigrationExecutionStateService`) without reimplementing or duplicating their domain rules.
- **Deterministic Lifecycle Flow**:
  1. **Validated Context**: Gated pre-mutation validation evaluates 12 criteria (Phase 11B). Invalid requests throw `MigrationExecutionValidationException` and immediately halt before any remote writes.
  2. **Idempotency Inspection**: If a durable execution already exists for the review session:
     - `COMPLETED`: Returns summary immediately (no redundant destination mutations).
     - `RUNNING`: Fails fast with `InvalidStateException` (HTTP 409 Conflict).
     - `RECOVERY_REQUIRED`: Fails fast with `InvalidStateException`, directing the caller to reconcile uncertain tracks.
     - `INITIALIZED` / `PARTIALLY_COMPLETED` / `FAILED`: Safely resumes insertion of remaining pending tracks.
  3. **Playlist Creation (Gated)**: If no prior execution exists, creates the destination playlist on YouTube with verified metadata and privacy settings (Phase 11C).
  4. **Durable Initialization**: Persists the aggregate `MigrationExecution` and initial `PENDING` `ExecutionTrackRecord` entities in PostgreSQL (Phase 11E).
  5. **Ordered Track Insertion**: Claims execution, iteratively claims tracks `ADDING`, invokes provider outside DB transactions, persists outcomes (`ADDED`, `FAILED`, `UNCERTAIN`), and dynamically reconciles summary counters.
- **Explicit Recovery, Rollback & Reconciliation Delegation**: Provides clean, correlated methods for `recoverMigration`, `rollbackMigration`, and `reconcileMigration`.

---

### 2. Complete Execution & Track State Machine Specification

Phase 11 establishes a strictly defined, audited state machine across both aggregate executions and individual track records.

#### Execution Status Transitions:
```text
                  +-----------------------------------+
                  |            INITIALIZED            |
                  +-----------------+-----------------+
                                    | claimExecution()
                                    v
                  +-----------------+-----------------+
+---------------->|             RUNNING               |<----------------+
|                 +-----------------+-----------------+                 |
|                   |               |               |                   |
|   all ADDED       | some ADDED,   | all FAILED    | any UNCERTAIN     |
|   (or completed)  | some FAILED   |               | (or stale lease)  |
|                   v               v               v                   |
| +-----------+   +---------------+ +--------+   +-------------------+  |
| | COMPLETED |   |   PARTIALLY   | | FAILED |   | RECOVERY_REQUIRED |  |
| | (terminal)|   |   COMPLETED   | +----+---+   +---------+---------+  |
| +-----------+   +-------+-------+      |                 |            |
|                         |              |                 | reconcile  |
|     resumeExecution()   |              | recover         | / release  |
|     +-------------------+              | Execution()     |            |
|     |                                  v                 v            |
|     +----------------------------------+-----------------+------------+
|
|                 +-----------------+-----------------+
|                 |             RUNNING               | (during rollback)
|                 +-----------------+-----------------+
|                                   |
|                   +---------------+---------------+
|                   |                               |
|   all ADDED       | some ADDED,                   |
|   rolled back     | some rolled back              |
|                   v                               v
|            +--------------+             +--------------------+
|            | ROLLED_BACK  |             | PARTIALLY_ROLLED_  |
|            |  (terminal)  |             |       BACK         |
|            +--------------+             +---------+----------+
|                                                   |
+---------------------------------------------------+
```

#### Track Record Status Transitions:
```text
           +------------------+
           |     PENDING      |<----------------------------+
           +--------+---------+                             |
                    | markAdding()                          |
                    v                                       |
           +--------+---------+                             |
           |      ADDING      |                             |
           +---+----+-----+---+                             |
               |    |     |                                 |
+--------------+    |     +-------------+                   |
| success           | provider reject   | timeout / 500     | resetForRecovery()
v                   v                   v                   | (Phase 11F)
+-------+       +--------+        +-----------+             |
| ADDED |       | FAILED |        | UNCERTAIN |-------------+
+---+---+       +---+----+        +-----+-----+
    |               |                   |
    |               +-------------------+ (reconcileConfirmedNotInserted -> FAILED)
    | markRollingBack()                 |
    v                                   | (reconcileConfirmedAdded / Present -> ADDED)
+---+----------+                        |
| ROLLING_BACK |                        | (reconcileConfirmedDeleted -> ROLLED_BACK)
+---+----+-----+                        |
    |    |     |                        |
    |    |     +------------------------+
    |    | timeout / 500
    |    v
    |  +-----------+ (revertRollingBackToAdded -> ADDED)
    |
    v provider DELETE success or 404
+---------------+
|  ROLLED_BACK  |
+---------------+
```

#### Invariants Enforced:
1. `COMPLETED` and `ROLLED_BACK` are terminal statuses that reject further claims or mutations.
2. An execution containing even one `UNCERTAIN` track is strictly constrained to `RECOVERY_REQUIRED`; blind recovery is prohibited until reconciled.
3. Every state transition is guarded by pre-condition checks throwing `IllegalStateException` or `InvalidStateException` upon violation.

---

### 3. Database Schema & Migration Inventory

All schema changes across Phase 11 are managed through forward-only Flyway migrations:

| Version | Migration File | Description | Audit Retention Semantics |
|---|---|---|---|
| `V1` | `V1__baseline.sql` | Initial system baseline | N/A |
| `V2` | `V2__init_schema.sql` | Users, playlists, tracks, sync jobs | `ON DELETE CASCADE` |
| `V3` | `V3__phase2_auth_oauth_schema.sql` | OAuth tokens and user credentials | `ON DELETE CASCADE` |
| `V4` | `V4__phase2_oauth_connection_status.sql` | OAuth connection flags | N/A |
| `V5` | `V5__phase10_review_session_schema.sql` | `review_sessions`, `review_tracks` | `ON DELETE CASCADE` |
| `V6` | `V6__phase11e_durable_execution_schema.sql` | `migration_executions`, `execution_track_records` | `ON DELETE CASCADE` from user/session |
| `V7` | `V7__phase11f_failure_recovery_and_rollback_schema.sql` | `rolled_back_insertions` column, `execution_rollback_records` | Initial rollback audit table |
| `V8` | `V8__phase11f_rollback_audit_retention.sql` | Rollback audit foreign key alteration | **`ON DELETE RESTRICT`** on `execution_rollback_records` |
| `V9` | `V9__phase11g_reconciliation_schema.sql` | `execution_reconciliation_records` audit table | **`ON DELETE RESTRICT`** on `execution_reconciliation_records` |

#### Data Integrity Invariants:
- Foreign keys on `execution_rollback_records` and `execution_reconciliation_records` use `ON DELETE RESTRICT`, preventing accidental parent deletion from silently erasing audit trails.
- Unique constraints prevent duplicate executions (`review_session_id`) and duplicate track positions (`execution_id, target_position`).
- Optimistic locking (`@Version`) prevents lost updates in concurrent environments.

---

### 4. API Inventory & Security Architecture

Phase 11 REST API endpoints on `MigrationExecutionController` (`/api/migrations/execution` and `/api/migrations/approved`):

| Method | Route | Subphase | Purpose |
|---|---|---|---|
| `POST` | `/validate` | 11B | Validates approved review session eligibility |
| `GET` | `/eligibility/{sessionId}` | 11B | Convenience eligibility check |
| `POST` | `/create-playlist` | 11C | Creates YouTube destination playlist |
| `POST` | `/create-playlist/{sessionId}`| 11C | Convenience playlist creation |
| `POST` | `/insert-tracks` | 11D | Stateless ordered track insertion |
| `POST` | `/insert-tracks/{sessionId}` | 11D | Convenience stateless insertion |
| `POST` | `/initialize` | 11E | Idempotent durable execution initialization |
| `GET` | `/{executionId}` | 11E | Durable execution status and track records |
| `GET` | `/session/{sessionId}` | 11E | Durable execution status by review session |
| `POST` | `/{executionId}/resume` | 11E | Safe resume of pending tracks |
| `POST` | `/{executionId}/recover` | 11F | Controlled recovery of eligible failed tracks |
| `POST` | `/{executionId}/rollback` | 11F | Safe partial rollback of confirmed inserted tracks |
| `GET` | `/{executionId}/rollbacks` | 11F | Audit history of rollback attempts (alias `/rollback-history`) |
| `POST` | `/{executionId}/reconcile` | 11G | Evidence-based reconciliation of uncertain tracks |
| `GET` | `/{executionId}/reconciliations` | 11G | Audit history of reconciliation attempts (alias `/reconciliation-history`) |
| `GET` | `/{executionId}/reconciliation-status` | 11G | Current uncertainty status and eligible tracks |
| `POST` | `/run` | 11H | End-to-end orchestrated approved migration |
| `POST` | `/run/{sessionId}` | 11H | Convenience end-to-end orchestration by session ID |

#### Security Enforcements:
- Mandatory authentication via Spring Security (`Principal`).
- Anti-impersonation: Mismatches between caller-supplied `userId` and authenticated principal return HTTP 400 Bad Request.
- Anti-IDOR: Access to review sessions or executions belonging to another user returns HTTP 404 Not Found.
- Sensitive credential scrubbing: Bearer tokens and client secrets are scrubbed before persistence or API response serialization.

---

### 5. Operational Capacity & Quota Assessment

Under YouTube Data API v3, provider quota costs dictate operational throughput:
- `POST /playlists` (playlist creation): 50 units.
- `POST /playlistItems` (track insertion): 50 units.
- `GET /playlistItems` (playlist inspection, 50 items/page): 1 unit per page.
- `DELETE /playlistItems` (compensating deletion): 50 units.
- Default daily quota: 10,000 units per Google Cloud project.

#### Capacity Calculations Across Realistic Playlist Sizes:

| Playlist Size | Creation Cost | Insertion Cost | Inspection Cost | Total Migration Cost | Daily Quota Capacity |
|---|---|---|---|---|---|
| **50 tracks** | 50 units | 2,500 units | 1 unit (1 page) | 2,551 units | ~3 complete migrations / day |
| **100 tracks** | 50 units | 5,000 units | 2 units (2 pages) | 5,052 units | ~1 complete migration / day |
| **500 tracks** | 50 units | 25,000 units | 10 units (10 pages) | 25,060 units | Requires quota extension |
| **1,000 tracks** | 50 units | 50,000 units | 20 units (20 pages) | 50,070 units | Requires quota extension |

#### Operational Guidance:
- For standard projects operating on default 10,000 units/day, playlists up to 100 tracks migrate safely.
- Larger playlists (>100 tracks) require Google Cloud quota extension or batching across quota windows (Phase 12).
- Inspection consumes negligible quota (1 unit per 50 tracks), making reconciliation highly quota-efficient compared to blind retries.

---

### 6. Phase 12 Boundary & Handoff Requirements

Phase 11 strictly preserves synchronous execution. The handoff boundary to Phase 12 is defined as:
1. **Background Job Orchestration**: Phase 12 will introduce asynchronous execution workers (`@Async`, thread pools, or message queues) to execute `ApprovedMigrationOrchestrator.executeApprovedMigration()` in background threads without blocking client HTTP connections.
2. **Progress Reporting & Streaming**: Phase 12 will provide Server-Sent Events (SSE) or WebSockets to stream per-track insertion progress to the user interface.
3. **Chunked / Batched Execution**: For large playlists exceeding single-request timeouts or quota windows, Phase 12 will chunk execution and safe resume across scheduled windows.

---

## Subsystem Freeze Declaration

The Phase 11 subsystem (Phases 11A, 11B, 11C, 11D, 11E, 11F, 11G, 11H) is hereby **COMPLETE, AUDITED, VERIFIED, AND FROZEN**.
No modifications to Phase 11 domain models, contracts, database schemas, or execution invariants shall be made without an approved Architectural Decision Record.
