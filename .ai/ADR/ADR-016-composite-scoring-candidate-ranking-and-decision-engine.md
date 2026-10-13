# ADR-016: Composite Scoring, Candidate Ranking, and Decision Engine (Phase 7G)

## Context

Following Title Similarity (Phase 7C), Artist Similarity (Phase 7D), Duration & Album Scoring (Phase 7E), and Recording Version Penalty Engine (Phase 7F), the Matching Engine requires the synthesis layer to integrate individual evidence signals into a unified migration decision:
1. **Multi-dimensional Evidence Integration**: Component similarities (title, artist, duration, album, official provenance) and recording version penalties must combine into a deterministic composite numerical score bounded strictly in $[0.0, 1.0]$.
2. **Missing Evidence & Dynamic Re-normalization**: Secondary dimensions (such as album metadata or playback duration) are frequently absent on YouTube video candidates. Missing evidence must never be coerced to zero (which would unfairly penalize valid tracks) nor assumed to be an exact match (which would fabricate evidence). Instead, component weights must re-normalize dynamically across active dimensions.
3. **Deterministic Candidate Ranking**: Multiple candidate videos for a single track must be prioritized using a reproducible, multi-level tie-breaking hierarchy that rewards core title/artist alignment, verified provenance, and minimal version discrepancy.
4. **Transparent Match Decision Policy**: Migration decisions must distinguish between automated matches, manual review requirements, and non-matches using clear, threshold-based boundaries, with explainable diagnostic reasons for every decision.
5. **Top-level Pipeline Orchestration**: A cohesive facade must coordinate feature extraction, component scoring, composite calculation, ranking, and decision formulation without leaking provider-specific details.

## Decision

We implement Phase 7G across four core areas in `com.spotifyyoutube.migrator.matching.engine`:

### 1. Composite Score Calculation (`CompositeScoreCalculator`, `DefaultCompositeScoreCalculator`)

- **Nominal Component Weights**:
  - Title Similarity ($w_{\text{title}}$): $0.40$ (40%)
  - Artist Similarity ($w_{\text{artist}}$): $0.30$ (30%)
  - Duration Alignment ($w_{\text{dur}}$): $0.10$ (10%)
  - Album Correspondence ($w_{\text{album}}$): $0.10$ (10%)
  - Official Provenance Signal ($w_{\text{off}}$): $0.10$ (10%)
- **Dynamic Weight Re-normalization**:
  Let $A \subseteq \{\text{title}, \text{artist}, \text{dur}, \text{album}, \text{off}\}$ be the set of active dimensions where component score $s_i \ne \text{null}$.
  If $W_A = \sum_{i \in A} w_i > 0$, the weighted base similarity score is:
  $$\text{baseScore} = \frac{\sum_{i \in A} w_i \cdot s_i}{W_A}$$
  If $W_A = 0$, $\text{baseScore} = 0.0$.
- **Version Penalty Deduction**:
  The aggregate version penalty $p \in [0.0, 1.0]$ from Phase 7F is deducted once from the re-normalized base score:
  $$\text{finalScore} = \max\left(0.0, \min\left(1.0, \text{baseScore} - p\right)\right)$$
  The composite score is rounded to four decimal places for deterministic cross-platform repeatability.
- **Input Validation**: Rejects `NaN`, infinities, and negative weights/scores with `IllegalArgumentException`.

### 2. Official Provenance Signal Scoring (`OfficialSignalScorer`, `DefaultOfficialSignalScorer`)

- Evaluates conservative authentic signals from `OfficialSignalEvidence`:
  - Auto-generated topic channels (`- Topic`)
  - VEVO syndication channels (`VEVO`)
  - Direct normalized primary artist channel match
  - DDEX distributor release delivery descriptions (`Provided to YouTube by...`)
  - Verified official audio and video title markers
