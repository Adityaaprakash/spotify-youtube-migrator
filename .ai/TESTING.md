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
  - Case 9 (Exception Handling): records `ResourceNotFoundException` and exceptions with null messages gracefully in diagnostics; sanitizes sensitive credentials across API keys (`key=***`, `api_key=***`), tokens (`token=***`, `access_token=***`), secrets (`client_secret=***`), and Authorization headers (Bearer and Basic) without leaking secrets into logs or error diagnostics.
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

### Matching Engine & Feature Extraction Testing Scope (Phase 7A & 7B Complete)
Implemented and verified (11 test suites covering Phase 7A contracts and Phase 7B feature extraction):
- `MatchDecisionTest`: Enum values (`MATCH`, `REVIEW_REQUIRED`, `NO_MATCH`), valueOf string parsing, and value integrity.
- `MatchConfidenceTest`: Enum values (`EXCELLENT`, `GOOD`, `POOR`), valueOf string parsing, and value integrity.
- `MatchScoreBreakdownTest`: 6 tests across 3 nested categories:
  - Empty construction: `empty()` produces all null component scores without defaulting to 0.0; `isEmpty()` returns true; optional accessors return empty.
  - Builder construction: builds populated component scores, leaves unpopulated scores as null, populates detailed penalties.
  - Immutability: `detailedPenalties` map is unmodifiable; mutations throw `UnsupportedOperationException`; verifies record equality and hashCode.
- `CandidateMatchEvaluationTest`: 5 tests across 2 nested categories:
  - Validation: rejects null `DestinationTrackCandidate` with `NullPointerException`.
  - Unevaluated factory: produces candidate container with honest nulls for scores, probability, confidence, and breakdown.
  - Of factory: populates candidate, overall score, confidence, breakdown, and reasons.
  - Immutability: `matchReasons` list is unmodifiable and throws on mutation.
- `TrackMatchRequestTest`: 8 tests across 4 nested categories:
  - Validation: rejects null `NormalizedTrackMetadata` and null candidates with `NullPointerException`; trims `sourceTrackId`; preserves null `sourceTrackId` when omitted.
  - Empty candidate policy: empty candidate list is explicitly valid (`hasCandidates() == false`, `candidateCount() == 0`).
  - Discovery result conversion: `fromDiscoveryResult` factory extracts source track and candidates faithfully.
  - Immutability: `candidates` list is unmodifiable and defensively copied.
- `TrackMatchResultTest`: 7 tests across 3 nested categories:
  - Validation: rejects null `sourceTrack` and null `decision`; enforces selected-candidate consistency against evaluated candidates (throws `IllegalArgumentException` on mismatch).
  - Factory methods: `noMatch` and `unevaluated` construct valid `NO_MATCH` results with diagnostic reasons.
  - Immutability: `candidateEvaluations` and `matchReasons` are unmodifiable and defensively copied.
- `OfficialSignalEvidenceTest`: 8 tests across 5 nested categories:
  - Topic/VEVO detection: auto-generated ` - Topic` channels and `VEVO` syndication channels detected.
  - Artist equivalence: candidate channel exactly matching source primary artist confirmed.
  - Distributor delivery: `Provided to YouTube by...` and `Auto-generated by YouTube` in descriptions detected.
  - Conservative authenticity rule: channels containing "official" flagged in `channelClaimsOfficial`, but strictly excluded from confirming authenticity in `hasOfficialEvidence()`.
  - Null safety: `none()` factory produces all false flags; null candidate yields `none()`.
- `CandidateVersionEvidenceTest`: 5 tests across 3 nested categories:
  - Empty construction: `empty()` produces empty indicators and false indicator queries.
  - Indicator helpers: accurately reports active indicators; flags conflicting indicators (`SPED_UP` and `SLOWED`).
  - Immutability: indicator sets and token lists are unmodifiable.
- `SourceTrackFeaturesTest`: 3 tests:
  - Preservation: exact preservation of normalized title, clean title, artists, album, duration, and variants.
  - Missing metadata: honest preservation of missing album, duration, and artist without synthetic defaults.
  - Validation: rejects null source metadata with `NullPointerException`.
