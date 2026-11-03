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

## Destination Playlist Creation Layer (Phase 11C)

- Packages:
  - Contracts: `com.spotifyyoutube.migrator.migration.application.execution.contract`
  - Application Service: `com.spotifyyoutube.migrator.migration.application.execution`
  - Presentation & API: `com.spotifyyoutube.migrator.migration.api`, `com.spotifyyoutube.migrator.migration.api.dto`, `com.spotifyyoutube.migrator.migration.api.mapper`
  - Destination Provider: `com.spotifyyoutube.migrator.migration.application.destination`, `com.spotifyyoutube.migrator.youtube.infrastructure.destination`
- Architecture Decisions: ADR-025 (Destination Playlist Creation Architecture and Mutation Safety Boundary)
- Purpose: Orchestrates destination playlist creation on YouTube Music using the server-side validated `ApprovedMigrationContext`. Gated by Phase 11A/11B validation and enforces zero track insertion.
- Architecture Rules:
  - **Gated Validation Boundary**: Before invoking the destination provider, the application resolves the `ApprovedMigrationContext` via `ApprovedMigrationExecutionService.prepareExecution`. If validation fails, zero provider calls are made (`verifyNoInteractions(musicDestination)`).
  - **Zero Track Insertion Boundary**: Phase 11C strictly creates the playlist only (`MusicDestination.createPlaylist`). It NEVER calls `MusicDestination.addTrackToPlaylist(...)` and never inserts tracks. Track insertion is completely isolated and deferred to Phase 11D.
  - **Non-Empty Approved Selection Guard**: Attempts to create a destination playlist for an approved selection with 0 tracks reject with `InvalidStateException` (HTTP 409 Conflict) prior to calling the provider.
  - **Server-Side Security & Anti-Impersonation**: Authenticated user identity is resolved from the trusted security context. Client-supplied `userId` mismatches reject with HTTP 400. Cross-user session access throws `ResourceNotFoundException` (HTTP 404).
  - **Metadata Bounds & Defaults**: Playlist titles are bounded to 150 characters (falling back to "Migrated - " + sourceTitle); descriptions are bounded to 5000 characters.
  - **Idempotency & Ambiguous Timeout Handling**: Network/socket timeouts (`ResourceAccessException`) are logged with high-visibility warnings as `UNCERTAIN` and mapped to HTTP 504 Gateway Timeout. Blind retries are strictly avoided to prevent duplicate playlist creation on the user's YouTube account.
  - **Credential Sanitization**: External provider error messages are scrubbed against `TOKEN_PATTERN` (`bearer`, `access_token=`, `api_key=`, `secret=`) before logging or inclusion in exceptions.

## Ordered Destination Track Insertion Layer (Phase 11D)

- Packages:
  - Contracts: `com.spotifyyoutube.migrator.migration.application.execution.contract`
  - Application Service: `com.spotifyyoutube.migrator.migration.application.execution`
  - Presentation & API: `com.spotifyyoutube.migrator.migration.api`, `com.spotifyyoutube.migrator.migration.api.dto`, `com.spotifyyoutube.migrator.migration.api.mapper`
  - Destination Provider: `com.spotifyyoutube.migrator.migration.application.destination`, `com.spotifyyoutube.migrator.youtube.infrastructure.destination`
- Architecture Decisions: ADR-026 (Ordered Destination Track Insertion and Outcome Handling)
- Purpose: Deterministic, sequential insertion of approved tracks into an existing destination playlist created during Phase 11C, adhering to canonical target position ordering, recording per-track insertion outcomes, and enforcing conservative continuation policies under failure.
- Architecture Rules:
  - **Pre-Mutation Validation & Gating**: Validates execution context and permissions via `ApprovedMigrationExecutionService.prepareExecution` before any destination call. If validation fails, zero provider mutations are executed.
  - **Destination Verification**: Verifies that the destination playlist exists and is accessible under the authenticated user's credentials via `YouTubePlaylistService.getPlaylist(userId, playlistId)` before attempting insertions.
  - **Deterministic Canonical Ordering**: Tracks are inserted sequentially in ascending order of `targetPosition` ($0 \le \text{targetPosition} < M$), passing explicit 0-indexed positions to `MusicDestination.addTrackToPlaylist`.
  - **Sequential Execution**: All insertions are performed synchronously on the calling thread. No asynchronous workers, parallel streams, or background queues are permitted (preserving Phase 12 boundaries and preventing out-of-order provider mutations).
  - **Per-Track Outcome Tracking**: Every approved track receives an immutable `TrackInsertionOutcome` with explicit status (`SUCCESS`, `FAILED`, `UNCERTAIN`, `SKIPPED`).
  - **Conservative Failure-Continuation Policy**:
    - Confirmed Provider Rejection: When a track insertion fails with a confirmed provider rejection, the track is marked `FAILED` and all subsequent unattempted tracks are aborted and marked `SKIPPED` to preserve destination playlist ordering.
    - Ambiguous Network Timeouts: Caught as `ResourceAccessException`, classified as `UNCERTAIN`, and never blindly retried. All subsequent tracks are aborted and marked `SKIPPED`.
    - Reconciliation Guarantee: `totalApprovedTracks == successfulInsertions + failedInsertions + uncertainInsertions + skippedInsertions` is strictly enforced.
  - **Credential & Secret Redaction**: Upstream Bearer and OAuth tokens in error messages are scrubbed (`[REDACTED_TOKEN]`).
  - **Zero Database Schema Intrusion**: Preserves ADR-022 transient plan guarantee without creating new database tables or entities.

