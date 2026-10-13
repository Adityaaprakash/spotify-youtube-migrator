# ADR-013: Title and Artist Similarity Scoring (Phase 7C & 7D)

## Context

Following the establishment of matching contracts in Phase 7A and candidate feature extraction in Phase 7B, the Matching Engine requires deterministic, production-quality similarity scoring components:
1. **Phase 7C — Title Similarity Scoring**: Comparing source track titles against destination candidate titles using normalized features.
2. **Phase 7D — Artist Similarity Scoring**: Comparing source artist metadata against candidate artist/channel metadata with multi-artist and featured-credit handling.

Key challenges and architectural constraints:
- **Phase boundary discipline**: Phase 5 normalization and Phase 6 discovery are frozen. Scorers must not duplicate normalization or modify frozen components.
- **Provider quirks**: YouTube candidate titles frequently embed artist names, featured guests, and track titles around delimiters (` - `, ` | `, ` : `), while artist channels often use syndication patterns (e.g., ` - Topic`, `VEVO`).
- **False-positive prevention**: Simplistic substring matching (`contains`) causes severe false positives (e.g. "XO" matching "XOXO", "Iron & Wine" matching "Iron Maiden", or a single common word like "The" causing high similarity).
- **Multi-artist attribution**: Candidate artists must not be counted repeatedly against multiple source artists (duplicate-credit inflation).
- **Featured-credit discipline**: A match based solely on a featured artist when primary artists differ must never score as a primary match.
- **Honest missing data**: Absent metadata must yield `null` in `MatchScoreBreakdown`, never synthetic `0.0` or `1.0`.

## Decision

We implement title and artist similarity scoring as independent, stateless Spring components in `com.spotifyyoutube.migrator.matching.engine.scorer`:

### 1. Title Similarity Scoring (`TitleSimilarityScorer`, `DefaultTitleSimilarityScorer`)

- **Contracts**: Accepts `SourceTrackFeatures` and `CandidateTrackFeatures`; returns `Double` normalized to `[0.0, 1.0]` or `null` if title evidence is missing.
- **Multi-signal comparison algorithm**:
  1. **Exact match fast-path**: Returns `1.0` if normalized titles are identical.
  2. **Character-level Levenshtein similarity**: Measures normalized edit distance `1.0 - (distance / max(len1, len2))`.
  3. **Token-sort similarity**: Normalizes token order to tolerate word-order inversions (e.g. "Get Lucky - Daft Punk" vs "Daft Punk - Get Lucky"). Capped at `0.95` when characters differ to reward true exact ordering over scrambled tokens.
  4. **Delimiter segment matching**: Evaluates candidate title segments split by ` - `, ` | `, and ` : `. If a candidate title is structured as `Artist - Title`, the title segment is compared against the source title.
  5. **Token overlap / Jaccard similarity**: Measures set overlap of normalized whitespace tokens.
  6. **Short-title safeguard**: For titles of length <= 3 characters (e.g. "XO", "Why"), partial-token and substring matches are strictly constrained to require high edit distance similarity, preventing misleading matches against longer compound titles.
- **Version non-interference**: Does not apply version penalties or official content boosts, preserving separation of concerns for Phase 7F/7G.

### 2. Artist Similarity Scoring (`ArtistSimilarityScorer`, `DefaultArtistSimilarityScorer`)

- **Contracts**: Accepts `SourceTrackFeatures` and `CandidateTrackFeatures`; returns `Double` normalized to `[0.0, 1.0]` or `null` if artist evidence is missing.
- **Injected normalization**: Constructor-injects Phase 5 `ArtistNormalizer` to normalize candidate channel and title credits without duplicating normalization logic.
- **Official channel equivalence**: Reuses Phase 7B `OfficialSignalEvidence.isArtistVerifiedName()` (handling VEVO channels and Topic channels where spaces are stripped) to award `1.0` for verified official artist channels.
- **Canonical Unicode & accent folding**: Handles canonically equivalent strings and accented Latin names (e.g. `Beyoncé` vs `Beyonce`, `Sigur Rós` vs `Sigur Ros`, `Björk` vs `Bjork`) using standard NFD diacritic stripping without altering Phase 5 normalizer outputs.
- **Multi-artist optimal 1-to-1 assignment**:
  - Compares source primary and discrete artists against candidate channel and parsed credits.
  - Enforces optimal 1-to-1 assignment via exact bipartite matching: each candidate credit can be paired with at most one source artist, completely eliminating duplicate-credit inflation.
  - Reordered artist sets (e.g. `Calvin Harris & Dua Lipa` vs `Dua Lipa feat. Calvin Harris`) are recognized and awarded high scores (`0.90`).
- **Primary vs. Featured artist policy**:
  - Exact primary artist agreement yields `1.0` (or `0.9+` if primary matches and some featured artists are omitted).
  - When primary artists differ, but a candidate artist matches a source featured artist, the score is strictly capped at `0.35` (or `0.40` for multiple shared featured artists). A shared featured artist alone can never produce a high score.
- **Substring collision protection**: Rejects partial token collisions (e.g. `Future` vs `Afterlife`, `The Who` vs `Wham!`, `Iron Maiden` vs `Iron & Wine`), returning `0.0` or strictly low scores (< 0.50) through length-bounded edit distance thresholds.

### 3. Integration Seam (`CandidateSimilarityScorer`, `DefaultCandidateSimilarityScorer`)

- **Role**: Minimal composition seam that accepts `SourceTrackFeatures` and `CandidateTrackFeatures`, invokes title and artist scorers independently, and produces a populated `MatchScoreBreakdown`.
- **Honest preservation**: Sets `titleScore` and `artistScore`, while preserving `durationScore`, `albumScore`, `officialSignalScore`, and `versionPenalty` as `null` for future phases.
- **No premature decisions**: Does not apply composite weights (Phase 7G) or emit `MatchDecision` / `MatchConfidence` (Phase 7H).

## Consequences

### Benefits
- **Deterministic & explainable**: Every score is reproducible with clear algorithmic semantics.
- **Robust against YouTube formatting**: Handles common delimiter patterns and VEVO channel name squashing cleanly.
- **Zero duplicate-credit inflation**: Multi-artist 1-to-1 assignment ensures multi-artist tracks are evaluated fairly.
- **True phase isolation**: Completely decoupled from duration, album, version penalty, and composite ranking logic.
- **Zero Phase 5/6 regression**: Reuses frozen normalizers without modifying them.

### Tradeoffs
- Segment splitting on delimiters assumes standard conventions (` - `, ` | `, ` : `). Candidate titles using non-standard punctuation without spaces may rely on token-overlap or Levenshtein similarity.
