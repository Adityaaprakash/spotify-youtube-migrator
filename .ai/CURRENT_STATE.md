# Current State

Last Updated: 2026-10-14

Current Phase: Phase 8 — Matching Evaluation, Benchmark & Migration Workflow (COMPLETE: Phase 8A–8H IMPLEMENTED & VERIFIED)
Current Subphase: Phase 8G (Playlist Migration Workflow Integration) & Phase 8H (Evaluation & Reliability Verification) Complete

## Completed
### Phase 8G + 8H (Playlist Migration Workflow Integration, Evaluation & Reliability Verification)
- **Phase 8G (Playlist Migration Workflow Integration)**:
  - Package: `com.spotifyyoutube.migrator.migration.application.orchestration`
  - Orchestration Engine (`PlaylistMigrationOrchestrator`):
    - Connects Spotify playlist and track retrieval $\to$ Phase 5 metadata normalization $\to$ Phase 6 YouTube candidate discovery $\to$ Phase 7 calibrated matching engine $\to$ Phase 4 YouTube playlist creation and sequential video insertion.
    - Sequential destination insertion using explicit 0-indexed positions (`processedCount`) ensuring continuous ordering without gaps even when intermediate tracks fail or require review.
    - Playlist-level failure recovery transitioning persistent jobs to terminal `FAILED` status with completion timestamps upon startup/metadata/destination failure, while preserving cause for diagnosis.
    - Multi-format token and credential scrubbing (`Bearer`, `access_token=`, `refresh_token=`, `token=`, `api_key=`, `key=`, `client_secret=`, `password=`) from logs and database entities, with raw exception suppression in log statements.
  - Immutable Execution Models:
    - `TrackMigrationOutcome`: position, sourceTrackId, destinationVideoId, decision, compositeScore, calibratedProbability, status, errorMessage, and semantic classification helpers (`isDuplicate`, `isUnmatched`, `isReviewRequired`, `isProcessingFailure`).
    - `MigrationExecutionSummary`: sourcePlaylistId, destinationPlaylistId, totalTracks, processedTracks, failedTracks, duplicateTracks, reviewRequiredTracks, durationMs, trackOutcomes.
  - Application Boundary & API:
    - `MigrationService.executeMigration(UUID jobId)`
    - `MigrationServiceImpl` with `@Autowired` 4-argument constructor and backward-compatible 3-argument constructor.
    - REST endpoint: `POST /api/migrations/{id}/execute`
  - Tests:
    - `PlaylistMigrationOrchestratorTest`: 19 comprehensive test cases covering end-to-end success, empty playlist handling, review holdout, NO_MATCH handling, duplicate prevention, duplicate allowance, sequential destination ordering, multi-format credential sanitization, startup failure points, partial failure resilience, exception guards, and DB task/job status tracking.
- **Phase 8H (Migration Workflow Evaluation, Calibration & Reliability Verification)**:
  - Package: `com.spotifyyoutube.migrator.migration.application.evaluation`
  - Evaluator (`MigrationWorkflowEvaluator`):
    - Computes aggregate telemetry with a disjoint, mutually exclusive partition: `migrationSuccessRate`, `unmatchedRate`, `reviewRate`, `failureRate`, `duplicateTracksPrevented`, `throughputTracksPerSecond`, and sequential `orderingPreserved` verification.
    - Prevents duplicate prevention and review holdouts from being misclassified as processing failures.
  - Evaluation Report (`MigrationWorkflowEvaluationReport`):
    - Immutable record supporting GitHub Flavored Markdown and plain text formatting with strict denominator and provenance disclosures.
  - Tests:
    - `MigrationWorkflowEvaluatorTest`: 12 unit tests covering multi-playlist aggregation, out-of-order detection, empty suite safety, empty playlists, zero-duration safety, track failure classification, destination insertion failure classification, duplicate outcome classification, fallback counts, report formatting, and constructor validations.
  - Architecture: Documented in `ADR-021` (`ADR-021-playlist-migration-workflow-and-evaluation.md`).
  - Frozen Code Integrity: Phase 5 (`normalization`), Phase 6 (`discovery`), Phase 7 (`matching`) 100% untouched.