- Returns `1.0` if `hasOfficialEvidence()` is true, and `0.0` if evaluated without authentic markers.
- Returns `null` when `candidate.officialSignal()` evidence is null/unextracted (preserving honest missing data).
- Rejects unverified channel claims (e.g. channel names containing the word "official" alone) with `0.0`.

### 3. Candidate Ranking Engine (`CandidateRankingEngine`, `DefaultCandidateRankingEngine`)

- Ranks evaluated candidates in descending match quality using a 7-level deterministic tie-breaking ladder:
  1. **Overall Composite Score**: Descending (highest composite score ranks first).
  2. **Title Similarity Score**: Descending (rewards stronger core title matches).
  3. **Artist Similarity Score**: Descending (rewards stronger primary and featured artist alignment).
  4. **Official Provenance Score**: Descending (prefers verified official releases over fan uploads).
  5. **Version Penalty**: Ascending (prefers recordings with fewer/smaller version discrepancies).
  6. **Duration Alignment Score**: Descending (closer playback length alignment).
  7. **Candidate Identifier**: Natural lexicographical ascending order (ensures 100% reproducible ordering).
- Pure and non-mutating: does not modify caller collections; returns an unmodifiable list.

### 4. Match Decision Engine (`MatchDecisionEngine`, `DefaultMatchDecisionEngine`)

- Evaluates ranked candidates and produces immutable `TrackMatchResult` with explicit diagnostic reasons:
  - **Empty Candidates**: Produces `MatchDecision.NO_MATCH` with reason "No destination candidates discovered".
  - **Version Penalty Discrepancy on Viable Candidate ($p > 0.10$ and $\text{baseScore} \ge 0.50$)**: Produces `MatchDecision.REVIEW_REQUIRED` (`selectedCandidate = top`, `POOR` confidence). A detected cover, live mismatch, or variant of a viable candidate requires human migration review rather than silent rejection as non-match or automated approval.
  - **Score $< 0.50$ (Below Minimum Viable Threshold)**: Produces `MatchDecision.NO_MATCH` (`selectedCandidate = null`, `POOR` confidence).
  - **Borderline Score ($0.50 \le \text{score} < 0.75$)**: Produces `MatchDecision.REVIEW_REQUIRED` (`selectedCandidate = top`, `POOR` confidence).
  - **Ambiguity Margin ($s_{\text{top}} - s_{\text{runnerUp}} < 0.05$)**: Produces `MatchDecision.REVIEW_REQUIRED` (`GOOD` confidence) due to close competition between leading candidates.
  - **Unambiguous Match ($\text{score} \ge 0.75$)**: Produces `MatchDecision.MATCH` (`EXCELLENT` confidence for $\ge 0.90$, `GOOD` confidence for $0.75$ to $< 0.90$).

### 5. Top-Level Orchestration (`TrackMatchingEngine`, `DefaultTrackMatchingEngine`)

- Orchestrates the full pipeline:
  $$\text{TrackMatchRequest} \to \text{Feature Extraction} \to \text{Candidate Scoring} \to \text{Composite Scoring} \to \text{Ranking} \to \text{Decision} \to \text{TrackMatchResult}$$
- Generates itemized diagnostic notes for evaluated candidates (title matches, artist matches, duration alignment, album correspondence, official signals, version penalties).

## Consequences

### Benefits
- **Deterministic & Repeatable**: Pure, stateless mathematical formulas ensure identical inputs produce identical composite scores, rankings, and decisions across runs.
- **Robust Against Missing Data**: Proportional re-normalization prevents candidates from being penalized simply because YouTube video metadata lacks album descriptions or duration.
- **Explainability**: Every match or review decision contains clear, human-readable reasons detailing score thresholds, margins, penalties, and provenance.
- **Architectural Seam Integrity**: Reuses existing contracts (`TrackMatchRequest`, `TrackMatchResult`, `CandidateMatchEvaluation`, `MatchScoreBreakdown`) without creating duplicate domain models or modifying frozen Phase 5 and Phase 6 implementations.