- `CandidateTrackFeaturesTest`: 4 tests:
  - Validation: rejects null candidate and null candidateId; preserves optional accessors.
  - Missing metadata: honest preservation of missing duration, description, and publishedAt.
- `DefaultTrackFeatureExtractorTest`: 17 tests across 7 nested categories:
  - Source track feature extraction: exact preservation of source metadata, clean title, and variants; missing metadata handling; null guards.
  - Destination candidate metadata extraction: normalized title/channel, duration, description, publishedAt; null guards; blank ID rejection.
  - Version indicator detection: cover (`(Cover)`, `[Acoustic Cover]`, `- Cover`, `Cover by`), live (`(Live at Wembley)`, `- Live`, `[Live in Tokyo]`), remix (`(Remix)`, `- Club Remix`), karaoke (`(Karaoke Version)`, `- Karaoke`), reaction (`(Reaction)`, `[REACTION]`, `(First Time Hearing Reaction)`, `- Producer Reacts`), nightcore (`(Nightcore)`, `Nightcore - Title`), sped-up (`(Sped Up)`, `- Speed Up`), slowed (`(Slowed + Reverb)`, `(Chopped and Screwed)`).
  - False positive protection in unrelated contexts: *Live to Tell*, *Stayin' Alive*, *Live Forever*, *Under Cover of Darkness*, *Cover Me*, *Recovery*, *Chain Reaction*, *Chemical Reaction* verified to NOT trigger version indicators.
  - Multiple and conflicting indicators: captures multiple indicators (`(Live Acoustic Cover)`); detects conflicting indicators (`(Sped Up / Slowed Down)`); flags ambiguity on 3+ indicators.
  - Official provenance evidence: topic channels, VEVO, description label delivery; conservative rule on channels claiming "official".
  - Batch extraction and determinism: encounter-order preservation, filtering blank candidate IDs, idempotent repeated execution.
- `TitleSimilarityScorerTest` (Phase 7C): 21 tests across 8 nested categories:
  - Exact and casing matches: exact match (`1.0`), case insensitivity, harmless whitespace trimming.
  - Typographical and punctuation differences: minor typo edit distance (`>= 0.8`), punctuation standardization.
  - Word order tolerance: permuted token order scored with high similarity (`0.95`), rewarding true exact word order above permuted tokens.
  - Delimiter and partial title matching: candidate title embedding artist (`daft punk - get lucky`) scored with high similarity (`>= 0.9`); pipe delimiters handled; unrelated track with one shared word kept low (`<= 0.5`).
  - Short-title safeguard: very short titles ("XO", "Why") protected against superficial substring collisions (e.g. "XO" vs "XOXO" scored `<= 0.7`), avoiding false positive match inflation.
  - Missing and empty handling: returns `null` when source or candidate normalized title is missing or blank.
  - Unicode, determinism & immutability: non-Latin scripts (Japanese/Cyrillic) scored accurately, repeated invocations deterministic, feature inputs unmodified.
- `ArtistSimilarityScorerTest` (Phase 7D): 20 tests across 6 nested categories:
  - Exact and primary artist matches: exact primary artist (`1.0`), case insensitivity, whitespace trimming.
  - Official channel equivalence: auto-generated Topic channel and VEVO channel without spaces (`DuaLipaVEVO`) matching source artist scored as `1.0`.
  - Multi-artist credits & reordering: all artists matching (`1.0`), primary artist matching with missing featured artist scoring high (`>= 0.85`), featured artist in different position recognized, inverted artist order (`Dua Lipa feat. Calvin Harris` vs `Calvin Harris & Dua Lipa`) awarded `0.90` (`>= 0.85`).
  - Multi-artist duplicate-credit prevention: optimal 1-to-1 bipartite assignment prevents single candidate artist or duplicate candidate tokens from matching multiple source credits (`<= 0.65`).
  - Primary vs. featured priority: primary artist match outranks featured-only match; different primary artists sharing a featured artist strictly bounded to low score (`<= 0.35`).
  - Substring collision safeguards: distinct artists sharing tokens ("Iron & Wine" vs "Iron Maiden") or prefix collisions ("Future" vs "Afterlife", "The Who" vs "Wham!") strictly scored `0.0` or `< 0.50`.
  - Unicode, diacritics & determinism: accented Latin names (`Beyoncé` vs `Beyonce`, `Sigur Rós` vs `Sigur Ros`, `Björk` vs `Bjork`) scored `1.0` via NFD diacritic stripping; Cyrillic preserved; missing artist metadata returns `null` honestly; idempotent execution.
