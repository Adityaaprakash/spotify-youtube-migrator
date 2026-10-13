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

## Matching Engine Layer (Phase 7A & 7B)

- Packages: `com.spotifyyoutube.migrator.matching.engine`, `com.spotifyyoutube.migrator.matching.engine.feature`
- Purpose: Provider-neutral contracts and deterministic candidate feature extraction for comparing normalized source tracks against discovered YouTube candidates.
- Architecture Rules:
  - Phase 7A contracts (`TrackMatchRequest`, `TrackMatchResult`, `MatchDecision`, `MatchScoreBreakdown`, `CandidateMatchEvaluation`, `MatchConfidence`) MUST be immutable records with defensive copying for all collections and maps.
  - Phase 7A contracts MUST NOT invent source identifiers inside the pure normalization model; `TrackMatchRequest` carries optional `sourceTrackId` alongside `NormalizedTrackMetadata`.
  - Empty candidate collections MUST be treated as explicitly valid input (e.g. following legitimate `EMPTY` discovery), cleanly producing `NO_MATCH` without throwing exceptions.
  - `MatchScoreBreakdown` MUST represent missing or uncomputed evidence honestly as `null`, avoiding premature defaults to `0.0`.
  - `CandidateMatchEvaluation` separates candidate-level metrics and feature representations from overall match resolution.
  - `TrackFeatureExtractor` and `DefaultTrackFeatureExtractor` MUST be pure, deterministic, and stateless, reusing Phase 5 normalizers (`TitleNormalizer`, `ArtistNormalizer`, `MetadataTokenizer`) without modifying frozen Phase 5 or Phase 6 code.
  - Feature extraction captures `VersionIndicator` indicators (`COVER`, `LIVE`, `REMIX`, `KARAOKE`, `REACTION`, `NIGHTCORE`, `SPED_UP`, `SLOWED`, `ACOUSTIC`, `INSTRUMENTAL`, `REMASTER`) with word-boundary safeguards preventing false positives on legitimate titles (*Live to Tell*, *Stayin' Alive*, *Chain Reaction*).
  - Official provenance evidence (`OfficialSignalEvidence`) MUST be evaluated conservatively: channels claiming "official" without verified provider markers (Topic channel, VEVO, artist match, distributor description) MUST NOT confirm authenticity on their own.
  - Feature extraction MUST NOT implement similarity scoring, weighted composite scoring, candidate ranking, confidence classification, or automatic match decisions (strictly deferred to Phase 7C+).