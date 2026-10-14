package com.spotifyyoutube.migrator.matching.benchmark;

import com.spotifyyoutube.migrator.matching.benchmark.pool.BenchmarkCandidatePoolProvider;
import com.spotifyyoutube.migrator.matching.benchmark.pool.ControlledDecoyCandidatePoolProvider;
import com.spotifyyoutube.migrator.matching.benchmark.pool.EmptyCandidatePoolProvider;
import com.spotifyyoutube.migrator.matching.benchmark.pool.FixtureCandidatePoolProvider;
import com.spotifyyoutube.migrator.matching.benchmark.runner.BenchmarkEligibilityPolicy;
import com.spotifyyoutube.migrator.matching.benchmark.runner.BenchmarkErrorCategory;
import com.spotifyyoutube.migrator.matching.benchmark.runner.BenchmarkExampleResult;
import com.spotifyyoutube.migrator.matching.benchmark.runner.BenchmarkRunRequest;
import com.spotifyyoutube.migrator.matching.benchmark.runner.BenchmarkRunResult;
import com.spotifyyoutube.migrator.matching.benchmark.runner.DefaultBenchmarkRunner;
import com.spotifyyoutube.migrator.matching.benchmark.runner.EvaluationExclusionReason;
import com.spotifyyoutube.migrator.matching.engine.DefaultTrackMatchingEngine;
import com.spotifyyoutube.migrator.matching.engine.MatchConfidence;
import com.spotifyyoutube.migrator.matching.engine.MatchDecision;
import com.spotifyyoutube.migrator.matching.engine.TrackMatchRequest;
import com.spotifyyoutube.migrator.matching.engine.TrackMatchResult;
import com.spotifyyoutube.migrator.matching.engine.TrackMatchingEngine;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationTrackCandidate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("BenchmarkRunner Integration & Pipeline Tests")
class BenchmarkRunnerTest {

    private DefaultBenchmarkRunner runner;

    private final BenchmarkSourceTrack sourceSong1 = BenchmarkSourceTrack.of(
            "spotify:track:song1",
            "Midnight City",
            "M83",
            "Hurry Up, We're Dreaming",
            243000L
    );

    private final BenchmarkTargetCandidate targetSong1 = BenchmarkTargetCandidate.of(
            "dX3k_QDnzHE",
            "M83 - Midnight City (Official Video)",
            "M83",
            243000L,
            true
    );

    private final BenchmarkSourceTrack sourceUnreleased = BenchmarkSourceTrack.of(
            "spotify:track:unreleased",
            "Unreleased Track 1999",
            "Obscure Artist",
            "Vault Sessions",
            195000L
    );

    private final BenchmarkExample verifiedMatchExample = BenchmarkExample.verifiedMatch(
            "BM-0101",
            sourceSong1,
            targetSong1,
            "Official band video matching master track",
            BenchmarkDatasetSplit.DEV,
            BenchmarkTrackCategory.OFFICIAL_MUSIC_VIDEO
    );

    private final BenchmarkExample verifiedNoMatchExample = BenchmarkExample.verifiedNoMatch(
            "BM-0102",
            sourceUnreleased,
            "No commercial or user upload available on YouTube",
            BenchmarkDatasetSplit.DEV,
            BenchmarkTrackCategory.LEGITIMATE_NO_MATCH
    );

    private final BenchmarkExample unverifiedExample = BenchmarkExample.unverifiedMatch(
            "BM-0103",
            sourceSong1,
            targetSong1,
            BenchmarkDatasetSplit.DEV,
            BenchmarkTrackCategory.STANDARD_POPULAR
    );

    private final BenchmarkExample disputedExample = new BenchmarkExample(
            "BM-0104",
            BenchmarkExpectedOutcome.MATCH,
            sourceSong1,
            targetSong1,
            BenchmarkVerificationStatus.DISPUTED,
            null,
            "Disputed video ID",
            BenchmarkDatasetSplit.DEV,
            BenchmarkTrackCategory.STANDARD_POPULAR
    );

    @BeforeEach
    void setUp() {
        runner = new DefaultBenchmarkRunner();
    }

    @Nested
    @DisplayName("Candidate Matching & Ranking Scenarios")
    class CandidateMatchingAndRankingScenarios {

