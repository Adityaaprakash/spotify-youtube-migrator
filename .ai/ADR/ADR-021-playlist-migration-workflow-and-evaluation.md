# ADR-021: End-to-End Playlist Migration Workflow Orchestration, Deduplication, and Verification (Phase 8G + 8H)

## Status
**ACCEPTED / IMPLEMENTED**

## Context

Following the completion and freeze of Phase 5 (Source Metadata Normalization), Phase 6 (Destination Candidate Discovery), Phase 7 (Composite Scoring, Calibration, and Decision Engine), and Phase 8A–8F (Ground-Truth Benchmark Dataset and Calibration), the project required the integration of the end-to-end migration pipeline (Phase 8G) and reliability evaluation (Phase 8H).

### 1. Discrepancy and Scope Clarification
The user prompt for Phase 8G/8H referenced a "Python/FastAPI ML service" and a "football simulator".
- The repository `c:\Projects\spotify-youtube-migrator` contains zero Python files and is an established Java 22 / Spring Boot modular monolith.
- Following Section 1 and Section 2 of the prompt (*"If the repository's actual roadmap does not define Phase 8G or 8H as described below, report the discrepancy and follow the authoritative roadmap rather than implementing unrelated features"*), we adhere strictly to the authoritative architecture defined in `.ai/ARCHITECTURE.md` and `.ai/ROADMAP.md`.

### 2. Migration Pipeline Requirements Prior to Phase 8G
- Prior to Phase 8G, the application had:
  - Phase 1: Identity, User, and Migration Job data models (`MigrationJob`, `MigrationTask`).
  - Phase 2: Spotify client integration (`SpotifyPlaylistClient`).
  - Phase 4: YouTube client integration (`YouTubePlaylistClient`, `YouTubeSearchService`).
  - Phase 5: Normalization pipeline (`DefaultTrackMetadataNormalizer`).
  - Phase 6: Candidate discovery pipeline (`DefaultCandidateDiscoveryPipeline`).
  - Phase 7: Matching engine and decision policy (`DefaultTrackMatchingEngine`, `DefaultMatchDecisionEngine`).
- However, there was no cohesive orchestrator tying these isolated modules into an end-to-end migration workflow. Specifically:
  1. Fetching Spotify playlist metadata and tracks.
  2. Normalizing source tracks via Phase 5.
  3. Discovering YouTube candidates via Phase 6.
  4. Scoring and classifying matches via Phase 7.
  5. Creating the YouTube destination playlist and sequentially inserting matched tracks.
  6. Preserving source ordering, handling errors per track (partial progress), and preventing duplicate additions.
  7. Recording detailed task execution records (`MigrationTask`) in PostgreSQL.

## Decision

We implement Phase 8G and Phase 8H through clean domain orchestration and evaluation services without modifying frozen Phase 5–7 code:

### 1. Workflow Orchestration (`PlaylistMigrationOrchestrator`, Phase 8G)
Located in `com.spotifyyoutube.migrator.migration.application.orchestration`:
- **Workflow Pipeline**:
  1. **Job Retrieval & Validation**: Reads `MigrationJob`, checks source platform (`SPOTIFY`) and target platform (`YOUTUBE`). Sets status to `IN_PROGRESS`.
  2. **Source Playlist Fetching**: Calls `SpotifyPlaylistClient.getPlaylist(sourcePlaylistId)` and `getPlaylistTracks(sourcePlaylistId)` to retrieve source tracks.
  3. **Target Playlist Creation**: Calls `YouTubePlaylistClient.createPlaylist(title, description, privacy)` with name `"Migrated - " + sourcePlaylist.name()`. Stores target URL in `MigrationJob`.
  4. **Per-Track Processing Loop**:
     - **Phase 5 Normalization**: Converts `SpotifyTrackDto` to `NormalizedTrackMetadata`.
     - **Phase 6 Candidate Discovery**: Discovers top-10 candidate tracks on YouTube using search and metadata filters.
     - **Phase 7 Matching & Calibration**: Evaluates candidates through `TrackMatchingEngine.match(request)` to yield a `MatchDecisionResult` (decision, score, calibrated probability).
     - **Decision Gating & Review Holdback**:
       - `EXACT_MATCH`, `HIGH_CONFIDENCE_MATCH`, `MEDIUM_CONFIDENCE_MATCH`: Eligible for destination insertion.
       - `REVIEW_REQUIRED`: Held back from auto-insertion to maintain destination playlist quality; recorded as `REVIEW_REQUIRED`.
       - `NO_MATCH`: No acceptable destination track; recorded as `SKIPPED`.
     - **Deduplication**: Tracks seen YouTube video IDs. If a candidate video ID was already added in this migration run, it is skipped as a duplicate to prevent playlist clutter.
     - **Destination Insertion**: Calls `musicDestination.addTrackToPlaylist(userId, targetPlaylistId, new AddTrackToDestinationPlaylistRequest(candidateId, processedCount))` sequentially. Positions are 0-indexed and strictly monotonically increasing matching the target playlist size (`processedCount`), preserving relative source ordering among successfully inserted tracks without holes even when intermediate tracks fail or require review.
  5. **Job Lifecycle Update & Playlist-Level Failure Recovery**:
     - If source playlist metadata retrieval, destination playlist creation, or source track retrieval fails after job start, the orchestrator catches the failure, sanitizes the message, transitions the `MigrationJob` to terminal `FAILED` status, sets `completedAt`, persists it, and wraps the sanitized message with the original cause for internal diagnosis.
     - Already completed jobs throw `InvalidStateException` immediately and are protected from accidental mutation or failure marking.
     - Per-track lifecycle: if all tracks fail, marks job `FAILED`; if all succeed or playlist is empty, marks job `COMPLETED`; if partial success, marks job `PARTIALLY_COMPLETED`.
  6. **Multi-Format Credential & Secret Protection**:
     - All log messages, exceptions, and `MigrationTask.errorMessage` fields are scrubbed with regex `TOKEN_PATTERN` to strip `Bearer` tokens, `access_token=`, `refresh_token=`, `token=`, `api_key=`, `apikey=`, `key=`, `client_secret=`, `secret=`, and `password=` values.
     - Raw exception objects or stack traces are not passed to loggers, preventing third-party library secrets from leaking in console/file logs.

