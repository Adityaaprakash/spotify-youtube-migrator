# ADR-023: Review Domain Architecture, State Management, and Review Contracts (Phase 10A & 10B)

## Status
**ACCEPTED / IMPLEMENTED**

## Context

Following the completion of Phase 9 (Migration Planning Subsystem and Pre-Flight Preview under ADR-022), the project requires the design and implementation of the **Review & Correction Subsystem** (Phase 10).

Phase 9 established the generation of immutable, read-only pre-flight `MigrationPlan` instances without mutating destination music services (YouTube Music). A plan categorizes every source track into one of six mutually exclusive outcomes: `AUTO_MATCH`, `REVIEW_REQUIRED`, `UNMATCHED`, `DUPLICATE`, `FAILED`, and `EXCLUDED`.

While high-confidence matches (`AUTO_MATCH`) can proceed directly to destination creation in Phase 11, tracks categorized as `REVIEW_REQUIRED`, `UNMATCHED`, or `DUPLICATE` require human intervention and explicit decision-making:
- **`REVIEW_REQUIRED`**: Ambiguous similarity, duration variance, or recording version/live penalty.
- **`UNMATCHED`**: No destination track candidate met the minimum threshold.
- **`DUPLICATE`**: An automated match selected a destination video ID already claimed by an earlier source track.
- **`AUTO_MATCH` Overrides**: Users may wish to override an automated match if they prefer an alternate candidate or wish to exclude the track.

### 1. The Persistence & State Challenge
Under ADR-022, `MigrationPlan` is strictly a point-in-time, transient, in-memory domain model. It represents a read-only snapshot.
However, human review workflows are inherently asynchronous, multi-step, and stateful across separate HTTP requests:
1. User reviews the plan.
2. User selects an alternative YouTube candidate for track $A$.
3. User excludes track $B$.
4. User approves the auto-match for track $C$.
5. User resolves duplicate conflicts.
6. User finalizes the review session when all required tracks are resolved.

If review state were held solely in memory, server restarts, container scaling, or multi-tab user interactions would discard manual corrections. Conversely, mutating the existing `migration_jobs` and `migration_tasks` tables would prematurely corrupt operational execution logs, conflate execution statuses (`COMPLETED`, `FAILED`) with pre-execution review statuses (`AWAITING_REVIEW`, `ACCEPTED`, `USER_SELECTED`, `EXCLUDED`), and violate the single-responsibility principle.

### 2. Discrepancy Investigation: `MigrationTrack` and `match_source`
The roadmap and audit requirements reference a contract: `MigrationTrack.match_source = USER_SELECTED`.
Inspection of the codebase confirms:
- No class named `MigrationTrack` existed in the repository prior to Phase 10.
- Existing persistence entities are `MigrationJob` and `MigrationTask` (which tracks execution tasks, not review state).
- In Phase 9, `MigrationPlanTrack` was introduced as an in-memory record for preview plans, lacking review-decision mutability and provenance tracking.
- No `match_source` column existed in any database migration (V1 through V4).

**Architectural Resolution**:
Rather than forcing incompatible review fields into `migration_tasks` or mutating Phase 9's immutable `MigrationPlanTrack`, Phase 10 introduces a dedicated, decoupled review persistence domain:
- `ReviewSession` (entity mapping to table `review_sessions`)
- `ReviewTrack` (entity mapping to table `review_tracks`)
- `ReviewDecisionSource` enum with values `AUTOMATIC` and `USER_SELECTED`.

When a user manually selects a candidate, `ReviewTrack.decision_source` is set to `USER_SELECTED`.

---

## Architectural Decisions

