# API Contract

## Base Path
All APIs start with `/api`.

## Conventions
- Standard REST semantics (GET, POST, PUT, DELETE).
- Status codes: 200 OK, 201 Created, 202 Accepted, 400 Bad Request, 401 Unauthorized, 403 Forbidden, 404 Not Found, 409 Conflict, 429 Too Many Requests, 500 Server Error.
- Standardized error formatting matching `GlobalExceptionHandler` (`ApiErrorResponse` with `timestamp`, `status`, `error`, `message`, `errorCode`, and `path`).
- Pagination response wrapper: `PagedResponse<T>` (`content`, `page`, `size`, `totalElements`, `totalPages`, `first`, `last`).
- Non-disclosure policy: Anti-IDOR security returns HTTP 404 Not Found (never HTTP 403) when accessing unowned migrations or resources to prevent ID enumeration.

---

## Migration History & Detail Endpoints (Phase 14C & 14D)

### 1. `GET /api/migrations/history`
Retrieves a paginated list of migrations executed by the authenticated user.

- **Authentication**: Required (Session Cookie / OAuth).
- **Query Parameters**:
  - `page` (optional, integer, default: `0`): 0-indexed page number.
  - `size` (optional, integer, default: `20`): page size (1–100).
  - `status` (optional, string): Filter by `MigrationExecutionStatus` (`INITIALIZED`, `QUEUED`, `ALLOCATING_DESTINATION`, `RUNNING`, `COMPLETED`, `FAILED`, `PARTIALLY_COMPLETED`, `RECOVERY_REQUIRED`, `ROLLED_BACK`, `PARTIALLY_ROLLED_BACK`).
- **Default Sort**: Reverse chronological (`createdAt DESC`).
- **Response**: HTTP 200 OK with `PagedResponse<MigrationHistoryItemResponse>`.

#### Example Response
```json
{
  "content": [
    {
      "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
      "status": "COMPLETED",
      "sourcePlaylistId": "spotify-pl-123",
      "sourcePlaylistTitle": "Road Trip Hits",
      "destinationPlatform": "YOUTUBE",
      "destinationPlaylistId": "yt-pl-456",
      "destinationPlaylistUrl": "https://youtube.com/playlist?list=yt-pl-456",
      "totalSourceTracks": 25,
      "matchedTracks": 25,
      "totalApprovedTracks": 25,
      "successfulInsertions": 25,
      "failedInsertions": 0,
      "uncertainInsertions": 0,
      "skippedInsertions": 0,
      "rolledBackInsertions": 0,
      "failureSummary": null,
      "startedAt": "2026-03-01T10:00:05Z",
      "completedAt": "2026-03-01T10:01:20Z",
      "createdAt": "2026-03-01T10:00:00Z",
      "updatedAt": "2026-03-01T10:01:20Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1,
  "first": true,
  "last": true
}
```
---

### 2. `GET /api/migrations/{id}`
Retrieves complete execution details, summary metrics, and per-track execution records for a single migration execution.

- **Authentication**: Required.
- **Path Parameters**:
  - `id` (UUID): Unique ID of the migration execution.
- **Response Codes**:
  - `200 OK`: Successful retrieval of user-owned execution.
  - `400 Bad Request`: Invalid or malformed UUID.
  - `403 Forbidden`: Unauthenticated request.
  - `404 Not Found`: Execution does not exist or belongs to another user (anti-IDOR non-disclosure).
- **Response Format**: `MigrationExecutionResponse`

