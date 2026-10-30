Architecture style
Module boundaries
Dependency direction
Domain isolation
Application layer
Infrastructure layer
API layer
External provider isolation
Transaction boundaries
Configuration architecture
Frontend architecture

┌───────────────────────────────┐
│            API                │
└───────────────┬───────────────┘
                ↓
┌───────────────────────────────┐
│        APPLICATION            │
└───────────────┬───────────────┘
                ↓
┌───────────────────────────────┐
│           DOMAIN              │
└───────────────┬───────────────┘
                ↑
┌───────────────┴───────────────┐
│       INFRASTRUCTURE           │
└───────────────────────────────┘


## Forbidden Dependencies

Domain MUST NOT depend on:

- Spotify SDK
- YouTube SDK
- Spring MVC
- HTTP clients
- Controller classes
- Provider DTOs
- Database implementations

Controllers MUST NOT:

- call external APIs directly
- contain business logic
- access JPA repositories directly
- expose provider DTOs

## Matching & Normalization Layer

- Package: `com.spotifyyoutube.migrator.matching.normalization`
- Purpose: Provider-neutral normalization of raw track metadata for deterministic similarity comparisons.
- Architecture Rules:
  - Normalizers, tokenizers, and orchestrator (`TitleNormalizer`, `ArtistNormalizer`, `AlbumNormalizer`, `DurationNormalizer`, `MetadataTokenizer`, `NormalizationOrchestrator`) MUST NOT depend on provider DTOs (`spotify`, `youtube`).
  - Normalizers and tokenizers MUST NOT mutate incoming objects or domain models.
  - Normalizers and tokenizers MUST be pure, deterministic, and independently testable without database or network infrastructure.
  - Duration representation is standardized on canonical `java.time.Duration`, maintaining millisecond and nanosecond precision while strictly validating inputs.
  - Tokenizer decomposes metadata into immutable `TokenizedTitle` records classifying `VariantType`, `MediaFormat`, `TokenCategory`, `RemasterDetails`, `LivePerformanceDetails`, and preserved unclassified annotations.
  - Orchestration is unified under `NormalizationOrchestrator`, accepting `RawTrackMetadata` and producing immutable, comparison-oriented `NormalizedTrackMetadata` aggregating titles, artist credits, list normalizations, consolidated featured artists, canonical durations, and tokenized metadata.
  - Downstream matching and candidate discovery layers consume `NormalizedTrackMetadata` as their standard comparison model.

## Candidate Discovery Layer

- Package: `com.spotifyyoutube.migrator.matching.discovery`
- Purpose: Retrieve and bound prospective YouTube candidate tracks for a normalized source track while strictly governing API quota consumption.
- Architecture Rules:
  - Consumes `NormalizedTrackMetadata` from the frozen normalization engine via `CandidateDiscoveryRequest`.
  - Generates deterministic, prioritized search queries via `SearchQueryGenerator`, categorized by `SearchQueryStrategy`.
  - Reuses provider-neutral `MusicDestination` and `DestinationTrackCandidate` from `com.spotifyyoutube.migrator.migration.application.destination.model`. YouTube provider DTOs MUST NOT leak into the discovery layer.
  - Preserves candidate provenance using `DiscoveredCandidate` without modifying the core `DestinationTrackCandidate` model.
  - Deduplicates candidates by unique provider ID while preserving encounter order based on query priority.
  - Enforces quota bounding through configurable `DiscoveryOptions` (`maxQueries`, `maxCandidates`, `resultsPerQuery`).
  - Represents discovery outcomes using structured `CandidateDiscoveryResult` and explicit `CandidateDiscoveryStatus` (`SUCCESS`, `EMPTY`, `PARTIAL_FAILURE`, `FAILED`), capturing query-level execution history in `SearchQueryExecution`.
  - Strictly avoids scoring, ranking, confidence estimation, and mutation operations (deferred to Phase 7 and later).

## Matching Engine Layer (Phase 7 — Complete & Frozen)

- Packages:
  - Contracts & Top-Level Engine: `com.spotifyyoutube.migrator.matching.engine`
  - Feature Extraction: `com.spotifyyoutube.migrator.matching.engine.feature`
  - Similarity Scorers & Penalties: `com.spotifyyoutube.migrator.matching.engine.scorer`
  - Candidate Ranking: `com.spotifyyoutube.migrator.matching.engine.ranking`
  - Match Probability Calibration: `com.spotifyyoutube.migrator.matching.engine.calibration`
  - Match Decision Engine: `com.spotifyyoutube.migrator.matching.engine.decision`