## Durable Execution State & Safe Resume Layer (Phase 11E)

- Packages:
  - Persistence & Domain: `com.spotifyyoutube.migrator.migration.execution.domain`, `com.spotifyyoutube.migrator.migration.execution.repository`
  - Contracts: `com.spotifyyoutube.migrator.migration.application.execution.contract`
  - Application Service: `com.spotifyyoutube.migrator.migration.application.execution`
  - Presentation & API: `com.spotifyyoutube.migrator.migration.api`, `com.spotifyyoutube.migrator.migration.api.dto`, `com.spotifyyoutube.migrator.migration.api.mapper`
- Architecture Decisions: ADR-027 (Durable Execution State, Per-Track Outcome Persistence, and Safe Resume Semantics)
- Database Migration: `V6__phase11e_durable_execution_schema.sql` (`migration_executions`, `execution_track_records`)
- Purpose: Durable persistence of approved migration execution state and per-track insertion outcomes in PostgreSQL, establishing process-restart survivability, concurrency claim locking, crash recovery, and deterministic safe resume semantics without duplicate remote insertions.
- Architecture Rules:
  - **Durable Persistence Model**:
    - `MigrationExecution` persists execution lifecycle (`INITIALIZED`, `RUNNING`, `PARTIALLY_COMPLETED`, `COMPLETED`, `FAILED`, `RECOVERY_REQUIRED`), destination playlist identity, user ownership, review session link, and reconciled counters.
    - `ExecutionTrackRecord` persists per-track outcomes (`PENDING`, `ADDING`, `ADDED`, `FAILED`, `UNCERTAIN`, `SKIPPED`), canonical target positions, destination item IDs, attempt counts, and timestamps.
  - **Transaction Boundaries & External API Separation**:
    - Database transactions are kept discrete and short using programmatic `TransactionTemplate`.
    - Long-running YouTube API calls (`MusicDestination.addTrackToPlaylist`) are strictly executed *outside* active database transactions to prevent connection pool exhaustion and locking contention during external network latency.
  - **Concurrency Claim Locking**:
    - Executions must be claimed by setting `status = RUNNING` within a short transaction.
    - Concurrent requests targeting an active `RUNNING` execution are immediately rejected with `InvalidStateException` (HTTP 409 Conflict).
    - Optimistic locking via `@Version` columns protects both `MigrationExecution` and `ExecutionTrackRecord` from stale concurrent updates.
  - **Crash Recovery & Stale State Reconciliation**:
    - If the application crashes or restarts while a track was in `ADDING` status, reload/resume reconciles that track to `UNCERTAIN` and the execution to `RECOVERY_REQUIRED`.
    - Stale `ADDING` tracks are *never* blindly reset to `PENDING` or automatically reinserted, avoiding duplicate playlist tracks on YouTube.
  - **Safe Resume Semantics**:
    - Resuming an execution skips all tracks already confirmed as `ADDED`.
    - Only tracks remaining in `PENDING` status are eligible for automatic insertion, preserving canonical target position order.
    - Terminal executions (`COMPLETED`) cannot be resumed.
  - **ADR-022 Transient Plan Preservation**:
    - Only the execution metadata and `planId` (UUID) are stored; the transient in-memory `MigrationPlan` entity is never persisted.
  - **Synchronous Execution Boundary**:
    - All Phase 11E execution and resume operations are strictly synchronous on the calling thread. No background queues, schedulers, or `@Async` workers are introduced (preserving Phase 12 boundaries).

## Failure Recovery & Partial Rollback Layer (Phase 11F)

- Packages:
  - Failure Classification: `com.spotifyyoutube.migrator.migration.application.execution.failure`
  - Persistence & Domain: `com.spotifyyoutube.migrator.migration.execution.domain`, `com.spotifyyoutube.migrator.migration.execution.repository`
  - Application Service & Contracts: `com.spotifyyoutube.migrator.migration.application.execution`, `com.spotifyyoutube.migrator.migration.application.execution.contract`
  - Destination Adapters: `com.spotifyyoutube.migrator.migration.application.destination`, `com.spotifyyoutube.migrator.youtube.infrastructure`
  - Presentation & API: `com.spotifyyoutube.migrator.migration.api`, `com.spotifyyoutube.migrator.migration.api.dto`, `com.spotifyyoutube.migrator.migration.api.mapper`
- Architecture Decisions: ADR-028 (Failure Recovery and Partial Rollback for Destination Execution)
- Database Migrations:
  - `V7__phase11f_failure_recovery_and_rollback_schema.sql`: `rolled_back_insertions` on `migration_executions`, `execution_rollback_records` audit table.
  - `V8__phase11f_rollback_audit_retention.sql`: Replaces `ON DELETE CASCADE` foreign keys with `ON DELETE RESTRICT` on `execution_rollback_records` to enforce audit retention and prevent accidental deletion of rollback audit history.