#### Example Response
```json
{
  "summary": {
    "executionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "reviewSessionId": "2fa85f64-5717-4562-b3fc-2c963f66afa5",
    "userId": "1fa85f64-5717-4562-b3fc-2c963f66afa4",
    "status": "COMPLETED",
    "destinationPlatform": "YOUTUBE",
    "destinationPlaylistId": "yt-pl-456",
    "destinationPlaylistUrl": "https://youtube.com/playlist?list=yt-pl-456",
    "totalSourceTracks": 1,
    "matchedTracks": 1,
    "totalApprovedTracks": 1,
    "successfulInsertions": 1,
    "failedInsertions": 0,
    "uncertainInsertions": 0,
    "skippedInsertions": 0,
    "rolledBackInsertions": 0,
    "failureSummary": null,
    "sourcePlaylistId": "spotify-pl-123",
    "sourcePlaylistTitle": "Road Trip Hits",
    "startedAt": "2026-03-01T10:00:05Z",
    "completedAt": "2026-03-01T10:01:20Z",
    "createdAt": "2026-03-01T10:00:00Z",
    "updatedAt": "2026-03-01T10:01:20Z"
  },
  "trackRecords": [
    {
      "id": "4fa85f64-5717-4562-b3fc-2c963f66afa7",
      "sourceTrackId": "sp-track-1",
      "sourcePosition": 0,
      "sourceTitle": "Midnight City",
      "sourceArtist": "M83",
      "destinationTrackId": "yt-video-1",
      "destinationTitle": "M83 - Midnight City (Official Video)",
      "destinationChannel": "M83",
      "destinationDurationMs": 243000,
      "decisionSource": "AUTOMATIC",
      "matchScore": 0.98,
      "matchDecision": "MATCH",
      "destinationPlaylistItemId": "pl-item-1",
      "targetPosition": 0,
      "status": "ADDED",
      "errorMessage": null,
      "attemptCount": 1,
      "maxAttempts": 3,
      "startedAt": "2026-03-01T10:00:06Z",
      "completedAt": "2026-03-01T10:00:08Z"
    }
  ]
}
```
---

### 3. `GET /api/migrations/{id}/history`
Retrieves a chronological audit timeline of lifecycle state transitions, retry attempts, worker lease acquisitions, rollbacks, and reconciliations.

- **Authentication**: Required.
- **Path Parameters**:
  - `id` (UUID): Unique ID of the migration execution.
- **Query Parameters**:
  - `page` (optional, default: `0`): 0-indexed page number.
  - `size` (optional, default: `50`): page size.
- **Ordering**: Chronological (`createdAt ASC`).
- **Response Codes**:
  - `200 OK`: Chronological timeline list.
  - `400 Bad Request`: Malformed UUID.
  - `403 Forbidden`: Unauthenticated request.
  - `404 Not Found`: Execution does not exist or belongs to another user (anti-IDOR non-disclosure).
- **Response Format**: `PagedResponse<MigrationAuditEventResponse>`

#### Example Response
```json
{
  "content": [
    {
      "id": "5fa85f64-5717-4562-b3fc-2c963f66afa8",
      "executionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
      "eventType": "STATE_TRANSITION",
      "previousState": null,
      "newState": "INITIALIZED",
      "reason": "Execution initialized from approved review session",
      "details": "Total tracks: 1",
      "actor": "USER",
      "correlationId": "corr-101",
      "rollbackRecordId": null,
      "reconciliationRecordId": null,
      "createdAt": "2026-03-01T10:00:00Z"
    },
    {
      "id": "6fa85f64-5717-4562-b3fc-2c963f66afa9",
      "executionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
      "eventType": "LEASE_ACQUIRED",
      "previousState": null,
      "newState": null,
      "reason": "Acquired lease generation 1",
      "details": "worker-1",
      "actor": "SCHEDULER",
      "correlationId": null,
      "rollbackRecordId": null,
      "reconciliationRecordId": null,
      "createdAt": "2026-03-01T10:00:02Z"
    },
    {
      "id": "7fa85f64-5717-4562-b3fc-2c963f66afb0",
      "executionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
      "eventType": "STATE_TRANSITION",
      "previousState": "RUNNING",
      "newState": "COMPLETED",
      "reason": "All approved tracks processed successfully",
      "details": "Added: 1, Failed: 0, Uncertain: 0",
      "actor": "SYSTEM",
      "correlationId": null,
      "rollbackRecordId": null,
      "reconciliationRecordId": null,
      "createdAt": "2026-03-01T10:01:20Z"
    }
  ],
  "page": 0,
  "size": 50,
  "totalElements": 3,
  "totalPages": 1,
  "first": true,
  "last": true
}
```