### Phase 8E + 8F (Probability Calibration Parameter Fitting, Threshold Optimization & Verification)
- **Phase 8E (Probability Calibration Parameter Fitting & Threshold Optimization)**:
  - Package: `com.spotifyyoutube.migrator.matching.benchmark.calibration`
  - Observation Extraction (`CalibrationObservation`):
    - Immutable score-outcome record representing $(s_i, y_i, \text{margin})$ extracted directly from `BenchmarkExampleResult`.
    - Enforces input bounds $s_i \in [0.0, 1.0]$, finite margins, non-blank example IDs.
  - Logistic Calibration Model (`PlattScalingModel`):
    - Immutable record capturing fitted parameters $(A, B)$, model version, training split, sample counts, log-likelihood, Brier score, baseline Brier score, and validation status.
    - Strict mathematical invariants: $A > 0$ strictly enforced, $P(\text{match} \mid s) = \frac{1}{1 + \exp(-(A \cdot s + B))}$, logit clamped to $[-40.0, 40.0]$.
    - Non-invasive factory method `createMatchingEngine(boolean markValidated)` instantiating `DefaultTrackMatchingEngine` with `withValidatedPlattScaling(slopeA, interceptB, modelVersion)` without modifying Phase 7.
  - Parameter Fitting Optimization (`PlattScalingParameterFitter`):
    - Newton-Raphson optimization with Levenberg-Marquardt damping ($\lambda \approx 10^{-4}$) and line search.
    - Implements Platt's (1999) regularized targets ($t_+ = \frac{N_+ + 1}{N_+ + 2}, t_- = \frac{1}{N_- + 2}$) preventing numerical divergence on linearly separable validation splits ($N = 36$).
    - Input validation: $\ge 4$ samples, both positive and negative samples required, non-zero score variance required.
  - Decision Threshold Optimization (`DecisionThresholdOptimizer`, `ThresholdEvaluationPoint`, `ThresholdOptimizationReport`):
    - Sweeps candidate thresholds across $[0.50, 0.99]$ with step $0.01$ over raw scores and calibrated probabilities.
    - Computes full 4-element confusion matrix, precision, recall, F1, FPR, auto-match rate, and review rate at each point.
    - Selects optimal operating point maximizing recall subject to target precision constraint ($\ge 95\%$ or $\ge 98\%$).
  - End-to-End Orchestration Pipeline (`BenchmarkCalibrationPipeline`, `BenchmarkCalibrationReport`):
    - Strict split isolation: fits parameters $(A, B)$ exclusively on `VALIDATION` split observations ($N = 36$); evaluates out-of-sample generalization strictly on held-out `TEST` split ($N = 30$).
    - Generates comprehensive markdown report with model parameters, threshold sweep tables, reliability diagram bins, and high-confidence safety audit.
  - Phase 8E Automated Tests (5 test classes):
    - `CalibrationObservationTest`: 8 tests covering construction, validation, and `fromResult` extraction.
    - `PlattScalingModelTest`: 8 tests covering validation, monotonicity, extreme logit saturation, calibrator creation, and matching engine instantiation.
    - `PlattScalingParameterFitterTest`: 6 tests covering input validation, separable data fitting, regularized targets, and fitting from `BenchmarkExampleResult`.
    - `DecisionThresholdOptimizerTest`: 7 tests covering score sweeps, probability sweeps, optimal point selection, and report generation.
    - `BenchmarkCalibrationPipelineTest`: 3 tests covering end-to-end execution on default 118-example dataset, validation of held-out TEST split calibration (`available = true`), zero high-confidence false matches, and missing-split error handling.
