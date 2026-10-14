# ADR-019: Benchmark Runner and Evaluation Metrics (Phase 8C + 8D)

## Context

Following the implementation of Phase 8A (Benchmark Architecture and Dataset Contract) and Phase 8B (Ground-Truth Benchmark Dataset of 118 tracks), the system required a deterministic execution runner and comprehensive metric calculators to empirically evaluate the frozen Phase 7 matching engine.

Prior to Phase 8C and 8D:
1. **Missing Execution Pipeline**:
   - The ground-truth dataset existed in `ground_truth_benchmark.json`, but there was no runner to feed benchmark examples into the frozen `TrackMatchingEngine` through its real `matchTrack(TrackMatchRequest)` contract.
2. **Candidate-Pool Integrity Challenge**:
   - The ground-truth dataset contains source tracks and their expected targets, but not full multi-candidate search pools with realistic distractors.
   - Passing only the expected target would trivialise ranking evaluation (100% Top-1 accuracy by default).
   - Live querying of YouTube during benchmark execution is non-deterministic, consumes API quota, requires network access/credentials, and violates offline determinism rules.
3. **Abstention and Multi-Class Decision Handling**:
   - The Phase 7 engine produces a 3-way decision (`MATCH`, `REVIEW_REQUIRED`, `NO_MATCH`).
   - Standard binary classification metrics (precision, recall) can be severely distorted if `REVIEW_REQUIRED` (abstention) is silently treated as a match or non-match, or ignored without reporting coverage.
4. **Probability Calibration Representation**:
   - The default Phase 7 matching engine operates under `DefaultMatchProbabilityCalibrator` in an uncalibrated baseline mode (`calibratedProbability = null`).
   - Metric calculators must honestly report calibration status rather than fabricating probabilities or misrepresenting heuristic similarity scores.

## Decision

We implement Phase 8C (Benchmark Runner & Candidate Pools) and Phase 8D (Evaluation Metrics & Reporting) under `com.spotifyyoutube.migrator.matching.benchmark` across dedicated subpackages:

### 1. Candidate Pool Strategy (Phase 8C — `pool`)
- **`BenchmarkCandidatePoolProvider`**: Core strategy interface decoupling candidate pool sourcing from the runner.
- **`ControlledDecoyCandidatePoolProvider`**: For controlled offline component testing, provides the verified target candidate (if positive match and requested) alongside deterministic, challenging distractors (live versions, cover recordings, remixes, and different artists with the same title). Clearly marks synthetic candidates with synthetic identifiers (e.g. `decoy_live_...`) and reports `hasRealisticPool = false`.
- **`FixtureCandidatePoolProvider`**: Supports version-controlled, pre-recorded discovery fixtures mapped by example ID.
- **`TargetOnlyCandidatePoolProvider`**: Non-discriminating baseline provider passing only the single expected candidate for isolated regression testing.
- **`EmptyCandidatePoolProvider`**: Returns zero candidates to test candidate discovery recall failures.

### 2. Benchmark Runner Architecture (Phase 8C — `runner`)
- **`BenchmarkRunner` & `DefaultBenchmarkRunner`**: Coordinates validation, candidate pool resolution, metadata normalization via Phase 5 `NormalizationOrchestrator`, matching engine execution via Phase 7 `TrackMatchingEngine`, result mapping, and error classification.
- **`BenchmarkEligibilityPolicy`**: Controls which examples enter formal scored metrics.
  - Excludes `UNVERIFIED` examples (e.g. `BM-0029`) and `DISPUTED` examples by default.
  - Supports split-level targeting (e.g. held-out `TEST` partition) and diagnostic policies inspecting all records.
- **`BenchmarkExampleResult`**: Immutable per-example evaluation result recording expected target, predicted target, predicted decision, score, probability, confidence, candidate pool size, target presence in pool, eligibility, exclusion reason, execution duration, and structured error category.
- **`BenchmarkRunResult`**: Aggregated run result holding all example results, summary queries, and run metadata.

