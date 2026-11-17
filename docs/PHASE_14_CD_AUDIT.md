# Phase 14C & 14D — Architecture Audit & Gap Analysis Report
## Spotify-to-YouTube Migrator

**Date:** 2026-11-17
**Author:** AI Engineering Agent
**Status:** COMPLETE
**Target Subphases:** Phase 14C (Migration History & Detail APIs) & Phase 14D (Audit Logging & State Transition History)

---

## 1. Executive Summary

This audit evaluates the existing codebase to design and implement Phase 14C (Migration History & Detail APIs) and Phase 14D (Audit Logging & State Transition History).

Phases 14A and 14B established the persistent data model on `MigrationExecution` and `ExecutionTrackRecord`, including source/matched track counts, top-level failure summaries, matching scores/decisions, decision provenance, and Flyway migration V15.

This audit confirms that:
1. `MigrationExecutionRepository` already possesses user-scoped queries and pagination support (`findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable)` and `findByIdAndUserId(UUID id, UUID userId)`).
2. Existing controllers (`MigrationController` and `MigrationExecutionController`) expose async progress and execution detail endpoints, but require canonical user-scoped history endpoints (`GET /api/migrations/history` and `GET /api/migrations/{id}`) with strict anti-IDOR protection, status filtering, and paginated responses.
3. While `ExecutionRollbackRecord` (Phase 11F) and `ExecutionReconciliationRecord` (Phase 11G) provide immutable audit logs for compensating mutations and remote inspections, there is no generalized append-only audit trail capturing lifecycle state transitions, retry scheduling, and worker lease lifecycle events.
4. Phase 14D can cleanly introduce an `execution_audit_events` table (Flyway V16) linked to `MigrationExecution`, referencing existing rollback and reconciliation records, with a user-scoped timeline retrieval endpoint (`GET /api/migrations/{id}/history`).

---

## 2. Existing Architecture & Components

### 2.1 Domain & Entity Model
- **`MigrationExecution`**: Authoritative aggregate root (Phases 11–14). Tracks `userId`, source/destination playlist info, execution counters (`totalApprovedTracks`, `successfulInsertions`, `failedInsertions`, `uncertainInsertions`, `skippedInsertions`, `rolledBackInsertions`, `totalSourceTracks`, `matchedTracks`), timestamps, worker leases, and `failureSummary`.
- **`ExecutionTrackRecord`**: Child entity containing per-track execution results, candidate metadata, matching evidence (`matchScore`, `matchDecision`), decision provenance (`decisionSource`), and provider error messages.
- **`ExecutionRollbackRecord`**: Immutable audit record for track rollback deletions (Phase 11F).
- **`ExecutionReconciliationRecord`**: Immutable audit record for remote destination reconciliation (Phase 11G).
- **`MigrationIdempotencyRecord`**: Idempotency token and fingerprint registry (Phase 13E).

### 2.2 Repositories
- **`MigrationExecutionRepository`**: Contains `findByIdAndUserId(UUID id, UUID userId)`, `findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable)`, and `countByUserId(UUID userId)`.
- **`ExecutionTrackRecordRepository`**: Contains `findByExecutionIdOrderByTargetPositionAsc(UUID executionId)`.
- **`ExecutionRollbackRecordRepository`**: Contains `findByExecutionIdOrderByAttemptedAtAsc(UUID executionId)`.
- **`ExecutionReconciliationRecordRepository`**: Contains `findByExecutionIdOrderByInspectionStartedAtAsc(UUID executionId)`.

### 2.3 Services & Application Layer
- **`MigrationExecutionStateService` / `DefaultMigrationExecutionStateService`**: Manages execution state transitions, optimistic locking, worker claims, recovery, and rollback.
- **`MigrationProgressService`**: Provides real-time read-only progress snapshots (`MigrationProgressSnapshot`).
- **`DurableMigrationScheduler`**: Manages worker claiming with PostgreSQL `FOR UPDATE SKIP LOCKED`, generational fencing, and saturation compensation.
- **`ProviderFailureClassifier` / `DefaultRetryPolicy`**: Evaluates retry eligibility and exponential backoff with jitter.