- `CandidateSimilarityScorerIntegrationTest`: 12 integration tests:
  - End-to-end scoring of matching track: exact title, artist, and duration yield `1.0` in `MatchScoreBreakdown`, version penalty is `0.0`.
  - Album evidence extraction: candidate description with album details populates `albumScore` (`1.0`).
  - Duration penalty isolation: duration discrepancies (>60s) penalized to `0.0` while title and artist scores remain high.
  - Cover penalty integration: candidate marked as cover incurs `0.25` version penalty while preserving title, artist, and duration scores.
  - Reaction penalty integration: candidate marked as reaction incurs `0.30` version penalty.
  - Live mismatch penalty integration: candidate marked as live for studio source incurs `0.15` live mismatch penalty.
  - Live alignment: matching live candidate for live source incurs `0.0` penalty.
  - Additive penalty combination: multiple variant penalties combine additively in seam (`COVER` + `REACTION` $\to 0.55$).
  - Permuted candidate title with embedded artist: `daft punk - get lucky` on official channel yields high title and artist scores.
  - Partial match preservation: mismatched artist preserves high title score while artist score is low.
  - Honest missing data: absent candidate metadata yields `null` scores across missing dimensions (`titleScore`, `artistScore`, `durationScore`, `albumScore`) and `0.0` version penalty.
  - Validation: rejects null inputs with `NullPointerException`.
- `DurationSimilarityScorerTest` (Phase 7E.1): 15 tests across 4 nested categories:
  - Exact and tolerance matches: exact match (`1.0`), offset within 2.0s tolerance window (`1.0`), candidate shorter within tolerance (`1.0`).
  - Piecewise continuous decay: linear decay in small offset range 2s–10s (`0.85`–`1.0`), moderate range 10s–30s (`0.40`–`0.85`), large range 30s–60s (`0.0`–`0.40`), cutoff beyond 60s (`0.0`).
  - Monotonicity & invariants: monotonic non-increasing with respect to offset (piecewise constant on [0s, 2s] and >60s), symmetric across source and candidate, bounded in `[0.0, 1.0]`.
  - Missing metadata & validation: returns `null` when source or candidate duration is missing, rejects negative values with `IllegalArgumentException`, strictly deterministic.
- `AlbumSimilarityScorerTest` (Phase 7E.2): 19 tests across 5 nested categories:
  - Exact and normalized matches: exact match (`1.0`), case insensitivity, smart quote & dash normalization, whitespace collapsing.
  - Unicode & accent folding: accented Latin (`Ágætis byrjun` vs `Agaetis byrjun` $\to 1.0$), Cyrillic, Japanese scripts.
  - Typos & unrelated mismatches: minor typo on long album title ($\ge 0.88$), unrelated albums (`0.0`), substring collisions (`War` vs `Star Wars` $\to 0.0$), generic title collisions (`Queen: Greatest Hits` vs `Blink-182: Greatest Hits` $\to 0.0$).
  - Candidate description extraction: explicit `Album: <name>` line, standard YouTube topic delivery layout line 3, missing album in description returns `null`.
  - Missing metadata & validation: returns `null` when source or candidate album missing, rejects null feature objects with `NullPointerException`, strictly deterministic.