### 1. Existing Architecture & Immutability Audit
Phase 9 operates as follows:
- **Pipeline**: Spotify retrieval $\to$ Phase 5 `NormalizationOrchestrator` $\to$ Phase 6 `CandidateDiscoveryService` $\to$ Phase 7 `TrackMatchingEngine`.
- **Classification**: `PlanTrackClassification` (`AUTO_MATCH`, `REVIEW_REQUIRED`, `UNMATCHED`, `DUPLICATE`, `FAILED`, `EXCLUDED`).
- **Object Mutability**:
  - `MigrationPlan`, `MigrationPlanTrack`, `MigrationPlanSummary`: **Immutable records** (Phase 9).
  - `DestinationTrackCandidate`: **Immutable record** (Phase 6/4).
  - `TrackMatchResult`, `CandidateMatchEvaluation`: **Immutable records** (Phase 7).
  - `MigrationJob`, `MigrationTask`: **Mutable JPA entities** dedicated to Phase 8/Phase 11+ execution.
  - `ReviewSession`, `ReviewTrack`: **Mutable JPA entities** dedicated to Phase 10 review state management.

### 2. Review Session Lifecycle
A review session represents a stateful user workflow reviewing and resolving tracks from a migration plan.
- **Creation**: Initialized from a valid `MigrationPlan` and authenticated `User`.
- **States (`ReviewSessionStatus`)**:
  - `IN_PROGRESS`: Default state. The user is actively reviewing and resolving tracks.
  - `COMPLETED`: Terminal state. All required decisions have been resolved and no unresolved duplicate conflicts remain. Once completed, no further track decisions can be applied.
  - `CANCELLED`: Terminal state. The review session was abandoned or superseded.
- **State Transitions**:
  - `IN_PROGRESS` $\to$ `COMPLETED` (Valid only when `isEligibleForCompletion()` is true; rejected otherwise with `InvalidStateException`).
  - `IN_PROGRESS` $\to$ `CANCELLED` (Valid at any time while in progress).
  - Terminal states (`COMPLETED`, `CANCELLED`) cannot transition to any other state.

### 3. Review Track Lifecycle & States
Every source track in the review session maintains an independent review decision state:
- **Statuses (`ReviewTrackStatus`)**:
  - `AWAITING_REVIEW`: Track has unresolved ambiguity, no match, processing failure, or duplicate conflict. Requires user decision before session completion.
  - `ACCEPTED`: The track's automatic match has been approved (either high-confidence auto-match or explicit user acceptance of an ambiguous candidate).
  - `USER_SELECTED`: The user explicitly chose a replacement candidate from discovery/search.
  - `EXCLUDED`: The track is explicitly excluded from destination migration by the user.
- **Initial Mapping from `MigrationPlanTrack`**:
  - `AUTO_MATCH` (without duplicate conflict) $\to$ `ACCEPTED` (`decisionSource = AUTOMATIC`, candidate populated).
  - `REVIEW_REQUIRED` $\to$ `AWAITING_REVIEW` (`decisionSource = AUTOMATIC`, candidate held back).
  - `UNMATCHED` $\to$ `AWAITING_REVIEW` (`decisionSource = AUTOMATIC`, no candidate).
  - `DUPLICATE` $\to$ `AWAITING_REVIEW` (`hasConflict = true`, conflict reason populated).
  - `FAILED` $\to$ `AWAITING_REVIEW` (`decisionSource = AUTOMATIC`, no candidate).
  - `EXCLUDED` $\to$ `EXCLUDED` (`excluded = true`).

### 4. Decision Provenance & Preservation of Automatic Evidence
A fundamental architectural invariant is:
**The original automatic matching result and evidence must never be overwritten, modified, or discarded when a user records a manual decision.**

To enforce this, `ReviewTrack` strictly separates:
- **Automatic Matching Evidence (Immutable)**:
  - `originalClassification` (`PlanTrackClassification`)
  - `originalCandidateId`, `originalCandidateTitle`, `originalCandidateChannel`, `originalCandidateDurationMs`
  - `originalMatchScore` ($[0.0, 1.0]$)
  - `originalCalibratedProbability` ($[0.0, 1.0]$)
  - `originalMatchDecision` (`MatchDecision`)
  - `originalMatchReasons`
- **User Decision & Corrected State (Mutable)**:
  - `reviewStatus` (`ReviewTrackStatus`)
  - `decisionSource` (`ReviewDecisionSource`: `AUTOMATIC` vs `USER_SELECTED`)
  - `selectedCandidateId`, `selectedCandidateTitle`, `selectedCandidateChannel`, `selectedCandidateDurationMs`
  - `excluded` (`boolean`)
  - `hasConflict` (`boolean`)
  - `conflictReason` (`String`)
  - `decisionNotes` (`String`)

