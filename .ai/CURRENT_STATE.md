# Current State

Last Updated: 2026-10-12

Current Phase: Phase 6 — Candidate Discovery
Current Subphase: Phase 6C COMPLETE (YouTube Search Orchestration Implemented & Verified)

## Completed
### Phase 6C (YouTube Search Orchestration)
- **Concrete Orchestration Service**: `DefaultCandidateDiscoveryService` implements `CandidateDiscoveryService` in `com.spotifyyoutube.migrator.matching.discovery`.
- **Bounded Multi-Query Execution**:
  - Consumes Phase 5 `NormalizedTrackMetadata` and Phase 6A `DiscoveryOptions`.
  - Dispatches generated queries via provider-neutral `MusicDestination.searchTracks()`.
  - Strictly enforces `options.maxQueries()` limit (1–5) and `options.resultsPerQuery()`.
  - Early termination: dynamically skips downstream queries once `options.maxCandidates()` quota is satisfied, conserving YouTube Data API v3 quota (100 units/search).
  - Truncates candidate collection to `options.maxCandidates()` even if an individual query returns more candidates.
- **Deduplication & Provenance**:
  - Filters malformed candidates (null or blank IDs).
  - Eliminates duplicate candidate IDs within a single query response.
  - Preserves provenance across queries in `discoveredCandidates()` while deduplicating in encounter order in `candidates()`.
- **Error Semantics & Resilience**:
  - Handles 10 standard execution and error scenarios:
    1. `SUCCESS`: All queries succeed and produce candidates.
    2. `EMPTY`: All queries succeed legitimately with zero candidates found (distinguished from provider outages).
    3. `PARTIAL_FAILURE`: Some queries fail, but candidates discovered by earlier/successful queries are preserved.
    4. `FAILED`: Some queries fail while successful queries return no candidates (not masked as empty).
    5. `FAILED`: All queries fail due to provider errors.
    6. Malformed/null responses handled gracefully without throwing.
    7. Quota bounding and early termination strictly enforced.
    8. Deterministic query execution order and encounter-order diagnostics.
    9. Authentication, quota, and transient exceptions recorded in `SearchQueryExecution` diagnostics.
    10. Query failures never erase earlier successful results.
- **Spring Integration**: Annotated with `@Service`, constructor-injects `SearchQueryGenerator` and `MusicDestination` (`YouTubeMusicDestinationAdapter`).

### Phase 6B (Deterministic Search Query Generation)
- **Concrete Implementation**: `DefaultSearchQueryGenerator` implements `SearchQueryGenerator` in `com.spotifyyoutube.migrator.matching.discovery`.
- **Pure & Deterministic**: Stateless, thread-safe, pure function execution operating exclusively on Phase 5 `NormalizedTrackMetadata` and Phase 6A `DiscoveryOptions`. Zero external dependencies or I/O.
- **Canonical Strategy Prioritization**:
  1. `PRIMARY_TITLE_ARTIST`: Primary artist and clean title (e.g., `daft punk - get lucky`).
  2. `TITLE_ARTIST_FEATURED`: Primary artist, aggregated featured artists, and clean title (e.g., `dua lipa feat. dababy - levitating`).
  3. `TITLE_ARTIST_ALBUM`: Primary artist, clean title, and album qualifier (e.g., `daft punk - get lucky random access memories`), suppressed when album is identical to title.
  4. `TITLE_ARTIST_VERSION`: Primary artist, clean title, and structured version details extracted from `TokenizedTitle`, `RemasterDetails`, `LivePerformanceDetails`, and `VariantType` (e.g., `eagles - hotel california live at wembley`).
  5. `RAW_TITLE_AND_ARTIST`: High-fidelity raw title fallback retaining stripped promotional markers (e.g., `The Weeknd - Blinding Lights (Official Video)`) or as safety fallback when clean queries cannot be formed.
  6. `CLEAN_TITLE_ONLY`: Clean title fallback for artist-less tracks or compilation/soundtrack tracks (e.g., `Various Artists`).
- **Quota & Boundary Enforcement**:
  - Strictly caps generated queries at `options.maxQueries()` (1–5).
  - Filters strategies by `options.enabledStrategies()`.
  - Enforces string bounds: 1 to 500 characters, whitespace-collapsed, non-blank.
  - Returns unmodifiable `List<SearchQuery>` with deterministic 1-based sequential priority rankings.
- **Deduplication Policy**: Case-insensitive and whitespace-collapsed normalization prevents duplicate or semantically redundant queries from consuming search quotas.

