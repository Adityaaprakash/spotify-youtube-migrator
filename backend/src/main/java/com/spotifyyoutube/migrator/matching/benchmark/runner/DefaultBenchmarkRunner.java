package com.spotifyyoutube.migrator.matching.benchmark.runner;

import com.spotifyyoutube.migrator.matching.benchmark.BenchmarkDataset;
import com.spotifyyoutube.migrator.matching.benchmark.BenchmarkDatasetLoader;
import com.spotifyyoutube.migrator.matching.benchmark.BenchmarkDatasetValidator;
import com.spotifyyoutube.migrator.matching.benchmark.BenchmarkExample;
import com.spotifyyoutube.migrator.matching.benchmark.BenchmarkExpectedOutcome;
import com.spotifyyoutube.migrator.matching.benchmark.BenchmarkSourceTrack;
import com.spotifyyoutube.migrator.matching.benchmark.pool.BenchmarkCandidatePoolProvider;
import com.spotifyyoutube.migrator.matching.engine.CandidateMatchEvaluation;
import com.spotifyyoutube.migrator.matching.engine.MatchDecision;
import com.spotifyyoutube.migrator.matching.engine.TrackMatchRequest;
import com.spotifyyoutube.migrator.matching.engine.TrackMatchResult;
import com.spotifyyoutube.migrator.matching.engine.TrackMatchingEngine;
import com.spotifyyoutube.migrator.matching.normalization.NormalizedTrackMetadata;
import com.spotifyyoutube.migrator.matching.normalization.RawTrackMetadata;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationTrackCandidate;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Default implementation of {@link BenchmarkRunner}.
 * <p>
 * Orchestrates the full offline benchmark evaluation pipeline:
 * <ol>
 *   <li>Validates the dataset schema and referential integrity using {@link BenchmarkDatasetValidator}.</li>
 *   <li>Filters examples according to the specified {@link BenchmarkEligibilityPolicy}.</li>
 *   <li>Obtains controlled candidate pools via {@link BenchmarkCandidatePoolProvider}.</li>
 *   <li>Normalizes source track metadata using the existing Phase 5 {@link com.spotifyyoutube.migrator.matching.normalization.NormalizationOrchestrator}.</li>
 *   <li>Invokes the frozen Phase 7 {@link TrackMatchingEngine} via its public {@code matchTrack(TrackMatchRequest)} contract.</li>
 *   <li>Maps ranked candidate evaluations, decisions, confidence, and execution durations.</li>
 *   <li>Deterministically classifies error categories and aggregates an immutable {@link BenchmarkRunResult}.</li>
 * </ol>
 */
public class DefaultBenchmarkRunner implements BenchmarkRunner {

    private final BenchmarkDatasetValidator validator;

    public DefaultBenchmarkRunner() {
        this(new BenchmarkDatasetValidator());
    }

    public DefaultBenchmarkRunner(BenchmarkDatasetValidator validator) {
        this.validator = Objects.requireNonNull(validator, "validator must not be null");
    }

    @Override
    public BenchmarkRunResult run(BenchmarkRunRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        BenchmarkDataset dataset = request.dataset();
        validator.validateAndThrow(dataset);

        long runStartTime = System.currentTimeMillis();
        List<BenchmarkExampleResult> results = new ArrayList<>(dataset.size());

        for (BenchmarkExample example : dataset.examples()) {
            BenchmarkExampleResult result = evaluateExample(example, request);
            results.add(result);
        }

        long totalDurationMs = System.currentTimeMillis() - runStartTime;

        return new BenchmarkRunResult(
                dataset.version(),
                dataset.description(),
                dataset.createdAt(),
                dataset.size(),
                results,
                request.candidatePoolProvider().strategyDescription(),
                request.eligibilityPolicy(),
                totalDurationMs
        );
    }

    /**
     * Executes the benchmark runner on the default version-controlled ground-truth dataset.
     */
    public BenchmarkRunResult runDefault() {
        BenchmarkDataset dataset = new BenchmarkDatasetLoader().loadDefault();
        return run(BenchmarkRunRequest.of(dataset));
    }

    /**
     * Executes the benchmark runner on a given dataset with default configurations.
     */
    public BenchmarkRunResult run(BenchmarkDataset dataset) {
        return run(BenchmarkRunRequest.of(dataset));
    }

