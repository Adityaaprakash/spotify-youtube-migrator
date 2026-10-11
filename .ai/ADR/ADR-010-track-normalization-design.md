# ADR-010: Track Normalization Engine Design

## Context
When migrating playlists from Spotify to YouTube, track metadata exhibits significant divergence:
- Spotify metadata is structured (discrete title, artist array, album, duration), but may include promotional strings or irregular casing.
- YouTube video metadata is unstructured, community-titled, and frequently contains promotional annotations (e.g., `(Official Video)`, `[Official Audio]`), differing casing, varying Unicode representations (decomposed vs. precomposed accents), and embedded artist/collaboration tokens.

To enable deterministic and reliable candidate discovery and similarity scoring in subsequent phases (Phase 6 and Phase 7), raw metadata must be normalized into comparable representations without destroying artistic identity or confusing different recordings of a song.

## Decision
1. **Package Placement**:
   Place normalization components in `com.spotifyyoutube.migrator.matching.normalization` under the `matching` module.

2. **Component Lifecycle & State**:
   Normalizers (`TitleNormalizer`, `ArtistNormalizer`, `AlbumNormalizer`) are implemented as stateless Spring `@Component`s. They contain pure, deterministic functions, requiring no external dependencies, no database, no network calls, and no Spring context for unit testing.

3. **Provider Isolation**:
   Normalizers accept standard Java types (`String`, `List<String>`) rather than provider-specific DTOs (`SpotifyTrackDto`, `YouTubeVideoSummaryResponse`). They contain zero imports from `com.spotifyyoutube.migrator.spotify.*` or `com.spotifyyoutube.migrator.youtube.*`.

4. **Immutability & Display Separation**:
   Normalized strings are comparison-oriented representations, never overwriting or mutating original domain entities (`Track`, `DestinationTrackCandidate`). Original source metadata is preserved untouched.

5. **Unicode & Casing Policy**:
   - Canonical Composition (`Normalizer.Form.NFC`) is applied to resolve canonical equivalence (e.g. composed vs decomposed accents) without corrupting non-Latin scripts (Japanese, Cyrillic, Korean, Arabic).
   - Case folding is strictly performed using `Locale.ROOT` to avoid locale-dependent behavior.

6. **Promotional Annotations vs. Meaningful Versions**:
   - `TitleNormalizer` conservatively removes recognized promotional suffixes (`Official Audio`, `Official Video`, `Official Music Video`, `Official Lyric Video`, `Official Visualizer`) enclosed in brackets or following separators at the end of titles, provided substantive title content remains.
   - It strictly preserves meaningful version qualifiers (`(Live)`, `(Acoustic)`, `(Remix)`, `(Karaoke)`, `(Nightcore)`, `(Sped Up)`, `(Slowed)`), ensuring distinct recordings are never equated.
   - Promotional terms within legitimate titles (e.g. `Official Secrets`, `The Official Song`) are left intact.

7. **Artist Normalization & Collaboration Boundaries**:
   - `ArtistNormalizer` identifies token-bounded featured artist markers (`feat.`, `feat`, `ft.`, `ft`, `featuring`) into `NormalizedArtistCredit(primaryArtist, featuredArtists)`.
   - It avoids naive splitting on commas, ampersands, or plus signs, thus preserving intact multi-name bands (e.g. `Simon & Garfunkel`, `Earth, Wind & Fire`, `AC/DC`, `Florence + the Machine`).

8. **Album Normalization & Edition Preservation**:
   - `AlbumNormalizer` performs basic normalization (Unicode NFC, `Locale.ROOT` lowercasing, whitespace collapsing, and quote/dash standardization).
   - Unlike `TitleNormalizer`, `AlbumNormalizer` intentionally preserves album edition descriptors (e.g. `Deluxe Edition`, `Expanded Edition`, `Anniversary Edition`, `Remastered`, `Live`, `Live at Wembley`, `Soundtrack`, `Special Edition`, `[Explicit]`, `[Clean]`). These qualifiers represent substantive release distinctions rather than promotional noise, allowing downstream match evaluation to reward exact album edition matches.

9. **Duration Normalization & Representation**:
   - `DurationNormalizer` converts diverse provider durations (Spotify millisecond integers/longs and YouTube ISO-8601 strings) into canonical `java.time.Duration`.
   - Exact precision is preserved at the millisecond and nanosecond level without lossy floating-point conversions or premature rounding.
   - Matching tolerance strategy is intentionally deferred to downstream matching evaluation (Phase 6 / Phase 7); the normalizer focuses exclusively on exact, deterministic conversion.
   - Strict error contract: `null` or blank strings return `null` (representing absent duration). Legitimate zero durations return `Duration.ZERO`. Negative values, malformed ISO strings, and arithmetic overflows reject with `IllegalArgumentException`. Invalid or unparseable durations are never silently converted to zero or synthetic defaults.