- `VersionPenaltyScorerTest` (Phase 7F): 27 tests across 7 nested categories:
  - Individual variant penalties: COVER (`0.25`), REACTION (`0.30`), KARAOKE (`0.30`), NIGHTCORE (`0.20`), SPED_UP (`0.15`), SLOWED (`0.15`), LIVE_MISMATCH (`0.15`), REMIX_MISMATCH (`0.15`), ACOUSTIC_MISMATCH (`0.10`), INSTRUMENTAL_MISMATCH (`0.15`), REMASTER (`0.00`).
  - Source variant alignment: live + live $\to 0.0$; remix + remix $\to 0.0$; acoustic + acoustic $\to 0.0$; source live + candidate studio $\to 0.15$; source remix + candidate studio $\to 0.15$; source acoustic + candidate standard $\to 0.10$.
  - Multiple simultaneous variants & bounds: cover + reaction $\to 0.55$; cover + live mismatch $\to 0.40$; multiple penalties clamped to upper bound `1.0`; itemized `detailedPenalties` tracking; lower bound `0.0`.
  - False positive protection for genuine song titles: *Live to Tell*, *Live and Let Die*, *Live Forever*, *Stayin' Alive*, *Under Cover of Darkness*, *Chain Reaction*, and official media annotations (*Official Video*, *Audio*, *Visualizer*, *Lyric Video*) verified to produce `0.0` penalty.
  - End-to-end extraction and scoring integration: verifies tokenization through `DefaultTrackFeatureExtractor` and penalty evaluation through `DefaultVersionPenaltyScorer`.
  - Null, blank, and missing data safety: `NullPointerException` on null source or candidate; null indicator overloads handled as empty sets; `score()` delegation.
  - Immutability & determinism: unmodifiable `detailedPenalties` map; identical output across 100 repeated executions.

- `OfficialSignalScorerTest` (Phase 7G.2): 10 tests across 3 nested categories:
  - Verified signals: auto-generated Topic channel (`1.0`), VEVO channel (`1.0`), verified artist channel match (`1.0`), DDEX release description (`1.0`), official audio marker (`1.0`), official video marker (`1.0`).
  - Unverified claims & non-official: channel name merely containing "official" without verified proof awards `0.0`; non-official candidate awards `0.0`.
  - Missing data & validation: null candidate official signal evidence honestly returns `null`; null inputs throw `NullPointerException`.
- `CompositeScoreCalculatorTest` (Phase 7G.1): 12 tests across 4 nested categories:
  - Complete evidence & weights: perfect 1.0 across all 5 dimensions yields `1.0`; linear combination follows documented weights (Title 40%, Artist 30%, Duration 10%, Album 10%, Official 10%).
  - Dynamic re-normalization: secondary dimensions absent re-normalizes proportionally over active dimensions; all scores absent returns `0.0`.
  - Version penalties: single deduction of version penalty from base composite score; clamped to `0.0` when penalty exceeds base; null penalty treated as 0.0 deduction.
  - Validation & determinism: rejects null breakdown with `NullPointerException`; rejects NaN, infinite, and out-of-bounds scores with `IllegalArgumentException`; rejects invalid constructor weights; strictly deterministic.
- `CandidateRankingEngineTest` (Phase 7G.3): 12 tests across 3 nested categories:
  - Basic ranking: empty candidate list returns empty list; single candidate preserved; candidates sorted descending by overall composite score.
  - 7-level tie-breaking ladder: breaks composite tie using title score (descending); breaks title tie using artist score (descending); breaks artist tie using official signal (descending); breaks official signal tie using version penalty (ascending); breaks version penalty tie using duration score (descending); breaks complete tie using candidate ID (lexicographical ascending).
  - Immutability & determinism: caller input collection never mutated; returns unmodifiable list; null overall score treated as 0.0 and placed after scored candidates; null list throws `NullPointerException`; deterministic repeated ordering.