    /**
     * Executes the benchmark runner on a dataset with a custom candidate pool provider.
     */
    public BenchmarkRunResult run(
            BenchmarkDataset dataset,
            BenchmarkCandidatePoolProvider candidatePoolProvider
    ) {
        return run(BenchmarkRunRequest.of(dataset, candidatePoolProvider));
    }

    /**
     * Executes the benchmark runner with a custom matching engine and candidate pool provider.
     */
    public BenchmarkRunResult run(
            BenchmarkDataset dataset,
            TrackMatchingEngine matchingEngine,
            BenchmarkCandidatePoolProvider candidatePoolProvider
    ) {
        return run(BenchmarkRunRequest.of(dataset, matchingEngine, candidatePoolProvider));
    }

    private BenchmarkExampleResult evaluateExample(
            BenchmarkExample example,
            BenchmarkRunRequest request
    ) {
        // 1. Eligibility Check
        BenchmarkEligibilityPolicy.EligibilityDecision eligibility =
                request.eligibilityPolicy().evaluateEligibility(example);

        String expectedVideoId = example.targetTrack() != null
                ? example.targetTrack().videoId()
                : null;

        if (!eligibility.eligible()) {
            BenchmarkErrorCategory excludedErrorCategory = mapExclusionErrorCategory(eligibility.reason());
            return BenchmarkExampleResult.excluded(
                    example.id(),
                    example.expectedOutcome(),
                    expectedVideoId,
                    eligibility.reason(),
                    example.category(),
                    example.split(),
                    excludedErrorCategory
            );
        }

        // 2. Obtain Candidate Pool
        List<DestinationTrackCandidate> candidates;
        try {
            candidates = request.candidatePoolProvider().getCandidates(example);
        } catch (Exception ex) {
            return BenchmarkExampleResult.failure(
                    example.id(),
                    example.expectedOutcome(),
                    expectedVideoId,
                    0,
                    false,
                    example.category(),
                    example.split(),
                    0L,
                    "Candidate pool provider error: " + ex.getMessage()
            );
        }

        boolean targetPresent = false;
        if (expectedVideoId != null && candidates != null) {
            targetPresent = candidates.stream()
                    .anyMatch(c -> c != null && Objects.equals(c.id(), expectedVideoId));
        }

        // 3. Normalize Source Track & Build Request
        BenchmarkSourceTrack source = example.sourceTrack();
        RawTrackMetadata rawMetadata = RawTrackMetadata.builder()
                .title(source.title())
                .artistCredit(source.primaryArtist())
                .artists(source.artists())
                .album(source.album() != null ? source.album() : "")
                .durationMs(source.durationMs())
                .build();

        NormalizedTrackMetadata normalizedSource;
        try {
            normalizedSource = request.normalizationOrchestrator().normalize(rawMetadata);
        } catch (Exception ex) {
            return BenchmarkExampleResult.failure(
                    example.id(),
                    example.expectedOutcome(),
                    expectedVideoId,
                    candidates != null ? candidates.size() : 0,
                    targetPresent,
                    example.category(),
                    example.split(),
                    0L,
                    "Source normalization error: " + ex.getMessage()
            );
        }

        TrackMatchRequest matchRequest = new TrackMatchRequest(
                source.id() != null ? source.id() : example.id(),
                normalizedSource,
                candidates != null ? candidates : List.of()
        );

        // 4. Invoke Matching Engine
        long exampleStart = System.currentTimeMillis();
        TrackMatchResult matchResult;
        try {
            matchResult = request.matchingEngine().matchTrack(matchRequest);
        } catch (Exception ex) {
            long duration = System.currentTimeMillis() - exampleStart;
            return BenchmarkExampleResult.failure(
                    example.id(),
                    example.expectedOutcome(),
                    expectedVideoId,
                    candidates != null ? candidates.size() : 0,
                    targetPresent,
                    example.category(),
                    example.split(),
                    duration,
                    "Matching engine error: " + ex.getMessage()
            );
        }
        long duration = System.currentTimeMillis() - exampleStart;

        // 5. Map Ranked Candidate Summaries
        List<RankedCandidateSummary> rankedSummaries = new ArrayList<>();
        if (matchResult.candidateEvaluations() != null) {
            for (int i = 0; i < matchResult.candidateEvaluations().size(); i++) {
                CandidateMatchEvaluation eval = matchResult.candidateEvaluations().get(i);
                DestinationTrackCandidate candidate = eval.candidate();
                boolean isTarget = expectedVideoId != null
                        && candidate != null
                        && Objects.equals(candidate.id(), expectedVideoId);

                rankedSummaries.add(new RankedCandidateSummary(
                        i + 1,
                        candidate != null ? candidate.id() : "unknown",
                        candidate != null ? candidate.title() : "",
                        candidate != null ? candidate.channelOrArtist() : "",
                        eval.overallScore(),
                        eval.calibratedProbability(),
                        eval.confidence(),
                        isTarget
                ));
            }
        }

        // 6. Evaluate Target Match
        String predictedVideoId = matchResult.selectedCandidate() != null
                ? matchResult.selectedCandidate().id()
                : null;

        boolean predictedMatchesExpected = false;
        if (expectedVideoId != null && predictedVideoId != null) {
            predictedMatchesExpected = Objects.equals(expectedVideoId, predictedVideoId);
        }

        // 7. Classify Error Category
        BenchmarkErrorCategory errorCategory = classifyError(
                example.expectedOutcome(),
                matchResult.decision(),
                targetPresent,
                predictedMatchesExpected,
                matchResult,
                candidates != null ? candidates.size() : 0
        );

        return new BenchmarkExampleResult(
                example.id(),
                example.expectedOutcome(),
                expectedVideoId,
                predictedVideoId,
                matchResult.decision(),
                matchResult.confidence(),
                matchResult.score(),
                matchResult.calibratedProbability(),
                rankedSummaries,
                candidates != null ? candidates.size() : 0,
                targetPresent,
                predictedMatchesExpected,
                true,
                EvaluationExclusionReason.NONE,
                example.category(),
                example.split(),
                duration,
                errorCategory,
                null
        );
    }

