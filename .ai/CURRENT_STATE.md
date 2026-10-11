# Current State

Last Updated: 2026-10-11

Current Phase: Phase 5 — Track Normalization Engine
Current Subphase: Phase 5A–5E COMPLETE (Phases 5F–5H Pending)

## Completed
### Phase 5A–5E (Track Normalization Engine: Foundation, Title, Artist, Album & Duration Normalization)
- **Phase 5A (Repository Audit & Normalization Design)**:
  - Audited existing codebase and established ADR-010 (`.ai/ADR/ADR-010-track-normalization-design.md`).
  - Placed normalization in `com.spotifyyoutube.migrator.matching.normalization` within the modular monolith.
  - Implemented normalizers as stateless Spring `@Component`s with pure, deterministic functions, requiring no external dependencies, database, or network calls, fully testable as POJOs.
  - Preserved strict provider isolation: inputs are standard Java types (`String`, `List<String>`, `Long`, `Integer`), zero imports from Spotify or YouTube DTO packages.
  - Enforced source immutability: inputs are never mutated, original source metadata in `Track` or destination candidate objects is preserved untouched.
- **Phase 5B (Title Normalization — TitleNormalizer)**:
  - Implemented `TitleNormalizer` in `com.spotifyyoutube.migrator.matching.normalization`.
  - Whitespace handling: trims leading/trailing whitespace, collapses consecutive whitespace (`\s+`) including tabs and newlines to a single space.
  - Null/blank policy: returns `""` (empty string) for `null`, empty, or whitespace-only inputs.
  - Casing: deterministic case folding using `Locale.ROOT`.
  - Unicode policy: canonical composition via `Normalizer.Form.NFC`, ensuring canonical equivalence (composed vs. decomposed accents) while strictly preserving non-Latin scripts (Japanese, Cyrillic, Korean, Arabic).
  - Punctuation & separators: standardizes curly/smart apostrophes (`’`, `‘`) to `'`, quotes (`“`, `”`, `„`) to `"`, and dash variants (en-dash, em-dash, minus) to `-`.
  - Conservative promotional annotation removal: strips bracketed/parenthetical suffixes (`(Official Audio)`, `[Official Video]`, `(Official Music Video)`, `(Official Lyric Video)`, `(Official Visualizer)`) and trailing separator-delimited suffixes (`- Official Video`, `| Official Audio`) only when substantive title content remains. Preserves promotional words in legitimate titles (e.g. `Official Secrets`, `The Official Song`).
  - Meaningful version preservation: strictly preserves version variants (`(Live)`, `(Acoustic)`, `(Remix)`, `(Karaoke)`, `(Nightcore)`, `(Sped Up)`, `(Slowed)`) to prevent false identity collapse.
- **Phase 5C (Artist Normalization — ArtistNormalizer & NormalizedArtistCredit)**:
  - Implemented `ArtistNormalizer` and `NormalizedArtistCredit` record in `com.spotifyyoutube.migrator.matching.normalization`.
  - Basic normalization: Unicode NFC, `Locale.ROOT` lowercasing, smart apostrophes/quotes and dash normalization, whitespace collapsing.
  - Featured artist conventions: recognizes token-bounded `feat.`, `feat`, `ft.`, `ft`, `featuring` in parenthetical or trailing positions into `NormalizedArtistCredit(primaryArtist, featuredArtists)`.
  - Identity & collaboration preservation: preserves bands and collaborations with ampersands, commas, and plus signs (e.g., `Simon & Garfunkel`, `Earth, Wind & Fire`, `AC/DC`, `Florence + the Machine`, `Crosby, Stills, Nash & Young`) without naive splitting.
  - Word-boundary safety: prevents false positives on legitimate artist names containing similar character sequences (e.g., `Soft Cell`, `Kraftwerk`, `Leftfield`, `Afterlife`, `Future`, `Def Leppard`, `Aerosmith`, `The Feat`).
  - List normalization: `normalizeArtistList(List<String>)` normalizes discrete artist lists while strictly preserving original artist order and omitting blanks.
