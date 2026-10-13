# ADR-012: Matching Engine Architecture, Contracts & Candidate Feature Extraction

## Context

Following the completion of the Track Normalization Engine (Phase 5) and YouTube Candidate Discovery (Phase 6), the Spotify-to-YouTube migrator requires a Matching Engine to identify the best destination track for each source Spotify track.

Matching is a multi-stage process:
1. **Phase 7A (Matching Architecture & Contracts):** Establish stable, immutable, provider-neutral contracts for requests, results, decisions, and component score breakdowns.
2. **Phase 7B (Candidate Feature Extraction):** Deterministically extract comparison-ready features and evidence from normalized source metadata and discovery candidates without applying numerical scores or penalties.
3. **Phase 7C+ (Scoring & Pipeline):** Implement component similarity scorers (Title in 7C, Artist, Duration, Album, Official signals), weighted composite scoring, version penalties, candidate ranking, and decision rules.

To maintain modularity and avoid premature coupling:
- Phase 5 (Normalization) and Phase 6 (Candidate Discovery) implementations are frozen and must not be modified.
- Evidence extraction (Phase 7B) must be strictly divorced from numerical similarity scoring and ranking (Phase 7C+).
- Missing evidence (e.g. absent duration or album) must be represented honestly as unavailable (`null`), never coerced to zero.
- Channel authenticity signals must be evaluated conservatively: a channel name containing the word "official" is never treated as conclusive proof on its own.

## Decision

We establish the matching engine foundation in `com.spotifyyoutube.migrator.matching.engine` and `com.spotifyyoutube.migrator.matching.engine.feature`:

### 1. Phase 7A Core Contracts (`com.spotifyyoutube.migrator.matching.engine`)

- **`TrackMatchRequest`:**
  - Encapsulates non-null `NormalizedTrackMetadata` from Phase 5 and an unmodifiable `List<DestinationTrackCandidate>` from Phase 6.
  - Carries an optional `sourceTrackId` (e.g. Spotify external ID) to preserve entity tracking across migration pipelines without inventing an ID inside the pure normalization model.
  - Formally treats empty candidate collections as valid input (e.g. when discovery yields `EMPTY`), resolving cleanly downstream without throwing exceptions.
- **`TrackMatchResult`:**
  - Immutable outcome container holding `NormalizedTrackMetadata`, `MatchDecision`, optional `selectedCandidate`, optional score, calibrated probability, `MatchConfidence`, `MatchScoreBreakdown`, evaluated alternatives (`CandidateMatchEvaluation`), and explainable diagnostic reasons.
  - Enforces selected-candidate consistency against evaluated candidates.
- **`MatchDecision`:**
  - Vocabulary: `MATCH` (high-confidence automated migration), `REVIEW_REQUIRED` (borderline/ambiguous match requiring human verification), and `NO_MATCH` (no candidate met threshold or candidate pool empty).
- **`MatchConfidence`:**
  - Qualitative confidence classification: `EXCELLENT`, `GOOD`, `POOR`.
- **`MatchScoreBreakdown`:**
  - Provider-neutral evidence container for component scores: `titleScore`, `artistScore`, `durationScore`, `albumScore`, `officialSignalScore`, `versionPenalty`, and detailed penalty map.
  - Preserves unavailable evidence honestly as `null` rather than defaulting to `0.0`.
- **`CandidateMatchEvaluation`:**
  - Individual candidate evaluation record encapsulating the candidate, extracted features, score breakdown, raw score, calibrated probability, confidence, and diagnostic notes.

### 2. Phase 7B Feature Extraction (`com.spotifyyoutube.migrator.matching.engine.feature`)

- **`TrackFeatureExtractor` & `DefaultTrackFeatureExtractor`:**
  - Stateless, deterministic Spring component reusing Phase 5 normalizers (`TitleNormalizer`, `ArtistNormalizer`, `MetadataTokenizer`) without duplicating normalization logic.
  - Extracts `SourceTrackFeatures` from `NormalizedTrackMetadata` preserving exact titles, primary and featured artists, albums, durations, and recording variants.
  - Extracts `CandidateTrackFeatures` from `DestinationTrackCandidate` normalizing titles and channel names, parsing durations, and extracting version and official evidence.
- **`VersionIndicator` & `CandidateVersionEvidence`:**
  - Standardized indicators: `COVER`, `LIVE`, `REMIX`, `KARAOKE`, `REACTION`, `NIGHTCORE`, `SPED_UP`, `SLOWED`, `ACOUSTIC`, `INSTRUMENTAL`, `REMASTER`.
  - Captures evidence from bracketed annotations, trailing delimiters, and title prefixes.
  - Implements word-boundary false-positive safeguards: legitimate titles like *Live to Tell*, *Stayin' Alive*, *Under Cover of Darkness*, or *Chain Reaction* are never misclassified as version variants.
  - Detects and flags conflicting indicators (e.g. both `SPED_UP` and `SLOWED`) and unresolved ambiguities.
- **`OfficialSignalEvidence`:**
  - Captures verified signals: auto-generated Topic channels (` - Topic`), VEVO syndication (`VEVO`), artist-channel name equivalence, distributor label delivery descriptions (`Provided to YouTube by...`), and verified audio/video title markers.
  - **Conservative Authenticity Policy:** Unverified uploader claims (e.g. channels containing "official") are flagged in `channelClaimsOfficial`, but strictly excluded from confirming authenticity in `hasOfficialEvidence()`.

## Consequences

### Benefits
- **Strict Separation of Concerns:** Feature extraction produces objective evidence records; scoring algorithms and weights are deferred to Phase 7C+.
- **Honest Missing-Data Semantics:** Absent durations or albums do not penalize tracks with artificial 0.0 scores.
- **Provider Neutrality:** All contracts operate exclusively on application models (`NormalizedTrackMetadata`, `DestinationTrackCandidate`), isolating YouTube DTOs.
- **Complete Immutability & Thread Safety:** All contracts are records with defensive collection copies, ensuring safe parallel execution.

### Tradeoffs
- Evaluated candidates must carry detailed breakdown objects, slightly increasing memory footprint during active matching execution.
