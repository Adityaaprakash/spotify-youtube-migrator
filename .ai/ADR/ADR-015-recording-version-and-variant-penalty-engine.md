# ADR-015: Recording Version and Variant Penalty Engine (Phase 7F)

## Context

Following Title Similarity Scoring (Phase 7C), Artist Similarity Scoring (Phase 7D), and Duration & Album Similarity Scoring (Phase 7E), the Matching Engine requires an objective, deterministic mechanism to evaluate audio recording discrepancies and apply penalties for unwanted candidate variants:
1. **Unwanted Audio Variants**: YouTube search candidates frequently contain non-canonical or derivative media: amateur covers, content creator reaction/commentary videos, karaoke accompaniment tracks, nightcore/sped-up/slowed-down audio edits, remixes, and live concert bootlegs when the user requested an official studio recording.
2. **Phase Boundary Discipline**: Phase 5 normalization and Phase 6 candidate discovery implementations are frozen and must not be altered. Normalization tokens and Phase 7B candidate feature extraction (`TrackFeatureExtractor`, `CandidateVersionEvidence`, `VersionIndicator`) must be reused directly without re-implementing tokenizer logic.
3. **Consistent Score Scale**: The roadmap outlines integer deduction examples (e.g., Cover: -25, Reaction: -30, Karaoke: -30, Nightcore: -20, Sped up: -15, Slowed: -15). The matching engine operates strictly on a normalized scale bounded in $[0.0, 1.0]$. The penalty engine must convert these values to consistent decimal deductions ($0.25, 0.30, 0.20, 0.15$) and ensure aggregate penalties remain bounded in $[0.0, 1.0]$.
4. **Legitimate Title Protection**: Many canonical song titles contain words that resemble variant markers (e.g. *Live to Tell* by Madonna, *Live and Let Die* by Wings, *Live Forever* by Oasis, *Stayin' Alive* by Bee Gees, *Under Cover of Darkness* by The Strokes, *Chain Reaction* by Diana Ross). The penalty engine must never penalize a candidate merely because a keyword appears in a legitimate song title.
5. **Variant Alignment & Symmetry**: When the source track itself is a live performance, remix, acoustic version, or cover, the candidate possessing that corresponding variant is the intended recording (penalty is $0.0$). A penalty applies only when there is an unaligned discrepancy between the source track expectation and the candidate recording.

## Decision

We implement recording version and variant penalty scoring as stateless Spring components in `com.spotifyyoutube.migrator.matching.engine.scorer`:

### 1. Version Penalty Scorer Contract (`VersionPenaltyScorer`, `DefaultVersionPenaltyScorer`)

- **Contracts**:
  - `Double calculatePenalty(SourceTrackFeatures source, CandidateTrackFeatures candidate)`: Computes aggregate deduction bounded in $[0.0, 1.0]$. Returns `0.0` when no discrepancies exist.
  - `Map<String, Double> calculateDetailedPenalties(SourceTrackFeatures source, CandidateTrackFeatures candidate)`: Returns an unmodifiable map of specific penalty names to deduction values for full auditability.
  - Overloaded helpers for indicator sets: `calculatePenalty(Set<VersionIndicator>, Set<VersionIndicator>)`.
- **Deterministic Penalty Schedule ($[0.0, 1.0]$ Scale)**:
  - `REACTION`: $0.30$ deduction (creator commentary / reaction video).
  - `KARAOKE`: $0.30$ deduction (backing track / sing-along version).
  - `COVER`: $0.25$ deduction (unwanted alternate artist / amateur cover performance).
  - `NIGHTCORE`: $0.20$ deduction (accelerated tempo + high pitch shift).
  - `SPED_UP`: $0.15$ deduction (accelerated tempo / fast version).
  - `SLOWED`: $0.15$ deduction (slowed down / chopped & screwed / slowed + reverb).
  - `LIVE_MISMATCH`: $0.15$ deduction (live concert vs studio recording mismatch).
  - `REMIX_MISMATCH`: $0.15$ deduction (remix vs original recording mismatch).
  - `ACOUSTIC_MISMATCH`: $0.10$ deduction (acoustic arrangement vs standard recording mismatch).
  - `INSTRUMENTAL_MISMATCH`: $0.15$ deduction (instrumental accompaniment vs vocal recording mismatch).
  - `REMASTER`: $0.00$ deduction (remastered releases represent the same core recording; no penalty).
- **Variant Alignment Policy**:
  - Source Live + Candidate Live $\to 0.0$ penalty (matching live performance).
  - Source Studio + Candidate Live $\to 0.15$ penalty (`LIVE_MISMATCH`).
  - Source Live + Candidate Studio $\to 0.15$ penalty (`LIVE_MISMATCH`).
  - Source Remix + Candidate Remix $\to 0.0$ penalty (matching remix).
  - Source Original + Candidate Remix $\to 0.15$ penalty (`REMIX_MISMATCH`).
  - Source Acoustic + Candidate Acoustic $\to 0.0$ penalty.
  - Source Standard + Candidate Acoustic $\to 0.10$ penalty (`ACOUSTIC_MISMATCH`).
- **Multi-variant Combination & Bounding**:
  - Itemized deductions accumulate additively into `detailedPenalties` (e.g., Cover + Reaction = $0.25 + 0.30 = 0.55$).
  - Aggregate `versionPenalty` is strictly clamped to $[0.0, 1.0]$: $\text{versionPenalty} = \min\left(1.0, \max\left(0.0, \sum \text{penalties}\right)\right)$.
- **Missing & Ambiguous Evidence**:
  - When candidate version evidence contains no detected indicators (`indicators().isEmpty()`), `versionPenalty` is `0.0` and `detailedPenalties` is empty.
  - Unmatched parenthetical annotations (e.g. `(Official Video)`, `(Audio)`, `(feat. Artist)`) do not trigger penalties.
  - Missing descriptions or duration fields do not trigger synthetic version penalties.

### 2. Seam Integration (`CandidateSimilarityScorer`, `DefaultCandidateSimilarityScorer`)

- Injects `VersionPenaltyScorer` into `DefaultCandidateSimilarityScorer`.
- Evaluates `versionPenalty` and `detailedPenalties` alongside `TitleSimilarityScorer`, `ArtistSimilarityScorer`, `DurationSimilarityScorer`, and `AlbumSimilarityScorer`.
- Populates `MatchScoreBreakdown`:
  - `titleScore`: computed title similarity.
  - `artistScore`: computed artist similarity.
  - `durationScore`: computed duration similarity.
  - `albumScore`: computed album similarity (or `null` if missing).
  - `versionPenalty`: calculated variant deduction ($0.0$ if no discrepancy).
  - `detailedPenalties`: unmodifiable map of itemized deductions.
  - `officialSignalScore`: preserved honestly as `null` for Phase 7G composite evaluation.
- Preserves backward compatibility via overloaded constructors.

## Consequences

### Benefits
- **Deterministic & Bounded**: All version penalties use pure, stateless mathematical logic bounded in $[0.0, 1.0]$.
- **False Positive Protection**: Token-level boundary rules prevent spurious penalties on canonical titles (*Live to Tell*, *Stayin' Alive*, *Under Cover of Darkness*, *Chain Reaction*).
- **Auditability**: `detailedPenalties` exposes each exact penalty reason and magnitude.
- **Phase Boundary Integrity**: Frozen Phase 5, Phase 6, and Phase 7A–7E components remain untouched.

### Tradeoffs
- Candidates whose titles conceal variant markers in unstructured video descriptions without brackets or delimiters will not trigger token-based version penalties; those candidates are penalized via duration discrepancies and lack of official signals downstream in Phase 7G.
