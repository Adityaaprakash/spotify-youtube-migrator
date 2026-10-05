## Implemented Domain Model

### User
Represents an application user mapping to identity providers.
- **Attributes:** `id` (UUID), `email` (String), `spotifyId` (String), `youtubeId` (String), `createdAt`, `updatedAt`

### Playlist
Represents a source or generated playlist under a platform.
- **Relationships:** Many-to-One to `User`
- **Attributes:** `id`, `name`, `description`, `platform` (Spotify/YouTube), `externalId`, `url`, `totalTracks`

### Track
Represents a specific track inside a playlist.
- **Relationships:** Many-to-One to `Playlist`
- **Attributes:** `id`, `name`, `artist`, `album`, `durationMs`, `externalId`

### MigrationJob
Represents the user intent to migrate a playlist to a target platform.
- **Relationships:** Many-to-One to `User`
- **Attributes:** `id`, `sourcePlaylistId`, `sourcePlatform`, `targetPlatform`, `status` (`MigrationStatus`), `totalTracks`, `processedTracks`, `failedTracks`, `startedAt`, `completedAt`

### MigrationTask
Granularly tracks the migration progression of an individual track.
- **Relationships:** Many-to-One to `MigrationJob`
- **Attributes:** `id`, `sourceTrackId`, `targetTrackId`, `status`, `errorMessage`

## Future Planned Models (Phase 2+)
- `OAuthConnection`: Will handle specific tokens and scopes per provider.
- `TrackArtist`, `TrackCandidate`: Core abstractions for the Matching Engine.
- `MatchResult`: Structured output evaluating matching confidence.
- Advanced migration lifecycle states (`ANALYZING`, `MATCHING`, `CREATING_PLAYLIST`) replacing the simplified `IN_PROGRESS` workflow.