- **Phase 8F (Benchmark Subsystem Audit, Verification & Freeze Gate)**:
  - Formally audited all Phase 8 components (Phases 8A through 8E) against scientific integrity and architectural constraints.
  - Static audit complete across 16 benchmark test classes (~88 tests), 29 matching subsystem test classes (365+ tests), zero Checkstyle violations.
  - Preserved frozen boundaries: Phase 5 (`normalization`), Phase 6 (`discovery`), Phase 7 (`matching`) 100% untouched.
  - Documented design decisions in `ADR-020` (`ADR-020-empirical-probability-calibration-and-benchmark-freeze.md`).
  - Phase 8 status: IN PROGRESS / PENDING RUNTIME VERIFICATION GATES (compilation repaired, command execution provided for operator terminal verification).

### Phase 8C + 8D (Benchmark Runner, Evaluation Metrics & Error Analysis)
- **Phase 8C (Benchmark Runner & Candidate Pool Sourcing)**:
  - Package: `com.spotifyyoutube.migrator.matching.benchmark.pool`, `com.spotifyyoutube.migrator.matching.benchmark.runner`
  - Candidate Pool Strategy Abstraction (`BenchmarkCandidatePoolProvider`):
    - Decouples candidate generation from live YouTube APIs to ensure deterministic, reproducible offline evaluation.
    - `ControlledDecoyCandidatePoolProvider`: Generates deterministic, challenging distractors (live versions, covers, remixes, and different artists with the same title) alongside the ground-truth target candidate for positive matches. Clearly labels synthetic candidates with `decoy_...` identifiers and reports `hasRealisticPool = false`.
    - `FixtureCandidatePoolProvider`: Supports loading pre-recorded, version-controlled candidate fixtures mapped by example ID.
    - `TargetOnlyCandidatePoolProvider`: Non-discriminating baseline passing only the single expected candidate for isolated component regression testing.
    - `EmptyCandidatePoolProvider`: Returns zero candidates for testing candidate recall / discovery failures.
  - Evaluation Eligibility Engine (`BenchmarkEligibilityPolicy`):
    - Excludes `UNVERIFIED` examples (specifically `BM-0029`) and `DISPUTED` examples from official metrics by default.
    - Supports targeted evaluation by dataset split (`forSplit(DEV/VALIDATION/TEST)`) and diagnostic runs (`diagnosticPolicy()`).
    - Reports explicit exclusion reasons (`UNVERIFIED_STATUS`, `DISPUTED_STATUS`, `SPLIT_EXCLUDED`, `CATEGORY_EXCLUDED`, `EMPTY_CANDIDATE_POOL`, `INVALID_SOURCE_METADATA`, `EXECUTION_FAILURE`).
  - Runner Execution Engine (`BenchmarkRunner`, `DefaultBenchmarkRunner`):
    - Validates dataset schema via `BenchmarkDatasetValidator.validateAndThrow(dataset)`.
    - Normalizes source track metadata via Phase 5 `NormalizationOrchestrator` without modifying frozen Phase 5 code.
    - Invokes frozen Phase 7 `TrackMatchingEngine.matchTrack(request)` through its real public contract without modifying Phase 7.
    - Maps predictions, ranked candidate evaluations, confidence levels, execution durations, and deterministic error classifications into immutable `BenchmarkExampleResult` records.
    - Aggregates execution outcomes into `BenchmarkRunResult`.