### 2.4 Existing API Endpoints
- `POST /api/migrations`: Async migration submission (202 Accepted) and legacy job creation.
- `GET /api/migrations/{executionId}/progress`: Execution progress polling.
- `GET /api/migrations/execution/{executionId}`: Execution detail retrieval under `/api/migrations/execution`.
- `GET /api/migrations/{id}` (in `MigrationController`): Early legacy endpoint returning `MigrationJobResponse` for legacy `MigrationJob`.

---

## 3. Gaps to Address in Phase 14C & 14D

| Requirement | Current State | Target Solution in Phase 14C / 14D |
| :--- | :--- | :--- |
| **`GET /api/migrations/history`** | Repository method exists, but no controller endpoint exists. | Implement endpoint in `MigrationController` with status filtering, Spring `Pageable` pagination, newest-first default sorting, and `PagedResponse<MigrationHistoryItemResponse>`. |
| **`GET /api/migrations/{id}`** | Legacy `MigrationJob` endpoint exists on `MigrationController`; execution detail exists under `/api/migrations/execution/{id}`. | Unify `GET /api/migrations/{id}` on `MigrationController` to return full `MigrationExecutionResponse` for the authenticated user, while preserving legacy fallback if needed. |
| **Anti-IDOR Protection** | Implemented on some endpoints, but needs consistent enforcement. | Derive user strictly from security principal; enforce `findByIdAndUserId` so attempting to access another user's execution returns 404 NOT_FOUND. |
| **State Transition Audit History** | Rollback and reconciliation have audit tables, but execution state changes, retries, and worker lease events are not durably logged in an audit table. | Create `execution_audit_events` table (Flyway V16), `ExecutionAuditEvent` entity, and `ExecutionAuditService` to append events on lifecycle transitions, retries, and worker lease actions. |
| **Audit Integration** | Rollback and reconciliation records exist in separate tables. | `ExecutionAuditEvent` includes nullable references `rollback_record_id` and `reconciliation_record_id` to link compensating actions without duplicate business records. |
| **`GET /api/migrations/{id}/history`** | Rollback history endpoint exists under `/api/migrations/execution/{id}/rollbacks`, but no unified audit timeline. | Implement `GET /api/migrations/{id}/history` returning chronological paginated audit events for the authenticated owner. |

---

## 4. Architecture Decisions for Phase 14C & 14D

1. **Reuse `MigrationExecution` as Authoritative Root**:
   - Do not introduce duplicate history entities. All history queries resolve through `MigrationExecution`.
2. **DTO & API Design**:
   - `MigrationHistoryItemResponse`: Summary DTO for history lists (`migrationId`, `sourcePlaylistTitle`, `destinationPlaylistId`, `destinationPlaylistUrl`, `destinationPlatform`, `status`, `totalSourceTracks`, `matchedTracks`, `totalApprovedTracks`, `successfulInsertions`, `failedInsertions`, `createdAt`, `startedAt`, `completedAt`, `failureSummary`).
   - `PagedResponse<T>`: Canonical paginated wrapper providing `content`, `page`, `size`, `totalElements`, `totalPages`, `first`, `last`.
   - `MigrationExecutionResponse`: Rich detail DTO for `GET /api/migrations/{id}` including track records with candidate metadata and matching evidence.
   - `MigrationAuditEventResponse`: DTO for `GET /api/migrations/{id}/history` exposing timeline events without internal worker secrets.
3. **Database Schema Evolution (Flyway V16)**:
   - Create `execution_audit_events` with foreign keys to `migration_executions`, `execution_rollback_records`, and `execution_reconciliation_records`.
   - Add composite index on `(execution_id, created_at ASC)` for fast chronological timeline queries.
   - Add composite index on `migration_executions(user_id, status, created_at DESC)` for efficient status-filtered history queries.
4. **Audit Logging Integration**:
   - Synchronous audit recording within domain state transitions in `DefaultMigrationExecutionStateService`, guaranteeing that state transitions and audit logs commit atomically.
   - Non-noisy worker lease auditing: Log lease acquisition, release, expiry, and rejected saturation compensation; avoid high-frequency lease renewal noise.
5. **Anti-IDOR Security**:
   - Enforce user authentication via Spring Security.
   - Always query by authenticated `userId`.
   - Inaccessible or non-existent IDs return `404 NOT_FOUND` with error code `NOT_FOUND`, preventing ID disclosure.
