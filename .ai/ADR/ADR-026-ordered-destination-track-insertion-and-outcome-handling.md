# ADR-026: Ordered Destination Track Insertion and Outcome Handling (Phase 11D)

## Status
**ACCEPTED / IMPLEMENTED**

## Context

Following the completion of Phase 11A (Execution Architecture & Contracts), Phase 11B (Approved Plan Validation), and Phase 11C (Destination Playlist Creation), the application requires the capability to insert approved tracks into the created YouTube Music destination playlist in strict, deterministic order.

### 1. Architectural Evolution and Separation of Concerns
- **Phase 8G/8H**: Connected Spotify track retrieval directly to inline playlist creation and track additions (`PlaylistMigrationOrchestrator`). This orchestrator remains frozen and operational for backward compatibility.
- **Phase 9**: Introduced pre-flight planning (`MigrationPlan`) providing read-only preview snapshots without destination mutations (ADR-022).
- **Phase 10**: Introduced persistent human review and correction (`ReviewSession`, `ReviewTrack`) allowing users to accept, replace candidates, or exclude tracks without mutating destination music services (ADR-023).
- **Phase 11A & 11B**: Established application execution contracts and an all-or-nothing validation boundary (`ApprovedPlanValidator`) ensuring zero destination writes occur before full validation (ADR-024).
- **Phase 11C**: Safely invoked destination playlist creation (`MusicDestination.createPlaylist`) using the validated context, capturing confirmed playlist identity while strictly prohibiting track insertion (ADR-025).
- **Phase 11D Objective**: Implement sequential, deterministic insertion of approved tracks into the destination playlist, record granular per-track outcomes, handle partial failures and ambiguous timeouts safely, and produce an accurate summary.

---

## Architectural Decisions

### 1. Dedicated Application Service Boundary
- Phase 11D introduces `OrderedTrackInsertionService` and `DefaultOrderedTrackInsertionService` in `com.spotifyyoutube.migrator.migration.application.execution`.
- This service coordinates:
  1. Resolving the authenticated user from the trusted security context.
  2. Gated validation: Resolving the validated `ApprovedMigrationContext` via `ApprovedMigrationExecutionService.prepareExecution`.
  3. Verifying that the approved track selection is non-empty (`hasApprovedTracks() == true`).
  4. Verifying destination playlist identity and accessibility (`musicDestination.getPlaylist`).
  5. Sequential track insertion via `MusicDestination.addTrackToPlaylist` in strict ascending `targetPosition` order.
  6. Granular per-track outcome tracking (`SUCCESS`, `FAILED`, `UNCERTAIN`, `SKIPPED`).
  7. Conservative failure-continuation policy: stopping further insertions on failure or timeout to preserve relative ordering integrity and marking subsequent tracks as `SKIPPED`.
  8. Returning an immutable, typed `PlaylistTrackInsertionSummary`.

### 2. Deterministic Canonical Ordering
- In accordance with ADR-024, the canonical destination order is defined by `ApprovedTrack.targetPosition()`.
- `targetPosition` represents a gapless, 0-indexed sequence ($0 \le \text{targetPosition} < M$), where $M$ is the count of approved tracks.
- Insertion is executed strictly sequentially in ascending order of `targetPosition` ($0, 1, \dots, M-1$).
- Parallel insertion (e.g. `parallelStream`, `CompletableFuture`, thread pools) is strictly prohibited to prevent race conditions in playlist ordering.

### 3. Gated Pre-Mutation Security and Authorization
- **No Untrusted Client Track Lists**: The insertion service never accepts client-supplied track lists or video IDs. All tracks are derived strictly from the server-side completed `ReviewSession`.
- **Anti-Impersonation**: Caller-supplied `userId` is strictly checked against the authenticated principal (HTTP 400 on mismatch).
- **Anti-IDOR Protection**: Review session ownership is verified before any external call; unauthorized access throws `ResourceNotFoundException` (HTTP 404) to avoid leaking session existence.
- **Terminal State Requirement**: Only review sessions in `ReviewSessionStatus.COMPLETED` status can proceed; in-progress or cancelled sessions are rejected with HTTP 409 (`MigrationExecutionValidationException`).
- **All-or-Nothing Guarantee**: Any track-level ineligibility (unresolved tracks, active conflicts, invalid destination candidate IDs, duplicates) fails validation before the first track insertion is attempted.