    private BenchmarkErrorCategory classifyError(
            BenchmarkExpectedOutcome expectedOutcome,
            MatchDecision decision,
            boolean targetPresentInPool,
            boolean predictedMatchesExpected,
            TrackMatchResult result,
            int candidatePoolSize
    ) {
        if (expectedOutcome.isMatch()) {
            if (decision == MatchDecision.MATCH) {
                if (predictedMatchesExpected) {
                    return BenchmarkErrorCategory.CORRECT_MATCH;
                } else {
                    return BenchmarkErrorCategory.WRONG_TARGET_SELECTED;
                }
            } else if (decision == MatchDecision.REVIEW_REQUIRED) {
                boolean hasVersionReason = result.matchReasons().stream()
                        .anyMatch(r -> r.toLowerCase().contains("version")
                                || r.toLowerCase().contains("penalty"));
                if (hasVersionReason) {
                    return BenchmarkErrorCategory.VERSION_MISMATCH;
                }
                boolean hasNamingReason = result.matchReasons().stream()
                        .anyMatch(r -> r.toLowerCase().contains("title")
                                || r.toLowerCase().contains("artist"));
                if (hasNamingReason) {
                    return BenchmarkErrorCategory.ARTIST_TITLE_CONFUSION;
                }
                return BenchmarkErrorCategory.REVIEW_REQUIRED;
            } else { // decision == NO_MATCH
                if (!targetPresentInPool) {
                    return BenchmarkErrorCategory.TARGET_ABSENT_FROM_CANDIDATE_POOL;
                }
                if (candidatePoolSize == 0) {
                    return BenchmarkErrorCategory.MISSING_OR_INSUFFICIENT_CANDIDATES;
                }
                return BenchmarkErrorCategory.FALSE_NO_MATCH_FOR_POSITIVE;
            }
        } else { // expectedOutcome is NO_MATCH
            if (decision == MatchDecision.NO_MATCH) {
                return BenchmarkErrorCategory.CORRECT_NO_MATCH;
            } else if (decision == MatchDecision.MATCH) {
                return BenchmarkErrorCategory.FALSE_ACCEPTANCE_OF_NO_MATCH;
            } else {
                return BenchmarkErrorCategory.REVIEW_REQUIRED;
            }
        }
    }

    private BenchmarkErrorCategory mapExclusionErrorCategory(EvaluationExclusionReason reason) {
        return switch (reason) {
            case UNVERIFIED_STATUS -> BenchmarkErrorCategory.EXCLUDED_UNVERIFIED;
            case DISPUTED_STATUS -> BenchmarkErrorCategory.EXCLUDED_DISPUTED;
            default -> BenchmarkErrorCategory.EXCLUDED_INELIGIBLE;
        };
    }
}
