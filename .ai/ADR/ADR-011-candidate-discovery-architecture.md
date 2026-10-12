# ADR-011: YouTube Candidate Discovery Architecture & Contracts

## Context

Phase 5 established the Track Normalization Engine, transforming heterogeneous track metadata into immutable `NormalizedTrackMetadata`. In Phase 6 (YouTube Candidate Discovery), the system must query YouTube/YouTube Music to locate potential matches for each normalized source track before passing them to the downstream Phase 7 Matching Engine.

Interacting with YouTube Data API v3 introduces severe operational constraints:
1. **API Quota Economics:**
   - Default YouTube Data API v3 project quota is **10,000 units/day**.
   - A `search.list` request consumes **100 units** (1% of total daily quota).
   - In contrast, a `videos.list` (metadata/duration hydration) request consumes only **1 unit**.
   - Naive, unbounded, or uncoordinated searching would exhaust daily quotas within 50–100 track migrations.
2. **Provider Isolation & Domain Boundary Rules:**
   - In accordance with ADR-002 and ADR-004, YouTube provider DTOs (`YouTubeSearchListResponseDto`, `YouTubeSearchResultDto`, etc.) must never leak into domain or matching packages.
   - The candidate discovery subsystem must consume provider-neutral contracts (`MusicDestination`, `DestinationTrackCandidate`) established in Phase 4G.
3. **Phase Separation:**
   - Candidate discovery is strictly responsible for locating prospective candidates (recall-oriented).
   - Scoring, similarity weighting, confidence thresholds, and candidate ranking are strictly deferred to Phase 7 (precision-oriented).

## Decision

We establish the candidate discovery architecture and public contracts in `com.spotifyyoutube.migrator.matching.discovery`:

### 1. Discovery Input Contract
- Discovery operations are initiated via `CandidateDiscoveryRequest`, taking `NormalizedTrackMetadata` (from Phase 5) and configurable `DiscoveryOptions`.
- `DiscoveryOptions` enforces configurable quota bounds:
  - `maxCandidates`: bounded total candidates to retain (1 to 50, default: 20, targeting 10–20 candidates).
  - `maxQueries`: maximum complementary queries dispatched per track (1 to 5, default: 2).
  - `resultsPerQuery`: limit per individual provider search request (1 to 50, default: 10).
  - `enabledStrategies`: set of allowed query generation strategies.

### 2. Deterministic Search Query Generation
- Defined under interface `SearchQueryGenerator`, producing an ordered list of immutable `SearchQuery` objects with priority rankings.
- Queries are categorized by `SearchQueryStrategy`:
  - `PRIMARY_TITLE_ARTIST`: Primary artist and clean title (highest-fidelity baseline).
  - `TITLE_ARTIST_FEATURED`: Primary artist, featured artists, and clean title.
  - `TITLE_ARTIST_ALBUM`: Primary artist, clean title, and album title for disambiguation.
  - `TITLE_ARTIST_VERSION`: Preserves variant/version tokens (e.g., Live, Acoustic, Remix).
  - `RAW_TITLE_AND_ARTIST`: Raw unstripped title fallback.
  - `CLEAN_TITLE_ONLY`: Broad fallback for obscure, soundtrack, or single-name credits.
- Query strings are strictly validated (non-blank, trimmed, <= 500 characters).

### 3. Destination Abstraction & Model Reuse
- Discovery leverages the existing `MusicDestination` interface and its implementation `YouTubeMusicDestinationAdapter`.
- Candidate data reuses `DestinationTrackCandidate` from `com.spotifyyoutube.migrator.migration.application.destination.model`.
- Candidate provenance is preserved via `DiscoveredCandidate`, pairing `DestinationTrackCandidate` with the discovering `SearchQuery`.

### 4. Bounded Collection & Deduplication
- Candidates are deduplicated by their unique provider ID (e.g., YouTube `videoId`).
- Encounter order is strictly preserved: the highest-priority query that returns a candidate dictates its initial position.
- Once `maxCandidates` is reached, downstream queries are skipped to conserve API quota.

### 5. Explicit Outcome Semantics & Failure Handling
- Outcomes are modeled through `CandidateDiscoveryResult` and `CandidateDiscoveryStatus`:
  - `SUCCESS`: Discovered at least one candidate; all executed queries succeeded.
  - `EMPTY`: All executed queries succeeded, but 0 candidates were found (legitimate absence of content).
  - `PARTIAL_FAILURE`: Candidates discovered, but one or more executed queries failed (e.g., network timeout or secondary query error). Discovered candidates are preserved.
  - `FAILED`: All executed queries failed; 0 candidates returned.
- Detailed execution history is tracked per query in `SearchQueryExecution`.

## Consequences

### Benefits
- **Quota Safety:** Bounded queries and configurable limits prevent runaway YouTube quota consumption.
- **Provider Neutrality:** The discovery contracts are decoupled from YouTube DTOs, enabling multi-destination support.
- **Immutability & Determinism:** All request and result contracts are immutable records, enabling reproducible matching pipelines and reliable caching.
- **Clear Phase Boundaries:** Candidate retrieval is cleanly isolated from similarity evaluation.

### Tradeoffs
- Multi-query discovery requires sequential or coordinated execution to balance candidate recall against quota cost.
- Provider errors on secondary queries require explicit handling as partial failures rather than fatal exceptions.
