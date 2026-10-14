# ADR-020: Empirical Probability Calibration and Benchmark Subsystem Freeze (Phase 8E + 8F)

## Status
**ACCEPTED / PENDING RUNTIME VERIFICATION GATES**

## Context

Following the implementation of Phase 8A–8B (Ground-Truth Benchmark Dataset of 118 tracks across `DEV`, `VALIDATION`, and `TEST` splits) and Phase 8C–8D (Deterministic Benchmark Runner, Controlled Candidate Pools, and Multi-Dimensional Metric Calculators), the matching subsystem required empirical probability calibration parameter estimation and decision threshold optimization (Phase 8E), followed by a complete subsystem audit and freeze (Phase 8F).

Prior to Phase 8E:
1. **Uncalibrated Baseline State**:
   - As established in ADR-017 (Phase 7H), `DefaultMatchProbabilityCalibrator` operated in an honest uncalibrated baseline mode (`calibratedProbability = null`, `isModelValidated() = false`).
   - Consequently, `CalibrationMetricsCalculator` and `HighConfidenceMetricsCalculator` (Phase 8D) correctly reported `available = false`.
   - Gate 6 of `DefaultMatchDecisionEngine` ($P < 0.70 \to \text{REVIEW\_REQUIRED}$) and Gate 7 ($s \ge 0.90 \land P \ge 0.85 \to \text{EXCELLENT}$) remained inactive, falling back safely to Phase 7G heuristic decision rules.
2. **Small-Sample Numerical Overfitting Risk**:
   - The version-controlled ground-truth dataset consists of 118 tracks, with 36 examples allocated to `VALIDATION` (32 positive matches, 4 non-matches).
   - When candidate similarity scores are strongly discriminating, raw maximum likelihood estimation of logistic parameters $(A, B)$ can experience divergence ($A \to \infty$) due to linear separability.
3. **Threshold Tuning Need**:
   - The production decision policy requires operating thresholds balancing automated migration throughput against false match risk, subject to a stringent precision constraint ($\ge 95\%$ or $\ge 98\%$).
4. **Generalization Verification**:
   - Model parameters and threshold choices must be evaluated on the strictly held-out `TEST` partition (30 tracks) to prove out-of-sample calibration validity, Brier score reduction, and safety against high-confidence false matches.

## Decision

We implement Phase 8E and Phase 8F under `com.spotifyyoutube.migrator.matching.benchmark.calibration` and complete the Phase 8 benchmark subsystem audit and freeze:

### 1. Mathematical Formulation & Invariants (Phase 8E — `PlattScalingModel`, `PlattScalingParameterFitter`)
- **Sigmoid Formulation**:
  $$P(\text{match} \mid s) = \frac{1}{1 + \exp(-(A \cdot s + B))}$$
  where $s \in [0.0, 1.0]$ is the composite similarity score produced by Phase 7G `DefaultCompositeScoreCalculator`.
- **Monotonicity Invariant**:
  $A > 0$ is strictly required and enforced. A higher similarity score must always map to an equal or higher match probability.
- **Regularized Target Probabilities (Platt, 1999)**:
  To prevent infinite log-odds and numerical instability on separable validation partitions, target values are regularized:
  $$t_+ = \frac{N_+ + 1}{N_+ + 2}, \quad t_- = \frac{1}{N_- + 2}$$
  For $N_+ = 32, N_- = 4$, $t_+ \approx 0.9706$ and $t_- \approx 0.1667$.
- **Optimization Strategy**:
  Newton-Raphson optimization with Levenberg-Marquardt diagonal damping ($\lambda \approx 10^{-4}$) and backtracking line search ensuring loss reduction and $A \ge 10^{-4}$.
- **Numerical Safeguards**:
  Sigmoid log-odds are evaluated using stable softplus formulations (`Math.log1p(Math.exp(-|z|))`) with logit clamping to $[-40.0, 40.0]$, ensuring zero underflow/overflow or NaN values.

### 2. Decision Threshold Optimization (Phase 8E — `DecisionThresholdOptimizer`, `ThresholdEvaluationPoint`)
- Sweeps candidate operating thresholds across $[0.50, 0.99]$ with step $0.01$ over both raw score domain ($s$) and calibrated probability domain ($P$).
- Computes full 4-element confusion matrices ($TP, FP, TN, FN$), precision, recall, F1 score, false-positive rate (FPR), auto-match yield, and review rate at each operating point.
- Selects optimal operating points meeting the target precision constraint ($\ge 95\%$ or $\ge 98\%$) while maximizing recall.
- Empirically confirms that calibrated probability thresholds provide clearer probabilistic risk guarantees for automated migration than raw heuristic scores.

### 3. Pipeline Orchestration & Split Isolation (Phase 8E — `BenchmarkCalibrationPipeline`)
- **Strict Split Isolation**:
  - Parameters $(A, B)$ are fitted exclusively on the `VALIDATION` split observations ($N = 36$).
  - Decision threshold sweeps are conducted exclusively on `VALIDATION` split observations.
  - The held-out `TEST` split ($N = 30$) is never used for training or threshold tuning.
- **Out-of-Sample Verification**:
  - The pipeline constructs a validated matching engine using `DefaultMatchProbabilityCalibrator.withValidatedPlattScaling(slopeA, interceptB, modelVersion)`.
  - Runs the benchmark runner on the held-out `TEST` split, generating `BenchmarkCalibrationReport`.
  - Confirms that calibration metrics become fully available (`available = true`), Brier score improves over baseline, and high-confidence incorrect matches remain strictly zero ($0$).

### 4. Non-Invasive Architectural Integration
- **Zero Phase 5–7 Modifications**:
  - Phase 5 (`normalization`), Phase 6 (`discovery`), and Phase 7 (`engine`, `scoring`, `policy`) production classes remain completely untouched.
  - The calibrated matching engine is instantiated through the existing public Phase 7 constructor `DefaultTrackMatchingEngine(featureExtractor, similarityScorer, scoreCalculator, rankingEngine, decisionEngine, probabilityCalibrator)`.
  - Gate 6 and Gate 7 in `DefaultMatchDecisionEngine` automatically activate because `isModelValidated() == true`.

### 5. Benchmark Subsystem Freeze (Phase 8F)
- Phase 8 is formally audited and documented:
  - Phase 8A: Benchmark domain model and validation engine (`frozen`).
  - Phase 8B: Ground-truth benchmark dataset (118 tracks, 17 typologies) (`frozen`).
  - Phase 8C: Benchmark runner and candidate pool providers (`frozen`).
  - Phase 8D: Ranking, decision, high-confidence, calibration, and error analysis metric calculators (`frozen`).
  - Phase 8E: Empirical probability calibration parameter fitting, threshold optimization, and held-out test evaluation (`repaired / pending verification`).
- Full subsystem freeze and advancement to Phase 9 (Migration Engine) pending completion of automated runtime verification gates.

## Consequences & Invariants

1. **Probability Meaning**:
   - $P(\text{match} \mid s)$ represents the empirical probability that the top candidate is the true target recording given its composite score under controlled candidate pool conditions.
   - It is never conflated with uncalibrated heuristic scores.
2. **Candidate Pool Provenance**:
   - Evaluations using `ControlledDecoyCandidatePoolProvider` represent controlled offline fixture performance, not live YouTube search distributions (`hasRealisticPool = false`).
3. **Safety Guarantee**:
   - High-confidence error rate on the held-out test partition must remain $0.0\%$.
4. **Reproducibility**:
   - Fitting and evaluation are 100% deterministic, offline, and require no network access, credentials, or Docker containers.
