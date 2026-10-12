# Migration Roadmap

## Phase Boundary Rule
Never implement functionality belonging to a future phase unless explicitly requested.
Current phase must be completed and verified before progressing.
When a future requirement affects current architecture, document the architectural requirement but do not implement the future feature prematurely.

## Current Progress

### **Phase 0 — Architecture & Project Foundation** [COMPLETE]
- Directory structures, build systems, framework boilerplate, and ADRs established.

### **Phase 1 — Domain Model & API Contracts** [IMPLEMENTATION COMPLETE BUT RUNTIME VERIFICATION BLOCKED]
- **Phase 1A/B/C**: [COMPLETE] Core Domain Model, Invariants, and Repositories built.
- **Phase 1D**: [COMPLETE] Application Service Contracts
- **Phase 1E/1F**: [COMPLETE] API DTOs and Validation/Error Contracts
- **Phase 1G**: [IMPLEMENTATION COMPLETE / BLOCKED] Persistence & Migration Verification statically verified; runtime testing blocked by Docker environment limits.
- **Phase 1H**: [COMPLETE] Domain / API Testing via Mockito and WebMvcTest.
- **Phase 1I/1J**: [COMPLETE / FROZEN] Architecture Audited and Code/Functional Contracts Frozen.

### **Phase 2 — Authentication & Identity** [IMPLEMENTATION COMPLETE BUT RUNTIME VERIFICATION BLOCKED]
- **Phase 2A-2C**: [COMPLETE] Application Identity, Authentication, and OAuth Infrastructure.
- **Phase 2D-2F**: [COMPLETE] Provider Adapters (Spotify/Google) and encrypted Token Lifecycle logic.
- **Phase 2G-2H**: [COMPLETE / FROZEN] Security hardening for sessions, state consumption race conditions, IDOR, and full unit test execution.

### **Phase 3 — Spotify Integration** [COMPLETE / FROZEN]
- **Phase 3A-3C**: [COMPLETE] Spotify API Foundation, Identity retrieval, and Paged Playlist Discovery implementation.
- **Phase 3D-3F**: [COMPLETE] Playlist Metadata Retrieval, Complete Paginated Track Retrieval, and safe Mapping boundaries enforcing canonical models.
- **Phase 3G-3H**: [COMPLETE] Spotify API error/rate-limit handling expansion and full phase QA audit.

### **Phase 4 — YouTube Integration** [COMPLETE / FROZEN]
- **Phase 4A**: [COMPLETE] YouTube API Feasibility, Quota Verification, and Configuration Properties.
- **Phase 4B**: [COMPLETE] YouTube API Client Foundation (`YouTubeClient`) with typed provider DTOs and resilient HTTP/error handling.
- **Phase 4C**: [COMPLETE] YouTube Video Search (`YouTubeSearchService`, `YouTubeMapper`, `YouTubeController` `/api/youtube/videos/search`).
- **Phase 4D**: [COMPLETE] YouTube Video Metadata Retrieval (`YouTubeClient.getVideoMetadata`, `YouTubeSearchService.getVideoDetails`, `/api/youtube/videos/{videoId}`).
- **Phase 4E**: [COMPLETE] YouTube Playlist Creation (`YouTubeClient.createPlaylist`, `YouTubePlaylistService.createPlaylist`, `/api/youtube/playlists`).
- **Phase 4F**: [COMPLETE] Adding Videos to Playlists (`YouTubeClient.addVideoToPlaylist`, `YouTubePlaylistService.addVideoToPlaylist`, `/api/youtube/playlists/{playlistId}/videos`).
- **Phase 4G**: [COMPLETE] MusicDestination Abstraction & YouTube Adapter (`MusicDestination`, domain models, `YouTubeMusicDestinationAdapter`).
- **Phase 4H**: [COMPLETE / FROZEN] Destination Verification, Verification Endpoints (`/api/youtube/playlists/{playlistId}`, `/api/youtube/playlists/{playlistId}/verify`), and Phase 4 Freeze.
### **Phase 5 — Track Normalization Engine** [COMPLETE / FROZEN]
- **Phase 5A**: [COMPLETE] Repository Audit & Normalization Architecture (ADR-010, pure stateless components, provider isolation).
- **Phase 5B**: [COMPLETE] Title Normalization (`TitleNormalizer`: Unicode NFC, Locale.ROOT, whitespace/quotes/dashes standardization, conservative promotional suffix removal, version preservation).
- **Phase 5C**: [COMPLETE] Artist Normalization (`ArtistNormalizer`, `NormalizedArtistCredit`: token-bounded featured extraction, band identity preservation, list normalization).
- **Phase 5D**: [COMPLETE] Album Normalization (`AlbumNormalizer`: Unicode NFC, Locale.ROOT, whitespace/quotes/dashes standardization, preservation of Deluxe/Remastered/Live/Soundtrack/Anniversary editions).
- **Phase 5E**: [COMPLETE] Duration Normalization (`DurationNormalizer`: canonical `java.time.Duration`, exact millisecond/nanosecond precision, Spotify ms & YouTube ISO-8601 parsing, strict error contract).
- **Phase 5F**: [COMPLETE] MetadataTokenizer & Variant Detection (`MetadataTokenizer`: token extraction, `TokenizedTitle`, `VariantType`, `MediaFormat`, `TokenCategory`, `RemasterDetails`, `LivePerformanceDetails`, unknown annotation preservation).
- **Phase 5G**: [COMPLETE] Normalization Pipeline Orchestration (`NormalizationOrchestrator`, `RawTrackMetadata`, `NormalizedTrackMetadata`, pipeline composition, immutability, determinism).
- **Phase 5H**: [COMPLETE / FROZEN] Phase 5 Verification & Freeze (cross-phase audit, 227 normalization tests, 454 regression tests, zero Checkstyle violations, documentation reconciled).
### **Phase 6 — YouTube Candidate Discovery** [ACTIVE]
- **Phase 6B**: [COMPLETE] Search Query Generation (`DefaultSearchQueryGenerator`, deterministic query formulation from `NormalizedTrackMetadata`, tiered strategies, priority ranking, quota bounding, deduplication).
- **Phase 6C**: [COMPLETE] Provider Search Orchestration (`DefaultCandidateDiscoveryService`, quota-aware multi-query dispatching via `MusicDestination`, bounded candidate collection, early termination, partial failure resilience).
- **Phase 6D**: Candidate Processing & Bounding (Validation, ID deduplication, encounter order preservation, collection limits). [NEXT ACTIVE SUBPHASE]
- **Phase 6E**: Discovery Pipeline Integration & Verification (End-to-end orchestration, partial failure handling, contract tests).
- Phase 7 — Matching Engine
- Phase 8 — Matching Evaluation & Benchmark
- Phase 9 — Migration Engine
- Phase 10 — Review & Correction
- Phase 11 — Playlist Creation
- Phase 12 — Async Migration & Progress
- Phase 13 — Reliability Engineering
- Phase 14 — Migration History
- Phase 15 — Frontend Product Layer
- Phase 16 — Security Hardening
- Phase 17 — Testing
- Phase 18 — Observability
- Phase 19 — Deployment