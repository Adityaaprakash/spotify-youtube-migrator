# ADR-014: Duration and Album Similarity Scoring (Phase 7E)

## Context

Following the completion of Title Similarity Scoring (Phase 7C) and Artist Similarity Scoring (Phase 7D), the Matching Engine requires objective, deterministic components for evaluating secondary matching dimensions:
1. **Duration Similarity Scoring**: Measuring playback length correspondence between source tracks and destination candidates.
2. **Album Similarity Scoring**: Evaluating album title correspondence when album metadata is present on both source and destination records.

Key architectural challenges and domain characteristics:
- **Phase Boundary Discipline**: Phase 5 normalization and Phase 6 candidate discovery implementations are frozen and must not be altered. Normalization contracts (`DurationNormalizer`, `AlbumNormalizer`) must be reused directly.
- **Provider Offset Realities**: YouTube candidate durations frequently differ slightly from studio track recordings due to audio container padding, trailing silence, video title slates, narrative video intros, or spoken dialogues. Small differences ($\le 2$s) should not penalize candidates, while substantial differences ($> 60$s) indicate different edits, extended mixes, or truncated clips.
- **Honest Missing-Data Semantics**: YouTube search candidates often lack duration (until enriched) or album metadata (YouTube does not have a dedicated top-level album field in search results, though structured descriptions often contain it). Missing evidence must yield `null` rather than artificial `0.0` penalties or synthetic defaults.
- **Monotonicity & Continuity**: Duration similarity is monotonic non-increasing with increasing duration offset (reflecting constant-score intervals for small offsets $\le 2$s and excessive offsets $> 60$s) without discontinuous score jumps.
- **Generic Title & Substring Protection**: Albums often share common generic titles across artists (e.g. *"Greatest Hits"*, *"Live"*, *"Deluxe Edition"*) or short words (e.g. *"War"* vs *"Star Wars"*). Album comparison must avoid awarding spurious credit to unrelated albums sharing words.

## Decision

We implement duration and album similarity scoring as independent, stateless Spring components in `com.spotifyyoutube.migrator.matching.engine.scorer`:

### 1. Duration Similarity Scoring (`DurationSimilarityScorer`, `DefaultDurationSimilarityScorer`)

- **Contracts**: Accepts `SourceTrackFeatures` and `CandidateTrackFeatures` (with overloads for `Duration` and milliseconds); returns `Double` in `[0.0, 1.0]` or `null` if duration is absent on either side.
- **Continuous Piecewise Decay Function**:
  Let $\Delta_{\text{sec}} = |d_{\text{source}} - d_{\text{candidate}}| / 1000.0$:
  1. **Tolerance Window ($\Delta_{\text{sec}} \le 2.0\text{s}$)**:
     $$\text{score} = 1.0$$
     Absorbs container padding, trailing silence, and encoding round-off without penalty.
  2. **Small Offset Window ($2.0\text{s} < \Delta_{\text{sec}} \le 10.0\text{s}$)**:
     $$\text{score} = 1.0 - \frac{\Delta_{\text{sec}} - 2.0}{8.0} \times 0.15 \quad (\text{score} \in [0.85, 1.0])$$
     Tolerates minor video slates and studio logos with modest decay.
  3. **Moderate Offset Window ($10.0\text{s} < \Delta_{\text{sec}} \le 30.0\text{s}$)**:
     $$\text{score} = 0.85 - \frac{\Delta_{\text{sec}} - 10.0}{20.0} \times 0.45 \quad (\text{score} \in [0.40, 0.85])$$
     Reflects music video intros, extended outros, and radio edits.
  4. **Large Offset Window ($30.0\text{s} < \Delta_{\text{sec}} \le 60.0\text{s}$)**:
     $$\text{score} = 0.40 - \frac{\Delta_{\text{sec}} - 30.0}{30.0} \times 0.40 \quad (\text{score} \in [0.0, 0.40])$$
     Meaningfully penalizes significant edit discrepancies.
  5. **Cutoff ($\Delta_{\text{sec}} > 60.0\text{s}$)**:
     $$\text{score} = 0.0$$
     Disqualifying duration discrepancy.
- **Mathematical Invariants**: Fully continuous ($C^0$), symmetric, monotonic non-increasing with respect to offset (piecewise constant on $[0, 2.0\text{s}]$ and $> 60.0\text{s}$), bounded in `[0.0, 1.0]`, and safe against arithmetic overflow.

### 2. Album Similarity Scoring (`AlbumSimilarityScorer`, `DefaultAlbumSimilarityScorer`)

- **Contracts**: Accepts `SourceTrackFeatures` and `CandidateTrackFeatures` (with overload for raw/normalized album strings); returns `Double` in `[0.0, 1.0]` or `null` if album metadata is absent on either side.
- **Injected Normalization**: Reuses Phase 5 `AlbumNormalizer` to normalize album strings (Unicode NFC, case folding, curly quote/dash standardization, whitespace collapsing) without modifying frozen code.
- **Candidate Description Extraction**: Robustly extracts album titles from candidate video descriptions when available:
  - Explicit label lines: `Album: <name>`.
  - YouTube standard distributor delivery layout: `Provided to YouTube by...` where line 3 denotes the album title.
  - When no verified album metadata is present in the description, returns `null` rather than guessing.
- **Multi-signal Album Comparison**:
  1. Exact normalized match: `1.0`.
  2. Whitespace-collapsed match: `1.0`.
  3. Canonically equivalent accent / diacritic folding (e.g. `Ágætis byrjun` vs `Agaetis byrjun`): `1.0`.
  4. Minor typographical differences on long album titles (Levenshtein similarity $\ge 0.88$, length difference $\le 2$): awards normalized edit distance.
  5. Distinct albums, generic collisions, and substring collisions (e.g. `Queen: Greatest Hits` vs `Blink-182: Greatest Hits`, `War` vs `Star Wars`, `Thriller` vs `Bad`): strictly `0.0`.

### 3. Seam Integration (`CandidateSimilarityScorer`, `DefaultCandidateSimilarityScorer`)

- Coordinates all four component scorers: `TitleSimilarityScorer`, `ArtistSimilarityScorer`, `DurationSimilarityScorer`, and `AlbumSimilarityScorer`.
- Populates `MatchScoreBreakdown` with `titleScore`, `artistScore`, `durationScore`, and `albumScore`.
- Preserves missing evidence honestly: absent dimensions remain `null`.
- Leaves future dimensions (`officialSignalScore`, `versionPenalty`) honestly `null` for subsequent phases (Phase 7F/7G).
- Completely decoupled from composite weighting (Phase 7G) and match decision rules (Phase 7H).

## Consequences

### Benefits
- **Deterministic & Objective**: All duration and album scoring rules are stateless, pure, and reproducible.
- **Smooth Duration Modeling**: The $C^0$ piecewise decay function prevents erratic score jumps while respecting music video operational reality.
- **Conservative Album Semantics**: Prevents false positive match inflation from generic album words or missing candidate album fields.
- **Phase Boundary Integrity**: Frozen Phase 5, Phase 6, Phase 7A, 7B, 7C, and 7D files remain 100% untouched.

### Tradeoffs
- YouTube candidates lacking duration (e.g. raw search results before hydration) or lacking structured album descriptions produce `null` for those component scores. Later composite scoring in Phase 7G must weight available evidence dynamically.
