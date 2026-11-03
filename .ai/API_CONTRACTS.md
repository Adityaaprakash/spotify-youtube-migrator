# API Contracts

The following API contracts represent the boundaries mapped statically between the Frontend Client and Internal Domain via Application Services. These mappings do not leak JPA specifics.

### User API (`/api/users`)
**GET /api/users/{id}**
- Retrieves a specific user natively.
- Returns `@UserResponse(id, email, spotifyId, youtubeId)`
- Will throw `404 Not Found` if unmatched.

### Playlist API (`/api/playlists`)
**GET /api/playlists**
- Resolves all playlists owned.
- `?userId={uuid}` explicitly required structurally until Authentication spans boundaries.
- Returns `List<PlaylistResponse>`.

**GET /api/playlists/{id}**
- Resolves distinct playlist targeting ID.
- Returns `@PlaylistResponse(...)`.

### Migration API (`/api/migrations`)
**POST /api/migrations**
- Creates an explicit job mapping utilizing valid invariants. 
- Accepts `@CreateMigrationRequest(userId, sourcePlaylistId, sourcePlatform, targetPlatform)`.
- Validations:
  - `sourcePlatform != targetPlatform` (Yields `409 Conflict - INVALID_STATE`).
  - `@NotNull` checks (Yields `400 Bad Request - INVALID_REQUEST`).
- Returns `@MigrationJobResponse(...)` holding initial `PENDING` states resolving to `201 Created`.

**GET /api/migrations/{id}**
- Queries specific migration state.
- Returns `@MigrationJobResponse`.

**GET /api/migrations/{id}/tasks**
- Queries sequential individual tracks processed during migration execution iteratively. 
- Returns `List<MigrationTaskResponse>`.

### Asynchronous Migration API (Phase 12E) (`/api/migrations`)

**POST /api/migrations**
- Dual-routed entry point supporting both approved asynchronous migration dispatch (Phase 12E) and legacy direct job creation (Phase 1).
- **Approved Async Submission** (detected when payload contains `reviewSessionId`, `sessionId`, `targetPlaylistTitle`, or `targetPlaylistDescription`):
  - Requires authenticated session context. Client `userId` is validated against the authenticated principal (anti-impersonation).
  - Body: `@RunMigrationApiRequest(reviewSessionId, userId, sourcePlaylistId, targetPlaylistTitle, targetPlaylistDescription, isPrivate)`
  - Pre-mutation validation confirms review session ownership (anti-IDOR) and approved status.
  - Stage 1 prepares destination playlist and persists `MigrationExecution` as `QUEUED`.
  - Stage 2 submits task to bounded background worker pool.
  - **Returns `202 Accepted`** for newly queued migrations with header `Location: /api/migrations/{executionId}/progress` and `@MigrationSubmissionResponse`.
  - **Returns `200 OK`** for idempotent submissions where the execution is already `COMPLETED`.
  - Responses:
    - `400 Bad Request`: Validation failure or client-supplied `userId` mismatch.
    - `401 Unauthorized`: Unauthenticated request.
    - `404 Not Found`: Unauthorized or missing review session (anti-IDOR).
    - `409 Conflict`: Migration already `RUNNING` or `QUEUED`.
    - `429 Too Many Requests`: Worker pool saturated (`TaskRejectedException`), includes `Retry-After: 30` header.
- **Legacy Synchronous Job Creation** (detected when payload contains `sourcePlatform` or `targetPlatform`):
  - Body: `@CreateMigrationRequest(userId, sourcePlaylistId, sourcePlatform, targetPlatform)`
  - Returns `@MigrationJobResponse(...)` with status `201 Created`.

**POST /api/migrations/submit**
- Explicit, typed convenience endpoint for approved asynchronous migration dispatch (Phase 12E).
- Body: `@RunMigrationApiRequest` (same semantics and responses as approved `POST /api/migrations`).

**GET /api/migrations/{executionId}/progress**
- Real-time polling endpoint retrieving execution progress and track outcome distribution by execution ID.
- Strictly read-only: performs zero DB writes, zero YouTube/Spotify API calls, and zero background worker triggers.
- Enforces user ownership via `MigrationProgressService`: unauthorized executions return `404 Not Found` (anti-IDOR).
- Rate-limited via `ProgressPollingRateLimiter`: excessive polling requests exceeding configured quota return `429 Too Many Requests` with `Retry-After: 5` header.
- Returns `@MigrationProgressResponse(executionId, status, totalTracks, pendingTracks, addingTracks, addedTracks, failedTracks, uncertainTracks, skippedTracks, rollingBackTracks, rolledBackTracks, processedTracks, remainingTracks, progressPercentage)`.
- Headers: `Cache-Control: no-cache`.
- Status: `200 OK` (or `429 Too Many Requests` if polling quota is exceeded).

