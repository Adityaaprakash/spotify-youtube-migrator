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

Reauthorization implies: Token bounds are wrapped around `OAuthTokenLifecycleService`. Reauth is purely transparent unless connection status breaks permanently.


## YouTube

Purpose:
Destination provider.

Integration location:
backend/.../youtube/

Rules:
YouTube DTOs remain inside YouTube infrastructure.

Mapping:
YouTube DTO
→ YouTubeMapper
→ Domain

## Provider Abstraction

Migration engine must depend on internal abstractions,
not Spotify/YouTube implementations.