package com.spotifyyoutube.migrator.matching.engine.scorer;

import com.spotifyyoutube.migrator.matching.engine.feature.CandidateTrackFeatures;
import com.spotifyyoutube.migrator.matching.engine.feature.SourceTrackFeatures;

import java.time.Duration;

/**
 * Strategy interface for evaluating duration similarity between a source track and a candidate track.
 * Produces a normalized score in {@code [0.0, 1.0]} or returns {@code null} when duration evidence
 * is absent on either side, adhering to honest missing-data semantics.
 */
public interface DurationSimilarityScorer {

    /**
     * Evaluates duration alignment between source and candidate track features.
     *
     * @param source source track features containing duration and durationMs, must not be null
     * @param candidate candidate track features containing duration and durationMs, must not be null
     * @return normalized similarity score in {@code [0.0, 1.0]}, or {@code null} if duration is absent on either side
     * @throws NullPointerException if source or candidate is null
     */
    Double score(SourceTrackFeatures source, CandidateTrackFeatures candidate);

    /**
     * Evaluates duration alignment between two {@link Duration} representations.
     *
     * @param sourceDuration duration of the source track, or null if absent
     * @param candidateDuration duration of the candidate track, or null if absent
     * @return normalized similarity score in {@code [0.0, 1.0]}, or {@code null} if either duration is null
     */
    Double score(Duration sourceDuration, Duration candidateDuration);

    /**
     * Evaluates duration alignment between two millisecond values.
     *
     * @param sourceDurationMs duration in milliseconds of the source track, or null if absent
     * @param candidateDurationMs duration in milliseconds of the candidate track, or null if absent
     * @return normalized similarity score in {@code [0.0, 1.0]}, or {@code null} if either value is null
     */
    Double score(Long sourceDurationMs, Long candidateDurationMs);
}