        @Test
        @DisplayName("should evaluate positive match with correct target ranked first")
        void shouldEvaluatePositiveMatchTop1() {
            BenchmarkDataset dataset = new BenchmarkDataset(
                    "1.0.0", "Test Dataset", "2026-10-14", List.of(verifiedMatchExample)
            );

            // Engine returns MATCH selecting targetSong1
            DestinationTrackCandidate destinationTarget = new DestinationTrackCandidate(
                    "dX3k_QDnzHE", "Midnight City", "M83", "description", null, null, Duration.ofMillis(243000)
            );

            DestinationTrackCandidate decoyCandidate = new DestinationTrackCandidate(
                    "decoy12345", "Midnight City (Live)", "M83", "description", null, null, Duration.ofMillis(260000)
            );

            TrackMatchingEngine mockEngine = req -> {
                com.spotifyyoutube.migrator.matching.engine.CandidateMatchEvaluation eval1 =
                        com.spotifyyoutube.migrator.matching.engine.CandidateMatchEvaluation.of(
                                destinationTarget, 0.95, MatchConfidence.EXCELLENT, null, List.of(), null
                        );
                com.spotifyyoutube.migrator.matching.engine.CandidateMatchEvaluation eval2 =
                        com.spotifyyoutube.migrator.matching.engine.CandidateMatchEvaluation.of(
                                decoyCandidate, 0.70, MatchConfidence.POOR, null, List.of(), null
                        );
                return TrackMatchResult.of(
                        req.sourceTrackId(),
                        req.sourceTrack(),
                        MatchDecision.MATCH,
                        destinationTarget,
                        0.95,
                        MatchConfidence.EXCELLENT,
                        null,
                        List.of(eval1, eval2),
                        List.of("Strong title and artist match")
                );
            };

            BenchmarkCandidatePoolProvider poolProvider =
                    new FixtureCandidatePoolProvider(Map.of("BM-0101", List.of(destinationTarget, decoyCandidate)));

            BenchmarkRunResult result = runner.run(dataset, mockEngine, poolProvider);

            assertThat(result.totalExamples()).isEqualTo(1);
            assertThat(result.eligibleCount()).isEqualTo(1);

            BenchmarkExampleResult r = result.exampleResults().get(0);
            assertThat(r.exampleId()).isEqualTo("BM-0101");
            assertThat(r.eligible()).isTrue();
            assertThat(r.isTop1Match()).isTrue();
            assertThat(r.isTop3Match()).isTrue();
            assertThat(r.isCorrectAutoAccept()).isTrue();
            assertThat(r.errorClassification()).isEqualTo(BenchmarkErrorCategory.CORRECT_MATCH);
            assertThat(r.targetPresentInCandidatePool()).isTrue();
            assertThat(r.predictedTargetMatchesExpected()).isTrue();
        }

        @Test
        @DisplayName("should evaluate positive match with target ranked in top 3 but not first")
        void shouldEvaluatePositiveMatchTop3NotTop1() {
            BenchmarkDataset dataset = new BenchmarkDataset(
                    "1.0.0", "Test Dataset", "2026-10-14", List.of(verifiedMatchExample)
            );

            DestinationTrackCandidate rivalCandidate = new DestinationTrackCandidate(
                    "rival123456", "Midnight City", "M83", "description", null, null, Duration.ofMillis(243000)
            );
            DestinationTrackCandidate destinationTarget = new DestinationTrackCandidate(
                    "dX3k_QDnzHE", "Midnight City", "M83", "description", null, null, Duration.ofMillis(243000)
            );

            TrackMatchingEngine mockEngine = req -> {
                com.spotifyyoutube.migrator.matching.engine.CandidateMatchEvaluation eval1 =
                        com.spotifyyoutube.migrator.matching.engine.CandidateMatchEvaluation.of(
                                rivalCandidate, 0.96, MatchConfidence.EXCELLENT, null, List.of(), null
                        );
                com.spotifyyoutube.migrator.matching.engine.CandidateMatchEvaluation eval2 =
                        com.spotifyyoutube.migrator.matching.engine.CandidateMatchEvaluation.of(
                                destinationTarget, 0.94, MatchConfidence.EXCELLENT, null, List.of(), null
                        );
                return TrackMatchResult.of(
                        req.sourceTrackId(),
                        req.sourceTrack(),
                        MatchDecision.MATCH,
                        rivalCandidate,
                        0.96,
                        MatchConfidence.EXCELLENT,
                        null,
                        List.of(eval1, eval2),
                        List.of()
                );
            };

            BenchmarkCandidatePoolProvider poolProvider =
                    new FixtureCandidatePoolProvider(Map.of("BM-0101", List.of(rivalCandidate, destinationTarget)));

            BenchmarkRunResult result = runner.run(dataset, mockEngine, poolProvider);
            BenchmarkExampleResult r = result.exampleResults().get(0);

            assertThat(r.isTop1Match()).isFalse();
            assertThat(r.isTop3Match()).isTrue();
            assertThat(r.isCorrectAutoAccept()).isFalse();
            assertThat(r.isIncorrectAutoAccept()).isTrue();
            assertThat(r.errorClassification()).isEqualTo(BenchmarkErrorCategory.WRONG_TARGET_SELECTED);
        }

