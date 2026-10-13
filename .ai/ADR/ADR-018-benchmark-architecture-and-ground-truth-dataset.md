# ADR-018: Benchmark Architecture and Ground-Truth Dataset (Phase 8A + 8B)

## Context

Following the freeze of the Phase 7 matching engine (Phases 7A–7I), evaluating the matching subsystem's empirical precision, recall, and calibration requires a rigorous benchmark foundation.

Prior to Phase 8:
1. **Lack of Trustworthy Ground Truth**:
   - The repository possessed synthetic unit tests and component fixtures, but no formal, verifiable ground-truth dataset linking source Spotify tracks to audited target YouTube videos.
   - Without an immutable, auditable ground-truth dataset, calculating Top-1 accuracy, Top-3 accuracy, precision, recall, false positive rates, or fitting probability calibration models was impossible.
2. **Phase Boundary and Production Isolation**:
   - The Phase 7 matching engine is frozen. Phase 8 must evaluate matching performance without modifying Phase 5 normalization, Phase 6 discovery, or Phase 7 scoring and decision logic.
   - Benchmark evaluation logic must remain strictly decoupled from production runtime dependencies (no database tables, no remote network calls, no runtime overhead).
3. **Ambiguity and Provenance Requirements**:
   - Real-world music matching features complex edge cases: cover versions, live recordings, acoustic renditions, clean/explicit variants, remixes, official videos vs. Topic channel uploads, and tracks with identical titles by different artists.
   - Positive matches (`MATCH`) and legitimate no-match cases (`NO_MATCH`) must be explicitly distinguished. Missing matches must not be represented as null positive matches.
   - Every human-verified ground-truth record must contain traceable verification evidence rather than unsubstantiated claims.

## Decision

We implement Phase 8A (Benchmark Architecture and Dataset Contract) and Phase 8B (Ground-Truth Benchmark Dataset) together under package `com.spotifyyoutube.migrator.matching.benchmark` and classpath resource `benchmark/ground_truth_benchmark.json`:

### 1. Benchmark Domain Model (Phase 8A)

The model is composed of immutable Java 22 records and enums:

- **`BenchmarkExpectedOutcome`**: Strongly typed outcome enum (`MATCH`, `NO_MATCH`) with query predicates `isMatch()` and `isNoMatch()`.
- **`BenchmarkVerificationStatus`**: Audit verification status (`VERIFIED`, `UNVERIFIED`, `DISPUTED`).
- **`BenchmarkDatasetSplit`**: Evaluation partition (`DEV`, `VALIDATION`, `TEST`).
- **`BenchmarkTrackCategory`**: Comprehensive typology classifying 17 track categories:
  - `STANDARD_POPULAR`, `OFFICIAL_MUSIC_VIDEO`, `OFFICIAL_AUDIO`, `TOPIC_CHANNEL`, `FEATURED_ARTIST`, `BAND_OR_COLLABORATION`, `REMIX`, `LIVE_VERSION`, `ACOUSTIC`, `COVER_VERSION`, `REMASTER`, `EXPLICIT_OR_CLEAN`, `DIACRITICS_OR_PUNCTUATION`, `SHORT_OR_AMBIGUOUS_TITLE`, `SAME_TITLE_DIFFERENT_ARTIST`, `DURATION_DISCREPANCY`, `LEGITIMATE_NO_MATCH`.
- **`BenchmarkSourceTrack`**: Source Spotify metadata with defensive unmodifiable artists list, title, primary artist, album, duration in milliseconds, explicit flag, and ISRC.
- **`BenchmarkTargetCandidate`**: Target YouTube candidate with video ID, video title, channel title, duration in milliseconds, channel ID, and official channel flag.
- **`BenchmarkExample`**: Core benchmark unit enforcing strict invariants:
  - Stable non-blank ID (e.g. `BM-0001`).
  - Positive match (`MATCH`) requires a non-null `targetTrack`.
  - Non-match (`NO_MATCH`) strictly forbids `targetTrack`.
  - `VERIFIED` status requires non-blank audit verification evidence.