- Purpose: Controlled failure recovery for eligible failed executions and safe partial rollback of confirmed inserted items from YouTube playlists with state-level idempotency, in-flight mutation claiming, and immutable audit trails.
- Architecture Rules:
  - **Deterministic Failure Classification**:
    - `ExecutionFailureClassifier` categorizes errors across 7 explicit taxonomies: retryable provider rejections, non-retryable provider rejections, authentication failures, invalid destination resources, local persistence failures, confirmed provider operation failures, and manual intervention states.
    - Context-Aware Mutation Ambiguity: Network timeouts (`ResourceAccessException`) and HTTP 500/503 errors during remote mutations (`destination_insertion` and `destination_deletion`) are evaluated for ambiguity and strictly classified as `MANUAL_INTERVENTION_REQUIRED` (`retryable = false`, `requiresManualIntervention = true`).
    - Ambiguous network timeouts and server errors during mutations remain classified as `UNCERTAIN` and are strictly excluded from blind recovery, preserving Phase 11G as the dedicated reconciliation subsystem.
  - **Controlled Recovery Rules**:
    - Strictly preserves confirmed `ADDED` tracks; never re-adds previously successful tracks.
    - Re-attempts eligible `FAILED` tracks strictly in their canonical 0-indexed destination position order.
    - Rejects recovery on `COMPLETED` executions or executions containing unresolved `UNCERTAIN` tracks (HTTP 409 Conflict).
    - Prevents concurrent recovery by claiming execution status as `RUNNING` within a short transaction.
  - **Safe Partial Rollback Rules**:
    - In-Flight Concurrency Protection: Track status transitions to `ROLLING_BACK` in a short transaction before calling remote provider deletion, preventing duplicate remote DELETE requests from concurrent rollback calls.
    - Provider deletion strictly targets the provider's unique playlist item identifier (`destinationPlaylistItemId`), not YouTube video ID.
    - Rollback requests are confined strictly to verified items belonging to the execution.
    - External provider deletions execute outside database transactions.
    - Remote HTTP 404 responses during deletion are treated as confirmed safe deletions (already removed remotely).
    - State-level idempotency: repeating rollback on an already `ROLLED_BACK` item bypasses remote calls and records `ALREADY_ROLLED_BACK`.
    - Every rollback operation generates an immutable audit record in `execution_rollback_records`.
    - If remote deletion encounters network timeout or 500/503, track outcome transitions to `UNCERTAIN`, audit is recorded as `UNCERTAIN`, and execution status becomes `RECOVERY_REQUIRED`.
    - On confirmed provider rejection (400, 401, 403), track reverts from `ROLLING_BACK` to `ADDED` and audit is recorded as `FAILED`.
    - Updates execution status to `ROLLED_BACK` (if `successful == 0 && rolledBack > 0`) or `PARTIALLY_ROLLED_BACK`.
  - **Security & Authorization**:
    - Anti-impersonation: verifies caller-supplied `userId` matches authenticated `Principal`.
    - Anti-IDOR: verifies execution ownership before any recovery or rollback mutation.
  - **Concurrency & Locking**:
    - Atomic cross-instance conditional query `claimExecutionRunningWithVersion` in `MigrationExecutionRepository` enforcing operation-specific eligible statuses and optimistic lock version matching.
    - Atomic cross-instance track claim query `claimTrackRollingBack` in `ExecutionTrackRecordRepository` preventing concurrent remote deletion invocations.
    - 5-minute lease threshold (`DEFAULT_STALE_THRESHOLD`) in `reconcileStaleCrashState` protecting active slow provider mutations.
    - Consistent HTTP 409 Conflict translation for all claim conflicts and optimistic locking exceptions (`OptimisticLockException`, `ObjectOptimisticLockingFailureException`).
    - Exact derived counter reconciliation in `reconcileCounts()` preventing duplicate increments or counter drift across partial rollbacks.
    - Optimistic locking `@Version` on `migration_executions` and `execution_track_records`.
  - **Strict Boundaries**:
    - Zero background threads, queues, or scheduled tasks (Phase 12 boundary).
    - Ambiguous mutation reconciliation completed in Phase 11G.
    - Zero modifications to frozen Phase 5–10 production code.

## Execution Reconciliation Layer (Phase 11G — Complete & Audited)

- Packages:
  - Persistence & Domain: `com.spotifyyoutube.migrator.migration.execution.domain`, `com.spotifyyoutube.migrator.migration.execution.repository`
  - Application Service & Contracts: `com.spotifyyoutube.migrator.migration.application.execution`, `com.spotifyyoutube.migrator.migration.application.execution.contract`
  - Destination Adapters: `com.spotifyyoutube.migrator.migration.application.destination`, `com.spotifyyoutube.migrator.migration.application.destination.model`, `com.spotifyyoutube.migrator.youtube.infrastructure`
  - Presentation & API: `com.spotifyyoutube.migrator.migration.api`, `com.spotifyyoutube.migrator.migration.api.dto`, `com.spotifyyoutube.migrator.migration.api.mapper`
- Architecture Decisions: ADR-029 (Ambiguous Outcome Reconciliation & Remote State Verification)
- Database Migrations:
  - `V9__phase11g_reconciliation_schema.sql`: Table `execution_reconciliation_records` with `ON DELETE RESTRICT` foreign keys referencing `migration_executions` and `execution_track_records`, with performance indexes.
