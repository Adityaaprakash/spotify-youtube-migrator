# ADR-017: Calibrated Match Probability and Decision Policy (Phase 7H)

## Context

Following the completion of composite scoring, candidate ranking, and the initial decision engine in Phase 7G, the matching system requires a formal distinction between heuristic metadata similarity, calibrated statistical match probability, and automated migration decision policies:

1. **Similarity Score vs. Calibrated Probability**:
   - A *similarity score* is a deterministic, heuristic metric in $[0.0, 1.0]$ quantifying how closely candidate metadata matches the source track. A composite score of $0.90$ does *not* imply a 90% probability that the candidate is the true recording.
   - A *calibrated match probability* represents the statistical likelihood $P(\text{true match} \mid \text{evidence}) \in [0.0, 1.0]$, derived from a validated empirical model trained on labeled source-destination track pairs.

2. **Configured Transformation vs. Validated Calibration Model**:
   - A logistic sigmoid function (such as Platt scaling) can map scores into $[0.0, 1.0]$, but configuring arbitrary slope $A$ and intercept $B$ parameters constitutes merely a *configured mathematical transformation*, not an *empirically validated probability model*.
   - A calibrator must not be represented as empirically validated simply because its configuration parameters are finite and non-zero.
   - True empirical validation requires estimating or fitting parameters against a trustworthy labeled ground-truth dataset and verifying calibration curves, Brier scores, or Expected Calibration Error (ECE).

3. **Readiness and Data Provenance Audit**:
   - An audit of the repository establishes that no trustworthy, real-world labeled matching dataset currently exists (the empirical benchmark dataset and evaluation suite are scheduled for Phase 8).
   - In accordance with architectural integrity constraints, synthetic unit tests or hand-authored fixtures must not be disguised as empirical training data, nor may fabricated calibration statistics or accuracy metrics be claimed.

4. **Architectural Need for Calibration Abstractions**:
   - The matching engine requires clean calibration abstractions (`MatchProbabilityCalibrator`, `CalibrationResult`) to support future empirical calibration models (e.g., Platt scaling, isotonic regression) without requiring pipeline redesign.
   - The API must explicitly distinguish between:
     - **Unavailable calibration**: Default baseline prior to empirical modeling.
     - **Configured transformation**: Mathematical formula parameterized without empirical ground-truth validation.
     - **Validated calibration model**: Empirically verified against labeled evaluation data.
   - When no validated empirical model is configured, the system must honestly represent this state, transparently falling back to documented Phase 7G heuristic decision policies.

5. **Decision Policy Refinement**:
   - The decision policy must explicitly incorporate calibrated probabilities as a decision gate only when the model's validated status and result metadata are consistent.
   - An unvalidated or unavailable calibration model must never trigger a probability decision gate, but must fall back to the Phase 7G heuristic baseline with honest diagnostic reasons.
   - Existing safety gates (recording version penalties, minimum viable scores, and ambiguity margins) remain strictly enforced and cannot be overridden by statistical transforms.

## Decision

We implement Phase 7H across `com.spotifyyoutube.migrator.matching.engine.calibration` and update existing decision and engine orchestration components:

### 1. Calibration Domain Model (`CalibrationResult`)

Encapsulates the outcome of a probability calibration attempt as an immutable record:
- `Double probability`: Computed probability in $[0.0, 1.0]$, or `null` when calibration is unavailable.
- `boolean available`: Boolean indicating whether a calibration transformation was executed.
- `boolean validated`: Boolean indicating whether the result originates from an empirically validated model.
- `String calibrationMethod`: Descriptive name of the applied method (`"PLATT_SCALING"`, `"NONE"`).
- `String modelVersion`: Version or identifier of the calibration model parameters (`"UNVALIDATED"` by default).

Key accessors and factories:
- `unavailable(String reason)`: `probability = null, available = false, validated = false, method = "NONE", modelVersion = "UNVALIDATED"`.
- `configured(Double probability, String method, String modelVersion)`: Configured mathematical transformation with `available = true, validated = false`.
- `validated(Double probability, String method, String modelVersion)`: Empirically validated model output with `available = true, validated = true`.
- `calibratedProbability()`: Returns `Optional<Double>` *only* if `validated == true`.
- `configuredProbability()`: Returns `Optional<Double>` if `available == true` (whether validated or unvalidated).
- Probability validation: Rejects `NaN`, infinite, negative, or $> 1.0$ probabilities with `IllegalArgumentException`. Clamps and rounds valid probabilities to 4 decimal places.

### 2. Strategy Interface (`MatchProbabilityCalibrator`)

Defines contracts for converting composite similarity scores and evidence breakdowns into true-match probabilities:
- `calibrate(Double compositeScore, MatchScoreBreakdown breakdown)` $\to$ `CalibrationResult`
- `calibrateProbability(Double compositeScore, MatchScoreBreakdown breakdown)` $\to$ `Optional<Double>` (returns non-empty only when model is validated)
- `calibrate(CandidateMatchEvaluation evaluation)` $\to$ `CalibrationResult`
- `isConfigured()` $\to$ returns `true` if a calibration method is configured
- `isModelValidated()` $\to$ returns `true` only if the calibration model is empirically validated
- `calibrationMethod()` $\to$ `String`
- `modelVersion()` $\to$ `String`

### 3. Default Implementation (`DefaultMatchProbabilityCalibrator`)

