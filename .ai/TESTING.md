# Testing Strategy

## Layers of Verification

1. **Unit Testing**: Tests domain objects, mappers, and pure business logic independently. (e.g. `DomainTest`, `SpotifyMapperTest`).
2. **Service Layer Testing**: Tests orchestration logic with mocked dependencies. (e.g. `SpotifyPlaylistServiceTest`, `UserServiceImplTest`).
3. **API / WebMvc Testing**: Validates controller endpoints, serialization, validation, and security intercepts via `MockMvc`. (e.g. `OAuthControllerTest`, `GlobalExceptionHandlerTest`).
4. **Infrastructure Integration Tests**: Validates interactions with external APIs using MockRestServiceServer. (e.g. `SpotifyApiClientTest`, `GoogleProviderAdapterTest`).
5. **Repository Integration Tests**: Validates JPA projections and queries against an actual database using `Testcontainers` (PostgreSQL). (e.g., `UserRepositoryTest`, `PlaylistRepositoryTest`).

## YouTube Testing Scope (Phase 4A-4C)
Implemented and verified:
- `YouTubeClientTest`: MockRestServiceServer-based tests validating URL building, parameter encoding, page tokens, timeout handling, and comprehensive error mapping (400, 401, 403 quota, 404, 429 rate limit with Retry-After, 500, parsing exceptions).
- `YouTubeMapperTest`: Isolated mapping unit tests testing provider DTO to application DTO transformations, thumbnail fallback priority, null entity handling, and page metadata extraction.
- `YouTubeSearchServiceTest`: Service layer unit tests with mocked client/mapper testing query validation (non-blank, max length 500), pagination bounding (1-50 limit), whitespace trimming, and exception propagation.
- `YouTubeControllerTest`: WebMvcTest validating authenticated search endpoint access, unauthenticated rejection (403/401), missing parameter handling (400), and exception mapping via GlobalExceptionHandler.

### Track Normalization Testing Scope (Phase 5A–5H Complete & Frozen)
Implemented and verified (227 total normalization tests across 6 suites, 454 total non-repository unit tests across all modules):
- `TitleNormalizerTest`: 30 tests across 7 nested categories:
  - Basic casing & whitespace: lowercase normalization (`Locale.ROOT`), trimming, collapsing multi-space/tabs/newlines.
  - Null & blank handling: returns empty string for `null`, `""`, and whitespace-only strings.
  - Punctuation & separators: smart/curly apostrophe normalization (`’`, `‘` -> `'`), double quote normalization (`“`, `”`, `„` -> `"`), dash variants (en-dash, em-dash, minus -> `-`).
  - Unicode: NFC canonical composition (decomposed vs. precomposed accents equivalence) and preservation of non-Latin scripts (Japanese, Cyrillic, Korean, Arabic).
  - Promotional annotations: conservative removal of `(Official Audio)`, `[Official Video]`, `(Official Music Video)`, `(Official Lyric Video)`, `(Official Visualizer)`, `- Official Video`, and `| Official Audio` without corrupting legitimate titles (`Official Secrets`, `The Official Song`) or blanking titles composed only of promotional words without brackets.
  - Meaningful versions: strictly preserves `(Live)`, `(Acoustic)`, `(Remix)`, `(Karaoke Version)`, `(Nightcore)`, `(Sped Up)`, and `(Slowed)`.
  - Determinism & immutability: idempotent (`normalize(normalize(x)) == normalize(x)`) and non-mutating.
- `ArtistNormalizerTest`: 31 tests across 6 nested categories:
  - Basic casing, whitespace, and tabs/newlines.
  - Null, blank, and empty collection handling.
  - Unicode NFC canonical composition and preservation of non-Latin alphabets and Latin diacritics (`Beyoncé`, `Björk`, `Sigur Rós`).
  - Featured artist conventions: token-bounded `feat.`, `ft.`, `featuring` (including casing variations like `FEAT.`, `Ft.`, `FEATURING`), parenthetical/bracketed markers (`(feat. Guest)`, `[ft. Guest]`), and multi-guest parsing (`Guest 1, Guest 2 & Guest 3`).
  - Token-boundary safety: prevents false matches on legitimate artist names (`Soft Cell`, `Kraftwerk`, `Leftfield`, `Afterlife`, `Future`, `Def Leppard`, `Aerosmith`, `The Feat`).
  - Identity preservation: preserves multi-name bands with ampersands, commas, and plus signs (`Simon & Garfunkel`, `Earth, Wind & Fire`, `AC/DC`, `Florence + the Machine`, `Crosby, Stills, Nash & Young`).
  - Discrete list normalization: `normalizeArtistList` preserves original list ordering and purges blanks.
  - Determinism & immutability: idempotent and non-mutating.