- Purpose: Safe, auditable reconciliation subsystem comparing local execution records with actual destination YouTube playlist state, resolving ambiguous remote mutations where sufficient evidence exists without guesswork.
- Architecture Rules:
  - **Core Engineering Principle**:
    - Reconciliation is evidence-driven state resolution, not retry logic.
    - Never infer that an insertion succeeded simply because an item with the same video ID exists.
    - Never infer that a mutation failed because an API request timed out.
    - Never interpret an incomplete remote playlist response as proof of absence.
    - When evidence is insufficient, preserve `UNCERTAIN` and require further investigation.
  - **Remote Playlist Inspection**:
    - `MusicDestination.inspectPlaylist` and `YouTubePlaylistService.inspectPlaylist` traverse remote playlist items using YouTube Data API v3 (`GET /playlistItems`).
    - Defensive traversal: 50-item pages, cycle detection on `pageToken`, and a maximum limit of 100 pages (5,000 items).
    - All-or-Nothing Completeness: If any page fails (HTTP error, timeout, malformed payload), `complete = false` and items are discarded. An incomplete response is NEVER treated as a full playlist snapshot.
  - **Evidence-Driven Insertion Reconciliation**:
    - `CONFIRMED_INSERTED`: Unassigned remote item exists with matching `destinationTrackId` and `publishedAt >= track.startedAt - 30s`. Transitions track to `ADDED` and records playlist-item ID.
    - `CONFIRMED_NOT_INSERTED`: Inspection completed 100% successfully and zero matching video items exist. Transitions track to `FAILED` (enabling subsequent safe Phase 11F recovery).
    - `AMBIGUOUS`: Matching item predates the execution attempt (already in playlist) or multiple unassigned matches exist. Preserves `UNCERTAIN` and `RECOVERY_REQUIRED`.
    - `INSPECTION_FAILED`: Provider inspection failed or was incomplete. Preserves `UNCERTAIN` and `RECOVERY_REQUIRED`.
  - **Evidence-Driven Deletion / Rollback Reconciliation**:
    - Uses exact `destinationPlaylistItemId` as authoritative identity.
    - `CONFIRMED_DELETED`: Exact item ID is absent from a complete, successful remote inspection. Transitions track to `ROLLED_BACK`.
    - `CONFIRMED_PRESENT`: Exact item ID is present in the remote playlist. Transitions track to `ADDED` (enabling subsequent safe rollback).
    - `AMBIGUOUS` / `INSPECTION_FAILED`: Incomplete snapshot or missing item ID preserves `UNCERTAIN`.
  - **Lease-Aware Stale In-Flight Reconciliation**:
    - Active operations within the 5-minute lease threshold are protected and untouched.
    - Stale operations exceeding 5 minutes in `ADDING` or `ROLLING_BACK` are safely reconciled via insertion or deletion inspection rules.
  - **Distributed Concurrency & Transaction Boundaries**:
    - Short Transaction #1: Claim execution via `claimExecutionRunningWithVersion` and identify target tracks.
    - External Inspection: Out-of-transaction external HTTP call to YouTube Data API.
    - Short Transaction #2: Apply domain transitions, persist `ExecutionReconciliationRecord` audit rows, reconcile aggregate counters via `releaseExecution()`.
    - Track-level claim: `claimTrackReconciling`.
  - **Security & Authorization**:
    - Anti-impersonation: verifies caller-supplied `userId` matches authenticated `Principal`.
    - Anti-IDOR: verifies execution ownership before any reconciliation inspection or status retrieval.
  - **Strict Boundaries**:
    - Zero background threads, workers, queues, or scheduled tasks (Phase 12 boundary).
    - Strictly synchronous execution triggered via explicit REST API.
    - Zero mutations or retries issued during reconciliation.
    - Zero modifications to frozen Phase 5–10 production code.

## End-to-End Migration Orchestration & Subsystem Freeze (Phase 11H — Complete & Frozen)

- Packages:
  - Application Service & Facade: `com.spotifyyoutube.migrator.migration.application.execution`
  - Contracts & DTOs: `com.spotifyyoutube.migrator.migration.application.execution.contract`, `com.spotifyyoutube.migrator.migration.api.dto`
  - Presentation & API: `com.spotifyyoutube.migrator.migration.api`, `com.spotifyyoutube.migrator.migration.api.mapper`
  - Persistence & Repositories: `com.spotifyyoutube.migrator.migration.execution.domain`, `com.spotifyyoutube.migrator.migration.execution.repository`