- **Phase 5D (Album Normalization — AlbumNormalizer)**:
  - Implemented `AlbumNormalizer` in `com.spotifyyoutube.migrator.matching.normalization`.
  - Basic normalization: Unicode NFC canonical composition, `Locale.ROOT` case folding, whitespace collapsing (`\s+` to single space), and trimming.
  - Null/blank policy: returns `""` (empty string) for `null`, empty, or whitespace-only inputs without placeholder substitutions.
  - Typography & punctuation standardization: standardizes curly/smart apostrophes to `'`, double quotes to `"`, and dash variants to `-`. Preserves colons, commas, ampersands, plus signs, brackets, currency symbols, and backslashes.
  - Meaningful album edition preservation: strictly preserves substantive album release editions and qualifiers (`Deluxe Edition`, `Expanded Edition`, `Anniversary Edition`, `Remastered`, `Remaster`, `Live`, venue names like `Live at Wembley`, `Acoustic`, `Soundtrack`, `Special Edition`, `[Explicit]`, `[Clean]`). Does NOT apply promotional stripping or delete parenthetical album annotations.
  - Determinism & immutability: stateless, deterministic, and idempotent.
- **Phase 5E (Duration Normalization — DurationNormalizer)**:
  - Implemented `DurationNormalizer` in `com.spotifyyoutube.migrator.matching.normalization`.
  - Canonical representation: normalizes heterogeneous provider formats (Spotify millisecond integers/longs and YouTube ISO-8601 strings) into canonical `java.time.Duration`.
  - Precision: preserves exact millisecond and nanosecond precision without rounding or floating-point conversions.
  - Millisecond API: `fromMilliseconds(Long)` and overloaded `fromMilliseconds(Integer)`.
  - ISO-8601 API: `fromIso8601(String)` parsing standard durations (`PT3M20S`, `PT45S`, `PT1H2M3S`, `PT0S`, `PT1H`, `PT5M`, fractional `PT3M20.5S`, `PT0.123S`).
  - Error and null contract: `null` or blank input safely returns `null` (absent duration); legitimate zero duration returns `Duration.ZERO`; negative durations, malformed ISO strings, and out-of-range arithmetic overflows safely reject with `IllegalArgumentException`. Invalid data is never silently substituted with zero or fallback values.
  - Pure & stateless: independent Spring `@Component` testable as POJO.

### Phase 4G–4H (YouTube Integration — MusicDestination & Verification)
- **Phase 4G (MusicDestination Abstraction & YouTube Adapter)**:
  - Defined provider-neutral `MusicDestination` interface in `migration.application.destination` decoupled from YouTube/Spotify SDK details.
  - Implemented domain models: `DestinationPlaylist`, `DestinationTrackCandidate`, `DestinationSearchResult`, `DestinationTrackDetails`, `CreateDestinationPlaylistRequest`, `AddTrackToDestinationPlaylistRequest`, `DestinationPlaylistItem`, `DestinationPlaylistVerification`, and `PlaylistVerificationStatus`.
  - Implemented `YouTubeMusicDestinationAdapter` implementing `MusicDestination`, binding `YouTubeSearchService` and `YouTubePlaylistService` to the provider-neutral abstraction with proper platform identifier (`Platform.YOUTUBE`).
- **Phase 4H (Destination Verification & Phase 4 Freeze)**:
  - Extended `YouTubeClient.getPlaylist(accessToken, playlistId)` targeting `/playlists?part=snippet,status&id={playlistId}` authenticated via Google OAuth Bearer token.
  - Implemented typed DTO: `YouTubePlaylistListResponseDto`.
  - Implemented `YouTubePlaylistService.getPlaylist(userId, playlistId)` retrieving playlist details or throwing `ResourceNotFoundException`.
  - Implemented `YouTubePlaylistService.verifyPlaylist(userId, playlistId)` validating destination playlist accessibility, distinguishing between `ACCESSIBLE`, `NOT_FOUND`, `INACCESSIBLE` (missing connection, expired token, 401/403), `UNCERTAIN` (transient 5xx/429/timeout), and `FAILED` (400 bad request).
  - Exposed endpoints `GET /api/youtube/playlists/{playlistId}` and `GET /api/youtube/playlists/{playlistId}/verify` in `YouTubeController`.
  - Comprehensive unit and slice testing across all layers (`YouTubeClientTest`, `YouTubePlaylistServiceTest`, `YouTubeMusicDestinationAdapterTest`, `YouTubeControllerTest`). Phase 4 is fully verified and **FROZEN**.

### Phase 4D–4F
- **Phase 4D (YouTube Video Metadata Retrieval)**:
  - Extended `YouTubeClient.getVideoMetadata(videoId)` targeting YouTube Data API v3 `/videos?part=snippet,contentDetails&id={videoId}&key={apiKey}` with resilient provider error handling.
  - Implemented typed DTOs: `YouTubeVideoListResponseDto`, `YouTubeVideoItemDto`, `YouTubeVideoSnippetDto`, `YouTubeVideoContentDetailsDto`, and `YouTubeVideoDetailsResponse`.
  - Added `YouTubeSearchService.getVideoDetails(videoId)` with parameter validation (non-blank ID) and 404 mapping when video is missing or deleted.
  - Added endpoint `GET /api/youtube/videos/{videoId}` in `YouTubeController` returning mapped video summary including ISO-8601 duration.