- `AlbumNormalizerTest`: 32 tests across 6 nested categories:
  - Null & blank handling: returns empty string for `null`, `""`, and whitespace-only strings without placeholders.
  - Basic casing & whitespace: lowercase normalization (`Locale.ROOT`), trimming, collapsing consecutive whitespace, tabs, and newlines.
  - Unicode & non-Latin scripts: NFC canonical composition (combining characters vs precomposed accented characters equivalence) and preservation of Japanese, Cyrillic, Greek, Korean scripts.
  - Typography & punctuation standardization: standardization of smart/curly apostrophes, double quotes, and dash variants to ASCII while preserving internal punctuation, colons, ampersands, plus signs, brackets, currency symbols, and backslashes.
  - Meaningful album edition preservation: verifies that `Deluxe Edition`, `Remastered`, `Expanded Edition`, `Anniversary Edition`, `Live at Wembley`, `Soundtrack`, `Special Edition`, `[Explicit]`, etc., are strictly retained and never stripped.
  - Determinism & immutability: idempotent and pure function execution.
- `DurationNormalizerTest`: 47 tests across 5 nested categories:
  - Millisecond conversion: whole-second conversions (200,000 ms -> 200s), fractional seconds (200,500 ms -> 200s 500ms), 1 ms, zero milliseconds (0L -> Duration.ZERO), null inputs, `Integer` overload support, large durations (100h), extreme numeric values (`Long.MAX_VALUE`), and negative value rejection (`IllegalArgumentException`).
  - ISO-8601 parsing: standard (`PT3M20S`), seconds-only (`PT45S`), compound (`PT1H2M3S`), zero variants (`PT0S`, `PT0M`, `PT0H`), hours-only (`PT1H`), minutes-only (`PT5M`), fractional seconds (`PT3M20.5S`, `PT0.123S`), whitespace trimming, null/blank handling, malformed rejection, negative duration rejection (`-PT3M`, `PT-3M`), and extreme arithmetic overflow rejection (`IllegalArgumentException`).
  - Consistency across representations: verifies identical `Duration` equality between equivalent milliseconds and ISO-8601 durations (e.g. 200,000 ms == `PT3M20S`, 45,000 ms == `PT45S`, 3,723,000 ms == `PT1H2M3S`, 0L == `PT0S`, 200,500 ms == `PT3M20.5S`, 123L == `PT0.123S`).
  - Determinism & purity: idempotent, stateless execution with no retained state across invocations.
  - Error contract: verifies invalid inputs never silently produce `Duration.ZERO` or fallback values, and legitimate zero durations are strictly distinct from absent `null` durations.
- `MetadataTokenizerTest`: 61 tests across 7 nested categories:
  - Official-content detection: verifies `Official Audio`, `Official Video`, `Official Music Video`, `Official Lyric Video`, `Lyric Video`, `Visualizer`, case-insensitivity, whitespace variations, and non-misclassification of legitimate titles containing words like "Official".
  - Remaster detection: verifies `Remastered`, trailing year (`Remastered 2014`), leading year (`2011 Remaster`), version phrasing (`2024 Remastered Version`), digital/anniversary remasters, and non-extraction of unrelated numeric title years.
  - Edit and mix detection: distinguishes and classifies `Radio Edit`, `Extended Mix`, `Original Mix`, `Club Mix`, `Instrumental`, and `Demo Version`.
  - Recording and performance detection: verifies `Live`, `Live Version`, `Live at Wembley` (capturing venue context), `Acoustic`, `Unplugged` / `MTV Unplugged`, `Studio Version`, and safety against false matches in titles starting with "Live".
  - Alternate-version detection: verifies `Remix` (including named remixers), `Cover`, `Karaoke`, `Nightcore`, `Sped Up`, `Slowed`, `Slowed + Reverb`, and `Reverb`.
  - Preservation and ambiguity: verifies preservation of unknown parenthetical annotations in `unknownAnnotations` and `cleanTitle`, compound comma-separated annotations, conflicting variants, multi-artist featured credits with delimiters, unbracketed trailing featured credits, empty/delimiter brackets, and featured artist credits routing to `featuredArtists` without variant confusion.
  - Input handling and determinism: verifies null, blank, whitespace-only strings, plain titles without markers, duplicate marker deduplication, non-Latin Unicode support, and deterministic repeatability.