- Architecture Decisions: ADR-030 (Phase 11 End-to-End Migration Orchestration, Production Hardening & Subsystem Freeze)
- Purpose: High-level orchestration facade coordinating the entire Phase 11 approved migration lifecycle into a unified, safe, auditable synchronous workflow, with formal freeze of the Phase 11 subsystem.
- Architecture Rules:
  - **Thin Orchestration Facade**:
    - `ApprovedMigrationOrchestrator` / `DefaultApprovedMigrationOrchestrator` coordinates existing domain services (`ApprovedMigrationExecutionService`, `DestinationPlaylistCreationService`, `MigrationExecutionStateService`).
    - Does not duplicate or reimplement validation, playlist creation, track insertion, or reconciliation rules.
  - **Orchestration Execution Invariants**:
    - Gated Validation: Pre-mutation validation (Phase 11B) must succeed before any destination writes.
    - Idempotent Execution: If an execution is already `COMPLETED`, returns the existing summary without redundant mutations.
    - Active Run Protection: Fails fast with `InvalidStateException` (HTTP 409 Conflict) if an execution is already `RUNNING`.
    - Uncertainty Protection: Fails fast with `InvalidStateException` (HTTP 409 Conflict) if an execution is in `RECOVERY_REQUIRED`, requiring reconciliation before resume.
  - **Complete Execution State Machine**:
    - 8 execution statuses: `INITIALIZED`, `RUNNING`, `PARTIALLY_COMPLETED`, `COMPLETED`, `FAILED`, `RECOVERY_REQUIRED`, `ROLLED_BACK`, `PARTIALLY_ROLLED_BACK`.
    - 8 track statuses: `PENDING`, `ADDING`, `ADDED`, `FAILED`, `SKIPPED`, `UNCERTAIN`, `ROLLING_BACK`, `ROLLED_BACK`.
    - All state transitions guarded by pre-condition checks; terminal states (`COMPLETED`, `ROLLED_BACK`) are immutable.
  - **Audit Retention Invariants**:
    - `execution_rollback_records` (V8) and `execution_reconciliation_records` (V9) enforce `ON DELETE RESTRICT` foreign keys, preventing parent execution deletion from erasing audit history.
  - **Capacity & Quota Bounding**:
    - YouTube Data API quota modeled across 50, 100, 500, and 1,000 track playlists.
    - Standard 10,000 units/day quota accommodates complete migration of playlists up to 100 tracks in a single window.
  - **Formal Subsystem Freeze**:
    - Phase 11 is complete, verified, and **FROZEN**.
    - Strict boundary with Phase 12: Zero asynchronous workers, message queues, scheduled tasks, SSE, or WebSockets in Phase 11.

## Async Migration Architecture & Contracts (Phase 12A — Design Complete)

- Packages:
  - Async Orchestration & Dispatcher: `com.spotifyyoutube.migrator.migration.async`
  - Application Service & Workers: `com.spotifyyoutube.migrator.migration.application.execution`
  - Contracts & DTOs: `com.spotifyyoutube.migrator.migration.api.dto`, `com.spotifyyoutube.migrator.migration.application.execution.contract`
  - Presentation & API: `com.spotifyyoutube.migrator.migration.api`
  - Persistence & Repositories: `com.spotifyyoutube.migrator.migration.execution.domain`, `com.spotifyyoutube.migrator.migration.execution.repository`
- Architecture Decisions: ADR-022 (Async Migration Architecture & Contracts)
- Purpose: Decouples long-running playlist migrations from HTTP request/response lifecycles, exposing non-blocking submission returning HTTP 202 Accepted, bounded background worker execution via Spring `ThreadPoolTaskExecutor`, and lightweight progress polling.
- Architecture Rules:
  - **Authoritative Domain Aggregate**:
    - Reuses `MigrationExecution` as the single authoritative aggregate representing migration jobs.
    - Zero duplicate `MigrationJob` entities introduced.
    - Lifecycle extends to include `QUEUED` status: `QUEUED -> RUNNING -> COMPLETED / PARTIALLY_COMPLETED / FAILED / RECOVERY_REQUIRED`.
  - **Bounded Worker Pool Configuration**:
    - Spring `ThreadPoolTaskExecutor`: `corePoolSize = 2`, `maxPoolSize = 4`, `queueCapacity = 50`, `threadNamePrefix = "migration-worker-"`.
    - Rejection policy: `AbortPolicy` mapping to HTTP 429 Too Many Requests when executor queue is saturated.
    - Graceful shutdown: `waitForTasksToCompleteOnShutdown = true`, `awaitTerminationSeconds = 30`.
  - **Database Connection Starvation Prevention**:
    - Programmatic discrete transactions (`TransactionTemplate`): zero DB connections held during external YouTube HTTP calls.
    - Workers consume database connections only for brief status updates (2–5ms).
  - **Cross-Instance Atomic Claiming**:
    - Row-level conditional update in PostgreSQL (`claimExecutionRunningWithVersion`): prevents duplicate worker execution across threads or cluster nodes.
  - **Lightweight Progress Polling**:
    - `GET /api/migrations/{id}/progress` returns lightweight snapshot DTO without loading bulky per-track collection into memory.
    - Mathematical progress invariant: `processedTracks = addedTracks + failedTracks + uncertainTracks + skippedTracks`.
    - `progressPercentage = (processedTracks * 100.0) / totalTracks`.
  - **Crash and Restart Limitations**:
    - In-memory queue tasks do not survive JVM process restarts.
    - Database entity retains `QUEUED` or `RUNNING` status; stale in-flight operations reconciled via 5-minute lease threshold (`DEFAULT_STALE_THRESHOLD`).
    - Durable polling dispatchers and automatic crash recovery deferred to Phase 13 Reliability Engineering.

## Migration Job Persistence & Queue Alignment (Phase 12B — Complete & Audited)

