# ADR-022: Migration Planning Architecture, Contracts, and Execution Boundary (Phase 9A)

## Status
**ACCEPTED / IMPLEMENTED**

## Context

Following the completion of Phase 8 (Matching Evaluation, Benchmark Calibration, and Workflow Orchestration via ADR-021), the project requires the design and introduction of a dedicated migration-planning subsystem (Phase 9).

### 1. The Existing Migration Subsystem & Coupling
In Phase 8G, `PlaylistMigrationOrchestrator` successfully established end-to-end orchestration connecting:
- Source playlist and track retrieval (`SpotifyPlaylistService`)
- Source track normalization (`NormalizationOrchestrator`, Phase 5)
- Destination candidate discovery (`CandidateDiscoveryService`, Phase 6)
- Probability-calibrated matching and decision engine (`TrackMatchingEngine`, Phase 7)
- Destination playlist creation and sequential video insertion (`MusicDestination`, Phase 4)

However, within `PlaylistMigrationOrchestrator.migratePlaylistInternal(...)`, the creation of the YouTube destination playlist (`musicDestination.createPlaylist`) and the insertion of tracks (`musicDestination.addTrackToPlaylist`) are tightly coupled with candidate discovery and match evaluation:
```text
Existing Coupled Flow:
Fetch Spotify Playlist -> Create YouTube Playlist -> Fetch Tracks -> (Normalize -> Discover -> Match -> Insert Track)
```
As a result:
- It was impossible to generate a migration preview without creating an empty YouTube playlist and mutating the destination.
- Users could not review or approve matches before destination mutations occurred.
- Dry-run or planning evaluations required running full destination operations.

### 2. Architectural Goal for Phase 9
Phase 9 decouples plan preview generation from destination mutation:
```text
Intended Decoupled Architecture:
Spotify Playlist
       |
       v
Source Track Data
       |
       v
Existing Normalization (Phase 5)
       |
       v
Existing Candidate Discovery (Phase 6)
       |
       v
Existing Matching Engine (Phase 7)
       |
       v
Migration Planning Contracts (Phase 9A)
       |
       v
Migration Plan Preview (Phase 9B)
       |
       v
Future Review / Approval (Phase 11)
       |
       v
Actual Destination Mutation (Phase 12)
```

Phase 9A establishes the architecture audit, boundary definitions, and immutable contracts without breaking the existing Phase 8 orchestration behavior or modifying frozen Phase 5–7 production code.

---

## Architectural Decisions

### 1. Planning versus Execution Boundary
- **Separation of Concerns**: Planning is strictly a read-only, non-mutating operation with respect to the destination music service.
- **Pre-Flight Preview**: A migration plan evaluates the source playlist, performs normalization, candidate discovery, calibrated matching, and deduplication tracking, assembling the results into a previewable plan without calling `MusicDestination.createPlaylist(...)` or `MusicDestination.addTrackToPlaylist(...)`.
- **Preservation of Phase 8 Orchestrator**: The existing `PlaylistMigrationOrchestrator` remains completely functional for direct execution workflows (`executeMigration(UUID jobId)` and `migratePlaylist(...)`), preserving all Phase 8 regression test suites.
- **Phase 9B Implementation Strategy**: Phase 9B will introduce a dedicated planning service (e.g. `PlaylistMigrationPlanner`) that encapsulates the read-only stages, which can later be shared or reused by the execution orchestrator to ensure zero divergence between preview and execution.

### 2. Transient In-Memory Previews vs. Persisted Snapshots
- **Transient Plan Model**: In Phase 9A and 9B, `MigrationPlan` instances are designed as immutable in-memory domain models.
- **No Schema Changes or Flyway Migrations**: The existing database entities (`MigrationJob` and `MigrationTask`) represent active or completed execution jobs with associated destination playlist URLs and status transitions (`IN_PROGRESS`, `COMPLETED`, `FAILED`). Persisting unapproved preview plans into the existing tables would prematurely bloat operational job history and require nullable foreign keys or schema workarounds.
- **Traceability**: Each `MigrationPlan` is assigned a unique `UUID planId` and an `Instant createdAt` snapshot timestamp. This allows client sessions to correlate preview responses, log metrics, and track plan lifecycles.
- **Future Persistence Deferral**: If human review workflows in Phase 11 or long-term plan draft saving requires persistent storage across multi-day sessions, an explicit schema migration (e.g. `migration_plans` table or JSONB snapshot) will be introduced in that phase based on validated persistence requirements.

### 3. Classification Semantics & Disjoint Partition
Existing matching engine outcomes (`MatchDecision`), match results (`TrackMatchResult`), and orchestrator outcomes (`TrackMigrationOutcome`) are mapped into a unified, mutually exclusive vocabulary: `PlanTrackClassification`.

The mapping rules are strictly defined as:
1. **`AUTO_MATCH`**:
   - `MatchDecision.MATCH` with a valid `selectedCandidate`.
   - Not flagged as a duplicate by the deduplication tracker.
   - Meets calibrated confidence thresholds for automated migration.