10. **Metadata Tokenizer & Variant Detection**:
    - `MetadataTokenizer` provides stateless, deterministic tokenization of track metadata strings into an immutable `TokenizedTitle` aggregate.
    - Tokenization classifies tokens into high-level categories (`TokenCategory`): `OFFICIAL_CONTENT`, `MEDIA_FORMAT`, `REMASTER`, `EDIT_OR_MIX`, `PERFORMANCE`, `DERIVATIVE_VERSION`, `FEATURED_ARTIST`, and `UNKNOWN_ANNOTATION`.
    - Recording and release variants (`VariantType`) are extracted into an immutable, insertion-ordered set (`REMASTER`, `LIVE`, `ACOUSTIC`, `UNPLUGGED`, `RADIO_EDIT`, `EXTENDED_MIX`, `ORIGINAL_MIX`, `CLUB_MIX`, `INSTRUMENTAL`, `DEMO`, `STUDIO`, `REMIX`, `COVER`, `KARAOKE`, `NIGHTCORE`, `SPED_UP`, `SLOWED`, `SLOWED_REVERB`, `REVERB`).
    - Media formats (`MediaFormat`) distinguish `AUDIO`, `VIDEO`, `MUSIC_VIDEO`, `LYRIC_VIDEO`, and `VISUALIZER`.
    - Rich contextual details are captured in structured records: `RemasterDetails` (with optional release/remaster year) and `LivePerformanceDetails` (with optional venue or event).
    - Unrecognized parenthetical and bracketed annotations are retained in `unknownAnnotations` and kept within `cleanTitle` to prevent accidental loss of identity information during candidate discovery and match scoring.
    - Featured artist parentheticals (`feat.`, `ft.`, `featuring`) are cleanly routed to `featuredArtists` and stripped from clean title without being misclassified as variants.
11. **Normalization Pipeline Orchestration**:
    - `NormalizationOrchestrator` provides the unified, stateless entry point composing `TitleNormalizer`, `ArtistNormalizer`, `AlbumNormalizer`, `DurationNormalizer`, and `MetadataTokenizer`.
    - Accepts immutable `RawTrackMetadata` and produces structured `NormalizedTrackMetadata`.
    - Aggregates title normalization, structured artist credits, discrete artist list normalizations, deduplicated consolidated featured artists, album normalization, canonical `Duration`, and tokenized title variants.
    - **Featured-Artist Consolidation**: Combines featured artists from structured artist credit, secondary elements of discrete artist list, and title parentheticals into `allFeaturedArtists` in deterministic encounter order. Deduplicates using `ArtistNormalizer.normalize()` and strictly excludes primary artist.
    - **Duration Semantics & Conflict Detection**: Evaluates direct `Duration`, milliseconds `durationMs`, and ISO-8601 `durationIso8601`. If multiple duration representations are provided, all must resolve to identical `Duration` values; conflicting durations strictly reject with `IllegalArgumentException`. Negative durations, malformed strings, and overflows reject with `IllegalArgumentException`. Legitimate zero durations yield `Duration.ZERO`.
    - **Clean Title & Cleanliness Semantics**: Exposes `cleanTitle()` (title stripped of promotional suffixes and recording variants) and `isClean()` (indicating absence of detected variants, media formats, or promotional markers). Javadoc and contracts strictly clarify that `isClean()` represents syntactic title/recording cleanliness, NOT parental advisory or explicit-content status (e.g., clean vs. explicit lyrics).
    - Thread-safe, stateless, and fully testable without Spring context or external dependencies.

## Consequences
- **Positive**: Clean, deterministic, fast comparison foundation with 100% test coverage and zero external dependencies. Heterogeneous provider duration formats map cleanly to standard `java.time.Duration`. Comprehensive metadata tokenization accurately detects variants, performance context, and official indicators while preserving unknown annotations. The unified orchestrator eliminates ad-hoc normalizer invocations across candidate discovery and matching engines.
- **Trade-offs**: Raw artist strings lacking explicit featured markers (e.g., legacy comma-separated lists) cannot be split automatically without risking band corruption; callers must rely on structured source lists when available.