- **Uncalibrated Baseline (Default)**:
  - Default constructor sets `configured = false, modelValidated = false, calibrationMethod = "NONE", modelVersion = "UNVALIDATED"`.
  - Always returns `CalibrationResult.unavailable(...)`, ensuring production defaults make no false statistical claims prior to Phase 8.
- **Configured Platt Scaling (`withPlattScaling`)**:
  - Accepts logistic parameters `(slopeA, interceptB, modelVersion)`.
  - Marks `configured = true, modelValidated = false`.
  - Outputs `CalibrationResult.configured(...)`.
  - Clearly documented as an unvalidated parameterization requiring empirical validation.
- **Validated Platt Scaling (`withValidatedPlattScaling`)**:
  - Accepts logistic parameters `(slopeA, interceptB, modelVersion)`.
  - Marks `configured = true, modelValidated = true`.
  - Outputs `CalibrationResult.validated(...)`.
  - Intended for future use once Phase 8 empirical benchmark validation is performed.
- **Numerical Stability Safeguards**:
  - The logistic logit $z = -(A \cdot s + B)$ is clamped to $[-40.0, 40.0]$:
    $$P(\text{match} \mid s) = \frac{1}{1 + e^{\text{clamp}(-(A \cdot s + B), -40.0, 40.0)}}$$
  - Prevents floating-point overflow (`e^z = Infinity`) or underflow under extreme finite parameter values (e.g., $A = 1000.0, B = -500.0$).
  - Clamps output strictly to $[0.0, 1.0]$ and rounds to 4 decimal places.
  - Rejects non-finite inputs, zero slope, and empty model version strings.

### 4. Decision Policy Integration (`DefaultMatchDecisionEngine`)

The decision engine applies a transparent, 7-gate decision policy that respects composite similarity and validated calibrated probability:

1. **Gate 1 (Empty Candidates)**: If candidate list is empty $\to$ `MatchDecision.NO_MATCH` with `POOR` confidence.
2. **Gate 2 (Version Penalty Mismatch)**: If top candidate version penalty $p > 0.10$ on a viable candidate ($\text{baseScore} \ge 0.50$) $\to$ `MatchDecision.REVIEW_REQUIRED`. High similarity or high probability never overrides a confirmed recording version discrepancy.
3. **Gate 3 (Below Minimum Viable Threshold)**: If top composite score $s < 0.50 \to$ `MatchDecision.NO_MATCH` with `POOR` confidence.
4. **Gate 4 (Borderline Similarity)**: If top composite score $0.50 \le s < 0.75 \to$ `MatchDecision.REVIEW_REQUIRED` with `POOR` confidence.
5. **Gate 5 (Candidate Ambiguity Margin)**: If $s_{\text{top}} - s_{\text{runnerUp}} < 0.05 \to$ `MatchDecision.REVIEW_REQUIRED` with `GOOD` confidence. A high score never bypasses close runner-up competition.
6. **Gate 6 (Calibrated Probability Divergence)**: **Only applies when calibration is empirically validated.** If `isProbabilityValidated == true` and $P(\text{match}) < 0.70 \to$ `MatchDecision.REVIEW_REQUIRED`. When calibration is unvalidated or unavailable, this gate is skipped, falling back to Phase 7G heuristic rules.
7. **Gate 7 (Automated Match)**: If all safety gates pass $\to$ `MatchDecision.MATCH`.
   - When probability is validated: confidence is `EXCELLENT` if $s \ge 0.90$ AND $P \ge 0.85$; `GOOD` otherwise.
   - When probability is unvalidated/unavailable: confidence is `EXCELLENT` if $s \ge 0.90$; `GOOD` if $0.75 \le s < 0.90$ (exact Phase 7G baseline).
   - Diagnostic reasons explicitly indicate whether a validated probability was confirmed, an unvalidated transformation was used with heuristic fallback, or calibration was unavailable.

### 5. Backward Compatibility & Pipeline Seam Integrity

- `CandidateMatchEvaluation`: Retains 6-argument `of(...)` overload delegating to 7-argument overload with `null` probability.
- `DefaultMatchDecisionEngine`: Retains Phase 7G constructors (0-arg, 5-arg, 6-arg) and adds calibrated constructor overloads.
- `DefaultTrackMatchingEngine`: Retains Phase 7G constructors (0-arg, 5-arg) and provides 6-arg constructor accepting `MatchProbabilityCalibrator`.
- Default pipeline execution maintains 100% Phase 7G behavior when no calibrated model is configured.

## Consequences

### Benefits

- **Clear Distinction**: Formally separates configured parameterization from statistical validation.
- **Scientific Honesty**: Never misrepresents arbitrary formulas as empirical truth; prevents premature reliance on unverified probabilities.
- **Robust Numerical Stability**: Clamped logits prevent `NaN`/`Infinity` edge cases even with extreme finite parameters.
- **Safety First**: Preserves all Phase 7G safety gates; version penalties and ambiguity margins cannot be bypassed.
- **Extensible Architecture**: Ready to ingest trained Platt scaling or isotonic regression parameters once Phase 8 benchmark evaluation is complete.

### Limitations & Future Work

- **Phase 8 Prerequisite**: Production deployments operate in the uncalibrated baseline mode until Phase 8 establishes a labeled ground-truth evaluation corpus of Spotify tracks matched to YouTube Music recordings.
- **Empirical Calibration Requirements**: Future calibration models require fitting parameters against the Phase 8 labeled dataset, evaluating Brier score, and verifying reliability curves across music genres.