### 3. Evaluation Metrics Engine (Phase 8D — `metric`)
Independent, testable metric calculators:
- **`RankingMetricsCalculator` / `RankingMetrics`**: Computes Top-1 and Top-3 accuracy on eligible positive `MATCH` examples. Isolates candidate discovery recall failures from downstream ranking accuracy by reporting metrics across both all positive examples and the candidate-present subset. Excludes `NO_MATCH` examples from ranking numerators and denominators.
- **`DecisionPolicyMetricsCalculator` / `DecisionPolicyMetrics`**: Formulates a complete 6-way confusion matrix:
  - `correctAutoAccept` (True Positive - Accepted)
  - `wrongCandidateAccepted` (False Positive - Wrong Target)
  - `falseAcceptanceOfNoMatch` (False Positive - Non-Match Accepted)
  - `reviewRequiredPositive` (Abstention on Match)
  - `reviewRequiredNoMatch` (Abstention on Non-Match)
  - `correctNoMatch` (True Negative)
  - `incorrectRejection` (False Negative)
  Calculates precision, recall, false-positive rate, false-negative rate, NO_MATCH precision/recall, review rate, auto-accept rate, and overall accuracy. Enforces safe undefined behavior on zero denominators.
- **`HighConfidenceMetricsCalculator` / `HighConfidenceMetrics`**: Evaluates accepted match accuracy above a probability threshold (default `0.90`). Honestly reports `available = false` when running against the default uncalibrated matching engine baseline.
- **`CalibrationMetricsCalculator` / `CalibrationMetrics`**: Calculates Brier score, empirical baseline comparative Brier score ($\bar{y}(1-\bar{y})$), Expected Calibration Error (ECE), reliability diagram bins, and mean confidence for correct vs. incorrect predictions. Handles empty bins and missing probabilities cleanly.
- **`ErrorAnalysisCalculator` / `BenchmarkErrorAnalysis`**: Aggregates structured error categories (`CORRECT_MATCH`, `CORRECT_NO_MATCH`, `WRONG_TARGET_SELECTED`, `TARGET_ABSENT_FROM_CANDIDATE_POOL`, `FALSE_ACCEPTANCE_OF_NO_MATCH`, `FALSE_NO_MATCH_FOR_POSITIVE`, `REVIEW_REQUIRED`, `VERSION_MISMATCH`, `ARTIST_TITLE_CONFUSION`, `EXECUTION_FAILURE`, `EXCLUDED_UNVERIFIED`, etc.) across splits and track typologies.
- **`BenchmarkMetricsCalculator` / `BenchmarkMetrics`**: Composite orchestrator consolidating all metric calculations.

### 4. Structured Reporting (Phase 8D — `report`)
- **`BenchmarkEvaluationReport`**: Structured container holding run metadata, eligibility breakdown, candidate pool coverage, all metric dimensions, and limitations notes.
- **`BenchmarkReportGenerator`**: Generates reports from `BenchmarkRunResult`.
- **`BenchmarkReportFormatter`**: Formats reports into GitHub Flavored Markdown (with tables and alert blocks) and clean plain text.

## Consequences

### Positive
- Production matching engine (Phases 5, 6, and 7) remains completely frozen and untouched.
- Clean separation between ground truth, candidate pools, engine predictions, and review statuses.
- Transparent reporting of abstentions (`REVIEW_REQUIRED`) preventing inflated precision claims.
- Honest handling of probability calibration: uncalibrated baseline reports `available = false` without fabricating empirical probabilities.
- Deterministic, offline execution with zero network, database, or container dependencies.
- Hand-calculated test coverage for every metric formula and edge case (zero denominators, empty pools, unverified records).

### Limitations / Trade-offs
- **Candidate Pool Fixtures**: Realistic multi-candidate discovery pools from YouTube search are not yet pre-recorded in the repository; evaluation currently uses controlled synthetic decoys for component testing.
- **Calibration Baseline**: Under default production matching configuration, `DefaultMatchProbabilityCalibrator` operates in uncalibrated mode; probability calibration metrics require a calibrated model instance (e.g. Platt scaling).
- **TEST Split Protection**: The 30-track `TEST` split remains held out and must not be used for threshold tuning.