- **Phase 4E (YouTube Playlist Creation)**:
  - Extended `YouTubeClient.createPlaylist(accessToken, request)` targeting `/playlists?part=snippet,status` authenticated via Google OAuth Bearer token.
  - Implemented typed DTOs: `YouTubePlaylistInsertRequestDto`, `YouTubePlaylistSnippetDto`, `YouTubePlaylistStatusDto`, `YouTubePlaylistDto`, `CreateYouTubePlaylistRequest`, and `YouTubePlaylistResponse`.
  - Implemented `YouTubePlaylistService.createPlaylist(userId, request)` integrating `OAuthTokenLifecycleService` for Google token verification and auto-refresh, validating title/description bounds (max 150/5000 chars) and default privacy status (`private`).
  - Added endpoint `POST /api/youtube/playlists` in `YouTubeController` returning `201 CREATED`.
- **Phase 4F (Adding Videos to YouTube Playlists)**:
  - Extended `YouTubeClient.addVideoToPlaylist(accessToken, playlistId, videoId, position)` targeting `/playlistItems?part=snippet` authenticated via Google OAuth Bearer token.
  - Implemented typed DTOs: `YouTubePlaylistItemInsertRequestDto`, `YouTubePlaylistItemSnippetRequestDto`, `YouTubePlaylistItemDto`, `YouTubePlaylistItemSnippetResponseDto`, `AddVideoToPlaylistRequest`, and `YouTubePlaylistItemResponse`.
  - Implemented `YouTubePlaylistService.addVideoToPlaylist(userId, playlistId, request)` with token lifecycle validation, playlist/video parameter validation, and error propagation.
  - Added endpoint `POST /api/youtube/playlists/{playlistId}/videos` in `YouTubeController` returning `201 CREATED`.
- **Testing Verification**:
  - Full suite of unit and slice tests (`YouTubeClientTest`, `YouTubeMapperTest`, `YouTubeSearchServiceTest`, `YouTubePlaylistServiceTest`, `YouTubeControllerTest`) verified with 100% passing tests and 0 Checkstyle violations.

### Phase 4A–4C
- **Phase 4A**: YouTube API Feasibility & Configuration:
  - Confirmed YouTube Data API v3 (`search.list`) capabilities, quota limits (100 units/call against 10,000 default units/day), and YouTube Music non-public API boundaries.
  - Reused Phase 2 Google OAuth infrastructure for user identity; added YouTube configuration properties (`apiKey`, `baseUrl`, `connectTimeoutMs`, `readTimeoutMs`, `maxResultsLimit`, `defaultMaxResults`) in `application.yml` and `application-test.yml`.
  - Added environment variable placeholders in `.env.example`.
- **Phase 4B**: YouTube Client Foundation:
  - Implemented `YouTubeClient` using Spring `RestClient`, strict provider DTO isolation (`YouTubeSearchListResponseDto`, `YouTubeSearchResultDto`, `YouTubeResourceIdDto`, `YouTubeSearchSnippetDto`, `YouTubeThumbnailDto`, `YouTubePageInfoDto`).
  - Implemented comprehensive error mapping: 400 Bad Request, 401 Unauthorized (invalid key), 403 Forbidden (quota exceeded), 404 Not Found, 429 Rate Limit (with `Retry-After`), and 5xx errors mapped cleanly to `ExternalProviderException` without leaking sensitive upstream details.
- **Phase 4C**: YouTube Video Search:
  - Implemented `YouTubeSearchService` with query validation (non-null, trimmed, max 500 characters) and pagination constraints (`maxResults` 1-50).
  - Implemented `YouTubeMapper` transforming provider DTOs into clean application-owned responses (`YouTubeVideoSearchResponse`, `YouTubeVideoSummaryResponse`) with high/medium/default thumbnail fallbacks and filtering of non-video entities.
  - Implemented `YouTubeController` exposing authenticated `GET /api/youtube/videos/search` endpoint.
  - Registered `IllegalArgumentException` in `GlobalExceptionHandler` mapping to standard `400 BAD_REQUEST` (`INVALID_REQUEST`).