- Packages:
  - Domain & State Machine: `com.spotifyyoutube.migrator.migration.execution.domain`
  - Repositories: `com.spotifyyoutube.migrator.migration.execution.repository`
  - Application Service: `com.spotifyyoutube.migrator.migration.application.execution`
- Architecture Decisions: ADR-022 (Section 3: Migration Execution Model Alignment & Phase 12B Persistence)
- Purpose: Introduces persistent queue-state lifecycle support into the existing `MigrationExecution` aggregate and application service contracts, ensuring atomic worker claiming without duplicate job models.
- Architecture Rules:
  - **Single Aggregate Enforcement**:
    - Reuses `MigrationExecution` entity (`migration_executions` table) with `@Version` optimistic locking, user ownership, review session association, and child `ExecutionTrackRecord` entries.
    - Zero new entities (`AsyncMigrationJob`, etc.) created; legacy `MigrationJob` is completely bypassed.
  - **QUEUED State Semantics & Resume Protection**:
    - Enum value `QUEUED` added to `MigrationExecutionStatus`.
    - `MigrationExecutionStatus.canResume()` strictly returns `false` for `QUEUED`. A queued execution is waiting for worker dispatch, not resuming an interrupted or failed execution.
    - `MigrationExecutionStatus.canRollback()` strictly returns `false` for `QUEUED` executions.
    - `MigrationExecution.markQueued()` enforces valid transition from `INITIALIZED` to `QUEUED` and is idempotent if already `QUEUED`.
  - **Queue Persistence Operations**:
    - `MigrationExecutionStateService.queueExecution(executionId, authenticatedUserId)`: Transitions an existing `INITIALIZED` execution to `QUEUED`.
    - `MigrationExecutionStateService.initializeQueuedExecution(request, authenticatedUserId)`: Idempotently creates or transitions an execution directly into `QUEUED` status with approved track records.
    - Both methods enforce anti-IDOR user ownership via `ResourceNotFoundException`.
  - **Atomic Conditional Worker Claiming**:
    - `DefaultMigrationExecutionStateService.processInsertion` includes `MigrationExecutionStatus.QUEUED` in `eligibleStatuses`.
    - PostgreSQL row-level conditional update in `MigrationExecutionRepository.claimExecutionRunningWithVersion(id, eligibleStatuses, expectedVersion, now)` atomically claims the execution row and increments `@Version`.
    - If multiple workers attempt to claim the same queued execution, exactly one succeeds (returns 1); competing claims return 0 and fail fast with `InvalidStateException` before performing any external YouTube API mutations.
  - **Schema & Flyway Compatibility**:
    - Table column `migration_executions.status` is `VARCHAR(50) NOT NULL` (Flyway V6) without CHECK constraints. Zero Flyway DDL migrations required.

## Background Worker Engine & TaskExecutor Configuration (Phase 12C — Complete & Audited)

- Packages:
  - Async Configuration & Dispatcher: `com.spotifyyoutube.migrator.migration.async`
  - Worker & Application Service: `com.spotifyyoutube.migrator.migration.async`, `com.spotifyyoutube.migrator.migration.application.execution`
  - Contracts & Facade: `com.spotifyyoutube.migrator.migration.application.execution`, `com.spotifyyoutube.migrator.migration.application.execution.contract`
  - Domain & State Machine: `com.spotifyyoutube.migrator.migration.execution.domain`
