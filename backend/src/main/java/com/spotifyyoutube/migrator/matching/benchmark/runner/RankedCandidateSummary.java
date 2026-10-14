package com.spotifyyoutube.migrator.matching.benchmark.runner;

import com.spotifyyoutube.migrator.matching.engine.MatchConfidence;

import java.util.Objects;
import java.util.Optional;

/**
 * Diagnostic summary of an individual ranked candidate produced by the matching engine.
 *
 * @param rank 1-based rank position assigned by candidate ranking engine
 * @param videoId destination candidate identifier
 * @param title candidate video title
 * @param channelOrArtist candidate channel or uploader name
 * @param score composite numerical similarity score, or null if uncomputed
 * @param calibratedProbability estimated probability of true match, or null if uncalibrated
 * @param confidence qualitative confidence level, or null if unclassified
 * @param isExpectedTarget true if this candidate matches the expected ground-truth target video ID
 */
public record RankedCandidateSummary(
        int rank,
        String videoId,
        String title,
        String channelOrArtist,
        Double score,
        Double calibratedProbability,
        MatchConfidence confidence,
        boolean isExpectedTarget
) {
    public RankedCandidateSummary {
        Objects.requireNonNull(videoId, "videoId must not be null");
    }

    public Optional<Double> getScore() {
        return Optional.ofNullable(score);
    }

    public Optional<Double> getCalibratedProbability() {
        return Optional.ofNullable(calibratedProbability);
    }

    public Optional<MatchConfidence> getConfidence() {
        return Optional.ofNullable(confidence);
    }
}
