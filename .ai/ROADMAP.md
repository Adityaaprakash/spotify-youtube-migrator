# Migration Roadmap

## Phase Boundary Rule
Never implement functionality belonging to a future phase unless explicitly requested.
Current phase must be completed and verified before progressing.
When a future requirement affects current architecture, document the architectural requirement but do not implement the future feature prematurely.

## Current Progress

### **Phase 0 — Architecture & Project Foundation** [COMPLETE]
- Directory structures, build systems, framework boilerplate, and ADRs established.

### **Phase 1 — Domain Model & API Contracts** [IMPLEMENTATION COMPLETE BUT RUNTIME VERIFICATION BLOCKED]
- **Phase 1A/B/C**: [COMPLETE] Core Domain Model, Invariants, and Repositories built.
- **Phase 1D**: [COMPLETE] Application Service Contracts
- **Phase 1E/1F**: [COMPLETE] API DTOs and Validation/Error Contracts
- **Phase 1G**: [IMPLEMENTATION COMPLETE / BLOCKED] Persistence & Migration Verification statically verified; runtime testing blocked by Docker environment limits.
- **Phase 1H**: [COMPLETE] Domain / API Testing via Mockito and WebMvcTest.
- **Phase 1I/1J**: [COMPLETE / FROZEN] Architecture Audited and Code/Functional Contracts Frozen.

### **Phase 2 — Authentication & Identity** [IMPLEMENTATION COMPLETE BUT RUNTIME VERIFICATION BLOCKED]
- **Phase 2A-2C**: [COMPLETE] Application Identity, Authentication, and OAuth Infrastructure.
- **Phase 2D-2F**: [COMPLETE] Provider Adapters (Spotify/Google) and encrypted Token Lifecycle logic.
- **Phase 2G-2H**: [COMPLETE / FROZEN] Security hardening for sessions, state consumption race conditions, IDOR, and full unit test execution.

### **Phase 3 — Spotify Integration** [COMPLETE / FROZEN]
- **Phase 3A-3C**: [COMPLETE] Spotify API Foundation, Identity retrieval, and Paged Playlist Discovery implementation.
- **Phase 3D-3F**: [COMPLETE] Playlist Metadata Retrieval, Complete Paginated Track Retrieval, and safe Mapping boundaries enforcing canonical models.
- **Phase 3G-3H**: [COMPLETE] Spotify API error/rate-limit handling expansion and full phase QA audit.