        @Test
        @DisplayName("should classify target absent from pool as candidate recall failure")
        void shouldClassifyTargetAbsentAsRecallFailure() {
            BenchmarkDataset dataset = new BenchmarkDataset(
                    "1.0.0", "Test Dataset", "2026-10-14", List.of(verifiedMatchExample)
            );

            DestinationTrackCandidate unrelatedCandidate = new DestinationTrackCandidate(
                    "unrelated11", "Completely Unrelated", "Artist X", "", null, null, Duration.ofMillis(120000)
            );

            // Engine returns NO_MATCH because candidate fails score
            TrackMatchingEngine mockEngine = req -> TrackMatchResult.noMatch(req.sourceTrack(), List.of("Score below threshold"));

            BenchmarkCandidatePoolProvider poolProvider =
                    new FixtureCandidatePoolProvider(Map.of("BM-0101", List.of(unrelatedCandidate)));

            BenchmarkRunResult result = runner.run(dataset, mockEngine, poolProvider);
            BenchmarkExampleResult r = result.exampleResults().get(0);

            assertThat(r.targetPresentInCandidatePool()).isFalse();
            assertThat(r.isCandidateRecallFailure()).isTrue();
            assertThat(r.errorClassification()).isEqualTo(BenchmarkErrorCategory.TARGET_ABSENT_FROM_CANDIDATE_POOL);
        }

        @Test
        @DisplayName("should evaluate legitimate NO_MATCH correctly")
        void shouldEvaluateLegitimateNoMatch() {
            BenchmarkDataset dataset = new BenchmarkDataset(
                    "1.0.0", "Test Dataset", "2026-10-14", List.of(verifiedNoMatchExample)
            );

            // Empty pool or irrelevant candidates -> Engine produces NO_MATCH
            TrackMatchingEngine mockEngine = req -> TrackMatchResult.noMatch(req.sourceTrack(), List.of("No candidates"));

            BenchmarkCandidatePoolProvider poolProvider = new EmptyCandidatePoolProvider();
            BenchmarkRunResult result = runner.run(dataset, mockEngine, poolProvider);
            BenchmarkExampleResult r = result.exampleResults().get(0);

            assertThat(r.expectedOutcome().isNoMatch()).isTrue();
            assertThat(r.isCorrectNoMatch()).isTrue();
            assertThat(r.errorClassification()).isEqualTo(BenchmarkErrorCategory.CORRECT_NO_MATCH);
        }

        @Test
        @DisplayName("should classify REVIEW_REQUIRED decision cleanly")
        void shouldClassifyReviewRequired() {
            BenchmarkDataset dataset = new BenchmarkDataset(
                    "1.0.0", "Test Dataset", "2026-10-14", List.of(verifiedMatchExample)
            );

            DestinationTrackCandidate coverCandidate = new DestinationTrackCandidate(
                    "cover123456", "Midnight City (Cover)", "Cover Artist", "description", null, null, Duration.ofMillis(243000)
            );

            TrackMatchingEngine mockEngine = req -> new TrackMatchResult(
                    req.sourceTrackId(),
                    req.sourceTrack(),
                    MatchDecision.REVIEW_REQUIRED,
                    coverCandidate,
                    0.65,
                    null,
                    MatchConfidence.POOR,
                    null,
                    List.of(),
                    List.of("Recording version discrepancy penalty: 0.25 (COVER)")
            );

            BenchmarkCandidatePoolProvider poolProvider =
                    new FixtureCandidatePoolProvider(Map.of("BM-0101", List.of(coverCandidate)));

            BenchmarkRunResult result = runner.run(dataset, mockEngine, poolProvider);
            BenchmarkExampleResult r = result.exampleResults().get(0);

            assertThat(r.isReviewRequired()).isTrue();
            assertThat(r.errorClassification()).isEqualTo(BenchmarkErrorCategory.VERSION_MISMATCH);
        }
    }