- Architecture Decisions: ADR-022 (Section: Background Worker Engine, Dispatcher, and Executor Configuration)
- Purpose: Provides the internal asynchronous execution infrastructure, executing approved migration track insertions on bounded background worker threads while ensuring strict transaction boundaries, atomic QUEUED-only database claiming, and orphan prevention upon executor saturation.
- Architecture Rules:
  - **Bounded TaskExecutor Configuration (`AsyncMigrationConfig`)**:
    - Bean: `ThreadPoolTaskExecutor` named `migrationTaskExecutor`.
    - Pool settings: `corePoolSize = 2`, `maxPoolSize = 4`, `queueCapacity = 50`, `threadNamePrefix = "migration-worker-"`.
    - Rejection policy: Explicit `ThreadPoolExecutor.AbortPolicy` throwing `TaskRejectedException`.
    - ThreadPool scaling semantics: Tasks 1-2 start worker threads; tasks 3-52 buffer in bounded queue; tasks 53-54 expand worker pool to maxPoolSize (4); task 55+ rejects immediately.
    - Graceful shutdown: `waitForTasksToCompleteOnShutdown = true`, `awaitTerminationSeconds = 30`.
  - **Two-Stage Orchestration Boundary**:
    - **Stage 1 (Synchronous Preparation)**: `ApprovedMigrationOrchestrator.prepareQueuedMigration(...)` validates approved plan, creates YouTube destination playlist (satisfying Flyway V6 `destination_playlist_id NOT NULL` constraint), and persists durable `MigrationExecution` in `QUEUED` status with child track records via `initializeQueuedExecution`.
    - **Stage 2 (Background Dispatch)**: `MigrationJobDispatcher` submits a lightweight task passing only `executionId` and `authenticatedUserId` to `ThreadPoolTaskExecutor`.
  - **QUEUED-Only Database Claim Contract**:
    - `MigrationExecutionStateService.executeQueuedTrackInsertion(executionId, authenticatedUserId)` enforces `eligibleStatuses = Set.of(MigrationExecutionStatus.QUEUED)`.
    - Background workers cannot claim or hijack executions in `INITIALIZED`, `PARTIALLY_COMPLETED`, or `RECOVERY_REQUIRED` states.
    - Preserves row-level conditional locking query (`claimExecutionRunningWithVersion`) preventing competing workers from processing the same execution.
  - **Executor Saturation & Orphan Prevention**:
    - When `ThreadPoolTaskExecutor` rejects submission (`TaskRejectedException`), `MigrationJobDispatcher` invokes `MigrationExecutionStateService.handleDispatchRejection(...)`.
    - Invokes domain transition `MigrationExecution.resetToInitialized()`, moving status from `QUEUED` to `INITIALIZED`.
    - Guarantees zero orphaned `QUEUED` executions without active workers in the database.
    - Preserves destination playlist ID and track records so job can be safely retried or handled by Phase 13 durable recovery.
    - Re-throws `TaskRejectedException` for upstream HTTP 429 Too Many Requests mapping.
  - **Worker Execution & Error Isolation (`MigrationBackgroundWorker`)**:
    - Worker delegates exclusively to `ApprovedMigrationOrchestrator.executeQueuedMigration(executionId, authenticatedUserId)`.
    - Worker never calls destination YouTube API or state service directly.
    - Does not blindly overwrite execution status to `FAILED` on error; preserves Phase 11 recovery classifications (`RECOVERY_REQUIRED`, `PARTIALLY_COMPLETED`, `UNCERTAIN`).
    - Structured logging with execution duration; strict redaction of credentials and OAuth tokens.
  - **Transaction & Connection Pool Boundaries**:
    - Zero `@Transactional` on worker or executor boundary.
    - Discrete programmatic transactions (`TransactionTemplate`) inside `DefaultMigrationExecutionStateService` ensure database connections are released during external YouTube API network calls.

## Migration Progress Tracking & Calculation (Phase 12D — Complete & Audited)

- Packages:
  - Progress Domain & Contracts: `com.spotifyyoutube.migrator.migration.progress.domain`
  - Application Service: `com.spotifyyoutube.migrator.migration.progress.application`
  - Persistence & Repositories: `com.spotifyyoutube.migrator.migration.execution.repository`
- Architecture Decisions: ADR-022 (Section: Progress Tracking Model & Mathematical Invariants)
- Purpose: Provides a fast, mathematically consistent, non-mutating read layer that calculates real-time progress snapshots directly from persisted track execution records.
- Architecture Rules:
  - **Immutable Progress Snapshot (`MigrationProgressSnapshot`)**:
    - Encapsulates execution ID, current `MigrationExecutionStatus`, total tracks, counts across all 8 `ExecutionTrackStatus` categories, processed tracks, remaining tracks, and progress percentage.
    - Processed tracks definition:
      $$\text{processedTracks} = \text{addedTracks} + \text{failedTracks} + \text{uncertainTracks} + \text{skippedTracks} + \text{rolledBackTracks}$$
    - Remaining tracks definition:
      $$\text{remainingTracks} = \max(0, \text{totalTracks} - \text{processedTracks})$$
    - Progress percentage calculation:
      $$\text{progressPercentage} = \min\left(100.0, \max\left(0.0, \text{roundToOneDecimal}\left(\frac{\text{processedTracks} \times 100.0}{\text{totalTracks}}\right)\right)\right)$$
    - Zero-Track Policy: Returns 0.0% progress when `totalTracks == 0`. Never divides by zero, returns NaN, or fabricates 100%.
    - Strict validation: All counts must be non-negative.
  - **Dedicated Read-Only Application Service (`MigrationProgressService`)**:
    - `DefaultMigrationProgressService` is annotated with `@Transactional(readOnly = true)`.
    - Zero entity mutations: never alters `MigrationExecution` or `ExecutionTrackRecord` rows.
    - Zero external provider calls: does not call YouTube or Spotify APIs.
    - Enforces anti-IDOR authorization: verifies `execution.getUser().getId().equals(authenticatedUserId)`. Unauthorized access throws `ResourceNotFoundException` (HTTP 404) to prevent resource enumeration.
  - **Indexed Database Aggregation Query (`TrackStatusCount`)**:
    - `ExecutionTrackRecordRepository.countTracksByStatusForExecution(executionId)` executes a single `GROUP BY t.status` aggregation query.
    - Leverages existing index `idx_executiontracks_execution_status` on `(execution_id, status)` from Flyway V6.
    - Bounded result set (at most 8 rows) eliminating N+1 queries and avoiding loading heavy track collections into JVM memory.
    - Any status absent from the query result defaults to 0 in an `EnumMap`.
  - **Real-Time Active Execution Visibility**:
    - Recognizes that while `MigrationExecution` summary counters are only updated at execution boundaries, individual rows in `execution_track_records` are committed after every track mutation.
    - Querying `execution_track_records` provides true, real-time progress while an execution is `RUNNING` in a background worker.

## Async Migration REST API, Hardening & Integrated Audit (Phase 12E — Complete & Audited)