- Purpose: Provider-neutral similarity evaluation, version penalty scoring, composite scoring with dynamic re-normalization, deterministic candidate ranking, probability calibration, and transparent migration decision policy.
- Architecture Rules:
  - **Immutable Contracts**: All pipeline models (`TrackMatchRequest`, `TrackMatchResult`, `MatchDecision`, `MatchScoreBreakdown`, `CandidateMatchEvaluation`, `MatchConfidence`, `CalibrationResult`) MUST be immutable records with defensive copying for all collections and maps.
  - **Honest Missing-Data Semantics**: Missing metadata or uncomputed component dimensions MUST be represented honestly as `null`, never fabricated or coerced to 0.0.
  - **Provider Neutrality**: Engine and scorer classes MUST NOT import provider-specific DTOs from Spotify or YouTube packages.
  - **Feature Extraction & Normalizer Reuse**: `DefaultTrackFeatureExtractor` reuses frozen Phase 5 normalizers (`TitleNormalizer`, `ArtistNormalizer`, `MetadataTokenizer`) without modifying frozen Phase 5 or Phase 6 code. Conservative official provenance policy requires verified channel markers (Topic, VEVO, artist match, distributor description).
  - **Independent Component Scorers**: Title (Levenshtein + token sort + delimiter segments), Artist (bipartite 1-to-1 matching + primary priority), Duration (piecewise continuous decay), Album (NFC + description extraction), and Official Provenance scorers return normalized values in $[0.0, 1.0]$ or `null`.
  - **Version Penalty Engine**: Evaluates version discrepancies (`COVER`, `LIVE_MISMATCH`, `REACTION`, `KARAOKE`, etc.) with bounded normalized deductions in $[0.0, 1.0]$ and itemized breakdowns, protected by false-positive title safeguards.
  - **Composite Scoring with Dynamic Re-normalization**: Linear combination (Title 40%, Artist 30%, Duration 10%, Album 10%, Official 10%) re-normalizes dynamically over active non-null dimensions, deducting version penalty once.
  - **Deterministic Ranking**: 7-level tie-breaking ladder (Composite score $\to$ Title $\to$ Artist $\to$ Official $\to$ Version Penalty $\to$ Duration $\to$ Candidate ID) guarantees 100% reproducible candidate ordering.
  - **Probability Calibration Separation**: `CalibrationResult` and `MatchProbabilityCalibrator` strictly separate heuristic composite similarity from empirical match probability. Default baseline is honestly uncalibrated (`configured = false, modelValidated = false`). Platt scaling transformations are clamped to logit $[-40.0, 40.0]$ for numerical stability.
  - **Decision Policy Gates**: Transparent 7-gate decision policy enforces safety gates: empty candidates ($\to$ `NO_MATCH`), version penalty $> 0.10$ on viable candidate ($\to$ `REVIEW_REQUIRED`), score $< 0.50$ ($\to$ `NO_MATCH`), borderline score $0.50 \le s < 0.75$ ($\to$ `REVIEW_REQUIRED`), ambiguity margin $< 0.05$ ($\to$ `REVIEW_REQUIRED`). Probability divergence gate applies strictly to empirically validated models.
  - **Subsystem Freeze**: The Matching Engine subsystem is complete, verified, and **FROZEN** at Phase 7I.

## Benchmark & Evaluation Layer (Phase 8A, 8B, 8C, 8D)

- Packages:
  - Core & Dataset: `com.spotifyyoutube.migrator.matching.benchmark`
  - Candidate Pools: `com.spotifyyoutube.migrator.matching.benchmark.pool`
  - Runner & Execution Models: `com.spotifyyoutube.migrator.matching.benchmark.runner`
  - Metric Calculators: `com.spotifyyoutube.migrator.matching.benchmark.metric`
  - Evaluation Reporting: `com.spotifyyoutube.migrator.matching.benchmark.report`
