# ADR-024: Approved Plan Execution Architecture, Contracts, and Validation Boundary (Phase 11A & 11B)

## Status
**ACCEPTED / IMPLEMENTED**

## Context

Following the completion and freeze of Phase 9 (Migration Planning Subsystem and Pre-Flight Preview under ADR-022) and Phase 10 (Review & Correction Subsystem under ADR-023), the project requires the implementation of Phase 11 (Execution Architecture, Approved Plan Validation, Destination Playlist Creation, and Ordered Track Insertion).

### 1. Architectural Evolution and Separation of Phases
- **Phase 8G/8H**: Connected Spotify retrieval to YouTube playlist creation and track insertion in a tightly coupled loop (`PlaylistMigrationOrchestrator`). This direct execution engine is frozen and preserved for backward compatibility.
- **Phase 9**: Introduced pre-flight planning (`MigrationPlan`, `MigrationPlanTrack`, `PlanTrackClassification`) providing read-only migration previews without destination mutations (ADR-022).
- **Phase 10**: Introduced persistent human review and correction (`ReviewSession`, `ReviewTrack`, `ReviewDecisionSource`) allowing users to accept, replace candidates, or exclude tracks without mutating destination music services (ADR-023).
- **Phase 11 Objective**: Establish safe, deterministic execution of approved migrations.

To ensure safety and auditability, Phase 11 is strictly decomposed into four subphases:
- **Phase 11A**: Execution Architecture & Contracts (Application-level contracts, approved migration context, immutable approved track representations).
- **Phase 11B**: Approved Plan Validation (Pure validation boundary establishing execution eligibility prior to any destination write).
- **Phase 11C**: Destination Playlist Creation (Creating YouTube Music playlist with verified metadata).
- **Phase 11D**: Ordered Track Insertion (Sequential insertion of approved tracks preserving source order).

Phase 11A and Phase 11B establish the execution architecture, contracts, and validation boundary without performing any destination-side mutations.

---

## Architectural Decisions

### 1. Reconciling Transient Plans (ADR-022) with Stateful Review Decisions (ADR-023)
- Under ADR-022, `MigrationPlan` is transient and in-memory. It is not persisted to PostgreSQL tables.
- In Phase 10, when a `ReviewSession` is created from a `MigrationPlan`, the session persists:
  - `planId` (the UUID of the transient preview plan).
  - `sourcePlaylistId` and `sourcePlaylistTitle`.
  - The complete list of source tracks with exact 0-indexed positions (`position = 0..N-1`).
  - Immutable automatic matching evidence (`originalClassification`, `originalCandidateId`, `originalMatchScore`, `originalCalibratedProbability`, `originalMatchDecision`, `originalMatchReasons`).
  - Mutable human review decisions (`reviewStatus`, `decisionSource`, `selectedCandidateId`, `selectedCandidateTitle`, `selectedCandidateChannel`, `selectedCandidateDurationMs`, `excluded`, `hasConflict`).
- Therefore, the persisted `ReviewSession` and its `ReviewTrack` entities in PostgreSQL fully encapsulate all necessary source, provenance, and decision state needed for execution.
- **Zero New Database Schema**: No new tables, entities, or Flyway migrations are introduced in Phase 11A/11B. ADR-022's transient plan guarantee is completely preserved.
- When execution is requested, the application resolves the completed `ReviewSession` by ID and derives the validated `ApprovedMigrationContext`. If an in-memory `MigrationPlan` is optionally supplied, cross-plan consistency is verified.

### 2. Immutability and State Freeze Protection
- A critical requirement is that approved decisions must not change after validation.
- In Phase 10, `ReviewSessionStatus.COMPLETED` is terminal. Once a session is completed:
  - `ReviewTrack.checkSessionModifiable()` fails fast with `InvalidStateException` if any mutation is attempted.
  - `DefaultReviewSessionService` rejects any track decision mutation on closed sessions.
- Phase 11B validation strictly enforces that only sessions in `ReviewSessionStatus.COMPLETED` status are eligible for execution. Sessions in `IN_PROGRESS` or `CANCELLED` status are rejected with `INCOMPLETE_REVIEW` or `INVALID_STATE`.
- The output of successful validation is `ApprovedMigrationContext`, an immutable domain record with defensive collection copying (`List.copyOf`). It captures the validated tracks, positions, and snapshot timestamp (`validatedAt`).