- `MatchDecisionEngineTest` (Phase 7G.4): 12 tests across 5 nested categories:
  - Empty candidates & unresolved: empty candidate list produces `NO_MATCH` with "No destination candidates discovered"; top score below minimum viable threshold (< 0.50) produces `NO_MATCH` with `selectedCandidate = null` and `POOR` confidence.
  - Borderline scores: score exactly at 0.50 or below 0.75 produces `REVIEW_REQUIRED` with top candidate selected and explainable diagnostic reasons.
  - Version penalty gate: penalty exceeding max (> 0.10) on viable candidate produces `REVIEW_REQUIRED`; penalty <= 0.10 allows automated `MATCH`.
  - Ambiguity margin: score difference between top two candidates < 0.05 margin produces `REVIEW_REQUIRED` with `GOOD` confidence; difference >= 0.05 allows automated `MATCH`.
  - Unambiguous matches: score between 0.75 and 0.90 produces `MATCH` with `GOOD` confidence; score >= 0.90 produces `MATCH` with `EXCELLENT` confidence; validation rejects null arguments.
  - Calibrated probability decision policy: validated probability < 0.70 produces `REVIEW_REQUIRED`; validated probability >= 0.70 produces `MATCH` with confirmed probability diagnostic reason; version penalty > 0.10 cannot be overridden by high probability; ambiguity margin cannot be overridden by high probability; uncalibrated baseline and unvalidated configured models fall back to Phase 7G heuristic rules with transparent explanation; `EXCELLENT` confidence requires both high score (>= 0.90) and high probability (>= 0.85).
- `CalibrationResultTest` (Phase 7H.1): 11 tests:
  - Unavailable factory: empty probability, isAvailable false, isValidated false, modelVersion UNVALIDATED, calibrationMethod NONE, reject null reason.
  - Configured factory: valid probability in `[0.0, 1.0]`, isAvailable true, isValidated false, calibratedProbability empty, configuredProbability present, method and modelVersion metadata.
  - Validated factory: valid probability in `[0.0, 1.0]`, isAvailable true, isValidated true, calibratedProbability present, configuredProbability present.
  - Bounds & validation: boundary probabilities 0.0 and 1.0; out-of-range rejection (< 0.0 or > 1.0); non-finite rejection (NaN and Infinity); blank/null metadata rejection; record equality and hashcode semantics.
- `DefaultMatchProbabilityCalibratorTest` (Phase 7H.2): 14 tests across 4 nested categories:
  - Uncalibrated baseline: reports unconfigured and unvalidated model status (`isConfigured() == false`, `isModelValidated() == false`, `NONE`, `UNVALIDATED`), returns unavailable result, empty optional, rejects invalid scores.
  - Configured unvalidated mode (`withPlattScaling`): reports `isConfigured() == true` and `isModelValidated() == false`, returns configured result with `isValidated() == false`, calibrated probability empty, rejects invalid scores/parameters.
  - Validated mode (`withValidatedPlattScaling`): reports `isConfigured() == true` and `isModelValidated() == true`, returns validated result, evaluates logistic sigmoid at midpoint ($P = 0.50$), high score ($P \ge 0.99$), low score ($P \le 0.01$), candidate evaluation calibration.
  - Numerical stability & boundary safeguards: clamped logit handling under extreme parameters (e.g. slope $\pm 1000.0$), edge scores 0.0 and 1.0, finite output guarantee.
- `TrackMatchingEngineIntegrationTest` (Phase 7G.5 & 7H.4): 7 end-to-end integration tests:
  - Full pipeline match: executes Feature Extraction $\to$ Similarity Scoring $\to$ Composite Scoring $\to$ Ranking $\to$ Decision and selects Case 7 unambiguous official candidate with `EXCELLENT` confidence, correct candidate rank ordering, and complete score breakdown.
  - Empty discovery handling: empty candidates list produces clean `NO_MATCH`.
  - Version discrepancy review: cover candidate incurs version penalty and requires manual review.
  - Ambiguity margin review: two closely competing candidates require manual review.
  - Calibrated probability pipeline: end-to-end execution with validated Platt scaling calibrator confirms calibrated probability retention on evaluations and top-level result with confirmed probability diagnostic reasons.
  - Unvalidated Platt scaling fallback: end-to-end execution with unvalidated Platt scaling falls back to Phase 7G heuristic decision and provides unvalidated transformation diagnostics.
  - Request validation: null request throws `NullPointerException`.