- Resources: `benchmark/ground_truth_benchmark.json`
- Architecture Decisions: ADR-018 (Dataset & Foundation), ADR-019 (Runner & Metrics)
- Purpose: Ground-truth benchmark data architecture, candidate pool management, deterministic benchmark execution against frozen matching engine, rigorous multi-dimensional evaluation metrics, probability calibration assessment, structured error analysis, and CI/diagnostic reporting.
- Architecture Rules:
  - **Zero Production Intrusion**: Benchmark domain models, runner, candidate pool providers, and metric calculators MUST NOT modify, alter, or introduce branches into the frozen Phase 5 normalization engine, Phase 6 candidate discovery, or Phase 7 matching engine.
  - **Explicit Outcome Representation**: Matches (`MATCH`) and legitimate non-matches (`NO_MATCH`) MUST be represented unambiguously as explicit enum variants. A non-match MUST NOT be represented as a match with a null video ID.
  - **Strong Invariant Validation**: `BenchmarkExample` enforces constructor invariants: positive matches require non-null `BenchmarkTargetCandidate`; non-matches forbid target candidates; `VERIFIED` status requires non-blank `verificationEvidence`.
  - **Deterministic Validator**: `BenchmarkDatasetValidator` enforces semantic versioning, uniqueness of example IDs, completeness of mandatory metadata, and produces itemized fatal errors and non-fatal warnings without silently modifying data.
  - **Offline Storage & Jackson Loading**: Benchmark datasets are stored in version-controlled JSON on the classpath and parsed using `BenchmarkDatasetLoader` without live network calls, remote databases, or container dependencies.
  - **Candidate-Pool Integrity**: The runner MUST NOT leak ground-truth targets into candidate pools to guarantee predictions. Ranking evaluation against trivial single-candidate pools is rejected. Where realistic pre-recorded discovery fixtures are absent, `ControlledDecoyCandidatePoolProvider` supplies deterministic synthetic decoys (covers, live variants, remixes, alternate artist distractors) for component verification while explicitly reporting `hasRealisticPool() = false`.
  - **Candidate Recall vs. Ranking Distinction**: When a target candidate is absent from the candidate pool, it is classified strictly as a candidate-recall/retrieval failure (`TARGET_ABSENT_FROM_CANDIDATE_POOL`), never hidden as a ranking failure.
  - **Default Evaluation Eligibility Policy**: Unverified records (e.g. `BM-0029`) and disputed records are excluded by default from scored evaluation metrics and reported transparently under excluded records with documented reasons (`UNVERIFIED_STATUS`, `DISPUTED_STATUS`). Diagnostic mode allows inspecting excluded records without skewing formal metrics.
  - **TEST Split Protection**: The 30-track `TEST` split remains strictly held out. Phase 8C/8D does not tune matching thresholds or Platt scaling parameters.
  - **Mathematical Metric Rigor & Zero-Denominator Safety**: Every metric calculator enforces documented mathematical definitions and returns empty/uncomputable states (`OptionalDouble.empty()`, `rate = null`) instead of `NaN`, `Infinity`, or misleading zeros when denominators are empty.
  - **Decision Policy Clarity**: Metrics distinguish 6 discrete outcomes: correct auto-accept, wrong candidate auto-accept, false acceptance of no-match, review required (positive), review required (no-match), and correct no-match. `REVIEW_REQUIRED` is treated as human escalation/abstention and never conflated with automatic match or automatic rejection.
  - **Honest Probability Semantics**: `HighConfidenceMetricsCalculator` and `CalibrationMetricsCalculator` evaluate probability calibration only when valid calibrated probabilities are present. Under the frozen uncalibrated baseline (`calibratedProbability = null`), metrics report `available = false` rather than misrepresenting heuristic composite similarity scores as probabilities.

## Probability Calibration & Threshold Tuning Layer (Phase 8E, 8F)

- Package: `com.spotifyyoutube.migrator.matching.benchmark.calibration`
- Architecture Decisions: ADR-020 (Empirical Probability Calibration and Benchmark Subsystem Freeze)
- Purpose: Estimation of Platt scaling logistic parameters $(A, B)$ on validation split, decision threshold optimization under precision constraints ($\ge 95\%$ or $\ge 98\%$), and held-out test split generalization verification.
- Architecture Rules:
  - **Regularized Targets**: Uses Platt (1999) regularized targets ($t_+ = \frac{N_+ + 1}{N_+ + 2}, t_- = \frac{1}{N_- + 2}$) to prevent divergence on linearly separable validation partitions.
  - **Strict Split Isolation**: Calibration parameter fitting and threshold sweeps are performed strictly on `VALIDATION` split observations ($N = 36$). The `TEST` partition ($N = 30$) is strictly held out for generalization verification.
  - **Monotonicity & Clamping**: Requires $A > 0$ and clamps logits to $[-40.0, 40.0]$ with stable log-sum-exp implementations.
  - **Zero Frozen Code Intrusion**: The matching engine is instantiated via public constructor `DefaultTrackMatchingEngine` with `withValidatedPlattScaling(slopeA, interceptB, modelVersion)` without modifying Phase 5–7 production code.