**No Confidence Score Fabrication**:
When a user manually selects a candidate (`USER_SELECTED`), no synthetic similarity score or calibrated probability is fabricated. The user's decision is authoritative by human provenance.

### 5. Centralized Domain Invariants
Phase 10 enforces the 10 domain invariants:
1. **Session & Track Integrity**: Every decision must reference a valid session and a track that belongs to that session. Cross-session or non-existent track references fail with `ResourceNotFoundException` or `IllegalArgumentException`.
2. **Candidate Validity**: User-selected candidates must be non-null, have non-blank IDs and titles, and non-negative durations. Malformed candidates are rejected with `IllegalArgumentException`.
3. **Exclusion Disjointness**: An excluded track (`excluded = true`) is never treated as approved for migration (`isApprovedForMigration() == false`).
4. **Duplicate Conflict Guard**: An unresolved duplicate conflict (`hasConflict = true`) prevents session completion (`isEligibleForCompletion() == false`).
5. **No Premature Completion**: A review session cannot be marked `COMPLETED` while any track remains `AWAITING_REVIEW` or in conflict.
6. **Idempotence**: Repeating an identical decision (e.g. re-excluding an already excluded track, or selecting the exact same candidate) produces an identical, consistent result without error.
7. **Invalid Transition Protection**: Modifications to `COMPLETED` or `CANCELLED` sessions, or accepting automatic matches for tracks without an automatic candidate, fail fast with `InvalidStateException`.
8. **Position & Identity Preservation**: Source track position ($0, 1, \dots, N-1$), source track ID, and playlist ordering are immutable.
9. **Read-Only / Destination Safety**: Review operations perform zero mutations on YouTube Music (no calls to `MusicDestination.createPlaylist` or `addTrackToPlaylist`).
10. **Phase 9 Preview Isolation**: Review domain operations never mutate Phase 9's transient `MigrationPlan` records.

### 6. Summary Derivation
`ReviewProgressSummary` is derived dynamically from the current collection of `ReviewTrack` entities:
$$\text{totalTracks} = \text{tracks.size()}$$
$$\text{remainingRequiredDecisions} = \text{awaitingReviewCount} + \text{unresolvedConflictCount}$$
$$\text{eligibleForCompletion} = (\text{remainingRequiredDecisions} == 0)$$

No redundant, drift-prone counters are maintained.

### 7. Persistence Strategy & Flyway Migration V5
Flyway migration `V5__phase10_review_session_schema.sql` creates:
- `review_sessions`: Stores session metadata, user relationship, source playlist info, plan UUID, status, and completion timestamp.
- `review_tracks`: Stores track position, original matching provenance, user decision status, selected candidate details, and conflict flags.

Indexes:
- `idx_reviewsessions_user_id` on `review_sessions(user_id)`
- `idx_reviewsessions_status` on `review_sessions(status)`
- `idx_reviewsessions_plan_id` on `review_sessions(plan_id)`
- `idx_reviewtracks_session_id` on `review_tracks(session_id)`
- `idx_reviewtracks_session_status` on `review_tracks(session_id, review_status)`
- `idx_reviewtracks_session_source_track` on `review_tracks(session_id, source_track_id)`
- Unique constraint: `uq_reviewtrack_session_position UNIQUE (session_id, position)`

---

## Boundaries and Deferred Work

### Scoped to Phase 10A & 10B:
- Architecture audit and domain contracts.
- Database migration V5 and JPA entities.
- Domain state machine and transition validation.
- Review session application service (`ReviewSessionService`, `DefaultReviewSessionService`).
- Unit and service test suites.

### Explicitly Deferred to Phase 10C+:
- Manual YouTube candidate search endpoint and UI.
- Review REST controllers and API DTOs.
- Advanced multi-track duplicate resolution graph algorithms.
- Destination playlist creation and video insertion (Phase 11).
- Async migration progress (Phase 12).