### Phase 6A (Candidate Discovery Architecture & Contracts)
- **Architecture & ADR-011**: Defined candidate discovery architecture in `.ai/ADR/ADR-011-candidate-discovery-architecture.md`, addressing YouTube Data API v3 quota economics (100 units/search vs 1 unit/video, 10,000 units/day project quota).
- **Public Contracts & Models**: Established in `com.spotifyyoutube.migrator.matching.discovery`:
  - `CandidateDiscoveryRequest`: Immutable input contract taking `NormalizedTrackMetadata` (from frozen Phase 5) and `DiscoveryOptions`.
  - `DiscoveryOptions`: Configurable quota bounds enforcing limits on `maxCandidates` (1–50, default 20), `maxQueries` (1–5, default 2), `resultsPerQuery` (1–50, default 10), and `enabledStrategies`.
  - `SearchQueryStrategy`: Categorized query generation enum (`PRIMARY_TITLE_ARTIST`, `TITLE_ARTIST_FEATURED`, `TITLE_ARTIST_ALBUM`, `TITLE_ARTIST_VERSION`, `RAW_TITLE_AND_ARTIST`, `CLEAN_TITLE_ONLY`).
  - `SearchQuery`: Immutable query value object validating non-blank strings and <= 500 characters.
  - `SearchQueryGenerator`: Interface contract for deterministic multi-query generation.
  - `DiscoveredCandidate`: Pairs candidate with discovering `SearchQuery` to retain full search provenance.
  - `SearchQueryExecution`: Captures execution telemetry, candidate yield, and error diagnostics per query.
  - `CandidateDiscoveryStatus`: Explicit outcome enum distinguishing `SUCCESS`, `EMPTY` (legitimate zero-match), `PARTIAL_FAILURE` (secondary query error with preserved candidates), and `FAILED`.
  - `CandidateDiscoveryResult`: Immutable, structured outcome with encounter-order candidate ID deduplication, candidate bounding, and status helpers.
  - `CandidateDiscoveryService`: Core service contract for orchestrating discovery.
- **Provider Neutrality & Model Reuse**: Reuses provider-neutral `MusicDestination` and `DestinationTrackCandidate` from `com.spotifyyoutube.migrator.migration.application.destination.model` without leaking YouTube DTOs or duplicating models.
- **Phase Boundary Discipline**: Zero scoring, ranking, confidence estimation, or playlist mutations implemented (reserved for Phase 7+).

### Phase 5A–5H (Track Normalization Engine: Foundation, Title, Artist, Album, Duration, MetadataTokenizer, Orchestration & Freeze)
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
- **Phase 5F (MetadataTokenizer & Variant Detection)**:
  - Implemented `MetadataTokenizer` and domain models (`TokenizedTitle`, `MetadataToken`, `TokenCategory`, `VariantType`, `MediaFormat`, `RemasterDetails`, `LivePerformanceDetails`) in `com.spotifyyoutube.migrator.matching.normalization`.
  - Stateless parsing of track metadata strings into structured, immutable result records.
  - High-level categorization: `OFFICIAL_CONTENT`, `MEDIA_FORMAT`, `REMASTER`, `EDIT_OR_MIX`, `PERFORMANCE`, `DERIVATIVE_VERSION`, `FEATURED_ARTIST`, `UNKNOWN_ANNOTATION`.
  - Variant classification: 19 distinct variants (`REMASTER`, `LIVE`, `ACOUSTIC`, `UNPLUGGED`, `RADIO_EDIT`, `EXTENDED_MIX`, `ORIGINAL_MIX`, `CLUB_MIX`, `INSTRUMENTAL`, `DEMO`, `STUDIO`, `REMIX`, `COVER`, `KARAOKE`, `NIGHTCORE`, `SPED_UP`, `SLOWED`, `SLOWED_REVERB`, `REVERB`) preserving encounter order via unmodifiable linked sets.
  - Media format classification: `AUDIO`, `VIDEO`, `MUSIC_VIDEO`, `LYRIC_VIDEO`, `VISUALIZER`.
  - Context extraction: `RemasterDetails` (release/remaster year) and `LivePerformanceDetails` (venue/event identification).
  - Conservative preservation: unclassified bracketed annotations are retained in `unknownAnnotations` and kept within `cleanTitle` to prevent information loss.
  - Featured artist routing: parenthetical and unbracketed guest credits (`feat. DaBaby`, `feat. Artist 1 & Artist 2`) extracted into `featuredArtists` with delimiter splitting and stripped from title without misclassification.
  - Multi-annotation parsing: correctly decomposes compound bracketed qualifiers separated by comma, slash, semicolon, or pipe (e.g. `[Live at Wembley, Remastered 2024]`).
