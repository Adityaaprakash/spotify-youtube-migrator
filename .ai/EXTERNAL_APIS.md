# External APIs

## Spotify

Purpose:
Source playlist provider.

Integration location:
backend/.../spotify/

Rules:
Spotify DTOs remain inside Spotify infrastructure.

Mapping:
Spotify DTO
→ SpotifyMapper
→ Domain (Playlist, Track)
→ Application Response DTO (PlaylistResponse, TrackResponse)

Endpoints Used:
- `GET /me/playlists`: Used for Discovery phase. Paging supported.
- `GET /playlists/{playlistId}`: Metadata retrieval. Captures external properties into `Playlist` domain object securely.
- `GET /playlists/{playlistId}/tracks`: Paginated tracks aggregation. Limit max 50 per request. Synchronous paginator loops through provider limits up until the total bound count or null `next` pointer is detected.

Pagination Strategy:
The Spotify limits cap at 50 tracks via `/playlists/{playlistId}/tracks`. We synchronously fetch loops via `limit` and `offset` internally. The frontend is protected from this complexity and gets a complete block list response, resolving missing paginations firmly within the Java domain execution context. 

Unavailable Track Policy:
In the Tracks loop, any null items (`nullItem`) or local items (`is_local=true`) are intentionally ignored, since they cannot be matched on YouTube reliably via standard Spotify APIs. No failure occurs, they are simply omitted from mapping.

Scopes:
Inherits `playlist-read-private` and `playlist-read-collaborative` from Phase 3A-3C. No additional scopes were required for retrieving metadata or tracks inside the authenticated bounds.

Error Mappings:
- HTTP 400 maps to `HttpStatus.BAD_REQUEST`.
- HTTP 401 maps to `HttpStatus.UNAUTHORIZED`.
- HTTP 403 maps to `HttpStatus.FORBIDDEN`.
- HTTP 404 maps to `HttpStatus.NOT_FOUND` (with explicit playlist/connection hints).
- HTTP 429 maps to `HttpStatus.TOO_MANY_REQUESTS` appending `Retry-After` appropriately.
- Invalid token connections are flagged structurally via `InvalidStateException` resulting natively as a 409 Conflict.
- Network timeouts (`ResourceAccessException`) map manually to 504 `HttpStatus.GATEWAY_TIMEOUT`.
- Malformed bodies map via `RestClientException` to 502 `HttpStatus.BAD_GATEWAY`.

Reauthorization implies: Token bounds are wrapped around `OAuthTokenLifecycleService`. Reauth is purely transparent unless connection status breaks permanently.


## YouTube

Purpose:
Destination provider.

Integration location:
backend/.../youtube/

Rules:
YouTube provider DTOs (`YouTubeSearchListResponseDto`, `YouTubeSearchResultDto`, etc.) remain strictly inside `youtube.infrastructure.dto`.
Internal domain or application response layers must only consume application-owned contracts (`YouTubeVideoSearchResponse`, `YouTubeVideoSummaryResponse`).

Mapping:
YouTube Provider DTO (`YouTubeSearchListResponseDto`, `YouTubeVideoListResponseDto`, `YouTubePlaylistDto`, `YouTubePlaylistListResponseDto`, `YouTubePlaylistItemDto`)
→ `YouTubeMapper`
→ Application Response DTO (`YouTubeVideoSearchResponse`, `YouTubeVideoDetailsResponse`, `YouTubePlaylistResponse`, `YouTubePlaylistItemResponse`)
→ Migration Destination Domain Model (`DestinationPlaylist`, `DestinationTrackCandidate`, `DestinationSearchResult`, `DestinationTrackDetails`, `DestinationPlaylistItem`, `DestinationPlaylistVerification`) via `YouTubeMusicDestinationAdapter`

Endpoints Used:
- `GET /search`: Search for video candidates (`part=snippet`, `type=video`, `q`, `maxResults`, `key`, `pageToken`). Costs 100 quota units.
- `GET /videos`: Retrieve detailed video metadata including ISO-8601 duration (`part=snippet,contentDetails`, `id`, `key`). Costs 1 quota unit.
- `POST /playlists`: Create a new YouTube playlist (`part=snippet,status`, Bearer access token). Costs 50 quota units.
- `GET /playlists`: Retrieve playlist metadata and verify accessibility (`part=snippet,status`, `id`, Bearer access token). Costs 1 quota unit.
- `POST /playlistItems`: Add a video track to a YouTube playlist (`part=snippet`, Bearer access token, payload with playlistId, videoId, position). Costs 50 quota units.

Quota Policy:
- The YouTube Data API v3 allocates a default project quota of 10,000 units/day.
- `search.list` costs **100 units** per call (1% of daily standard quota).
- `videos.list` costs **1 unit** per call.
- `playlists.list` (GET) costs **1 unit** per call.
- `playlists.insert` costs **50 units** per call.
- `playlistItems.insert` costs **50 units** per call.
- Search queries must be validated strictly (non-blank, max 500 chars, maxResults bounded between 1 and 50) to prevent quota exhaustion.
- Results should be cached whenever possible in downstream matching phases (Phase 6/7).

Authentication & Credential Policy:
- Read-only video discovery (`/search`) and metadata (`/videos`) use the project API key (`YOUTUBE_API_KEY`).
- User playlist management operations (`POST /playlists`, `GET /playlists`, `POST /playlistItems`) leverage the Phase 2 Google OAuth tokens (`GoogleProviderAdapter`, `OAuthTokenLifecycleService`).
- API keys and OAuth tokens are never returned to the frontend or logged.

Error Mappings:
- HTTP 400: `ExternalProviderException` ("YouTube API request invalid", 400)
- HTTP 401: `ExternalProviderException` ("YouTube API authentication failed: invalid API key or expired token", 401)
- HTTP 403: `ExternalProviderException` ("YouTube API request forbidden or quota exceeded", 403, retryAfter)
- HTTP 404: `ResourceNotFoundException` ("YouTube endpoint or resource not found")
- HTTP 429: `ExternalProviderException` ("YouTube API rate limit exceeded", 429, retryAfter)
- HTTP 5xx: `ExternalProviderException` ("YouTube API service error", 5xx, retryAfter)
- Network Timeouts / Connection drops: Handled via Spring RestClient and GlobalExceptionHandler (`504 Gateway Timeout` or `502 Bad Gateway`).

YouTube Music Limitations:
- YouTube Music does not provide an official public write/search REST API distinct from YouTube Data API v3.
- Songs and music videos on YouTube are searched and managed via standard YouTube Data API v3 video entities and playlists.

## Provider Abstraction

Migration engine depends strictly on internal abstractions (`MusicDestination`, `DestinationPlaylist`, etc.) in `migration.application.destination`, not directly on Spotify or YouTube implementations. `YouTubeMusicDestinationAdapter` bridges the YouTube subsystem to this provider-neutral contract.