### Phase 3A–3H
- **Phase 3A-3C**: Spotify API Foundation, Identity retrieval via Phase 2 tokens, and Paged Playlist Discovery implementation.
- **Phase 3D**: Playlist Metadata Retrieval implemented targeting `/playlists/{playlistId}` via `SpotifyApiClient` retaining canonical models.
- **Phase 3E**: Exhaustive page aggregation for `/playlists/{playlistId}/tracks`, securely ignoring local/null objects without crashing.
- **Phase 3F**: Safe abstraction boundaries built into `SpotifyMapper` which translates Spotify provider DTOs firmly into standard `.domain.Playlist` / `.domain.Track` models, before flushing out as `PlaylistResponse`/`TrackResponse`.
- **Phase 3G**: Application-level error handling implemented covering Spotify responses (400, 401, 403, 404, 429, 5xx), mapping timeouts, missing connections (via `InvalidStateException`), parsing bugs (via `RestClientException`), and relaying `Retry-After` headers faithfully without leaking provider structure payload internals.
- **Phase 3H**: Full testing verification applied. Phase 3 FROZEN.

### Phase 2A–2H
- **Phase 2A-2C**: Application Identity, Authentication, and OAuth Infrastructure.
- **Phase 2D-2F**: Provider Adapters (Spotify/Google) and encrypted Token Lifecycle logic.
- **Phase 2G-2H**: Complete Security Hardening, checking CSRF, State Atomicity, token leakage, and passing unit tests suite. Phase 2 FROZEN.

### Phase 0 / 1A / 1B / 1C
- Domain entities, invariants, and Repositories built. ADRs written.

### Phase 1D / 1E / 1F
- Application Service Boundaries conceptualized and implemented using explicit Service/Implementation pairings mapping across `Identity`, `Playlist`, and `Migration` features.
- Dedicated DTO records (`UserResponse`, `PlaylistResponse`, `TrackResponse`, `CreateMigrationRequest`, `MigrationJobResponse`, `MigrationTaskResponse`) mapped to strictly divorce Controller models from Domain realities. 
- Validation bounds handled via Jakarta validation limits inside DTOs (`@NotNull`, `@NotBlank`) resolving explicitly to `400 Bad Request` wrapped seamlessly by an augmented `GlobalExceptionHandler`. State constraints (`ResourceNotFound`, `InvalidState`, `Conflict`) seamlessly structured to render `404` or `409` HTTP responses ensuring stable API conventions.
- Explicit Mappers manually configured (no MapStruct) tracking strict one-to-one Domain-to-DTO conventions.

## Current Issues
- Environment configuration: `Testcontainers` Docker integration passes when Docker Desktop is available, but currently the local Docker engine is not running, blocking database containers. (Docker daemon is globally unavailable in this specific execution environment).

## Not Yet Implemented
- Phase 5F–5H: Normalization orchestration service, variant-token classification, and Phase 5 verification/freeze.
- Candidate Discovery (Phase 6).
- Matching Engine & Benchmarking (Phase 7 & Phase 8).
- Migration Orchestration Engine & Progress Tracking (Phase 9 & Phase 12).

## Current Verification
- Backend Compilation: `.\mvnw.cmd test-compile` — **PASS**
- Checkstyle: `.\mvnw.cmd checkstyle:check` — **PASS** (0 violations)
- Focused Normalization Test Suite (`TitleNormalizerTest`, `ArtistNormalizerTest`, `AlbumNormalizerTest`, `DurationNormalizerTest`): **PASS** (148 tests passing across 24 nested test categories: 28 title tests, 33 artist tests, 32 album tests, 55 duration tests)
- Focused YouTube Test Suite (`YouTubeClientTest`, `YouTubeMapperTest`, `YouTubeSearchServiceTest`, `YouTubePlaylistServiceTest`, `YouTubeMusicDestinationAdapterTest`, `YouTubeControllerTest`): **PASS** (100% passing)
- Regression Unit/Slice Test Suite (`GlobalExceptionHandlerTest`, `SpotifyApiClientTest`, `SpotifyControllerTest`, `SpotifyPlaylistServiceTest`, `GoogleProviderAdapterTest`, etc.): **PASS**
- Repository/Flyway Integration Tests (`PlaylistRepositoryTest`, `UserRepositoryTest`, `MigrationJobRepositoryTest`): **BLOCKED** — Docker daemon unavailable in environment

## Phase Freeze Status
- **Phase 2 (Auth & Identity)**: FROZEN
- **Phase 3 (Spotify Integration)**: FROZEN
- **Phase 4 (YouTube Integration)**: COMPLETE / FROZEN
- **Phase 5 (Track Normalization Engine)**: Phase 5A–5E COMPLETE; Phase 5F–5H PENDING

## Next Recommended Work
1. Phase 5F: `NormalizationOrchestrator` (composed normalization pipeline and metadata tokenization).
2. Phase 5G: Variant-Token Detection & Classification.