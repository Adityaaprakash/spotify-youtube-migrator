# Current State

Last Updated: 2026-10-13

Current Phase: Phase 7 — Matching Engine (COMPLETE / FROZEN)
Current Subphase: Phase 7I COMPLETE (Subsystem Verification & Matching Freeze — Matching Engine FROZEN)

## Completed
### Phase 7I (Subsystem Verification & Matching Freeze)
- **Subsystem-Wide Correctness & Determinism Audit**:
  - Audited full matching pipeline: `TrackMatchRequest` $\to$ Feature Extraction $\to$ Similarity Scoring (Title, Artist, Duration, Album) $\to$ Recording Version Penalties $\to$ Official Provenance $\to$ Composite Scoring with dynamic weight re-normalization $\to$ 7-level tie-breaking Ranking $\to$ Calibrated Probability Evaluation $\to$ 7-gate Match Decision $\to$ `TrackMatchResult`.
  - Audited and confirmed mathematical invariants: scores bounded in $[0.0, 1.0]$, minimum viable score $0.50$, automated match threshold $0.75$, excellent threshold $0.90$, version penalty gate $> 0.10$ forcing review for viable candidates, ambiguity margin $< 0.05$ forcing review, and logit clamping $[-40.0, 40.0]$.
  - Enforced calibration integrity: heuristic composite scores are never misrepresented as empirical probabilities; default calibrator honestly reports `configured = false, modelValidated = false, NONE, UNVALIDATED`; unvalidated models fall back safely to Phase 7G heuristic decision baseline; probability divergence gate applies strictly to empirically validated models.
  - Immutability and thread-safety: all models are immutable records with defensive copying for collections/maps; scorers and engines are pure, stateless Spring `@Component`s.
  - Backward compatibility: preserved all Phase 7G constructor and factory signatures alongside Phase 7H extensions.
  - Boundary integrity: frozen Phase 5 (`normalization`) and Phase 6 (`discovery`) production and test files remained 100% untouched.
- **Verification & Test Suite Results**:
  - `.\mvnw.cmd test-compile`: BUILD SUCCESS.
  - `.\mvnw.cmd checkstyle:check`: BUILD SUCCESS (0 violations).
  - Focused Phase 7H test suite: 71 tests passing (11 in `CalibrationResultTest`, 14 in `DefaultMatchProbabilityCalibratorTest`, 13 in `MatchDecisionEngineTest`, 7 in `TrackMatchingEngineIntegrationTest`, 17 in `TrackMatchResultTest`, 5 in `CandidateMatchEvaluationTest`).
  - Matching engine regression suite (18 test classes): 274 tests run, 0 failures, 0 errors, 0 skipped.
  - Complete matching test coverage: 24 test classes, 333 tests total, 100% passing.
  - Full non-container backend test suite: 100% passing across all domain, identity, spotify, youtube, migration, playlist, and matching modules.
  - Testcontainers integration tests blocked strictly by Docker daemon unavailability in environment.
- **Freeze Decision**:
  - The Matching Engine subsystem (Phases 7A–7H) is formally verified, hardened, and **FROZEN**.

### Phase 7H (Calibrated Match Probability and Decision Policy)
- **Phase 7H.1 (Calibration Model Contracts & Result Abstraction)**:
  - Package: `com.spotifyyoutube.migrator.matching.engine.calibration`
  - `CalibrationResult`: Immutable record distinguishing true statistical match probabilities in `[0.0, 1.0]` from heuristic similarity scores, and distinguishing configured mathematical transformations from empirically validated models.
    - Fields: `probability` (clamped to `[0.0, 1.0]` or null), `available` (boolean), `validated` (boolean), `calibrationMethod` (e.g. `PLATT_SCALING`, `NONE`), `modelVersion` (e.g. `UNVALIDATED`, `platt-v1.0`).
    - Honest baseline representation via `CalibrationResult.unavailable(reason)` (`available = false`, `validated = false`).
    - Configured unvalidated transformation factory via `CalibrationResult.configured(probability, method, modelVersion)` (`available = true`, `validated = false`).
    - Empirical validation factory via `CalibrationResult.validated(probability, method, modelVersion)` (`available = true`, `validated = true`).
    - Selective probability accessors: `calibratedProbability()` returns non-empty `Optional<Double>` *only* when `validated == true`; `configuredProbability()` returns `Optional<Double>` whenever available.
    - Strict input validation rejecting null, negative, > 1.0, NaN, and infinite values, with 4-decimal rounding.