### 4. Per-Track Outcome Representation and Failure Policy
- **Granular Statuses**:
  - `SUCCESS`: Confirmed provider insertion (`DestinationPlaylistItem` returned with non-null ID).
  - `FAILED`: Confirmed provider rejection (e.g. video unavailable, provider error, quota exceeded).
  - `UNCERTAIN`: Network timeout or connection reset (`ResourceAccessException`). Outcome cannot be confirmed remotely.
  - `SKIPPED`: Not attempted due to prior failure or uncertain outcome, preserving relative ordering invariants.
- **Conservative Continuation Policy**:
  - If a track fails with a confirmed provider rejection, proceeding would insert subsequent tracks into incorrect relative positions. Therefore, further insertions stop, and all remaining tracks are marked `SKIPPED`.
  - If a track encounters an ambiguous timeout (`UNCERTAIN`), the remote video may or may not have been added. To prevent duplicate additions and corrupted ordering, further insertions stop, and remaining tracks are marked `SKIPPED`.
  - The final summary reports exact counts for `successfulInsertions`, `failedInsertions`, `uncertainInsertions`, and `skippedInsertions`.
  - `fullySuccessful` is `true` if and only if `successfulInsertions == totalApprovedTracks` (zero failed, uncertain, or skipped tracks).

### 5. Idempotency Limitations and External Side Effects
- Destination playlist track insertion interacts with YouTube Data API v3 (`playlistItems.insert`), which does not support idempotency keys.
- Remote YouTube mutations are external side effects and cannot be rolled back via local database transactions.
- Ambiguous timeouts are logged as `UNCERTAIN` without blind retries to prevent duplicate video insertions.
- Re-executing an insertion request against an already partially-populated playlist requires durable per-track execution state, which will be addressed in Phase 11G (Recovery & Idempotency) / Phase 14 (History). Phase 11D reports accurate in-memory execution summaries.

### 6. Credential Sanitization
- Upstream error messages and diagnostic details are sanitized against `TOKEN_PATTERN` (`bearer`, `access_token=`, `api_key=`, `secret=`, etc.) before logging or inclusion in response DTOs.

---

## Contract Specifications

### Package: `com.spotifyyoutube.migrator.migration.application.execution.contract`
- **`TrackInsertionStatus`** (`enum`): `SUCCESS`, `FAILED`, `UNCERTAIN`, `SKIPPED`.
- **`TrackInsertionOutcome`** (`record`): Per-track outcome capturing target position, source position, source track details, destination video ID, destination playlist item ID, status, and sanitized message.
- **`PlaylistTrackInsertionSummary`** (`record`): Comprehensive summary capturing session ID, plan ID, user ID, destination playlist ID, counts (`totalApprovedTracks`, `successfulInsertions`, `failedInsertions`, `uncertainInsertions`, `skippedInsertions`), unmodifiable outcomes list, timestamps, and `fullySuccessful` flag.
- **`InsertTracksExecutionRequest`** (`record`): Application request capturing `reviewSessionId`, `destinationPlaylistId`, and optional `userId`.

### Package: `com.spotifyyoutube.migrator.migration.application.execution`
- **`OrderedTrackInsertionService`** (`interface`): Defines `insertApprovedTracks(InsertTracksExecutionRequest, UUID)` and `insertApprovedTracks(UUID, String, UUID)`.
- **`DefaultOrderedTrackInsertionService`** (`class`): Implements sequential ordered track insertion.

### Package: `com.spotifyyoutube.migrator.migration.api.dto`
- **`InsertTracksRequest`** (`record`): Request DTO with `@NotNull reviewSessionId`, `@NotBlank destinationPlaylistId`, and optional `userId`.
- **`TrackInsertionOutcomeResponse`** (`record`): Itemized outcome DTO.
- **`PlaylistTrackInsertionResponse`** (`record`): Execution summary DTO.

### Package: `com.spotifyyoutube.migrator.migration.api`
- **`MigrationExecutionController`**: Extended with `POST /api/migrations/execution/insert-tracks` and convenience route `POST /api/migrations/execution/insert-tracks/{sessionId}` (with alias `/api/migrations/approved/...`).

---

## Consequences

### Positive
- Strict order preservation: tracks are inserted in exact `targetPosition` sequence.
- Comprehensive outcome visibility: distinguishes confirmed success, confirmed failure, ambiguous timeouts, and skipped tracks.
- Mutation safety: zero insertions occur if context validation fails.
- No blind retries on ambiguous failures, preventing duplicate video clutter.

### Negative / Trade-offs
- YouTube playlist insertions cannot be rolled back atomically; partial insertions require re-synchronization or cleanup.
- In-memory execution summary: durable replay across process restarts is deferred to Phase 11G/Phase 14.