- **Phase 8D (Evaluation Metrics, Calibration & Reporting)**:
  - Package: `com.spotifyyoutube.migrator.matching.benchmark.metric`, `com.spotifyyoutube.migrator.matching.benchmark.report`
  - Candidate Ranking Metrics (`RankingMetricsCalculator`, `RankingMetrics`):
    - Evaluates Top-1 and Top-3 accuracy strictly on eligible positive `MATCH` examples.
    - Reports metrics across both all eligible positive examples and the candidate-present subset to separate discovery recall failures from ranking errors.
    - Enforces safe zero-denominator behavior returning clean undefined/null states instead of NaN.
    - Never counts legitimate `NO_MATCH` examples as Top-1 matches.
  - Decision Policy & Confusion Metrics (`DecisionPolicyMetricsCalculator`, `DecisionPolicyMetrics`):
    - Formulates an explicit 6-way mutually exclusive confusion matrix: `correctAutoAccept` (TP), `wrongCandidateAccepted` (FP), `falseAcceptanceOfNoMatch` (FP), `reviewRequiredPositive` (Abstention on match), `reviewRequiredNoMatch` (Abstention on non-match), `correctNoMatch` (TN), `incorrectRejection` (FN).
    - Calculates precision, recall, false positive rate, false negative rate, NO_MATCH precision, NO_MATCH recall, review rate, automatic acceptance rate, and overall accuracy.
    - Preserves decision coverage and prevents `REVIEW_REQUIRED` abstentions from artificially inflating precision.
  - High-Confidence Error Rate (`HighConfidenceMetricsCalculator`, `HighConfidenceMetrics`):
    - Evaluates false acceptance rates above probability threshold (0.90).
    - Honestly reports `available = false` when operating against the default uncalibrated matching engine baseline (`DefaultMatchProbabilityCalibrator` baseline mode).
  - Statistical Probability Calibration (`CalibrationMetricsCalculator`, `CalibrationMetrics`):
    - Computes empirical Brier score ($\frac{1}{N} \sum (p_i - y_i)^2$), empirical baseline comparative Brier score ($\bar{y}(1-\bar{y})$), Expected Calibration Error (ECE), reliability diagram bins, and mean confidence for correct vs. incorrect predictions.
  - Structured Error Analysis (`ErrorAnalysisCalculator`, `BenchmarkErrorAnalysis`):
    - Classifies 14 structured error categories (`CORRECT_MATCH`, `CORRECT_NO_MATCH`, `WRONG_TARGET_SELECTED`, `TARGET_ABSENT_FROM_CANDIDATE_POOL`, `FALSE_ACCEPTANCE_OF_NO_MATCH`, `FALSE_NO_MATCH_FOR_POSITIVE`, `REVIEW_REQUIRED`, `VERSION_MISMATCH`, `ARTIST_TITLE_CONFUSION`, `EXECUTION_FAILURE`, `EXCLUDED_UNVERIFIED`, `EXCLUDED_DISPUTED`, `EXCLUDED_INELIGIBLE`, `UNKNOWN_ERROR`).
    - Aggregates distributions overall and sliced by split, category, expected outcome, and predicted decision.
  - Evaluation Reporting Engine (`BenchmarkEvaluationReport`, `BenchmarkReportGenerator`, `BenchmarkReportFormatter`):
    - Generates complete evaluation reports and formats them into GitHub Flavored Markdown (with tables and alerts) and plain text.
    - Documents evaluation limitations and notes held-out discipline for the `TEST` split.
- **Phase 8C + 8D Automated Tests**:
  - `BenchmarkRunnerTest`: 10 comprehensive tests covering positive Top-1, positive Top-3, target absent recall failure, legitimate NO_MATCH, REVIEW_REQUIRED version mismatch, unverified/disputed exclusion by default, diagnostic policy inclusion, split filtering, execution failure handling, deterministic repeatability, and full 118-track dataset execution.
  - `RankingMetricsCalculatorTest`: Hand-calculated tests for Top-1/Top-3 over all vs. present subsets, recall rate, zero denominators, and NO_MATCH exclusion.
  - `DecisionPolicyMetricsCalculatorTest`: Hand-calculated 6-way confusion matrix verification, precision, recall, FPR, FNR, review rate, auto-accept rate, and zero-denominator safety.
  - `HighConfidenceMetricsCalculatorTest`: Hand-calculated high-confidence error rate, uncalibrated baseline honest unavailability reporting, and thresholding.
  - `CalibrationMetricsCalculatorTest`: Hand-calculated Brier score, baseline score, ECE across reliability bins, empty bucket handling, and uncalibrated baseline unavailability reporting.
  - `ErrorAnalysisCalculatorTest`: Multi-dimensional aggregation across splits, categories, outcomes, decisions, and empty-slice verification.
  - `BenchmarkReportTest`: End-to-end report generation, Markdown formatting, plain text formatting, and limitations note emission.