- **Phase 7H.2 (Probability Calibration Strategy Interface & Platt Scaling)**:
  - Package: `com.spotifyyoutube.migrator.matching.engine.calibration`
  - `MatchProbabilityCalibrator`: Strategy interface defining `calibrate(Double, MatchScoreBreakdown)`, `calibrateProbability(...)`, `calibrate(CandidateMatchEvaluation)`, `isConfigured()`, `isModelValidated()`, `calibrationMethod()`, and `modelVersion()`.
  - `DefaultMatchProbabilityCalibrator`: Stateless Spring `@Component` supporting:
    - Honest uncalibrated baseline (default constructor): `isConfigured() == false`, `isModelValidated() == false`, `calibrationMethod() == "NONE"`, `modelVersion() == "UNVALIDATED"`, returning `CalibrationResult.unavailable(...)`. Avoids fabricating calibration statistics prior to Phase 8 labeled benchmark collection.
    - Configured Platt scaling: Static factory `withPlattScaling(slopeA, interceptB, modelVersion)` implementing logistic sigmoid calibration with `isConfigured() == true` and `isModelValidated() == false`, returning unvalidated configured results.
    - Validated Platt scaling: Static factory `withValidatedPlattScaling(slopeA, interceptB, modelVersion)` with `isConfigured() == true` and `isModelValidated() == true`.
    - Numerical stability safeguards: Clamped logit $[-40.0, 40.0]$ preventing exponential overflow/underflow under extreme finite parameter values or boundary scores.
- **Phase 7H.3 (Decision Policy Calibration Integration)**:
  - Package: `com.spotifyyoutube.migrator.matching.engine.decision`
  - `DefaultMatchDecisionEngine`: Transparent 7-gate decision policy incorporating calibrated probability:
    - Preserves all Phase 7G safety gates: Gate 1 (empty candidates $\to$ `NO_MATCH`), Gate 2 (version penalty $> 0.10 \to$ `REVIEW_REQUIRED`), Gate 3 (top score $< 0.50 \to$ `NO_MATCH`), Gate 4 (borderline score $0.50 \le s < 0.75 \to$ `REVIEW_REQUIRED`), Gate 5 (ambiguity margin $< 0.05 \to$ `REVIEW_REQUIRED`).
    - Gate 6 (Probability Divergence): Gated *strictly* on validated models (`isProbabilityValidated == true`). If calibrated probability is validated and $< 0.70 \to$ `REVIEW_REQUIRED`. If unvalidated or unavailable, falls back to Phase 7G heuristic rules.
    - Gate 7 (Automated Match): Produces `MATCH` with `EXCELLENT` confidence (requires $s \ge 0.90$ and $P \ge 0.85$ when calibrated) or `GOOD` confidence ($s \ge 0.75$).
    - Diagnostic transparency: Explains whether match was confirmed via calibrated probability, evaluated using heuristic rules with unvalidated transformation, or evaluated under unavailable calibration.
- **Phase 7H.4 (Pipeline Orchestration & Model Evolution)**:
  - Package: `com.spotifyyoutube.migrator.matching.engine`
  - `CandidateMatchEvaluation`: Extended with additive factory overload accepting `calibratedProbability`, preserving 100% backward compatibility.
  - `DefaultTrackMatchingEngine`: Injects `MatchProbabilityCalibrator`, evaluates candidate calibrated probabilities, populates candidate diagnostics distinguishing validated vs unvalidated probabilities, and passes calibrated values to `DefaultMatchDecisionEngine`.

### Phase 7G (Composite Scoring, Candidate Ranking, and Decision Engine)
- **Phase 7G.1 (Composite Scoring with Dynamic Re-normalization)**:
  - Package: `com.spotifyyoutube.migrator.matching.engine.scorer`
  - `CompositeScoreCalculator`: Core strategy interface for calculating overall composite score in `[0.0, 1.0]`.
  - `DefaultCompositeScoreCalculator`: Stateless Spring `@Component` implementing linear combination with proportional re-normalization:
    - Nominal weights: Title Similarity (40%), Artist Similarity (30%), Duration Alignment (10%), Album Correspondence (10%), Official Provenance (10%).
    - Dynamic re-normalization: Missing dimensions (`null`) are excluded from active weight sum; active weights re-normalize proportionally so missing evidence never dilutes scores or fabricates matches.
    - Version penalty deduction: Deducts `versionPenalty` once from weighted base score; strictly clamped to `[0.0, 1.0]`.
    - Rounded to 4 decimal places for cross-platform determinism.
    - Input validation: Rejects `NaN`, infinities, out-of-bounds scores, and invalid constructor weights.
