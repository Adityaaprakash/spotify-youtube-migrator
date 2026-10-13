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