2. **`REVIEW_REQUIRED`**:
   - `MatchDecision.REVIEW_REQUIRED`.
   - Ambiguous similarity score, duration variance, or recording version/live penalty.
   - **Critical Safety Guard**: Review-required tracks can *never* silently become `AUTO_MATCH`.
3. **`UNMATCHED`**:
   - `MatchDecision.NO_MATCH` or zero discovered candidates.
   - No destination track met minimum similarity thresholds.
4. **`DUPLICATE`**:
   - `MatchDecision.MATCH`, but the selected candidate's unique video ID was already claimed by an earlier source track in this migration plan.
   - Prevents duplicate tracks from cluttering destination playlists.
5. **`FAILED`**:
   - An unexpected technical error, timeout, or external API exception occurred during track processing.
   - Distinguishable from legitimate matching non-matches (`UNMATCHED`).
6. **`EXCLUDED`**:
   - Source track explicitly excluded from migration by rule, user preference, or unsupported track format.

### 4. Summary Invariant & Exact Reconciliation
`MigrationPlanSummary` enforces a strict mathematical reconciliation invariant:
$$\text{totalTracks} = \text{autoMatchCount} + \text{reviewRequiredCount} + \text{unmatchedCount} + \text{duplicateCount} + \text{failedCount} + \text{excludedCount}$$
- Every source track is accounted for exactly once in the summary.
- Duplicate counts and processing failures are separated from unresolved matches to avoid double-counting.
- All counts are non-negative. Any summary instantiation violating this invariant fails fast with `IllegalArgumentException`.
- The factory method `MigrationPlanSummary.fromTracks(List<MigrationPlanTrack>)` guarantees construction consistency.

### 5. Plan Validity and Snapshot Semantics
- **Snapshot, Not Live View**: A `MigrationPlan` represents a deterministic snapshot of the source playlist and candidate discovery/matching at `createdAt`.
- **Staleness Handling in Future Phases**: Because external playlists can be edited on Spotify, later execution phases (Phase 11/12) will verify snapshot freshness against a configurable validity window (e.g., 1 hour) before triggering destination playlist mutations, re-fetching only if the source playlist version or track count changed.
- **Order Preservation Guarantee**: Source playlist sequence is deterministically preserved. `MigrationPlan` asserts that `tracks.get(i).position() == i` for all indices $0 \le i < \text{tracks.size()}$.

### 6. Credential Sanitization
`MigrationPlanTrack` automatically scrubs diagnostic error messages through regex redaction (`TOKEN_PATTERN`), ensuring bearer tokens, OAuth `access_token` values, API keys, client secrets, and passwords can never be stored in plan previews or exposed via REST APIs.

---

## Contract Specifications

The following immutable contracts are established in `com.spotifyyoutube.migrator.migration.application.plan`:

1. **`PlanTrackClassification`** (`enum`):
   - Mutually exclusive track disposition: `AUTO_MATCH`, `REVIEW_REQUIRED`, `UNMATCHED`, `DUPLICATE`, `FAILED`, `EXCLUDED`.
2. **`MigrationPlanTrack`** (`record`):
   - Position, source track ID, normalized title and artist.
   - Track classification, selected `DestinationTrackCandidate` (nullable).
   - Phase 7 `MatchDecision`, composite `matchScore`, `calibratedProbability`.
   - Unmodifiable `matchReasons`, sanitized `errorMessage`.
   - Comprehensive factory methods: `autoMatch`, `reviewRequired`, `duplicate`, `unmatched`, `failed`, `excluded`.
3. **`MigrationPlanSummary`** (`record`):
   - Aggregated counts across all 6 mutually exclusive categories.
   - Reconciliation validation in compact constructor.
   - Rate calculations (`getAutoMatchRate()`, `getReviewRate()`, etc.) safe against division by zero.
   - Derivation factory: `fromTracks(List<MigrationPlanTrack>)`.
4. **`MigrationPlan`** (`record`):
   - `planId` (UUID), `sourcePlaylistId`, `sourcePlaylistTitle`, `createdAt` (Instant).
   - Defensively copied unmodifiable `List<MigrationPlanTrack>`.
   - Reconciled `MigrationPlanSummary`.
   - Ordering invariant enforcement (`track.position() == i`).

---

## Invariants & Safety Guarantees

1. **Frozen Production Subsystems Untouched**:
   - Phase 5 Normalization: Zero changes.
   - Phase 6 Candidate Discovery: Zero changes.
   - Phase 7 Matching Engine: Zero changes.
   - Phase 8 Ground-Truth Benchmark & Calibration: Zero changes.
2. **Phase 8 Orchestration Unbroken**:
   - `PlaylistMigrationOrchestrator`, `MigrationService`, and `MigrationController` continue to operate with unchanged behavior.
3. **Strict Order Preservation**:
   - Source playlist order is enforced with 0-indexed positions without gaps or permutations.
4. **Exact Reconciliation**:
   - No track is counted more than once or omitted from the plan summary.
5. **Human Review Protection**:
   - `REVIEW_REQUIRED` tracks cannot be auto-inserted or treated as automatic matches.
6. **Credential Redaction**:
   - No tokens, API keys, or secrets can be retained in error messages or diagnostics.