    @Nested
    @DisplayName("Eligibility & Exclusion Rules")
    class EligibilityAndExclusionRules {

        @Test
        @DisplayName("default policy should exclude UNVERIFIED and DISPUTED examples from official scoring")
        void shouldExcludeUnverifiedAndDisputedByDefault() {
            BenchmarkDataset dataset = new BenchmarkDataset(
                    "1.0.0", "Test Dataset", "2026-10-14",
                    List.of(verifiedMatchExample, unverifiedExample, disputedExample)
            );

            BenchmarkRunResult result = runner.run(dataset);

            assertThat(result.totalExamples()).isEqualTo(3);
            assertThat(result.eligibleCount()).isEqualTo(1);
            assertThat(result.excludedCount()).isEqualTo(2);

            BenchmarkExampleResult unverifiedRes = result.getExampleResult("BM-0103").orElseThrow();
            assertThat(unverifiedRes.eligible()).isFalse();
            assertThat(unverifiedRes.exclusionReason()).isEqualTo(EvaluationExclusionReason.UNVERIFIED_STATUS);
            assertThat(unverifiedRes.errorClassification()).isEqualTo(BenchmarkErrorCategory.EXCLUDED_UNVERIFIED);

            BenchmarkExampleResult disputedRes = result.getExampleResult("BM-0104").orElseThrow();
            assertThat(disputedRes.eligible()).isFalse();
            assertThat(disputedRes.exclusionReason()).isEqualTo(EvaluationExclusionReason.DISPUTED_STATUS);
            assertThat(disputedRes.errorClassification()).isEqualTo(BenchmarkErrorCategory.EXCLUDED_DISPUTED);
        }