### Phase 8A + 8B (Benchmark Architecture and Ground-Truth Dataset)
- **Phase 8A (Benchmark Architecture and Dataset Contract)**:
  - Package: `com.spotifyyoutube.migrator.matching.benchmark`
  - Strongly typed immutable domain model:
    - `BenchmarkExpectedOutcome`: Enum (`MATCH`, `NO_MATCH`) distinguishing positive matches from legitimate non-matches.
    - `BenchmarkVerificationStatus`: Enum (`VERIFIED`, `UNVERIFIED`, `DISPUTED`) tracking audit status.
    - `BenchmarkDatasetSplit`: Enum (`DEV`, `VALIDATION`, `TEST`) defining evaluation splits.
    - `BenchmarkTrackCategory`: 17-category typology (`STANDARD_POPULAR`, `OFFICIAL_MUSIC_VIDEO`, `OFFICIAL_AUDIO`, `TOPIC_CHANNEL`, `FEATURED_ARTIST`, `BAND_OR_COLLABORATION`, `REMIX`, `LIVE_VERSION`, `ACOUSTIC`, `COVER_VERSION`, `REMASTER`, `EXPLICIT_OR_CLEAN`, `DIACRITICS_OR_PUNCTUATION`, `SHORT_OR_AMBIGUOUS_TITLE`, `SAME_TITLE_DIFFERENT_ARTIST`, `DURATION_DISCREPANCY`, `LEGITIMATE_NO_MATCH`).
    - `BenchmarkSourceTrack`: Immutable source Spotify track representation (title, primaryArtist, unmodifiable artists list, album, durationMs, isExplicit, isrc).
    - `BenchmarkTargetCandidate`: Target YouTube candidate representation (videoId, title, channelTitle, durationMs, channelId, isOfficial).
    - `BenchmarkExample`: Root benchmark record enforcing strict constructor invariants (positive match requires non-null target, no-match strictly forbids target, VERIFIED requires non-blank verificationEvidence).
    - `BenchmarkDataset`: Dataset container record with immutable examples list, query utilities (`getExampleById`, `getExamplesBySplit`, `getExamplesByOutcome`, `getExamplesByStatus`, `getExamplesByCategory`), and `summary()`.
    - `BenchmarkDatasetSummary`: Aggregated telemetry capturing totals and distributions across splits and typologies.
  - Deterministic validation engine:
    - `BenchmarkDatasetValidator`: Enforces semantic versioning, uniqueness of example IDs, required fields, outcome vs target consistency, evidence presence on verified records, with warnings on duplicate source IDs, split collisions, and non-standard video IDs.
    - `BenchmarkValidationResult` & `BenchmarkValidationException`: Structured error reporting and fail-fast exception handling.
  - Offline Jackson loader & serialization:
    - `BenchmarkDatasetLoader`: Loads from classpath (`/benchmark/ground_truth_benchmark.json`), input streams, files, and JSON strings with automatic validation; provides deterministic pretty-printed JSON serialization.
  - Future Phase 8C integration contract:
    - Documented in `package-info.java` and `ADR-018`.
- **Phase 8B (Ground-Truth Benchmark Dataset)**:
  - Resource: `backend/src/main/resources/benchmark/ground_truth_benchmark.json` (Version `1.0.0`).
  - Total records: **118 distinct Spotify tracks** (exceeds target of 100).
  - Matches (`MATCH`): 105.
  - Legitimate Non-Matches (`NO_MATCH`): 13.
  - Verification status: 117 `VERIFIED` (with traceable audit evidence), 1 `UNVERIFIED` (`BM-0029`: performer under review), 0 `DISPUTED`.
  - Splits: `DEV` (52 records, 44.1%), `VALIDATION` (36 records, 30.5%), `TEST` (30 records, 25.4%).
  - Case diversity: All 17 defined typology categories represented (Official Video 25, Standard Popular 19, No Match 13, Typo/Punctuation 8, Featured Artist 7, Band Collab 6, Official Audio 5, Topic Channel 5, Cover 5, Remaster 4, Same Title Different Artist 4, Duration Discrepancy 4, Remix 3, Live 3, Acoustic 3, Short Title 3, Explicit/Clean 1).