### **Phase 4 — YouTube Integration** [COMPLETE / FROZEN]
- **Phase 4A**: [COMPLETE] YouTube API Feasibility, Quota Verification, and Configuration Properties.
- **Phase 4B**: [COMPLETE] YouTube API Client Foundation (`YouTubeClient`) with typed provider DTOs and resilient HTTP/error handling.
- **Phase 4C**: [COMPLETE] YouTube Video Search (`YouTubeSearchService`, `YouTubeMapper`, `YouTubeController` `/api/youtube/videos/search`).
- **Phase 4D**: [COMPLETE] YouTube Video Metadata Retrieval (`YouTubeClient.getVideoMetadata`, `YouTubeSearchService.getVideoDetails`, `/api/youtube/videos/{videoId}`).
- **Phase 4E**: [COMPLETE] YouTube Playlist Creation (`YouTubeClient.createPlaylist`, `YouTubePlaylistService.createPlaylist`, `/api/youtube/playlists`).
- **Phase 4F**: [COMPLETE] Adding Videos to Playlists (`YouTubeClient.addVideoToPlaylist`, `YouTubePlaylistService.addVideoToPlaylist`, `/api/youtube/playlists/{playlistId}/videos`).
- **Phase 4G**: [COMPLETE] MusicDestination Abstraction & YouTube Adapter (`MusicDestination`, domain models, `YouTubeMusicDestinationAdapter`).
- **Phase 4H**: [COMPLETE / FROZEN] Destination Verification, Verification Endpoints (`/api/youtube/playlists/{playlistId}`, `/api/youtube/playlists/{playlistId}/verify`), and Phase 4 Freeze.
### **Phase 5 — Track Normalization Engine** [COMPLETE / FROZEN]
- **Phase 5A**: [COMPLETE] Repository Audit & Normalization Architecture (ADR-010, pure stateless components, provider isolation).
- **Phase 5B**: [COMPLETE] Title Normalization (`TitleNormalizer`: Unicode NFC, Locale.ROOT, whitespace/quotes/dashes standardization, conservative promotional suffix removal, version preservation).
- **Phase 5C**: [COMPLETE] Artist Normalization (`ArtistNormalizer`, `NormalizedArtistCredit`: token-bounded featured extraction, band identity preservation, list normalization).
- **Phase 5D**: [COMPLETE] Album Normalization (`AlbumNormalizer`: Unicode NFC, Locale.ROOT, whitespace/quotes/dashes standardization, preservation of Deluxe/Remastered/Live/Soundtrack/Anniversary editions).
- **Phase 5E**: [COMPLETE] Duration Normalization (`DurationNormalizer`: canonical `java.time.Duration`, exact millisecond/nanosecond precision, Spotify ms & YouTube ISO-8601 parsing, strict error contract).
- **Phase 5F**: [COMPLETE] MetadataTokenizer & Variant Detection (`MetadataTokenizer`: token extraction, `TokenizedTitle`, `VariantType`, `MediaFormat`, `TokenCategory`, `RemasterDetails`, `LivePerformanceDetails`, unknown annotation preservation).
- **Phase 5G**: [COMPLETE] Normalization Pipeline Orchestration (`NormalizationOrchestrator`, `RawTrackMetadata`, `NormalizedTrackMetadata`, pipeline composition, immutability, determinism).
- **Phase 5H**: [COMPLETE / FROZEN] Phase 5 Verification & Freeze (cross-phase audit, 227 normalization tests, 454 regression tests, zero Checkstyle violations, documentation reconciled).
### **Phase 6 — YouTube Candidate Discovery** [COMPLETE / FROZEN]
- **Phase 6A**: [COMPLETE] Candidate Discovery Architecture & Contracts (ADR-011, immutable contracts, bounded discovery options, provenance models).
- **Phase 6B**: [COMPLETE] Search Query Generation (`DefaultSearchQueryGenerator`, deterministic query formulation from `NormalizedTrackMetadata`, tiered strategies, priority ranking, quota bounding, deduplication).
- **Phase 6C**: [COMPLETE] Provider Search Orchestration (`DefaultCandidateDiscoveryService`, quota-aware multi-query dispatching via `MusicDestination`, bounded candidate collection, early termination, partial failure resilience).
- **Phase 6D**: [COMPLETE] Candidate Mapping & Metadata Enrichment (`DestinationTrackCandidate` duration support, ISO-8601 parsing, safe malformed record handling, zero-quota-overhead mapping, provider DTO isolation).
- **Phase 6E**: [COMPLETE] Candidate Validation, Deduplication & Filtering (Whitespace ID trimming, canonical deduplication keys, encounter-order uniqueness, variant preservation).
- **Phase 6F**: [COMPLETE] Discovery Pipeline Integration & End-to-End Verification (`CandidateDiscoveryIntegrationTest`, real multi-query generation and destination execution, provenance retention, quota preservation, partial/total failure resilience).
- **Phase 6G**: [COMPLETE / FROZEN] Final Discovery Audit, Edge-Case Hardening & Verification (credential and token sanitization, transient error recovery, empty-first query continuation, full non-container regression suite passing, Phase 6 frozen).
### **Phase 7 — Matching Engine** [COMPLETE / FROZEN]
- **Phase 7A**: [COMPLETE] Matching Architecture & Contracts (ADR-012, `TrackMatchRequest`, `TrackMatchResult`, `MatchDecision`, `MatchScoreBreakdown`, `CandidateMatchEvaluation`, `MatchConfidence`, provider-neutral immutable models, honest missing-data semantics).
- **Phase 7B**: [COMPLETE] Candidate Feature Extraction (`TrackFeatureExtractor`, `DefaultTrackFeatureExtractor`, `SourceTrackFeatures`, `CandidateTrackFeatures`, `VersionIndicator`, `CandidateVersionEvidence`, `OfficialSignalEvidence`, conservative official channel policy, word-boundary false-positive safeguards, no premature scoring/penalties).
- **Phase 7C**: [COMPLETE] Title Similarity Scorer (`TitleSimilarityScorer`, `DefaultTitleSimilarityScorer`, Levenshtein character edit distance, token-sort permutation tolerance, delimiter segment matching, short-title false-positive safeguards, honest missing-data handling).
- **Phase 7D**: [COMPLETE] Artist Similarity Scorer (`ArtistSimilarityScorer`, `DefaultArtistSimilarityScorer`, Phase 5 `ArtistNormalizer` reuse, official channel equivalence, greedy 1-to-1 multi-artist matching, primary vs featured priority, bounded featured-only scores, substring collision safeguards).
- **Phase 7E**: [COMPLETE] Duration & Album Similarity Scoring (`DurationSimilarityScorer`, `DefaultDurationSimilarityScorer`, continuous monotonic piecewise decay function, `AlbumSimilarityScorer`, `DefaultAlbumSimilarityScorer`, Phase 5 `AlbumNormalizer` reuse, YouTube description extraction, substring collision safeguards, honest missing-data handling).
- **Phase 7F**: [COMPLETE] Recording Version & Variant Penalty Engine (ADR-015, `VersionPenaltyScorer`, `DefaultVersionPenaltyScorer`, bounded normalized deductions, variant alignment policy, false-positive protection, `detailedPenalties` itemization).
- **Phase 7G**: [COMPLETE] Composite Scoring, Candidate Ranking, and Decision Engine (ADR-016, `CompositeScoreCalculator`, `DefaultCompositeScoreCalculator` with dynamic re-normalization, `OfficialSignalScorer`, `DefaultOfficialSignalScorer`, `CandidateRankingEngine`, `DefaultCandidateRankingEngine` with 7-level tie-breaking, `MatchDecisionEngine`, `DefaultMatchDecisionEngine` with threshold/ambiguity/penalty rules, `TrackMatchingEngine`, `DefaultTrackMatchingEngine`).
- **Phase 7H**: [COMPLETE] Calibrated Match Probability and Decision Policy (ADR-017, `CalibrationResult` distinguishing configured vs. validated results, `MatchProbabilityCalibrator`, `DefaultMatchProbabilityCalibrator` with uncalibrated baseline, configured Platt scaling, and validated Platt scaling with numerical stability safeguards, 7-gate decision policy in `DefaultMatchDecisionEngine` gating only on validated probability and falling back to Phase 7G heuristic baseline, end-to-end integration in `DefaultTrackMatchingEngine`).
- **Phase 7I**: [COMPLETE / FROZEN] Subsystem Verification & Matching Freeze (comprehensive verification of Phases 7A–7H, 274 matching regression tests passing across 18 test classes, 0 Checkstyle violations, mathematical invariants verified, Phase 5 and Phase 6 frozen boundaries preserved, subsystem frozen).
### **Phase 8 — Matching Evaluation & Benchmark** [IN PROGRESS]
- **Phase 8A**: [COMPLETE] Benchmark Architecture and Dataset Contract (ADR-018, typed immutable models `BenchmarkExample`, `BenchmarkSourceTrack`, `BenchmarkTargetCandidate`, `BenchmarkDataset`, validation engine `BenchmarkDatasetValidator`, offline Jackson loader `BenchmarkDatasetLoader`, future Phase 8C integration contract).
- **Phase 8B**: [COMPLETE] Ground-Truth Benchmark Dataset (118 distinct tracks in `ground_truth_benchmark.json`, 105 matches, 13 legitimate non-matches, 117 verified, 1 unverified candidate under review, 3 evaluation splits DEV 52 / VALIDATION 36 / TEST 30, 17 typology categories represented).
- **Phase 8C**: [COMPLETE] Benchmark Runner & Candidate Pool Architecture (ADR-019, `BenchmarkRunner`, `DefaultBenchmarkRunner`, immutable execution models `BenchmarkRunRequest`, `BenchmarkRunResult`, `BenchmarkExampleResult`, `RankedCandidateSummary`, `BenchmarkEligibilityPolicy`, candidate pool providers `BenchmarkCandidatePoolProvider`, `ControlledDecoyCandidatePoolProvider`, `FixtureCandidatePoolProvider`, `TargetOnlyCandidatePoolProvider`, `EmptyCandidatePoolProvider`, offline deterministic execution, zero Phase 5–7 modifications).
- **Phase 8D**: [COMPLETE] Evaluation Metrics, Calibration & Error Analysis (ADR-019, `RankingMetricsCalculator` for Top-1/Top-3/Recall, `DecisionPolicyMetricsCalculator` for 6-way confusion matrix/precision/recall/review rate/auto-accept rate, `HighConfidenceMetricsCalculator` with honest uncalibrated baseline handling, `CalibrationMetricsCalculator` for Brier score/empirical baseline/ECE/reliability buckets, `ErrorAnalysisCalculator` for 14 structured error categories across splits and categories, structured reporting via `BenchmarkReportGenerator` and `BenchmarkReportFormatter`).
- **Phase 8E**: [NEXT PLANNED SUBPHASE] Probability Calibration Parameter Fitting & Threshold Optimization (Platt scaling parameter estimation using VALIDATION split, empirical probability calibration verification, held-out TEST split evaluation, decision threshold tuning).
### **Phase 9 — Migration Engine**
### **Phase 10 — Review & Correction**
### **Phase 11 — Playlist Creation**
### **Phase 12 — Async Migration & Progress**
### **Phase 13 — Reliability Engineering**
### **Phase 14 — Migration History**
### **Phase 15 — Frontend Product Layer**
### **Phase 16 — Security Hardening**
### **Phase 17 — Testing**
### **Phase 18 — Observability**
### **Phase 19 — Deployment**