**GET /api/migrations/review-sessions/{reviewSessionId}/progress**
- Real-time polling endpoint retrieving execution progress by associated review-session ID.
- Enables clients holding only `reviewSessionId` to monitor progress without initiating new executions or database mutations.
- Enforces review session ownership via `MigrationProgressService`: unauthorized sessions return `404 Not Found` (anti-IDOR).
- Rate-limited via `ProgressPollingRateLimiter`: excessive polling requests exceeding configured quota return `429 Too Many Requests` with `Retry-After: 5` header.
- Returns the exact same `@MigrationProgressResponse` contract.
- Headers: `Cache-Control: no-cache`.
- Status: `200 OK` (or `429 Too Many Requests` if polling quota is exceeded).

### YouTube API (`/api/youtube`)
**GET /api/youtube/videos/search**
- Searches YouTube for video candidates.
- Parameters: `q` (required, max 500 chars), `pageToken` (optional), `maxResults` (optional, 1-50, default 25).
- Returns `@YouTubeVideoSearchResponse(items, nextPageToken, prevPageToken, totalResults, resultsPerPage)`.

**GET /api/youtube/videos/{videoId}`**
- Retrieves metadata for a specific YouTube video.
- Returns `@YouTubeVideoDetailsResponse(videoId, title, channelTitle, description, thumbnailUrl, publishedAt, duration)`.

**POST /api/youtube/playlists**
- Creates a new YouTube playlist for the authenticated user via Google OAuth Bearer token.
- Body: `@CreateYouTubePlaylistRequest(title, description, privacyStatus)`.
- Returns `@YouTubePlaylistResponse(id, title, description, privacyStatus, publishedAt, channelTitle)` resolving to `201 Created`.

**GET /api/youtube/playlists/{playlistId}**
- Retrieves metadata for a specific playlist owned by/accessible to the authenticated user.
- Returns `@YouTubePlaylistResponse(...)`.

**GET /api/youtube/playlists/{playlistId}/verify**
- Verifies accessibility of a target YouTube playlist.
- Returns `@DestinationPlaylistVerification(playlistId, status, message, playlist)` where status is one of `ACCESSIBLE`, `NOT_FOUND`, `INACCESSIBLE`, `UNCERTAIN`, `FAILED`.

**POST /api/youtube/playlists/{playlistId}/videos**
- Adds a video track to a YouTube playlist for the authenticated user.
- Body: `@AddVideoToPlaylistRequest(videoId, position)`.
- Returns `@YouTubePlaylistItemResponse(playlistItemId, playlistId, videoId, position, success)` resolving to `201 Created`.

## Error Conventions
All failed integrations return strict `ApiError` shapes resolving to explicitly modeled codes. 
```json
{
  "code": "INVALID_REQUEST | UNAUTHORIZED | FORBIDDEN | NOT_FOUND | CONFLICT | INVALID_STATE | EXTERNAL_PROVIDER_ERROR | TOO_MANY_REQUESTS | INTERNAL_SERVER_ERROR",
  "message": "Human readable context",
  "timestamp": "2026-11-03T12:00:00Z",
  "path": "/api/..."
}
```
- **400 Bad Request (`INVALID_REQUEST`)**: Malformed payload, missing required parameters, validation errors, or caller impersonation mismatch.
- **401 Unauthorized (`UNAUTHORIZED`)**: Unauthenticated request or expired session.
- **403 Forbidden (`FORBIDDEN`)**: Authenticated principal lacks permission.
- **404 Not Found (`NOT_FOUND`)**: Resource does not exist or caller is unauthorized (anti-IDOR protection).
- **409 Conflict (`CONFLICT` / `INVALID_STATE`)**: Conflicting state (e.g. migration already RUNNING or QUEUED).
- **429 Too Many Requests (`TOO_MANY_REQUESTS`)**:
  - *Executor Saturation*: Worker pool queue capacity exceeded during migration submission (`TaskRejectedException`), includes `Retry-After: 30` header. Execution reset from QUEUED to INITIALIZED.
  - *Progress Polling Limit*: Client exceeded configured progress endpoint polling rate limit (`RateLimitExceededException`), includes `Retry-After: 5` header. Execution state remains completely unmutated.
- **500 Internal Server Error (`INTERNAL_SERVER_ERROR`)**: Generic sanitised error message. Stack traces, credentials, and internal aggregate details are strictly prohibited from exposure. Count conservation invariant violations raise `ProgressConsistencyException` which maps safely here.