- **Phase 7G.2 (Official Provenance Signal Scoring)**:
  - Package: `com.spotifyyoutube.migrator.matching.engine.scorer`
  - `OfficialSignalScorer`: Core interface for evaluating candidate provenance.
  - `DefaultOfficialSignalScorer`: Stateless Spring `@Component` evaluating `OfficialSignalEvidence`:
    - Verified markers (Topic channel, VEVO, verified artist match, DDEX release description, official audio/video markers): `1.0`.
    - Unverified claims alone (e.g. channel name containing "official" without verified markers): `0.0`.
    - Missing/unextracted evidence: `null` (honest missing data).
  - Integrated into `DefaultCandidateSimilarityScorer` to populate `officialSignalScore` in `MatchScoreBreakdown`.
- **Phase 7G.3 (Deterministic Candidate Ranking Engine)**:
  - Package: `com.spotifyyoutube.migrator.matching.engine.ranking`
  - `CandidateRankingEngine`: Core interface for prioritizing evaluated candidates.
  - `DefaultCandidateRankingEngine`: Stateless Spring `@Component` implementing a 7-level tie-breaking hierarchy:
    1. Overall Composite Score (descending)
    2. Title Similarity Score (descending)
    3. Artist Similarity Score (descending)
    4. Official Provenance Signal Score (descending)
    5. Version Penalty (ascending: lower penalty preferred)
    6. Duration Alignment Score (descending)
    7. Candidate Identifier (lexicographical ascending for 100% reproducible ordering)
  - Pure, non-mutating: does not modify caller input collection, returns unmodifiable list.
- **Phase 7G.4 (Transparent Match Decision Engine)**:
  - Package: `com.spotifyyoutube.migrator.matching.engine.decision`
  - `MatchDecisionEngine`: Core interface for making final migration match decisions.
  - `DefaultMatchDecisionEngine`: Stateless Spring `@Component` applying transparent rules:
    - Empty Candidates $\to$ `NO_MATCH` with reason "No destination candidates discovered".
    - Score $< 0.50$ (Below Minimum Viable Threshold) $\to$ `NO_MATCH` (`selectedCandidate = null`, `POOR` confidence).
    - Borderline Score ($0.50 \le \text{score} < 0.75$) $\to$ `REVIEW_REQUIRED` (`selectedCandidate = top`, `POOR` confidence).
    - Version Penalty Discrepancy on Viable Candidate ($p > 0.10$, $\text{baseScore} \ge 0.50$) $\to$ `REVIEW_REQUIRED` (`selectedCandidate = top`, `POOR` confidence). A detected cover, live mismatch, or variant of a viable candidate requires human review rather than silent non-match or automated approval.
    - Ambiguity Margin ($s_{\text{top}} - s_{\text{runnerUp}} < 0.05$) $\to$ `REVIEW_REQUIRED` (`GOOD` confidence) due to close competition.
    - Unambiguous Match ($\text{score} \ge 0.75$) $\to$ `MATCH` (`EXCELLENT` confidence for $\ge 0.90$, `GOOD` confidence for $0.75$ to $< 0.90$).
- **Phase 7G.5 (Top-Level Matching Pipeline Orchestration)**:
  - Package: `com.spotifyyoutube.migrator.matching.engine`
  - `TrackMatchingEngine`: Top-level interface orchestrating complete matching pipeline.
  - `DefaultTrackMatchingEngine`: Stateless Spring `@Component` coordinating:
    $\text{TrackMatchRequest} \to \text{Feature Extraction} \to \text{Similarity Scoring} \to \text{Composite Scoring} \to \text{Ranking} \to \text{Decision} \to \text{TrackMatchResult}$.
    Generates explainable diagnostic notes for candidate matches.