- **`BenchmarkDataset`**: Top-level dataset container record holding semantic version, description, creation timestamp, and immutable list of examples. Provides filtering and query utilities (`getExampleById`, `getExamplesBySplit`, `getExamplesByOutcome`, `getExamplesByStatus`, `getExamplesByCategory`, `summary()`).
- **`BenchmarkDatasetSummary`**: Telemetry summary capturing total examples, counts by outcome, verification status, split, and category.

### 2. Validation and Integrity Rules (Phase 8A)

Enforced deterministically by `BenchmarkDatasetValidator`:
- **Semver Compliance**: Dataset version must conform to semantic versioning (e.g. `1.0.0`).
- **Referential Uniqueness**: Every benchmark example ID must be strictly unique.
- **Contract Adherence**: Mandatory non-blank source title and primary artist, positive durations, positive matches having target candidate, no-match examples having null target candidate.
- **Audit Compliance**: `VERIFIED` examples require non-blank `verificationEvidence`.
- **Integrity Warnings**: Non-fatal anomalies flagged as actionable warnings:
  - Repeated source track IDs across multiple examples.
  - Collisions of artist + title within the same dataset split.
  - Video IDs that do not conform to standard 11-character YouTube format.
- **Validation Exceptions**: `validateAndThrow()` halts dataset loading on any fatal error with itemized diagnostic descriptions.

### 3. Offline Serialization and Loading (Phase 8A)

Implemented by `BenchmarkDatasetLoader`:
- Offline loading from classpath (`/benchmark/ground_truth_benchmark.json`), file paths, input streams, or JSON strings.
- Uses Jackson ObjectMapper configured to ignore unknown properties and sort entries deterministically.
- Automatically executes `BenchmarkDatasetValidator.validateAndThrow` upon deserialization.
- Provides round-trip serialization (`toJson`) for reproducible dataset management.

### 4. Ground-Truth Dataset (Phase 8B)

The initial dataset (`ground_truth_benchmark.json`, version `1.0.0`) contains **118 distinct examples** exceeding the target of 100 tracks:
- **Total Examples**: 118
- **Matches (`MATCH`)**: 105
- **Legitimate Non-Matches (`NO_MATCH`)**: 13
- **Human-Verified (`VERIFIED`)**: 117 (auditable verification evidence provided)
- **Unverified (`UNVERIFIED`)**: 1 (`BM-0029`: performer under review)
- **Disputed (`DISPUTED`)**: 0
- **Dataset Splits**:
  - `DEV`: 52 examples (~44.1%)
  - `VALIDATION`: 36 examples (~30.5%)
  - `TEST`: 30 examples (~25.4%)
- **Typology Diversity**: All 17 defined categories are represented.

### 5. Future Phase 8C Integration Contract

Phase 8A and 8B intentionally decouple the benchmark data model from the evaluation execution. Phase 8C runner will interact with this layer through the following contract:
1. **Runner Inputs**:
   - `BenchmarkDataset` loaded via `BenchmarkDatasetLoader.loadDefault()`.
   - Frozen production `TrackMatchingEngine` orchestrated with candidate discovery or pre-discovered fixture candidates.
2. **Runner Execution**:
   - For each `BenchmarkExample`:
     - Construct a `TrackMatchRequest` from `example.sourceTrack()`.
     - Execute `matchingEngine.match(request, candidatePool)`.
     - Record predicted match candidate, predicted decision (`MATCH`, `REVIEW_REQUIRED`, `NO_MATCH`), probability calibration result, score breakdown, and execution latency.
3. **Evaluation Metrics**:
   - Distinguishes ground truth (`example.targetTrack()`), candidate set, predicted candidate, decision policy, and verification status.
   - Calculates Top-1 accuracy, Top-3 accuracy, precision, recall, false positive rate, no-match detection accuracy, and calibration Brier score across splits and typologies.

## Consequences

### Positive
- Fully reproducible, offline, and version-controlled evaluation foundation.
- Frozen Phase 5, Phase 6, and Phase 7 codebases remain completely untouched.
- Clean separation between ground truth, system predictions, and review statuses.
- Defensible, traceable ground truth for 117 verified tracks plus 1 flagged candidate.

### Neutral / Trade-offs
- Dataset currently contains 118 tracks; while sufficient for initial benchmark evaluation, statistical significance for Platt scaling will require ongoing expansion.
- 1 record (`BM-0029`) remains `UNVERIFIED` and should be excluded from strict acceptance benchmarks until audited.
