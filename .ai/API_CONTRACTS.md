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

## Error Conventions
All failed integrations return strict `ApiError` shapes resolving to explicitly modeled codes. 
```json
{
  "code": "INVALID_REQUEST | UNAUTHORIZED | FORBIDDEN | NOT_FOUND | CONFLICT | INVALID_STATE | INTERNAL_SERVER_ERROR",
  "message": "Human readable context",
  "timestamp": "2026-10-05T00:00:00Z",
  "path": "/api/..."
}
```
Stack traces and JPA references are strictly prohibited from exposure.