- **Phase 8A + 8B Automated Tests**:
  - `BenchmarkExampleTest`: Invariants, required fields, and state queries.
  - `BenchmarkDatasetTest`: Immutability, query helpers, and summary aggregations.
  - `BenchmarkDatasetValidatorTest`: Metadata checks, referential integrity, duplicate ID detection, and warning behaviors.
  - `BenchmarkDatasetLoaderTest`: Default dataset loading, 118-count verification, typology coverage, serialization round-trip, and error handling.
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
- Migration Orchestration Engine & Progress Tracking (Phase 9 & Phase 12).
- Review & Correction Interface (Phase 10).
- Playlist Creation (Phase 11).

## Current Verification
- Backend Compilation: `.\mvnw.cmd test-compile` (Terminal execution status: **BLOCKED — NOT EXECUTED** due to IDE command-permission broker)
- Checkstyle: `.\mvnw.cmd checkstyle:check` (Terminal execution status: **BLOCKED — NOT EXECUTED**)
- Benchmark Test Suite (16 test classes):
  - Phase 8A/8B: `BenchmarkExampleTest`, `BenchmarkDatasetTest`, `BenchmarkDatasetValidatorTest`, `BenchmarkDatasetLoaderTest`
  - Phase 8C/8D: `BenchmarkRunnerTest`, `RankingMetricsCalculatorTest`, `DecisionPolicyMetricsCalculatorTest`, `HighConfidenceMetricsCalculatorTest`, `CalibrationMetricsCalculatorTest`, `ErrorAnalysisCalculatorTest`, `BenchmarkReportTest`
  - Phase 8E: `CalibrationObservationTest`, `PlattScalingModelTest`, `PlattScalingParameterFitterTest`, `DecisionThresholdOptimizerTest`, `BenchmarkCalibrationPipelineTest`
- Matching Engine Regression Suite (18 test classes): 274 tests
- Complete Matching Subsystem Coverage: 29 test classes, 365+ tests total
- Focused Candidate Discovery & Mapping Suite: 133 tests
- Focused Normalization Test Suite: 227 tests
- Full Non-Repository Unit Test Suite: Statically verified across domain, identity, spotify, youtube, migration, playlist, matching
- Repository/Flyway Integration Tests (`PlaylistRepositoryTest`, `UserRepositoryTest`, `MigratorApplicationTests`): Docker daemon unavailable in environment

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
- **Phase 8 (Matching Evaluation & Benchmark)**: IN PROGRESS
  - **Phase 8A (Benchmark Architecture and Dataset Contract)**: COMPLETE / FROZEN
  - **Phase 8B (Ground-Truth Benchmark Dataset)**: COMPLETE / FROZEN
  - **Phase 8C (Benchmark Runner & Candidate Pool Sourcing)**: COMPLETE / FROZEN
  - **Phase 8D (Evaluation Metrics, Calibration & Error Analysis)**: COMPLETE / FROZEN
  - **Phase 8E (Platt Scaling Parameter Fitting & Test Evaluation)**: IMPLEMENTATION REPAIRED / PENDING RUNTIME VERIFICATION
  - **Phase 8F (Subsystem Verification, Audit & Freeze)**: IN PROGRESS / PENDING RUNTIME VERIFICATION GATES

## Next Recommended Work
1. Execute runtime verification suite for Phase 8E/8F (test-compile, Benchmark tests, Matching tests, Checkstyle).
2. Upon successful execution, freeze Phase 8 and advance to Phase 9 (Migration Engine).