- Packages:
  - Presentation & Controllers: `com.spotifyyoutube.migrator.migration.api`
  - Presentation DTOs: `com.spotifyyoutube.migrator.migration.api.dto`
  - Async Dispatcher & Service: `com.spotifyyoutube.migrator.migration.async`, `com.spotifyyoutube.migrator.migration.progress.application`
  - Exception Handling: `com.spotifyyoutube.migrator.common.exception`
- Architecture Decisions: ADR-022 (Section: REST Presentation Layer, Hardening & Progress Endpoints)
- Purpose: Exposes asynchronous migration submission and real-time progress retrieval through secure, non-mutating REST endpoints, returning HTTP 202 Accepted with canonical Location headers (or HTTP 200 OK for idempotent completed submissions), mapping worker saturation to HTTP 429 with Retry-After, and enforcing mandatory constructor injection, anti-IDOR authorization, and strict dual-routing backward compatibility.
- Architecture Rules:
  - **DTO Decoupling & Immutability**:
    - `MigrationSubmissionResponse`: API response DTO exposing `executionId`, `reviewSessionId`, `status`, `progressUrl`, `message`, `totalTracks`, and `submittedAt`. Factory methods `from(summary)` and `of(...)`. Zero domain or JPA entity leakage; zero OAuth token or credential exposure.
    - `MigrationProgressResponse`: API response DTO mapping 1-to-1 from `MigrationProgressSnapshot`. Exposes `executionId`, `status`, counts for all 8 track statuses (`pendingTracks`, `addingTracks`, `addedTracks`, `failedTracks`, `uncertainTracks`, `skippedTracks`, `rollingBackTracks`, `rolledBackTracks`), and calculated metrics (`totalTracks`, `processedTracks`, `remainingTracks`, `progressPercentage`). Factory method `from(snapshot)`. Zero recalculation in controller or DTO.
  - **Submission Endpoint & Dual-Routing (`POST /api/migrations`)**:
    - Dual-routing behavior preserving 100% backward compatibility for legacy Phase 1 tests without Spring MVC route ambiguity:
      - If request body contains `reviewSessionId`, `sessionId`, `targetPlaylistTitle`, or `targetPlaylistDescription`, routes to Phase 12E asynchronous dispatch (`submitAsyncMigration`), returning `HTTP 202 Accepted` (or `HTTP 200 OK` if already `COMPLETED`), `Location: /api/migrations/{executionId}/progress`, and `MigrationSubmissionResponse`.
      - If request body contains `sourcePlatform` or `targetPlatform`, routes to legacy Phase 1 synchronous job creation (`createLegacyMigration`), returning `HTTP 201 Created`.
      - Unrecognized payloads fail fast with `HTTP 400 Bad Request`, preventing malformed requests from silently falling through into legacy handlers.
    - Dedicated direct route `@PostMapping("/submit")` also provided for unambiguous approved async submission.
  - **Canonical Location Header**:
    - Returned on `HTTP 202 Accepted` and `HTTP 200 OK` pointing to `/api/migrations/{executionId}/progress`.
    - Uses `progressUrl` generated from the persisted execution aggregate.
  - **Progress Retrieval Endpoints (`GET /api/migrations/{executionId}/progress` & `GET /api/migrations/review-sessions/{reviewSessionId}/progress`)**:
    - Both endpoints delegate to `MigrationProgressService` (`getProgress` and `getProgressByReviewSession`).
    - Return identical `MigrationProgressResponse` payloads.
    - Annotated with `CacheControl.noCache()`.
    - Zero execution or track record mutation during polling; zero YouTube/Spotify API requests; zero background worker dispatch.
  - **Mandatory Constructor Injection & Production Startup Safety**:
    - `MigrationController` enforces strict non-null constructor injection for all mandatory components (`MigrationService`, `MigrationJobDispatcher`, `MigrationProgressService`, `UserService`, `ObjectMapper`, `Validator`) with zero `@Autowired(required = false)`.
  - **Security, Ownership & Anti-IDOR Enforcement**:
    - Resolves authenticated user identity via `Principal` or Spring Security `Authentication`.
    - Anonymous or missing principals fail fast with `HTTP 401 Unauthorized` (`InsufficientAuthenticationException` mapped by `GlobalExceptionHandler`).
    - Client-supplied `userId` mismatch is rejected with `HTTP 400 Bad Request` (`IllegalArgumentException`).
    - Both execution and review-session lookups enforce ownership through `MigrationProgressService` which throws `ResourceNotFoundException` (HTTP 404) for unauthorized or nonexistent IDs, preventing cross-user migration enumeration.
  - **Executor Saturation & Error Mapping**:
    - `GlobalExceptionHandler` maps `TaskRejectedException` to `HTTP 429 Too Many Requests` with header `Retry-After: 30` and `ErrorCode.TOO_MANY_REQUESTS`.
    - `AccessDeniedException` maps to `HTTP 403 Forbidden` (`ErrorCode.FORBIDDEN`).
    - `AuthenticationException` maps to `HTTP 401 Unauthorized` (`ErrorCode.UNAUTHORIZED`).
    - `InvalidStateException` maps to `HTTP 409 Conflict` (`ErrorCode.INVALID_STATE`).