- `NormalizationOrchestratorTest`: 26 tests across 5 nested categories:
  - Component reuse and delegation: verifies orchestrator delegates title normalization to `TitleNormalizer`, artist credit to `ArtistNormalizer`, album normalization to `AlbumNormalizer`, duration to `DurationNormalizer`, and tokenization to `MetadataTokenizer`.
  - Full pipeline integration: verifies end-to-end normalization of complex real-world tracks across standard pop, featured collaborations, live concert recordings, remixes, foreign non-Latin scripts, multi-source featured-artist aggregation in encounter order without duplication, `isClean()` indicator accuracy, and verification that `isClean()` distinguishes syntactic recording cleanliness from explicit content advisories.
  - Null and optional handling: verifies graceful handling of null raw track metadata (throwing `IllegalArgumentException`), null/empty fields (returning empty strings/null duration without throw or synthetic text), and single-artist raw inputs without secondary lists.
  - Error semantics and validation: verifies rejection of negative durations, malformed ISO-8601 durations, conflicting duration representations (e.g. milliseconds vs ISO-8601 strings mismatch), preservation of underlying normalizer exception contracts, and acceptance of matching multi-format duration inputs.
  - Immutability and determinism: verifies unmodifiable lists and sets in `NormalizedTrackMetadata`, identical repeated outputs, and zero mutation of raw input objects.

### Candidate Discovery Testing Scope (Phase 6A, 6B, 6C & 6D)
Implemented and verified (127 total candidate discovery and mapping tests, 577+ total non-repository unit tests across all modules):
- `DestinationTrackCandidateTest`: 22 test executions:
  - 6-arg constructor: creates candidate with `null` duration, preserves all metadata fields.
  - 7-arg constructor with `Duration`: accurately preserves non-null duration, handles `null` duration safely, preserves `Duration.ZERO`, rejects negative durations with `IllegalArgumentException`.
  - ISO-8601 string parsing constructor: accurately parses standard duration formats (`PT3M45S`, `PT1H2M3S`, `PT45S`, trimmed strings), preserves `PT0S` as `Duration.ZERO`, treats blank and null strings as `null` (not zero), and gracefully handles malformed strings (`invalid`, `PT`, `abc`, `-PT5M`) by returning `null` without throwing.
  - `fromDetails(DestinationTrackDetails)`: converts details to candidate, parses duration string safely, handles null/malformed durations, and returns `null` on null input.
  - Metadata resilience: accepts null optional metadata (title, channel, description, etc.) safely.
  - Value semantics: verifies record equality and `hashCode` consistency.
- `YouTubeMusicDestinationAdapterTest`: 12 test methods:
  - Verifies zero additional API calls to `getVideoDetails` during `searchTracks`, preserving API quota.
  - Filters out malformed records (null item, null video ID, empty video ID, blank video ID).
  - Trims video IDs with surrounding whitespace.
  - Handles null responses and null item lists gracefully.
  - Preserves null optional metadata without throwing.
- `YouTubeMapperTest`: 15 test methods (verifying response mapping, thumbnail fallbacks, and video ID trimming / blank ID filtering).
- `DefaultCandidateDiscoveryServiceTest`: 25 test methods across 11 nested categories covering all 10 standard execution & error scenarios:
  - Validation: constructor null guards for `SearchQueryGenerator` and `MusicDestination`, request null check, convenience overload delegations with default and custom options.
  - Empty query generation: returns legitimate `EMPTY` result with zero executions when generator yields empty list or null, verifying `musicDestination` is never called.
  - Case 1 (SUCCESS): single query and multiple query execution, accumulating candidates and preserving encounter order.
  - Case 2 (EMPTY): all queries return 0 candidates, producing `EMPTY` status with successful executions and zero candidates.
  - Case 3 (PARTIAL_FAILURE): queries failing while others produce candidates, preserving earlier and later candidates, capturing error messages.
  - Case 4 (FAILED): some queries fail while successful queries return 0 candidates (ensuring failure is not disguised as empty).
  - Case 5 (FAILED): all queries fail due to provider errors (`ExternalProviderException`), aggregating error diagnostics.
  - Case 6 (Invalid / Empty responses): null `DestinationSearchResult`, null candidates list, and malformed candidates with null/blank IDs safely filtered without throwing.
  - Case 7 (Quota Bounding & Limits): strictly respects `DiscoveryOptions.maxQueries()`, early termination when `maxCandidates` quota is reached (skipping remaining queries to save quota), candidate truncation to `maxCandidates`, passing `resultsPerQuery` directly to provider.
  - Case 8 (Deduplication & Provenance): ignores within-query duplicate candidate IDs, preserves cross-query duplicate candidates in `discoveredCandidates` while deduplicating in `candidates`.
  - Case 9 (Exception Handling): records `ResourceNotFoundException` and exceptions with null messages gracefully in diagnostics.