### 3. All-or-Nothing Track Eligibility Validation (Phase 11B)
Validation evaluates 12 distinct criteria and enforces an all-or-nothing guarantee:
1. **Request Validity**: Non-null request, valid IDs, and existing session context.
2. **Session Ownership & Anti-IDOR**: The authenticated user must own the review session (`session.getUser().getId().equals(authenticatedUserId)`). Unauthorized access is rejected without data disclosure.
3. **Anti-Impersonation**: Caller-supplied user ID must match the authenticated principal.
4. **Session State**: Session must be in `ReviewSessionStatus.COMPLETED`.
5. **Session-Source Consistency**: Source playlist ID and plan ID must match session context.
6. **Deterministic Source Ordering**: Source tracks must be contiguously 0-indexed (`track.getPosition() == i` for $0 \le i < N$).
7. **Unresolved Decisions Rejection**: Any track with `AWAITING_REVIEW` or `isUnresolved() == true` causes validation failure.
8. **Duplicate Conflict Rejection**: Any track with `hasConflict() == true` causes validation failure.
9. **Exclusion Filtering**: Tracks marked `isExcluded() == true` or `reviewStatus == EXCLUDED` are strictly excluded from `approvedTracks` and recorded in `excludedTrackCount`.
10. **Destination Identity Validity**: Every approved track (`ACCEPTED` or `USER_SELECTED`) must have a non-null, non-blank `selectedCandidateId` (YouTube video ID) and `isApprovedForMigration() == true`.
11. **User Candidate Validity**: For `USER_SELECTED` decisions, candidate title must be non-blank and duration must be non-negative.
12. **Duplicate Destination Track Prevention**: No two approved tracks in the selection may claim the same destination video ID.

If even one track violates eligibility, validation fails completely. No partial execution is permitted.

### 4. Deterministic Destination Ordering
- Destination insertion in Phase 11D requires explicit, gapless 0-indexed positions matching destination playlist size.
- `ApprovedTrack` assigns:
  - `sourcePosition`: Preserves original source playlist index ($0 \le \text{sourcePosition} < N$).
  - `targetPosition`: Monotonically increasing $0 \le \text{targetPosition} < M$, where $M$ is the count of approved tracks.
- This guarantees 100% deterministic, gap-free insertion ordering without depending on hash map iteration, database retrieval order, or human review sequence.

### 5. Mutation Safety Boundary
- Phase 11A and Phase 11B perform **zero destination mutations**.
- `DefaultApprovedPlanValidator` and `DefaultApprovedMigrationExecutionService` maintain **zero fields of type `MusicDestination`** and make zero calls to YouTube or Spotify APIs.
- Phase 9 preview remains strictly read-only.
- Phase 8 orchestrator production files remain 100% untouched and operational.

---

## Contract Specifications

### Package: `com.spotifyyoutube.migrator.migration.application.execution.contract`
1. **`ValidationFailureCode`** (`enum`): Structured error codes (`MISSING_CONTEXT`, `UNAUTHORIZED`, `INCOMPLETE_REVIEW`, `UNRESOLVED_TRACK`, `UNRESOLVED_CONFLICT`, `INVALID_CANDIDATE`, `CANDIDATE_ASSOCIATION_MISMATCH`, `INCONSISTENT_CONTEXT`, `INVALID_DESTINATION_ID`, `INVALID_STATE`, `DUPLICATE_DESTINATION_TRACK`, `ORDERING_MISMATCH`).
2. **`ExecutionValidationError`** (`record`): Itemized validation error with code, position, sourceTrackId, and message.
3. **`ApprovedTrack`** (`record`): Immutable approved track with `sourcePosition`, `sourceTrackId`, `sourceTitle`, `sourceArtist`, `destinationTrackId`, `destinationTitle`, `destinationChannel`, `destinationDuration`, `decisionSource`, and `targetPosition`.
4. **`ApprovedMigrationContext`** (`record`): Immutable validated execution context containing `reviewSessionId`, `planId`, `userId`, playlist titles/descriptions, privacy flag, unmodifiable `approvedTracks`, counts, and `validatedAt` timestamp.
5. **`ApprovedMigrationExecutionRequest`** (`record`): Application execution request containing `reviewSessionId`, optional `userId`, playlist metadata overrides, and optional `MigrationPlan`.
6. **`ExecutionValidationResult`** (`record`): Typed validation outcome exposing `isValid()`, `Optional<ApprovedMigrationContext>`, and unmodifiable `List<ExecutionValidationError>`.
7. **`MigrationExecutionValidationException`** (`class`): Domain exception extending `InvalidStateException` holding validation result details.

### Package: `com.spotifyyoutube.migrator.migration.application.execution.validation`
1. **`ApprovedPlanValidator`** (`interface`): Contract for validating execution eligibility.
2. **`DefaultApprovedPlanValidator`** (`class`): Pure, stateless implementation enforcing all 12 validation invariants.

### Package: `com.spotifyyoutube.migrator.migration.application.execution`
1. **`ApprovedMigrationExecutionService`** (`interface`): Application service coordinating execution validation and context preparation.
2. **`DefaultApprovedMigrationExecutionService`** (`class`): Implementation enforcing transaction boundaries, security ownership, and validator delegation.

### Package: `com.spotifyyoutube.migrator.migration.api`
1. **`MigrationExecutionController`**: REST controller exposing `/api/migrations/execution/validate` and `/api/migrations/execution/eligibility/{sessionId}`.
2. **DTOs & Mappers**: `ValidateExecutionRequest`, `ExecutionValidationResponse`, `ApprovedMigrationContextResponse`, `ApprovedTrackResponse`, `ExecutionValidationErrorResponse`, `MigrationExecutionApiMapper`.