- **Phase 5G (Normalization Pipeline Orchestration — NormalizationOrchestrator)**:
  - Implemented `NormalizationOrchestrator` in `com.spotifyyoutube.migrator.matching.normalization` as the single unified entry point for track metadata normalization.
  - Coordinates all specialized normalizers (`TitleNormalizer`, `ArtistNormalizer`, `AlbumNormalizer`, `DurationNormalizer`, `MetadataTokenizer`) without duplicating normalization algorithms or regex patterns.
  - Introduced immutable containers: `RawTrackMetadata` (builder pattern, polymorphic provider duration inputs) and `NormalizedTrackMetadata` (structured result aggregating clean title, normalized artist credit, list normalization, consolidated featured artists, canonical duration, and tokenized variants).
  - Robust error semantics: propagates `IllegalArgumentException` on invalid/negative durations and conflicting multi-format durations; null/blank raw fields yield empty strings/null duration without throw or synthetic text.
  - Consolidated multi-source featured artists: merges featured artists from structured credit, discrete artist list, and title tokenization in deterministic encounter order, deduplicating and excluding the primary artist.
  - Clear syntactic cleanliness contracts: `cleanTitle()` strips variants and promotional markers; `isClean()` indicates absence of variants, media formats, or promotional suffixes without conflating with explicit-content parental advisories.
  - Thread-safe, stateless Spring `@Component` with zero side effects or caching.
- **Phase 5H (Final Subsystem Verification & Normalization Freeze)**:
  - Conducted full cross-phase correctness audit of all normalization components (`TitleNormalizer`, `ArtistNormalizer`, `AlbumNormalizer`, `DurationNormalizer`, `MetadataTokenizer`, `NormalizationOrchestrator`).
  - Verified regression safety and stability across all preceding phases (common, domain, identity, spotify, youtube, migration, playlist).
  - Executed 227 normalization tests and 454 total non-repository unit tests with 100% pass rate.
  - Verified 0 Checkstyle violations and preserved strict provider-neutral isolation.
  - Phase 5 is fully verified and **FROZEN**.

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
- Candidate Discovery Execution Subphases (Phase 6D: Candidate Bounding & Processing, Phase 6E: Pipeline Integration).
- Matching Engine & Benchmarking (Phase 7 & Phase 8).
- Migration Orchestration Engine & Progress Tracking (Phase 9 & Phase 12).

## Current Verification
- Backend Compilation: `.\mvnw.cmd test-compile` — **PASS**
- Checkstyle: `.\mvnw.cmd checkstyle:check` — **PASS** (0 violations)
- Focused Candidate Discovery Suite (`DefaultCandidateDiscoveryServiceTest`, `DefaultSearchQueryGeneratorTest`, `SearchQueryTest`, `DiscoveryOptionsTest`, `CandidateDiscoveryRequestTest`, `DiscoveredCandidateTest`, `SearchQueryExecutionTest`, `CandidateDiscoveryResultTest`): **PASS** (101 tests passing: 25 orchestration service tests, 25 query generator tests [29 executions], 10 search query tests, 19 discovery options tests, 4 request tests, 3 candidate tests, 5 query execution tests, 7 result tests with deduplication/immutability validation)
- Focused Normalization Test Suite (`TitleNormalizerTest`, `ArtistNormalizerTest`, `AlbumNormalizerTest`, `DurationNormalizerTest`, `MetadataTokenizerTest`, `NormalizationOrchestratorTest`): **PASS** (227 tests passing)
- Full Non-Repository Unit Test Suite: **PASS** (555 tests across common, domain, identity, spotify, youtube, migration, playlist, matching - 100% passing)
- Focused YouTube Test Suite (`YouTubeClientTest`, `YouTubeMapperTest`, `YouTubeSearchServiceTest`, `YouTubePlaylistServiceTest`, `YouTubeMusicDestinationAdapterTest`, `YouTubeControllerTest`): **PASS** (100% passing)
- Repository/Flyway Integration Tests (`PlaylistRepositoryTest`, `UserRepositoryTest`, `MigrationJobRepositoryTest`): **BLOCKED** — Docker daemon unavailable in environment

## Phase Freeze Status
- **Phase 2 (Auth & Identity)**: FROZEN
- **Phase 3 (Spotify Integration)**: FROZEN
- **Phase 4 (YouTube Integration)**: COMPLETE / FROZEN
- **Phase 5 (Track Normalization Engine)**: COMPLETE / FROZEN
- **Phase 6A (Candidate Discovery Architecture & Contracts)**: COMPLETE
- **Phase 6B (Deterministic Search Query Generation)**: COMPLETE
- **Phase 6C (YouTube Search Orchestration)**: COMPLETE

## Next Recommended Work
1. Phase 6D: Candidate Processing & Bounding (Validation, ID deduplication, encounter order preservation, collection limits).