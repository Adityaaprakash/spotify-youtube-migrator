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

## Track Normalization Testing Scope (Phase 5A-5E)
Implemented and verified (148 total normalization tests):
- `TitleNormalizerTest`: 28 tests across 7 nested categories:
  - Basic casing & whitespace: lowercase normalization (`Locale.ROOT`), trimming, collapsing multi-space/tabs/newlines.
  - Null & blank handling: returns empty string for `null`, `""`, and whitespace-only strings.
  - Punctuation & separators: smart/curly apostrophe normalization (`’`, `‘` -> `'`), double quote normalization (`“`, `”`, `„` -> `"`), dash variants (en-dash, em-dash, minus -> `-`).
  - Unicode: NFC canonical composition (decomposed vs. precomposed accents equivalence) and preservation of non-Latin scripts (Japanese, Cyrillic, Korean, Arabic).
  - Promotional annotations: conservative removal of `(Official Audio)`, `[Official Video]`, `(Official Music Video)`, `(Official Lyric Video)`, `(Official Visualizer)`, `- Official Video`, and `| Official Audio` without corrupting legitimate titles (`Official Secrets`, `The Official Song`) or blanking titles composed only of promotional words without brackets.
  - Meaningful versions: strictly preserves `(Live)`, `(Acoustic)`, `(Remix)`, `(Karaoke Version)`, `(Nightcore)`, `(Sped Up)`, and `(Slowed)`.
  - Determinism & immutability: idempotent (`normalize(normalize(x)) == normalize(x)`) and non-mutating.
- `ArtistNormalizerTest`: 33 tests across 6 nested categories:
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
  - Punctuation & typography: standardization of smart/curly apostrophes, double quotes, and dash variants to ASCII while preserving internal punctuation, colons, ampersands, plus signs, brackets, currency symbols, and backslashes.
  - Meaningful album edition preservation: verifies that `Deluxe Edition`, `Remastered`, `Expanded Edition`, `Anniversary Edition`, `Live at Wembley`, `Soundtrack`, `Special Edition`, `[Explicit]`, etc., are strictly retained and never stripped.
  - Determinism & immutability: idempotent and pure function execution.
- `DurationNormalizerTest`: 55 tests across 5 nested categories:
  - Millisecond conversion: whole-second conversions (200,000 ms -> 200s), fractional seconds (200,500 ms -> 200s 500ms), 1 ms, zero milliseconds (0L -> Duration.ZERO), null inputs, `Integer` overload support, large durations (100h), extreme numeric values (`Long.MAX_VALUE`), and negative value rejection (`IllegalArgumentException`).
  - ISO-8601 parsing: standard (`PT3M20S`), seconds-only (`PT45S`), compound (`PT1H2M3S`), zero variants (`PT0S`, `PT0M`, `PT0H`), hours-only (`PT1H`), minutes-only (`PT5M`), fractional seconds (`PT3M20.5S`, `PT0.123S`), whitespace trimming, null/blank handling, malformed rejection, negative duration rejection (`-PT3M`, `PT-3M`), and extreme arithmetic overflow rejection (`IllegalArgumentException`).
  - Consistency across representations: verifies identical `Duration` equality between equivalent milliseconds and ISO-8601 durations (e.g. 200,000 ms == `PT3M20S`, 45,000 ms == `PT45S`, 3,723,000 ms == `PT1H2M3S`, 0L == `PT0S`, 200,500 ms == `PT3M20.5S`, 123L == `PT0.123S`).
  - Determinism & purity: idempotent, stateless execution with no retained state across invocations.
  - Error contract: verifies invalid inputs never silently produce `Duration.ZERO` or fallback values, and legitimate zero durations are strictly distinct from absent `null` durations.

## Future Testing Scope (Phases 5F+)
As the migrator logic scales, testing will expand to:
- `NormalizationOrchestratorTest` (Phase 5F)
- `VariantClassifierTest` (Phase 5G)
- `MatchScorerTest` (Phase 7)
- `MigrationStateMachineTest` (Phase 9)
- Full End-to-End Migration Flow Testing

## Current Blockers & Status
- **Phase 4A-4C Freeze**: Unit, Service, WebMvcTest, and MockRestServiceServer tests pass cleanly across both Spotify and YouTube feature sets.
- **Docker Dependency**: Repository and full context integration testing (e.g., `PlaylistRepositoryTest`, `MigratorApplicationTests`) via `Testcontainers` is failing strictly due to the current execution environment lacking a functional Docker daemon binding. (Use focused tests e.g. `$ mvnw test -Dtest=...` to avoid Docker tests).
- **Format Verification**: `mvnw checkstyle:check` is completely passing with 0 violations.