- `DefaultSearchQueryGeneratorTest`: 25 test methods (29 test executions) across 13 nested categories:
  - Ordinary title and artist: primary artist and clean title query formulation (`PRIMARY_TITLE_ARTIST`), single-argument overload defaults.
  - Featured artists: multi-artist joining (`dua lipa feat. dababy - levitating`), discrete featured list aggregation.
  - Album-qualified queries: album appending (`daft punk - get lucky random access memories`), suppression when album matches track title.
  - Version tokens: live performance (`eagles - hotel california live at wembley`), remaster with year (`phil collins - in the air tonight 2015 remaster`), variant type fallback.
  - Missing or blank metadata: clean title only for missing artist or compilation credits (`Various Artists`), empty output for blank title+artist, null safety validations.
  - Unicode and punctuation safety: non-Latin script preservation (Japanese, Cyrillic), ampersands, slashes.
  - Duplicate suppression: case-insensitive and whitespace duplicate elimination, selective raw query retention for stripped promotional markers (`The Weeknd - Blinding Lights (Official Video)`).
  - Disabled strategies: strict adherence to `options.enabledStrategies()`.
  - MaxQueries boundaries: strict adherence to configured query bounds (1–5) without artificial padding.
  - Max query length: safe truncation at `SearchQuery.MAX_QUERY_LENGTH` (500 chars).
  - Determinism and stability: reproducible query strings, strategies, and sequential 1-based priorities across repeated invocations.
  - No fabricated metadata: omission of missing attributes without placeholder generation.
  - Hygiene and immutability: unmodifiable query list return, non-blank query string enforcement.
- Discovery Contracts & Model Suites (52 tests):
  - `SearchQueryTest`: 10 tests (validation, immutability, trimming, length bounding).
  - `DiscoveryOptionsTest`: 19 tests (default values, custom bounds, strategy filtering, immutable sets).
  - `CandidateDiscoveryRequestTest`: 4 tests (track metadata validation, default and custom options).
  - `DiscoveredCandidateTest`: 4 tests (candidate-to-query provenance pairing, null rejections, duration accessor delegation).
  - `SearchQueryExecutionTest`: 5 tests (telemetry, duration tracking, error capturing, yield metrics).
  - `CandidateDiscoveryResultTest`: 7 tests (encounter-order ID deduplication, candidate bounding, status evaluation).
- `CandidateDiscoveryIntegrationTest`: 12 comprehensive end-to-end integration tests:
  - Full pipeline query formulation, provider execution, encounter-order deduplication, and provenance tracking across multiple queries.
  - Quota bound enforcement and early termination when candidate limit is satisfied on initial queries.
  - Continued execution across subsequent queries when duplicates leave unique candidate count below limit.
  - Legitimate alternate version preservation (Live, Remix, Acoustic, Cover, Karaoke, Remaster, Radio Edit, Slowed, Sped Up).
  - Partial failure resilience preserving primary candidates when secondary query encounters quota exhaustion (403).
  - Failed-first query resilience preserving candidates discovered by subsequent queries with `PARTIAL_FAILURE`.
  - Empty-first query continuation allowing subsequent queries to discover candidates and achieve `SUCCESS`.
  - Total provider outage handling yielding structured `FAILED` status with aggregated error messages.
  - Legitimate empty result handling yielding `EMPTY` status with null error message.
  - Whitespace-padded and blank ID handling ensuring safe trimming and filtering.
  - Missing duration and null optional candidate metadata fields handled safely without NPE.
  - Credential and token sanitization in error messages (masking `key=***` and `Bearer ***`).

## Future Testing Scope (Phase 7+)
As the migrator logic scales, testing will expand to:
- Matching Engine & Scorer Test Suite (Phase 7)
- Matching Benchmark Test Suite (Phase 8)
- Migration State Machine Test Suite (Phase 9)
- Full End-to-End Migration Flow Testing

## Current Blockers & Status
- **Phase 6 Complete & Frozen**: Full Candidate Discovery pipeline (Phases 6A–6G) implemented, audited, and verified across 131 discovery and candidate tests (358 total matching subsystem tests). Phase 6 is FROZEN.
- **Phase 5 Freeze**: Normalization subsystem (Phases 5A–5H) fully verified with 227 passing normalization tests and 0 Checkstyle violations. Phase 5 is FROZEN.
- **Phase 4 Freeze**: Unit, Service, WebMvcTest, and MockRestServiceServer tests pass cleanly across both Spotify and YouTube feature sets.
- **Docker Dependency**: Repository and full context integration testing (e.g., `PlaylistRepositoryTest`, `MigratorApplicationTests`) via `Testcontainers` is failing strictly due to the current execution environment lacking a functional Docker daemon binding. (Use focused tests e.g. `$ mvnw test -Dtest=...` to avoid Docker tests).
- **Format Verification**: `mvnw checkstyle:check` is completely passing with 0 violations.