## Playlist Migration Orchestration & Evaluation Layer (Phase 8G, 8H)

- Packages:
  - Workflow Orchestration: `com.spotifyyoutube.migrator.migration.application.orchestration`
  - Workflow Evaluation: `com.spotifyyoutube.migrator.migration.application.evaluation`
- Architecture Decisions: ADR-021 (Playlist Migration Workflow Orchestration, Deduplication, and Verification)
- Purpose: Connects source playlist metadata and track retrieval $\to$ Phase 5 normalization $\to$ Phase 6 candidate discovery $\to$ Phase 7 calibrated matching $\to$ Phase 4 destination playlist creation and sequential track addition, with comprehensive outcome accounting and offline workflow evaluation.
- Architecture Rules:
  - **Sequential Destination Ordering**: Destination insertions use explicit 0-indexed positions (`processedCount`) matching the destination playlist size, preserving relative source encounter order without holes when intermediate tracks fail or require review.
  - **Partial Progress & Terminal State Integrity**: Individual track errors are caught, sanitized, and stored as `FAILED` tasks while subsequent tracks proceed. Playlist-level startup/metadata/destination failures transition the persistent job to terminal `FAILED` status with completion timestamps, preserving cause for diagnosis while protecting already completed jobs.
  - **Review Holdback**: Ambiguous tracks requiring human review (`REVIEW_REQUIRED`) are held back from automated destination insertion to preserve destination playlist hygiene.
  - **Deduplication**: Destination candidate deduplication prevents inserting duplicate video IDs into the target playlist within the migration run when enabled; allows duplicates when disabled.
  - **Credential & Secret Protection**: All log messages, diagnostic strings, and `MigrationTask.errorMessage` records are sanitized with regex scrubbing of `Bearer` tokens, `access_token=`, `refresh_token=`, `token=`, `api_key=`, `apikey=`, `key=`, `client_secret=`, `secret=`, and `password=` values. Raw exception objects and stack traces are excluded from log statements.
  - **Provider Isolation**: Provider-specific Spotify and YouTube DTOs remain strictly encapsulated in their respective packages; orchestration communicates via domain models and provider-neutral interfaces (`MusicDestination`, `SpotifyPlaylistService`).
  - **Disjoint Metric Partition**: `MigrationWorkflowEvaluator` enforces a mutually exclusive partition where $\text{totalTracks} = \text{successfullyMigrated} + \text{unmatched} + \text{reviewRequired} + \text{duplicatePrevented} + \text{failedTracks}$, distinguishing between aggregate unmigrated database counts in `MigrationExecutionSummary.failedTracks` and genuine processing/API errors in `MigrationWorkflowEvaluationReport.failedTracks`.

## Migration Planning Subsystem (Phase 9A, 9B, 9C)

- Packages:
  - Domain Contracts & Generation: `com.spotifyyoutube.migrator.migration.application.plan`
  - REST Controller: `com.spotifyyoutube.migrator.migration.api`
  - API DTOs: `com.spotifyyoutube.migrator.migration.api.dto`
  - Mappers: `com.spotifyyoutube.migrator.migration.api.mapper`
