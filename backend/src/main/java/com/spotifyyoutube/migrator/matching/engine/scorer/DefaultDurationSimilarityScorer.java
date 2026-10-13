package com.spotifyyoutube.migrator.matching.engine.scorer;

import com.spotifyyoutube.migrator.matching.engine.feature.CandidateTrackFeatures;
import com.spotifyyoutube.migrator.matching.engine.feature.SourceTrackFeatures;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;

/**
 * Default implementation of {@link DurationSimilarityScorer}.
 * <p>
 * Evaluates duration alignment using a continuous, monotonic non-increasing, piecewise decay function
 * (with constant-score intervals on {@code <= 2.0s} and {@code > 60.0s}):
 * <ul>
 *   <li>{@code <= 2.0s} offset: {@code 1.0} (full credit; accounts for metadata rounding, container headers,
 *       and trailing silence padding).</li>
 *   <li>{@code 2.0s to 10.0s} offset: linearly decays from {@code 1.0} to {@code 0.85} (small provider-specific
 *       offsets such as video title slates or short intro bumpers).</li>
 *   <li>{@code 10.0s to 30.0s} offset: linearly decays from {@code 0.85} to {@code 0.40} (moderate music video
 *       narrative intros, extended outros, or radio edits).</li>
 *   <li>{@code 30.0s to 60.0s} offset: linearly decays from {@code 0.40} to {@code 0.0} (substantial edit discrepancies).</li>
 *   <li>{@code > 60.0s} offset: {@code 0.0} (disqualifying duration mismatch).</li>
 * </ul>
 * Missing evidence returns {@code null} without synthetic coercion.
 */
@Component
public class DefaultDurationSimilarityScorer implements DurationSimilarityScorer {

    private static final double TOLERANCE_SECONDS = 2.0;
    private static final double SMALL_OFFSET_THRESHOLD_SECONDS = 10.0;
    private static final double MODERATE_OFFSET_THRESHOLD_SECONDS = 30.0;
    private static final double MAX_ALLOWABLE_OFFSET_SECONDS = 60.0;

    private static final double SCORE_AT_SMALL_THRESHOLD = 0.85;
    private static final double SCORE_AT_MODERATE_THRESHOLD = 0.40;

    @Override
    public Double score(SourceTrackFeatures source, CandidateTrackFeatures candidate) {
        Objects.requireNonNull(source, "SourceTrackFeatures must not be null");
        Objects.requireNonNull(candidate, "CandidateTrackFeatures must not be null");

        Long sMs = source.durationMs();
        Long cMs = candidate.durationMs();

        return score(sMs, cMs);
    }

    @Override
    public Double score(Duration sourceDuration, Duration candidateDuration) {
        if (sourceDuration == null || candidateDuration == null) {
            return null;
        }
        if (sourceDuration.isNegative() || candidateDuration.isNegative()) {
            throw new IllegalArgumentException("Duration must not be negative");
        }
        return score(sourceDuration.toMillis(), candidateDuration.toMillis());
    }

    @Override
    public Double score(Long sourceDurationMs, Long candidateDurationMs) {
        if (sourceDurationMs == null || candidateDurationMs == null) {
            return null;
        }
        if (sourceDurationMs < 0 || candidateDurationMs < 0) {
            throw new IllegalArgumentException("Duration in milliseconds must not be negative");
        }

        long diffMs = Math.abs(sourceDurationMs - candidateDurationMs);
        double diffSeconds = diffMs / 1000.0;

        if (diffSeconds <= TOLERANCE_SECONDS) {
            return 1.0;
        }

        if (diffSeconds <= SMALL_OFFSET_THRESHOLD_SECONDS) {
            double range = SMALL_OFFSET_THRESHOLD_SECONDS - TOLERANCE_SECONDS;
            double progress = (diffSeconds - TOLERANCE_SECONDS) / range;
            double score = 1.0 - progress * (1.0 - SCORE_AT_SMALL_THRESHOLD);
            return roundToFourDecimals(score);
        }

        if (diffSeconds <= MODERATE_OFFSET_THRESHOLD_SECONDS) {
            double range = MODERATE_OFFSET_THRESHOLD_SECONDS - SMALL_OFFSET_THRESHOLD_SECONDS;
            double progress = (diffSeconds - SMALL_OFFSET_THRESHOLD_SECONDS) / range;
            double score = SCORE_AT_SMALL_THRESHOLD - progress * (SCORE_AT_SMALL_THRESHOLD - SCORE_AT_MODERATE_THRESHOLD);
            return roundToFourDecimals(score);
        }

        if (diffSeconds <= MAX_ALLOWABLE_OFFSET_SECONDS) {
            double range = MAX_ALLOWABLE_OFFSET_SECONDS - MODERATE_OFFSET_THRESHOLD_SECONDS;
            double progress = (diffSeconds - MODERATE_OFFSET_THRESHOLD_SECONDS) / range;
            double score = SCORE_AT_MODERATE_THRESHOLD - progress * SCORE_AT_MODERATE_THRESHOLD;
            return roundToFourDecimals(score);
        }

        return 0.0;
    }

    private double roundToFourDecimals(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}