### Matching Subsystem Verification & Freeze Scope (Phase 7I Complete & Frozen)
Verified across the comprehensive matching regression suite and focused Phase 7H suites:
- Focused Phase 7H test suite: 71 tests passing with 0 failures:
  - `CalibrationResultTest`: 11 tests (unavailable, configured, validated factories, probability clamping, NaN/Infinity validation).
  - `DefaultMatchProbabilityCalibratorTest`: 14 tests (uncalibrated baseline, configured Platt scaling, validated Platt scaling, clamped logit numerical stability, constructor validation).
  - `MatchDecisionEngineTest`: 13 tests across 7 nested classes (empty candidates, below viable threshold, borderline scores, version penalty gate, ambiguity margin gate, calibrated probability divergence gate, unambiguous match gate).
  - `TrackMatchingEngineIntegrationTest`: 7 end-to-end integration tests (full pipeline match, empty discovery, version discrepancy review, ambiguity review, validated probability pipeline, unvalidated fallback, request validation).
  - `TrackMatchResultTest`: 17 tests (validation, factories, selected candidate consistency, unmodifiable collections, equality).
  - `CandidateMatchEvaluationTest`: 5 tests (validation, unevaluated container, evaluated container, unmodifiable reasons).
- Phase 7A–7H Matching Regression Suite (18 test classes):
  - Classes: `SourceTrackFeaturesTest`, `CandidateTrackFeaturesTest`, `DefaultTrackFeatureExtractorTest`, `TitleSimilarityScorerTest`, `ArtistSimilarityScorerTest`, `DurationSimilarityScorerTest`, `AlbumSimilarityScorerTest`, `VersionPenaltyScorerTest`, `CandidateSimilarityScorerIntegrationTest`, `OfficialSignalScorerTest`, `CompositeScoreCalculatorTest`, `CandidateRankingEngineTest`, `MatchDecisionEngineTest`, `TrackMatchingEngineIntegrationTest`, `CalibrationResultTest`, `DefaultMatchProbabilityCalibratorTest`, `TrackMatchResultTest`, `CandidateMatchEvaluationTest`.
  - Results: 274 tests run, 0 failures, 0 errors, 0 skipped.
- Total Matching Subsystem Test Coverage: 24 test classes, 333 tests total, 100% passing.
- Checkstyle: 0 violations across all backend source files.
- Phase 5 & Phase 6 Boundary Audit: 100% untouched and preserved.

## Future Testing Scope (Phase 8+)
As the migrator logic scales, testing will expand to:
- Matching Evaluation & Benchmark Test Suite (Phase 8: labeled ground truth evaluation, Brier score, calibration curve validation)
- Migration State Machine & Orchestration Test Suite (Phase 9 & Phase 12)
- Review & Correction Flow Testing (Phase 10)
- Destination Playlist Creation Testing (Phase 11)
- Full End-to-End Migration Flow Testing

## Current Blockers & Status
- **Phase 7 Complete & Frozen**: Full Matching Engine subsystem (Phases 7A–7I) verified with 274 regression tests passing across 18 test classes (333 tests across 24 test classes), 0 Checkstyle violations, and full contract invariant verification. Phase 7 is **FROZEN**.
- **Phase 6 Complete & Frozen**: Full Candidate Discovery pipeline (Phases 6A–6G) verified across 133 discovery tests. Phase 6 is **FROZEN**.
- **Phase 5 Complete & Frozen**: Normalization subsystem (Phases 5A–5H) fully verified with 227 passing normalization tests and 0 Checkstyle violations. Phase 5 is **FROZEN**.
- **Phase 4 Complete & Frozen**: Unit, Service, WebMvcTest, and MockRestServiceServer tests pass cleanly across both Spotify and YouTube feature sets. Phase 4 is **FROZEN**.
- **Docker Dependency**: Repository and full context integration testing (e.g., `PlaylistRepositoryTest`, `UserRepositoryTest`, `MigratorApplicationTests`) via `Testcontainers` is failing strictly due to the current execution environment lacking a functional Docker daemon binding. Non-container unit tests pass 100%.
- **Format Verification**: `mvnw checkstyle:check` is completely passing with 0 violations.