- Architecture Decisions: ADR-022 (Migration Planning Architecture, Contracts, and Execution Boundary)
- Purpose: Provider-neutral, immutable domain contracts, application-layer generation service, and REST API exposing pre-flight playlist migration previews, per-track planning classifications, and mathematically reconciled aggregate summaries prior to destination mutation.
- Architecture Rules:
  - **Planning vs. Mutation Boundary**: Migration planning evaluates source tracks, candidate discovery, and calibrated matching to produce a preview without calling `MusicDestination.createPlaylist(...)` or `MusicDestination.addTrackToPlaylist(...)`. Planning is strictly read-only with respect to destination services.
  - **Transient In-Memory Preview Model**: `MigrationPlan` instances are transient in-memory domain models with unique `UUID planId` and `Instant createdAt` snapshot timestamps. Database entities (`MigrationJob`, `MigrationTask`) remain dedicated to active/completed execution jobs, avoiding schema pollution and premature Flyway migrations.
  - **Immutable Contracts**: All planning contracts (`MigrationPlan`, `MigrationPlanTrack`, `MigrationPlanSummary`, `PlanTrackClassification`) and API DTOs (`MigrationPlanResponse`, `MigrationPlanTrackResponse`, `MigrationPlanSummaryResponse`, `DestinationCandidateResponse`) MUST be immutable records with defensive collection copying (`List.copyOf`).
  - **Thin Controller & Security Architecture**: `MigrationPlanController` (`POST /api/migrations/preview`) validates requests, resolves user identity securely via authenticated `Principal` (retrieving the user through `UserService`), verifies that any caller-supplied `userId` matches the authenticated user to prevent impersonation, and delegates directly to `MigrationPlanService`. Missing/malformed request bodies are handled by `GlobalExceptionHandler` mapping `HttpMessageNotReadableException` to sanitized HTTP 400 `INVALID_REQUEST` responses without leaking internal Jackson or framework traces.
  - **Deterministic Source Ordering**: Source playlist order is strictly preserved. `MigrationPlan` validates that `tracks.get(i).position() == i` for all indices $0 \le i < \text{tracks.size()}$.
  - **Mutually Exclusive Classifications**: Every source track maps to exactly one of: `AUTO_MATCH`, `REVIEW_REQUIRED`, `UNMATCHED`, `DUPLICATE`, `FAILED`, `EXCLUDED`. Review-required tracks are held out and can never silently become `AUTO_MATCH`.
  - **Exact Reconciliation Invariant**: `MigrationPlanSummary` strictly validates that $\text{totalTracks} = \text{autoMatch} + \text{reviewRequired} + \text{unmatched} + \text{duplicate} + \text{failed} + \text{excluded}$. All counts must be non-negative.
  - **Credential Redaction**: Diagnostics and error messages are sanitized against `TOKEN_PATTERN` to prevent OAuth bearer tokens, API keys, or passwords from appearing in plan previews or logs.
  - **Plan Generation Service (`DefaultPlaylistMigrationPlanGenerator`)**: Coordinates `SpotifyPlaylistService` (source playlist metadata and track retrieval), Phase 5 `NormalizationOrchestrator`, Phase 6 `CandidateDiscoveryService`, and Phase 7 `TrackMatchingEngine`.
  - **Encounter-Order Deduplication**: Tracks candidate IDs assigned to prior tracks in the same plan. When deduplication is active, duplicate candidates are marked `DUPLICATE` without claiming additional destination slots.
  - **Per-Track Fault Isolation**: Exceptions thrown during individual track normalization, candidate discovery, or matching are isolated; error details are redacted, and the track is classified as `FAILED` while remaining tracks in the playlist continue processing.

## Review Subsystem (Phase 10 — Complete & Frozen)

- Packages:
  - Domain & State Machine: `com.spotifyyoutube.migrator.migration.review.domain`
  - Repositories: `com.spotifyyoutube.migrator.migration.review.repository`
  - Application Service: `com.spotifyyoutube.migrator.migration.review.application`
  - Presentation & REST Controllers: `com.spotifyyoutube.migrator.migration.review.api`
  - API Request/Response DTOs: `com.spotifyyoutube.migrator.migration.review.api.dto`
  - API Mappers: `com.spotifyyoutube.migrator.migration.review.api.mapper`
