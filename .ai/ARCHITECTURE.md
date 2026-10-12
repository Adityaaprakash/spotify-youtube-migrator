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