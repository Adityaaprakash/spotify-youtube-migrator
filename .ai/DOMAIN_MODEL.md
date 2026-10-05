# Domain Model

## Core Entities (Phase 1 Implemented)

### User
Represents an application user mapping to external identity providers.
- **Invariants**: `email` must be unique to prevent duplicative registrations. `spotifyId` and `youtubeId` are globally unique to provider but nullable structurally until Phase 2 OAuth connects mapping.

### Playlist
Represents either source extraction mapping or target creation intent explicitly owned by `User`.
- **Invariants**: A User, an external Playlist ID, and a distinct Platform must form a unique resolution lookup (mapped via `PlaylistRepository#findByUserIdAndExternalIdAndPlatform`).

### Track
Represents a **Playlist Occurrence** (not a global catalog aggregate) linked functionally `ManyToOne` to the `Playlist`.
- **Invariants**: Ordering structurally follows database extraction order (to be augmented with `position` index if UI drag-drop emerges). `durationMs` and string values represent raw values scraped from Provider at Migration runtime.

### MigrationJob
The root aggregate for tracking a user's migration attempt.
- **Invariants**: Tracks `sourcePlaylistId` without eagerly loading the entire Spotify DTO. Stores `sourcePlatform` and `targetPlatform` enum types. Progressions mapped intuitively via computed offsets over `MigrationTasks`.

### MigrationTask
The granular job execution leaf representing a single Track transfer.
- **Invariants**: A `sourceTrackId` string guarantees origin mapping, while `targetTrackId` captures target insertion outcome. `status` dictates partial resumption validity.

---

## Future Planned Models (Phase 2+)
- `OAuthConnection`: Future modular separation for identity handling avoiding overloading `User`.
- `TrackArtist`, `TrackCandidate`, `MatchResult`: Strictly deferred to Phase 6+ (Matching Engine logic).
- Future Phase 9 (Migration Engine) specific `MigrationStatus` states (e.g. `CREATING_PLAYLIST`, `ADDING_TRACKS`) explicitly deferred.