        @Test
        @DisplayName("diagnostic policy should permit inspecting unverified records without dropping")
        void diagnosticPolicyShouldIncludeUnverified() {
            BenchmarkDataset dataset = new BenchmarkDataset(
                    "1.0.0", "Test Dataset", "2026-10-14",
                    List.of(verifiedMatchExample, unverifiedExample)
            );

            BenchmarkRunRequest request = new BenchmarkRunRequest(
                    dataset,
                    null,
                    null,
                    BenchmarkEligibilityPolicy.diagnosticPolicy(),
                    null
            );

            BenchmarkRunResult result = runner.run(request);

            assertThat(result.totalExamples()).isEqualTo(2);
            assertThat(result.eligibleCount()).isEqualTo(2);
            assertThat(result.excludedCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("split policy should filter examples by dataset split")
        void splitPolicyShouldFilterBySplit() {
            BenchmarkExample testExample = BenchmarkExample.verifiedNoMatch(
                    "BM-0105", sourceUnreleased, "Evidence text here",
                    BenchmarkDatasetSplit.TEST, BenchmarkTrackCategory.LEGITIMATE_NO_MATCH
            );

            BenchmarkDataset dataset = new BenchmarkDataset(
                    "1.0.0", "Test Dataset", "2026-10-14",
                    List.of(verifiedMatchExample, testExample)
            );

            BenchmarkRunRequest request = BenchmarkRunRequest.ofSplit(dataset, BenchmarkDatasetSplit.DEV);
            BenchmarkRunResult result = runner.run(request);

            assertThat(result.eligibleCount()).isEqualTo(1);
            assertThat(result.getExampleResult("BM-0101").orElseThrow().eligible()).isTrue();

            BenchmarkExampleResult testRes = result.getExampleResult("BM-0105").orElseThrow();
            assertThat(testRes.eligible()).isFalse();
            assertThat(testRes.exclusionReason()).isEqualTo(EvaluationExclusionReason.SPLIT_EXCLUDED);
        }
    }

    @Nested
    @DisplayName("Error Recovery & Determinism")
    class ErrorRecoveryAndDeterminism {

        @Test
        @DisplayName("matching engine exception should be recorded as EXECUTION_FAILURE without dropping")
        void shouldRecordMatchingEngineFailure() {
            BenchmarkDataset dataset = new BenchmarkDataset(
                    "1.0.0", "Test Dataset", "2026-10-14", List.of(verifiedMatchExample)
            );

            TrackMatchingEngine failingEngine = req -> {
                throw new RuntimeException("Simulated matching engine crash");
            };

            BenchmarkRunResult result = runner.run(dataset, failingEngine, new ControlledDecoyCandidatePoolProvider());

            assertThat(result.totalExamples()).isEqualTo(1);
            assertThat(result.failedCount()).isEqualTo(1);
            assertThat(result.eligibleCount()).isEqualTo(0);
            assertThat(result.excludedCount()).isEqualTo(0);
            assertThat(result.eligibleCount() + result.excludedCount() + result.failedCount())
                    .isEqualTo(result.totalExamples());

            BenchmarkExampleResult r = result.exampleResults().get(0);
            assertThat(r.eligible()).isFalse();
            assertThat(r.errorClassification()).isEqualTo(BenchmarkErrorCategory.EXECUTION_FAILURE);
            assertThat(r.errorMessage()).contains("Simulated matching engine crash");
        }

        @Test
        @DisplayName("repeated runs with ControlledDecoyCandidatePoolProvider should produce deterministic results")
        void shouldProduceDeterministicResults() {
            BenchmarkDataset dataset = new BenchmarkDataset(
                    "1.0.0", "Test Dataset", "2026-10-14",
                    List.of(verifiedMatchExample, verifiedNoMatchExample)
            );

            ControlledDecoyCandidatePoolProvider poolProvider = new ControlledDecoyCandidatePoolProvider();
            TrackMatchingEngine engine = new DefaultTrackMatchingEngine();

            BenchmarkRunResult run1 = runner.run(dataset, engine, poolProvider);
            BenchmarkRunResult run2 = runner.run(dataset, engine, poolProvider);

            assertThat(run1.totalExamples()).isEqualTo(run2.totalExamples());
            assertThat(run1.eligibleCount()).isEqualTo(run2.eligibleCount());

            for (int i = 0; i < run1.exampleResults().size(); i++) {
                BenchmarkExampleResult r1 = run1.exampleResults().get(i);
                BenchmarkExampleResult r2 = run2.exampleResults().get(i);

                assertThat(r1.exampleId()).isEqualTo(r2.exampleId());
                assertThat(r1.predictedDecision()).isEqualTo(r2.predictedDecision());
                assertThat(r1.predictedTargetVideoId()).isEqualTo(r2.predictedTargetVideoId());
                assertThat(r1.predictedScore()).isEqualTo(r2.predictedScore());
                assertThat(r1.errorClassification()).isEqualTo(r2.errorClassification());
            }
        }

        @Test
        @DisplayName("should execute full default 118-track ground-truth dataset and exclude BM-0029 by default")
        void shouldExecuteFullDefaultGroundTruthDataset() {
            BenchmarkRunResult result = runner.runDefault();

            assertThat(result.totalExamples()).isEqualTo(118);
            assertThat(result.eligibleCount()).isEqualTo(117);
            assertThat(result.excludedCount()).isEqualTo(1);
            assertThat(result.failedCount()).isEqualTo(0);

            // Verify BM-0029 is excluded due to UNVERIFIED status
            BenchmarkExampleResult unverified = result.getExampleResult("BM-0029").orElseThrow();
            assertThat(unverified.eligible()).isFalse();
            assertThat(unverified.exclusionReason()).isEqualTo(EvaluationExclusionReason.UNVERIFIED_STATUS);
            assertThat(unverified.errorClassification()).isEqualTo(BenchmarkErrorCategory.EXCLUDED_UNVERIFIED);

            // Verify split distributions
            assertThat(result.resultsBySplit(BenchmarkDatasetSplit.DEV)).hasSize(52);
            assertThat(result.resultsBySplit(BenchmarkDatasetSplit.VALIDATION)).hasSize(36);
            assertThat(result.resultsBySplit(BenchmarkDatasetSplit.TEST)).hasSize(30);
        }
    }
}