- Database Migration: `V5__phase10_review_session_schema.sql` (`review_sessions`, `review_tracks`)
- Architecture Decisions: ADR-023 (Review Domain Architecture, State Management, and Review Contracts)
- Purpose: Persistent state management and REST API for asynchronous, multi-step human review of migration plans prior to destination playlist creation. Tracks human corrections, candidate replacements, track exclusions, duplicate conflict resolution, and completion eligibility.
- Architecture Rules:
  - **Decoupled Persistence vs Transient Preview**: While Phase 9 `MigrationPlan` instances remain transient, read-only preview snapshots under ADR-022, human review decisions are persisted across requests in dedicated `review_sessions` and `review_tracks` tables.
  - **Zero Execution Subsystem Pollution**: Operational `migration_jobs` and `migration_tasks` remain dedicated to Phase 8 and future Phase 11/12 execution jobs, preventing corruption of operational job logs with pre-execution review states.
  - **Preservation of Automatic Matching Evidence**: `ReviewTrack` strictly separates immutable automatic matching provenance (`originalClassification`, `originalCandidateId`, `originalMatchScore`, `originalCalibratedProbability`, `originalMatchDecision`, `originalMatchReasons`) from mutable review decision states (`reviewStatus`, `decisionSource`, `selectedCandidateId`, `excluded`, `hasConflict`). Original evidence is never overwritten.
  - **No Fabricated Confidence**: User-selected candidates are flagged with `decisionSource = USER_SELECTED`. No synthetic confidence scores or probabilities are generated for manual human choices.
  - **Thin REST Presentation Boundary**: Controllers delegate exclusively to application services (`ReviewSessionService`, `MigrationPlanService`, `UserService`). No domain business logic, no repository interactions, and no direct JPA entity exposure in controllers.
  - **Anti-Impersonation & Access Boundaries**: Authenticated principal verified against requested identifiers, returning HTTP 400 on mismatch and HTTP 404 for unauthorized session access to prevent information disclosure (IDOR).
  - **Safe Sanitization & Quota Transparency**: Error responses are safely sanitized, stripping upstream provider tokens and secrets, while passing HTTP 429 and Retry-After headers transparently.
  - **Centralized Invariants**:
    1. Decisions belong to the correct session and track.
    2. Selected candidates must be valid (non-null, non-blank ID/title, non-negative duration).
    3. Excluded tracks are never treated as approved for migration (`isApprovedForMigration() == false`).
    4. Unresolved duplicate conflicts prevent review completion (`eligibleForCompletion == false`).
    5. Review session cannot complete while required decisions remain unresolved.
    6. Repeated identical decisions are idempotent.
    7. Invalid transitions on closed sessions fail fast with `InvalidStateException`.
    8. Source track order and identity remain unchanged.
    9. Review decisions perform zero YouTube Music mutations (zero `MusicDestination` calls).
    10. Review state mutations never alter Phase 9's read-only preview snapshots.
  - **Reconciled Progress Derivation**: `ReviewProgressSummary` derives counts dynamically from `ReviewTrack` entities with exact mathematical reconciliation, guaranteeing zero counter drift.

## Approved Plan Execution & Validation Layer (Phase 11A, 11B)

- Packages:
  - Contracts: `com.spotifyyoutube.migrator.migration.application.execution.contract`
  - Validation: `com.spotifyyoutube.migrator.migration.application.execution.validation`
  - Application Service: `com.spotifyyoutube.migrator.migration.application.execution`
  - Presentation & API: `com.spotifyyoutube.migrator.migration.api`, `com.spotifyyoutube.migrator.migration.api.dto`, `com.spotifyyoutube.migrator.migration.api.mapper`
- Architecture Decisions: ADR-024 (Approved Plan Execution Architecture, Contracts, and Validation Boundary)
- Purpose: Application-level contracts, approved migration context derivation, and pure validation boundary establishing execution eligibility prior to any destination playlist creation or track insertion.
- Architecture Rules:
  - **Strict Mutation Boundary**: Phase 11A and Phase 11B perform ZERO destination-side mutations (no `MusicDestination.createPlaylist` or `addTrackToPlaylist` calls). Validation is strictly non-mutating and read-only.
  - **Zero Database Schema Intrusion**: Preserves the ADR-022 transient plan guarantee without creating new database tables or entities. Decisions are derived directly from the persisted `ReviewSession` in PostgreSQL.
  - **Terminal COMPLETED Guard**: Only review sessions in `ReviewSessionStatus.COMPLETED` status are eligible for execution. In-progress sessions reject with `INCOMPLETE_REVIEW` and cancelled sessions reject with `INVALID_STATE`.
  - **Deterministic Gapless Target Ordering**: Assigns strictly sequential, 0-indexed destination positions ($0 \le \text{targetPosition} < M$) matching destination playlist size, while strictly preserving relative source playlist encounter order.
  - **All-or-Nothing Safety**: Ineligibility on even one track (awaiting review, unresolved conflict, missing destination identity, duplicate destination ID) causes the entire validation to fail. Partially approved selections are never returned as executable.
  - **Server-Side Security & IDOR Protection**: The authenticated user must own the review session; cross-user session execution access throws `ResourceNotFoundException` (HTTP 404) to prevent information disclosure. Caller-supplied `userId` mismatches reject with HTTP 400.
  - **Evidence & Provenance Preservation**: Immutably preserves original matching evidence and human decision provenance (`AUTOMATIC` vs `USER_SELECTED`) without generating synthetic confidence scores.