package com.spotifyyoutube.migrator.matching.engine.scorer;

import com.spotifyyoutube.migrator.matching.engine.feature.CandidateTrackFeatures;
import com.spotifyyoutube.migrator.matching.engine.feature.SourceTrackFeatures;

import java.util.Map;

/**
 * Strategy interface for calculating recording-version and variant discrepancy penalties
 * between a source track and a candidate track.
 * <p>
 * Evaluates variant evidence (covers, reactions, karaoke, nightcore, sped-up, slowed, remixes,
 * live recordings, acoustic, instrumental) and produces deterministic deductions bounded in {@code [0.0, 1.0]}.
 */
public interface VersionPenaltyScorer {

    /**
     * Calculates the aggregate version penalty deduction for the given source and candidate tracks.
     *
     * @param source source track features, must not be null
     * @param candidate candidate track features, must not be null
     * @return aggregate penalty deduction bounded in {@code [0.0, 1.0]} (0.0 if no penalty applies)
     * @throws NullPointerException if source or candidate is null
     */
    Double calculatePenalty(SourceTrackFeatures source, CandidateTrackFeatures candidate);

    /**
     * Calculates the itemized breakdown of specific penalty names to deduction values.
     *
     * @param source source track features, must not be null
     * @param candidate candidate track features, must not be null
     * @return unmodifiable mapping of specific penalty names (e.g. "COVER", "LIVE_MISMATCH") to deduction values
     * @throws NullPointerException if source or candidate is null
     */
    Map<String, Double> calculateDetailedPenalties(SourceTrackFeatures source, CandidateTrackFeatures candidate);

    /**
     * Alias for {@link #calculatePenalty(SourceTrackFeatures, CandidateTrackFeatures)} conforming to
     * the standard scorer interface pattern.
     *
     * @param source source track features, must not be null
     * @param candidate candidate track features, must not be null
     * @return aggregate penalty deduction bounded in {@code [0.0, 1.0]}
     */
    default Double score(SourceTrackFeatures source, CandidateTrackFeatures candidate) {
        return calculatePenalty(source, candidate);
    }
}