### 2. Domain & API Separation (Phase 8G)
- Provider-specific DTOs (`SpotifyTrackDto`, `YouTubeVideoDto`) remain encapsulated inside `com.spotifyyoutube.migrator.spotify` and `com.spotifyyoutube.migrator.youtube`.
- Application boundary exposes `MigrationService.executeMigration(UUID jobId)` and REST endpoint `POST /api/migrations/{id}/execute`.
- Orchestrator returns immutable `MigrationExecutionSummary` and `TrackMigrationOutcome` records containing full traceability (source ID, target ID, position, match score, calibrated probability, status, message).

### 3. Reliability & Workflow Evaluation (`MigrationWorkflowEvaluator`, Phase 8H)
Located in `com.spotifyyoutube.migrator.migration.application.evaluation`:
- Computes aggregate evaluation metrics across executed workflows with a disjoint, mutually exclusive partition:
  $$\text{totalTracks} = \text{successfullyMigratedTracks} + \text{unmatchedTracks} + \text{reviewRequiredTracks} + \text{duplicateTracksPrevented} + \text{failedTracks}$$
- **Metric Definitions & Semantic Distinction**:
  - `MigrationExecutionSummary.failedTracks`: Represents the total unmigrated tracks for backward-compatible mapping to the `MigrationJob.failedTracks` database column ($\text{unmatched} + \text{review} + \text{duplicate} + \text{error}$).
  - `MigrationWorkflowEvaluationReport.failedTracks`: Strictly counts genuine unexpected processing, API, or insertion errors (`outcome.isProcessingFailure()`), preventing duplicate prevention or review holdouts from being misclassified as system failures.
  - `migrationSuccessRate` = $\frac{\text{successfullyMigratedTracks}}{\text{totalTracksEvaluated}}$
  - `unmatchedRate` = $\frac{\text{unmatchedTracks}}{\text{totalTracksEvaluated}}$
  - `reviewRate` = $\frac{\text{reviewRequiredTracks}}{\text{totalTracksEvaluated}}$
  - `failureRate` = $\frac{\text{failedTracks}}{\text{totalTracksEvaluated}}$
  - `duplicateTracksPrevented` count
  - `throughputTracksPerSecond` = $\frac{\text{totalTracksEvaluated} \times 1000}{\text{totalDurationMs}}$ (safe 0.0 for zero duration or empty suites)
  - `orderingPreserved` invariant verification (asserting track outcome position equals sequential index)
- Formats reports into GitHub Flavored Markdown and plain text with full denominator disclosures.

## Invariants & Safety Guarantees

1. **Frozen Production Code Integrity**:
   - Zero changes made to `com.spotifyyoutube.migrator.matching.normalization.*` (Phase 5).
   - Zero changes made to `com.spotifyyoutube.migrator.matching.discovery.*` (Phase 6).
   - Zero changes made to `com.spotifyyoutube.migrator.matching.engine.*` (Phase 7).
2. **Sequential Ordering Guarantee**:
   - Destination track insertions use explicit 0-indexed positions (`processedCount`) preserving relative source encounter order among successfully inserted tracks.
3. **Partial Failure Resilience & Terminal State Integrity**:
   - Individual track exceptions are isolated and stored as `FAILED` tasks; subsequent tracks continue.
   - Playlist-level startup failures transition the persistent job to `FAILED` with `completedAt` timestamp.
4. **Credential Protection**:
   - No bearer tokens, OAuth tokens, query parameters (`access_token`, `key`, `client_secret`, `password`), or stack traces leak to logs or databases.
5. **Human Review Protection**:
   - Ambiguous or penalized tracks (`REVIEW_REQUIRED`) are never inserted into user destination playlists automatically.