### Phase 7F (Recording Version & Variant Penalty Engine)
- **Phase 7F.1 (Version Penalty Scorer)**:
  - Package: `com.spotifyyoutube.migrator.matching.engine.scorer`
  - `VersionPenaltyScorer`: Strategy interface for evaluating version and variant discrepancies between source tracks and destination candidates, calculating aggregate deductions bounded in `[0.0, 1.0]` and providing itemized `calculateDetailedPenalties` breakdowns.
  - `DefaultVersionPenaltyScorer`: Stateless Spring `@Component` implementing deterministic, bounded deductions on a normalized `[0.0, 1.0]` scale:
    - `REACTION`: 0.30 deduction (creator commentary / reaction video).
    - `KARAOKE`: 0.30 deduction (backing track / sing-along version).
    - `COVER`: 0.25 deduction (unwanted alternate artist / amateur cover performance).
    - `NIGHTCORE`: 0.20 deduction (accelerated tempo + high pitch shift).
    - `SPED_UP`: 0.15 deduction (accelerated tempo / fast version).
    - `SLOWED`: 0.15 deduction (slowed down / chopped & screwed / slowed + reverb).
    - `LIVE_MISMATCH`: 0.15 deduction (live concert vs studio recording mismatch).
    - `REMIX_MISMATCH`: 0.15 deduction (remix vs original recording mismatch).
    - `ACOUSTIC_MISMATCH`: 0.10 deduction (acoustic arrangement vs standard recording mismatch).
    - `INSTRUMENTAL_MISMATCH`: 0.15 deduction (instrumental accompaniment vs vocal recording mismatch).
    - `REMASTER`: 0.00 deduction (remastered releases represent the same core recording; no penalty).
  - **Variant Alignment Policy**:
    - Source Live + Candidate Live $\to 0.0$ penalty (matching live performance).
    - Source Studio + Candidate Live $\to 0.15$ penalty (`LIVE_MISMATCH`).
    - Source Live + Candidate Studio $\to 0.15$ penalty (`LIVE_MISMATCH`).
    - Source Remix + Candidate Remix $\to 0.0$ penalty (matching remix).
    - Source Original + Candidate Remix $\to 0.15$ penalty (`REMIX_MISMATCH`).
    - Source Acoustic + Candidate Acoustic $\to 0.0$ penalty (matching acoustic).
    - Source Standard + Candidate Acoustic $\to 0.10$ penalty (`ACOUSTIC_MISMATCH`).
  - **False Positive Protection**:
    - Genuine song titles containing marker-like words (*Live to Tell*, *Live and Let Die*, *Live Forever*, *Stayin' Alive*, *Under Cover of Darkness*, *Chain Reaction*) are protected from spurious penalties (`0.0`).
    - Official video/audio markers (`(Official Video)`, `(Audio)`, `(Visualizer)`, `(Lyric Video)`) do not incur penalties (`0.0`).
  - **Multi-variant Combination & Bounding**:
    - Itemized deductions accumulate additively into `detailedPenalties` (e.g., Cover + Reaction = $0.25 + 0.30 = 0.55$).
    - Aggregate `versionPenalty` is strictly clamped to `[0.0, 1.0]`. Returns `0.0` when no discrepancies exist.
  - **Missing & Ambiguous Data**:
    - Candidates lacking version indicators return `0.0` penalty and empty `detailedPenalties`.
    - Unmatched parenthetical annotations do not trigger deductions.
- **Integration Seam (`DefaultCandidateSimilarityScorer`)**:
  - Injects `TitleSimilarityScorer`, `ArtistSimilarityScorer`, `DurationSimilarityScorer`, `AlbumSimilarityScorer`, and `VersionPenaltyScorer`.
  - Populates `versionPenalty` (`0.0` for matching recording) and `detailedPenalties` in `MatchScoreBreakdown`.
  - Preserves `officialSignalScore` honestly as `null` for Phase 7G composite confidence evaluation.
### Phase 7E (Duration & Album Similarity Scoring)
- **Phase 7E.1 (Duration Similarity Scoring)**:
  - Package: `com.spotifyyoutube.migrator.matching.engine.scorer`
  - `DurationSimilarityScorer`: Core interface for evaluating duration alignment between source and candidate tracks, returning a normalized `Double` in `[0.0, 1.0]` or `null` if duration is absent on either side.
  - `DefaultDurationSimilarityScorer`: Stateless Spring `@Component` implementing a continuous, monotonic non-increasing, piecewise decay function:
    - $\le 2.0$s offset: full `1.0` credit (tolerance window for container padding, trailing silence, encoding round-off).
    - $2.0$s to $10.0$s offset: linear decay from `1.0` to `0.85` (small provider offsets).
    - $10.0$s to $30.0$s offset: linear decay from `0.85` to `0.40` (moderate narrative intros, extended outros, radio edits).
    - $30.0$s to $60.0$s offset: linear decay from `0.40` to `0.0` (substantial edit discrepancies).
    - $> 60.0$s offset: `0.0` (disqualifying duration mismatch).
    - Symmetric, monotonic non-increasing (constant on [0s, 2s] and >60s), safe against arithmetic overflow, preserves honest missing data as `null`.
- **Phase 7E.2 (Album Similarity Scoring)**:
  - Package: `com.spotifyyoutube.migrator.matching.engine.scorer`
  - `AlbumSimilarityScorer`: Core interface for evaluating album title alignment, returning a normalized `Double` in `[0.0, 1.0]` or `null` if album metadata is absent on either side.
  - `DefaultAlbumSimilarityScorer`: Stateless Spring `@Component` implementing album comparison:
    - Constructor-injects Phase 5 `AlbumNormalizer` (Unicode NFC, case folding, curly quote/dash standardization, whitespace collapsing).
    - Extracts candidate album from structured description lines (`Album: <name>`, `Provided to YouTube by...` multi-line layout).
    - Exact normalized match: `1.0`. Whitespace-collapsed match: `1.0`.
    - Accent / diacritic folding (e.g. `Ágætis byrjun` vs `Agaetis byrjun`): `1.0`.
    - Minor typographical differences on long titles (Levenshtein $\ge 0.88$, length diff $\le 2$): normalized edit distance.
    - Protection against generic collisions (`Queen: Greatest Hits` vs `Blink-182: Greatest Hits`) and substring collisions (`War` vs `Star Wars`): strictly `0.0`.
    - Preserves honest missing data as `null` when album evidence is absent on either side.
- **Integration Seam (`DefaultCandidateSimilarityScorer`)**:
  - Injects `TitleSimilarityScorer`, `ArtistSimilarityScorer`, `DurationSimilarityScorer`, and `AlbumSimilarityScorer`.
  - Populates `titleScore`, `artistScore`, `durationScore`, and `albumScore` in `MatchScoreBreakdown`.
  - Preserves future dimensions (`officialSignalScore`, `versionPenalty`) honestly as `null`.

### Phase 7C & 7D (Title Similarity & Artist Similarity Scoring)
- **Phase 7C (Title Similarity Scoring)**:
  - Package: `com.spotifyyoutube.migrator.matching.engine.scorer`
  - `TitleSimilarityScorer`: Core interface for evaluating source vs. candidate title similarity, taking `SourceTrackFeatures` and `CandidateTrackFeatures` and returning a normalized `Double` in `[0.0, 1.0]` or `null` if title evidence is absent.
  - `DefaultTitleSimilarityScorer`: Stateless Spring `@Component` implementing multi-signal normalized title similarity:
    - Exact match fast-path: returns `1.0`.
    - Character-level Levenshtein similarity: normalized edit distance `1.0 - (distance / max(len1, len2))`.
    - Token-sort similarity: whitespace-split token sort permutation tolerance capped at `0.95` when characters differ to reward true canonical word order over scrambled tokens.
    - Delimiter segment matching: handles common YouTube title conventions (` - `, ` | `, ` : `) by checking segment candidates (e.g. `daft punk - get lucky` matched against `get lucky`).
    - Token overlap / Jaccard similarity: measures set overlap of whitespace tokens.
    - Short-title safeguard: titles with length <= 3 (e.g. "XO", "Why") strictly constrain substring and partial-token matches, requiring high edit distance to avoid false positives.
    - Preserves honest missing data as `null`.
- **Phase 7D (Artist Similarity Scoring)**:
  - Package: `com.spotifyyoutube.migrator.matching.engine.scorer`
  - `ArtistSimilarityScorer`: Core interface for evaluating source vs. candidate artist similarity, returning a normalized `Double` in `[0.0, 1.0]` or `null` if artist evidence is absent.
  - `DefaultArtistSimilarityScorer`: Stateless Spring `@Component` implementing multi-artist credit matching:
    - Constructor-injects Phase 5 `ArtistNormalizer` to normalize candidate channel names and video title artist credits.
    - Exact primary artist match: returns `1.0`.
    - Official channel equivalence: reuses Phase 7B `OfficialSignalEvidence.isArtistVerifiedName()` (handling VEVO channels and Topic channels where spaces are stripped) to award `1.0` for verified official artist channels.
    - Multi-artist optimal 1-to-1 bipartite assignment: matches candidate artist credits against source discrete artists with exact optimal assignment, completely preventing duplicate-credit inflation and supporting inverted artist orders (`0.90`).
    - Canonical Unicode & diacritic folding: standardizes accented Latin names (`Beyoncé` vs `Beyonce`, `Sigur Rós` vs `Sigur Ros`, `Björk` vs `Bjork`) to `1.0` using NFD diacritic stripping.
    - Primary vs. featured priority: primary match weighted heavily; when primary artists differ, a shared featured artist alone is strictly bounded and capped at `0.35` (or `0.40` for multiple shared featured artists) to prevent featured-only false matches.
    - Substring collision safeguards: strict length-bounded edit distance thresholds prevent superficial substring collisions (`Future` vs `Afterlife`, `The Who` vs `Wham!`, `Iron & Wine` vs `Iron Maiden`) from receiving spurious credit (`0.0` or `< 0.50`).
    - Preserves honest missing data as `null`.
- **Integration Seam (`CandidateSimilarityScorer`, `DefaultCandidateSimilarityScorer`)**:
  - Package: `com.spotifyyoutube.migrator.matching.engine.scorer`
  - Composition component that independently evaluates title and artist similarity and populates `MatchScoreBreakdown` (`titleScore`, `artistScore`), preserving future fields (`durationScore`, `albumScore`, `officialSignalScore`, `versionPenalty`) as `null`.
  - Zero premature ranking, decision assignment, or calibrated probability computation.

### Phase 7A & 7B (Matching Architecture, Immutable Contracts & Candidate Feature Extraction)
- **Phase 7A (Matching Engine Architecture & Contracts)**:
  - Package: `com.spotifyyoutube.migrator.matching.engine`
  - `MatchDecision`: Provider-neutral matching outcome enum (`MATCH`, `REVIEW_REQUIRED`, `NO_MATCH`) establishing the decision vocabulary for the matching pipeline without early assignment.
  - `MatchConfidence`: Qualitative confidence classification enum (`EXCELLENT`, `GOOD`, `POOR`), kept strictly distinct from numeric scoring and calibrated probability.
  - `MatchScoreBreakdown`: Immutable component evidence record holding nullable component scores (`titleScore`, `artistScore`, `durationScore`, `albumScore`, `officialSignalScore`, `versionPenalty`) and an unmodifiable `detailedPenalties` map. Preserves absent data honestly as `null` rather than defaulting to `0.0`.
  - `CandidateMatchEvaluation`: Immutable evaluation record encapsulating individual destination candidates, extracted comparison features, component score breakdown, overall score, calibrated probability, qualitative confidence, and explainable diagnostic reasons.
  - `TrackMatchRequest`: Immutable input contract taking `NormalizedTrackMetadata` (from Phase 5), unmodifiable `List<DestinationTrackCandidate>` (from Phase 6), and optional `sourceTrackId` (preserving Spotify track identity without modifying the pure normalization model). Formally defines empty candidate collections as valid input that cleanly resolves to `NO_MATCH` downstream.
  - `TrackMatchResult`: Immutable engine outcome contract enforcing selected-candidate consistency against candidate evaluations, preserving honest `null` values for uncalculated scores, and providing explainable diagnostic reasons.
- **Phase 7B (Candidate Feature Extraction)**:
  - Package: `com.spotifyyoutube.migrator.matching.engine.feature`
  - `VersionIndicator`: Standardized recording/playback indicator enum (`COVER`, `LIVE`, `REMIX`, `KARAOKE`, `REACTION`, `NIGHTCORE`, `SPED_UP`, `SLOWED`, `ACOUSTIC`, `INSTRUMENTAL`, `REMASTER`).
  - `CandidateVersionEvidence`: Immutable record holding detected indicators, raw matching tokens, unclassified annotations, conflict flags, and ambiguity flags.
  - `OfficialSignalEvidence`: Provider-neutral authenticity evidence evaluating auto-generated Topic channels (` - Topic`), VEVO syndication (`VEVO`), primary artist channel matches, label delivery descriptions (`Provided to YouTube by...`), and verified title markers.
  - **Conservative Authenticity Policy**: Channels claiming "official" without verified provider markers are captured in `channelClaimsOfficial` but strictly excluded from confirming authenticity in `hasOfficialEvidence()`.
  - **False Positive Protection**: Word-boundary safeguards prevent misclassifying legitimate song titles like *Live to Tell*, *Stayin' Alive*, *Under Cover of Darkness*, or *Chain Reaction* as recording variants.
  - `SourceTrackFeatures`: Immutable feature record extracting normalized title, clean title, primary artist, discrete artists, album, duration (with exact millisecond representation), and version indicators directly from Phase 5 `NormalizedTrackMetadata`.
  - `CandidateTrackFeatures`: Immutable feature record extracting normalized title, clean title, normalized channel/artist, duration, description, publication date, version evidence, and official signals from Phase 6 `DestinationTrackCandidate`.
  - `TrackFeatureExtractor` & `DefaultTrackFeatureExtractor`: Pure, deterministic, stateless Spring `@Component` reusing Phase 5 `TitleNormalizer`, `ArtistNormalizer`, and `MetadataTokenizer` without altering frozen Phase 5 or Phase 6 code.
- **Phase Boundary Discipline**:
  - Zero similarity scoring, weighted 40/30/10/10/10 composite scoring, candidate ranking, confidence assignment, or automatic selection logic implemented.
  - Phase 5 and Phase 6 production source files remain 100% untouched and frozen.

### Phase 6G (Final Candidate Discovery Audit, Hardening & Phase Freeze)
- **Credential & Token Sanitization**:
  - `DefaultCandidateDiscoveryService` implements sanitization of query execution error diagnostics to prevent leaking API keys (`key=***`, `api_key=***`), tokens (`token=***`, `access_token=***`), secrets (`client_secret=***`, `password=***`), Bearer tokens (`Bearer ***`), Basic auth (`Authorization: Basic ***`), or URL parameters with surrounding delimiters.
- **Edge-Case Hardening & Integration Verification**:
  - Empty-first query handling: verified that an initial query yielding zero results does not disrupt subsequent queries; downstream queries proceed normally to fulfill candidate quotas and yield `SUCCESS`.
  - Failed-first query handling: verified that an initial query failing due to transient network or provider errors preserves subsequent candidates and appropriately produces `PARTIAL_FAILURE`.
  - Missing duration resilience: candidates lacking duration or optional metadata fields flow cleanly through the pipeline without NPE or synthetic coercion.
- **Test Suite Metrics**:
  - `CandidateDiscoveryIntegrationTest` expanded to 12 comprehensive end-to-end integration test scenarios.
  - 133 total candidate discovery tests passing (34 in `DefaultCandidateDiscoveryServiceTest`, 29 in `DefaultSearchQueryGeneratorTest`, 12 integration tests, 58 contracts & models).
  - 360 total matching subsystem tests (227 normalization + 133 discovery) passing.
  - Full non-container test suite passes cleanly with 0 failures and 0 errors.
  - Zero Checkstyle violations across all packages.
- **Phase 6 Freeze**: Candidate Discovery subsystem is complete, audited, robust, and frozen for Phase 7 consumption.

### Phase 6E & 6F (Candidate Filtering, Discovery Pipeline Integration & Verification)
- **Candidate Validation & Trimming**:
  - `DestinationTrackCandidate` enforces canonical ID trimming in compact constructor (`id = id != null ? id.trim() : null`).
  - Candidate deduplication keys in `CandidateDiscoveryResult` and `DefaultCandidateDiscoveryService` canonicalize IDs before map indexing to ensure whitespace padding cannot bypass deduplication.
  - Zero-filtering policy for legitimate track variants: alternate audio versions, live recordings, remasters, and remixes are safely preserved as discovery candidates for subsequent Phase 7 matching.
- **End-to-End Pipeline Integration (`CandidateDiscoveryIntegrationTest`)**:
  - Full flow validated: `NormalizedTrackMetadata` -> `SearchQueryGenerator` -> `MusicDestination` -> `DefaultCandidateDiscoveryService` -> `CandidateDiscoveryResult`.
  - Multi-query orchestration verified with real query formulation (`PRIMARY_TITLE_ARTIST`, `TITLE_ARTIST_FEATURED`, `TITLE_ARTIST_ALBUM`).
  - Strict quota preservation verified: secondary queries are suppressed when primary queries satisfy `maxCandidates`.
  - Continued execution verified when duplicates leave unique candidate counts below `maxCandidates`.
  - Partial provider failure resilience verified: candidates discovered prior to 403 quota exhaustion are preserved with `PARTIAL_FAILURE` status.
  - Total provider outages verified to cleanly produce `FAILED` status with error diagnostics.

### Phase 6D (Candidate Mapping & Metadata Enrichment)
- **Candidate Model Enrichment**:
  - `DestinationTrackCandidate` in `com.spotifyyoutube.migrator.migration.application.destination.model` updated with `java.time.Duration duration`.
  - Backward compatibility preserved via 6-argument constructor delegating with `null` duration.
  - Added convenience constructor parsing ISO-8601 strings into `Duration` (e.g., `PT3M45S` -> 225s, `PT1H2M3S` -> 3723s).
  - Added `fromDetails(DestinationTrackDetails)` factory method converting details and parsing duration safely.
  - Strict duration semantics: negative durations rejected/handled safely, null/blank strings preserved as `null` (never coerced to `Duration.ZERO`), legitimate `Duration.ZERO` preserved, malformed ISO-8601 strings safely yield `null` without throwing.
- **Provenance & Accessor Extension**:
  - `DiscoveredCandidate` exposes `duration()` accessor delegating to candidate duration.
- **Provider Isolation & Zero-Quota-Overhead Mapping**:
  - Zero additional API calls introduced during candidate discovery search (0 extra quota units consumed). Search candidates retain `null` duration until explicitly hydrated or inspected.
  - `YouTubeMusicDestinationAdapter.searchTracks()` filters out `null` items, items with `null` video IDs, and items with blank video IDs, while trimming valid video IDs.
  - `YouTubeMapper.toSummaryResponse()` filters out non-video records and blank video IDs, and trims video IDs.
  - Malformed provider records do not corrupt or crash candidate discovery. Provider outages continue to propagate to `DefaultCandidateDiscoveryService` for proper error classification.

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
- Matching Evaluation & Benchmarking (Phase 8).
- Migration Orchestration Engine & Progress Tracking (Phase 9 & Phase 12).
- Review & Correction Interface (Phase 10).
- Playlist Creation (Phase 11).

## Current Verification
- Backend Compilation: `.\mvnw.cmd test-compile` — **PASS**
- Checkstyle: `.\mvnw.cmd checkstyle:check` — **PASS** (0 violations)
- Focused Phase 7H Test Suite: **PASS** (71 tests passing across `CalibrationResultTest`, `DefaultMatchProbabilityCalibratorTest`, `MatchDecisionEngineTest`, `TrackMatchingEngineIntegrationTest`, `TrackMatchResultTest`, `CandidateMatchEvaluationTest`)
- Matching Engine Regression Suite (18 test classes): **PASS** (274 tests run, 0 failures, 0 errors, 0 skipped in 12.912s)
- Complete Matching Subsystem Coverage: 24 test classes, 333 tests total, 100% passing across feature extraction, individual scorers, version penalties, composite calculation, ranking, calibration, decision policy, and pipeline integration.
- Focused Candidate Discovery & Mapping Suite: **PASS** (133 tests passing across discovery and mapping)
- Focused Normalization Test Suite: **PASS** (227 tests passing)
- Full Non-Repository Unit Test Suite: **PASS** (100% passing across domain, identity, spotify, youtube, migration, playlist, matching)
- Repository/Flyway Integration Tests (`PlaylistRepositoryTest`, `UserRepositoryTest`, `MigratorApplicationTests`): **BLOCKED** — Docker daemon unavailable in environment

## Phase Freeze Status
- **Phase 2 (Auth & Identity)**: FROZEN
- **Phase 3 (Spotify Integration)**: FROZEN
- **Phase 4 (YouTube Integration)**: COMPLETE / FROZEN
- **Phase 5 (Track Normalization Engine)**: COMPLETE / FROZEN
- **Phase 6 (Candidate Discovery Subsystem)**: COMPLETE / FROZEN
- **Phase 7 (Matching Engine Subsystem)**: COMPLETE / FROZEN
  - **Phase 7A (Matching Architecture & Contracts)**: COMPLETE / FROZEN
  - **Phase 7B (Candidate Feature Extraction)**: COMPLETE / FROZEN
  - **Phase 7C (Title Similarity Scoring)**: COMPLETE / FROZEN
  - **Phase 7D (Artist Similarity Scoring)**: COMPLETE / FROZEN
  - **Phase 7E (Duration & Album Similarity Scoring)**: COMPLETE / FROZEN
  - **Phase 7F (Recording Version & Variant Penalty Engine)**: COMPLETE / FROZEN
  - **Phase 7G (Composite Scoring, Ranking & Decision Engine)**: COMPLETE / FROZEN
  - **Phase 7H (Calibrated Match Probability & Decision Policy)**: COMPLETE / FROZEN
  - **Phase 7I (Subsystem Verification & Matching Freeze)**: COMPLETE / FROZEN

## Next Recommended Work
1. Phase 8: Matching Evaluation & Benchmark (empirical labeled dataset collection, Brier score evaluation, Platt scaling parameter fitting).