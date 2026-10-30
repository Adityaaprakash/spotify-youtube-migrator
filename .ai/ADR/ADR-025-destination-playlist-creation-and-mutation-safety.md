# ADR-025: Destination Playlist Creation Architecture and Mutation Safety Boundary (Phase 11C)

## Status
**ACCEPTED / IMPLEMENTED**

## Context

Following the completion of Phase 11A (Execution Architecture & Contracts) and Phase 11B (Approved Plan Validation) under ADR-024, the application requires the capability to create a destination playlist on YouTube Music using the server-side validated `ApprovedMigrationContext`.

### 1. Architectural Evolution and Separation of Concerns
- **Phase 8G/8H**: Tightly coupled Spotify extraction to YouTube playlist creation and track additions inline (`PlaylistMigrationOrchestrator`). This orchestrator remains frozen and intact for backward compatibility.
- **Phase 9**: Introduced pre-flight planning (`MigrationPlan`) providing read-only migration previews without destination mutations (ADR-022).
- **Phase 10**: Introduced persistent human review and correction (`ReviewSession`, `ReviewTrack`) allowing users to accept, replace candidates, or exclude tracks without mutating destination music services (ADR-023).
- **Phase 11A & 11B**: Established application execution contracts and an all-or-nothing validation boundary (`ApprovedPlanValidator`) ensuring zero destination writes occur before full validation (ADR-024).
- **Phase 11C Objective**: Safely invoke destination playlist creation using the validated execution context, capturing and returning the destination playlist identity while strictly prohibiting track insertion (deferred to Phase 11D).

---

## Architectural Decisions

### 1. Dedicated Application Service Boundary
- Rather than modifying `DefaultApprovedMigrationExecutionService` or `DefaultApprovedPlanValidator` (which strictly maintain zero destination dependencies to preserve pure validation isolation), Phase 11C introduces `DestinationPlaylistCreationService` and `DefaultDestinationPlaylistCreationService` in `com.spotifyyoutube.migrator.migration.application.execution`.
- This service coordinates:
  1. Resolving the authenticated user from the trusted security context.
  2. Delegating to `ApprovedMigrationExecutionService.prepareExecution` to obtain the validated, immutable `ApprovedMigrationContext`.
  3. Verifying that the approved track selection is non-empty (`hasApprovedTracks() == true`).
  4. Validating destination playlist metadata (title $\le 150$ characters, description $\le 5000$ characters).
  5. Delegating playlist creation to `MusicDestination.createPlaylist`.
  6. Validating the provider response to ensure a non-null, non-blank destination playlist ID.
  7. Returning an immutable, typed `DestinationPlaylistCreationResult` containing the confirmed identity needed by Phase 11D.

### 2. Gated Pre-Mutation Security and Validation
- **No Untrusted Client Context**: The service never accepts client-provided approved tracks or fabricated execution contexts. All tracks, source playlist details, and target metadata are derived strictly from the server-side completed `ReviewSession`.
- **Anti-Impersonation**: Caller-supplied `userId` is strictly checked against the authenticated principal (HTTP 400 on mismatch).
- **Anti-IDOR Protection**: Review session ownership is verified before any external call; unauthorized access throws `ResourceNotFoundException` (HTTP 404) to avoid leaking session existence.
- **Terminal State Requirement**: Only review sessions in `ReviewSessionStatus.COMPLETED` status can proceed; in-progress or cancelled sessions are rejected with HTTP 409 (`MigrationExecutionValidationException`).
- **All-or-Nothing Guarantee**: Any track-level ineligibility (unresolved tracks, active conflicts, invalid destination candidate IDs, duplicates) fails validation, resulting in zero calls to `MusicDestination`.

### 3. Strict Mutation Boundary: Zero Track Insertion
- Phase 11C is strictly bounded to destination playlist creation.
- Under NO circumstance does `DestinationPlaylistCreationService` call `MusicDestination.addTrackToPlaylist(...)` or iterate through `ApprovedTrack` items to insert videos.
- Track insertion is completely isolated and deferred to Phase 11D.

### 4. Idempotency Limitations and Ambiguous Outcomes
- The YouTube Data API v3 `playlists.insert` endpoint does not support client-supplied idempotency keys.
- If a network failure, socket timeout, or HTTP client error (`ResourceAccessException`) occurs during playlist creation, the remote outcome is **UNCERTAIN** (the playlist may have been created on YouTube before the timeout occurred).
- To adhere to ADR-022 and avoid speculative schema changes, Phase 11C does not introduce new database tables or entities to track in-flight mutations.
- **Handling Strategy**:
  - Ambiguous timeouts are logged with high-visibility warnings as `UNCERTAIN` without leaking credentials or tokens.
  - The application does not perform blind retries, preventing the generation of duplicate playlists on the user's YouTube account.
  - Full production recovery and idempotency reconciliation are designated for Phase 11G / Phase 13 reliability engineering.

### 5. Credential Sanitization
- All error messages and diagnostics from external providers are sanitized using `TOKEN_PATTERN` (`bearer`, `access_token=`, `api_key=`, `secret=`, etc.) before logging or inclusion in exception messages.
- Provider access tokens and internal OAuth connection objects are never exposed in API responses.

---

## Contract Specifications

### Package: `com.spotifyyoutube.migrator.migration.application.execution.contract`
- **`DestinationPlaylistCreationResult`** (`record`): Immutable domain contract capturing `reviewSessionId`, `planId`, `userId`, `destinationPlatform`, `destinationPlaylistId`, `destinationPlaylistUrl`, `playlistTitle`, `playlistDescription`, `isPrivate`, `approvedTrackCount`, and `createdAt`.

### Package: `com.spotifyyoutube.migrator.migration.application.execution`
- **`DestinationPlaylistCreationService`** (`interface`): Defines `createDestinationPlaylist(ApprovedMigrationExecutionRequest, UUID)` and `createDestinationPlaylist(UUID, UUID)`.
- **`DefaultDestinationPlaylistCreationService`** (`class`): Implements `DestinationPlaylistCreationService`, injecting `ApprovedMigrationExecutionService` and `MusicDestination`.

### Package: `com.spotifyyoutube.migrator.migration.api.dto`
- **`CreateDestinationPlaylistRequest`** (`record`): Request DTO with `@NotNull reviewSessionId`, optional `userId`, `targetPlaylistTitle`, `targetPlaylistDescription`, and `isPrivate`.
- **`DestinationPlaylistResponse`** (`record`): Presentation DTO returning created playlist details and status `CREATED`.

### Package: `com.spotifyyoutube.migrator.migration.api`
- **`MigrationExecutionController`**: Extended with `POST /api/migrations/execution/create-playlist` and convenience route `POST /api/migrations/execution/create-playlist/{sessionId}` (with alias `/api/migrations/approved/...`).

---

## Consequences

### Positive
- Strict separation of concerns: execution validation remains pure and isolated (Phase 11B), playlist creation is isolated (Phase 11C), and track insertion remains isolated (Phase 11D).
- Zero duplicate abstractions: reuses `ApprovedMigrationExecutionService`, `ApprovedPlanValidator`, `MusicDestination`, and `YouTubeMusicDestinationAdapter`.
- Zero database migrations: strictly preserves ADR-022 transient plan guarantee.
- Zero destination track insertions: strictly respects Phase 11C boundary.

### Negative / Trade-offs
- Network timeouts during playlist creation leave destination state uncertain; automatic deduplication across retries is deferred to Phase 11G/13.
