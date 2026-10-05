# ADR-009: Track Semantics

## Context
When modeling songs (Tracks) in a music application, we must decide if a `Track` entity represents:
1. A **Canonical Reusable Track** globally known to the system, linked via many-to-many relationships (e.g., `playlist_tracks`) to playlists.
2. A **Playlist Occurrence** — a concrete instance of a track belonging to exactly one playlist.

Since this application is a *migrator*, we are primarily concerned with processing individual users' source playlists sequentially and matching them linearly.

## Decision
The `Track` entity represents a **Playlist Occurrence**.

- It defines a `@ManyToOne` relationship to a single `Playlist`.
- It tracks the specific metadata as observed on the source playlist at the time of synchronization.

## Consequences
- If multiple users migrate a playlist containing the same song, our database will store duplicate `Track` entities.
- This is intentional. The matching engine and migration logic conceptually treat the *migration intent* as personal to the given source list.
- **Future Considerations**: In Phase 6 (Candidate Discovery) and Phase 7 (Matching Engine), we introduce `TrackCandidate` and `MatchResult` which represent purely transient resolution objects mapped per migration task. We will avoid the complexity of a global catalog of music unless a global caching layer becomes absolutely mandatory for performance.
