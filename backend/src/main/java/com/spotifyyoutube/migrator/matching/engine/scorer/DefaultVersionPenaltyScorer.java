package com.spotifyyoutube.migrator.matching.engine.scorer;

import com.spotifyyoutube.migrator.matching.engine.feature.CandidateTrackFeatures;
import com.spotifyyoutube.migrator.matching.engine.feature.CandidateVersionEvidence;
import com.spotifyyoutube.migrator.matching.engine.feature.SourceTrackFeatures;
import com.spotifyyoutube.migrator.matching.engine.feature.VersionIndicator;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Default implementation of {@link VersionPenaltyScorer}.
 * <p>
 * Deterministically evaluates recording version discrepancies and applies standardized deductions:
 * <ul>
 *   <li>{@code REACTION}: 0.30 deduction (commentary / reaction videos)</li>
 *   <li>{@code KARAOKE}: 0.30 deduction (backing track / sing-along versions)</li>
 *   <li>{@code COVER}: 0.25 deduction (alternate artist / amateur cover performances)</li>
 *   <li>{@code NIGHTCORE}: 0.20 deduction (accelerated tempo + high pitch)</li>
 *   <li>{@code SPED_UP}: 0.15 deduction (accelerated tempo)</li>
 *   <li>{@code SLOWED}: 0.15 deduction (slowed down / chopped &amp; screwed)</li>
 *   <li>{@code LIVE_MISMATCH}: 0.15 deduction (live vs studio recording mismatch)</li>
 *   <li>{@code REMIX_MISMATCH}: 0.15 deduction (remix vs original track mismatch)</li>
 *   <li>{@code ACOUSTIC_MISMATCH}: 0.10 deduction (acoustic vs standard recording mismatch)</li>
 *   <li>{@code INSTRUMENTAL_MISMATCH}: 0.15 deduction (instrumental vs vocal recording mismatch)</li>
 *   <li>{@code REMASTER}: 0.00 deduction (remastered audio releases represent the same core recording)</li>
 * </ul>
 * Aggregate penalty is bounded in {@code [0.0, 1.0]} and returns {@code 0.0} when no discrepancies exist.
 */
@Component
public class DefaultVersionPenaltyScorer implements VersionPenaltyScorer {

    public static final double COVER_PENALTY = 0.25;
    public static final double REACTION_PENALTY = 0.30;
    public static final double KARAOKE_PENALTY = 0.30;
    public static final double NIGHTCORE_PENALTY = 0.20;
    public static final double SPED_UP_PENALTY = 0.15;
    public static final double SLOWED_PENALTY = 0.15;
    public static final double LIVE_MISMATCH_PENALTY = 0.15;
    public static final double REMIX_MISMATCH_PENALTY = 0.15;
    public static final double ACOUSTIC_MISMATCH_PENALTY = 0.10;
    public static final double INSTRUMENTAL_MISMATCH_PENALTY = 0.15;
    public static final double MAX_PENALTY = 1.0;

    @Override
    public Double calculatePenalty(SourceTrackFeatures source, CandidateTrackFeatures candidate) {
        Objects.requireNonNull(source, "SourceTrackFeatures must not be null");
        Objects.requireNonNull(candidate, "CandidateTrackFeatures must not be null");

        Map<String, Double> detailed = calculateDetailedPenalties(source, candidate);
        if (detailed.isEmpty()) {
            return 0.0;
        }

        double sum = detailed.values().stream().mapToDouble(Double::doubleValue).sum();
        double clamped = Math.min(MAX_PENALTY, Math.max(0.0, sum));
        return roundToFourDecimals(clamped);
    }

    @Override
    public Map<String, Double> calculateDetailedPenalties(SourceTrackFeatures source, CandidateTrackFeatures candidate) {
        Objects.requireNonNull(source, "SourceTrackFeatures must not be null");
        Objects.requireNonNull(candidate, "CandidateTrackFeatures must not be null");

        CandidateVersionEvidence cEvidence = candidate.versionEvidence() != null
                ? candidate.versionEvidence()
                : CandidateVersionEvidence.empty();

        Map<String, Double> penalties = new LinkedHashMap<>();

        // 1. Cover
        boolean candidateIsCover = candidate.hasIndicator(VersionIndicator.COVER) || cEvidence.isCover();
        boolean sourceIsCover = source.hasIndicator(VersionIndicator.COVER);
        if (candidateIsCover != sourceIsCover) {
            penalties.put("COVER", COVER_PENALTY);
        }

        // 2. Reaction (never matches source track)
        boolean candidateIsReaction = candidate.hasIndicator(VersionIndicator.REACTION) || cEvidence.isReaction();
        if (candidateIsReaction) {
            penalties.put("REACTION", REACTION_PENALTY);
        }

        // 3. Karaoke
        boolean candidateIsKaraoke = candidate.hasIndicator(VersionIndicator.KARAOKE) || cEvidence.isKaraoke();
        boolean sourceIsKaraoke = source.hasIndicator(VersionIndicator.KARAOKE);
        if (candidateIsKaraoke != sourceIsKaraoke) {
            penalties.put("KARAOKE", KARAOKE_PENALTY);
        }

        // 4. Nightcore
        boolean candidateIsNightcore = candidate.hasIndicator(VersionIndicator.NIGHTCORE) || cEvidence.isNightcore();
        boolean sourceIsNightcore = source.hasIndicator(VersionIndicator.NIGHTCORE);
        if (candidateIsNightcore != sourceIsNightcore) {
            penalties.put("NIGHTCORE", NIGHTCORE_PENALTY);
        }

        // 5. Sped Up
        boolean candidateIsSpedUp = candidate.hasIndicator(VersionIndicator.SPED_UP) || cEvidence.isSpedUp();
        boolean sourceIsSpedUp = source.hasIndicator(VersionIndicator.SPED_UP);
        if (candidateIsSpedUp != sourceIsSpedUp) {
            penalties.put("SPED_UP", SPED_UP_PENALTY);
        }

        // 6. Slowed
        boolean candidateIsSlowed = candidate.hasIndicator(VersionIndicator.SLOWED) || cEvidence.isSlowed();
        boolean sourceIsSlowed = source.hasIndicator(VersionIndicator.SLOWED);
        if (candidateIsSlowed != sourceIsSlowed) {
            penalties.put("SLOWED", SLOWED_PENALTY);
        }

        // 7. Live mismatch
        boolean sourceIsLive = source.isLive() || source.hasIndicator(VersionIndicator.LIVE);
        boolean candidateIsLive = candidate.hasIndicator(VersionIndicator.LIVE) || cEvidence.isLive();
        if (sourceIsLive != candidateIsLive) {
            penalties.put("LIVE_MISMATCH", LIVE_MISMATCH_PENALTY);
        }

        // 8. Remix mismatch
        boolean sourceIsRemix = source.hasIndicator(VersionIndicator.REMIX)
                || source.normalizedTitle().toLowerCase(Locale.ROOT).matches(".*\\bremix\\b.*");
        boolean candidateIsRemix = candidate.hasIndicator(VersionIndicator.REMIX) || cEvidence.isRemix();
        if (sourceIsRemix != candidateIsRemix) {
            penalties.put("REMIX_MISMATCH", REMIX_MISMATCH_PENALTY);
        }

        // 9. Acoustic mismatch
        boolean sourceIsAcoustic = source.isAcoustic() || source.hasIndicator(VersionIndicator.ACOUSTIC);
        boolean candidateIsAcoustic = candidate.hasIndicator(VersionIndicator.ACOUSTIC) || cEvidence.isAcoustic();
        if (sourceIsAcoustic != candidateIsAcoustic) {
            penalties.put("ACOUSTIC_MISMATCH", ACOUSTIC_MISMATCH_PENALTY);
        }

        // 10. Instrumental mismatch
        boolean sourceIsInstrumental = source.hasIndicator(VersionIndicator.INSTRUMENTAL);
        boolean candidateIsInstrumental = candidate.hasIndicator(VersionIndicator.INSTRUMENTAL) || cEvidence.isInstrumental();
        if (sourceIsInstrumental != candidateIsInstrumental) {
            penalties.put("INSTRUMENTAL_MISMATCH", INSTRUMENTAL_MISMATCH_PENALTY);
        }

        return Collections.unmodifiableMap(penalties);
    }

    /**
     * Calculates the aggregate version penalty deduction for the given sets of version indicators.
     *
     * @param sourceIndicators set of recognized version indicators on the source track
     * @param candidateIndicators set of recognized version indicators on the candidate track
     * @return aggregate penalty deduction bounded in {@code [0.0, 1.0]}
     */
    public Double calculatePenalty(Set<VersionIndicator> sourceIndicators, Set<VersionIndicator> candidateIndicators) {
        Map<String, Double> detailed = calculateDetailedPenalties(sourceIndicators, candidateIndicators);
        if (detailed.isEmpty()) {
            return 0.0;
        }
        double sum = detailed.values().stream().mapToDouble(Double::doubleValue).sum();
        double clamped = Math.min(MAX_PENALTY, Math.max(0.0, sum));
        return roundToFourDecimals(clamped);
    }

    /**
     * Calculates the itemized breakdown of specific penalty names to deduction values for the given sets of indicators.
     *
     * @param sourceIndicators set of recognized version indicators on the source track
     * @param candidateIndicators set of recognized version indicators on the candidate track
     * @return unmodifiable mapping of specific penalty names to deduction values
     */
    public Map<String, Double> calculateDetailedPenalties(
            Set<VersionIndicator> sourceIndicators,
            Set<VersionIndicator> candidateIndicators
    ) {
        Set<VersionIndicator> sSet = sourceIndicators != null ? sourceIndicators : Collections.emptySet();
        Set<VersionIndicator> cSet = candidateIndicators != null ? candidateIndicators : Collections.emptySet();

        Map<String, Double> penalties = new LinkedHashMap<>();

        // Cover
        if (cSet.contains(VersionIndicator.COVER) != sSet.contains(VersionIndicator.COVER)) {
            penalties.put("COVER", COVER_PENALTY);
        }
        // Reaction
        if (cSet.contains(VersionIndicator.REACTION)) {
            penalties.put("REACTION", REACTION_PENALTY);
        }
        // Karaoke
        if (cSet.contains(VersionIndicator.KARAOKE) != sSet.contains(VersionIndicator.KARAOKE)) {
            penalties.put("KARAOKE", KARAOKE_PENALTY);
        }
        // Nightcore
        if (cSet.contains(VersionIndicator.NIGHTCORE) != sSet.contains(VersionIndicator.NIGHTCORE)) {
            penalties.put("NIGHTCORE", NIGHTCORE_PENALTY);
        }
        // Sped Up
        if (cSet.contains(VersionIndicator.SPED_UP) != sSet.contains(VersionIndicator.SPED_UP)) {
            penalties.put("SPED_UP", SPED_UP_PENALTY);
        }
        // Slowed
        if (cSet.contains(VersionIndicator.SLOWED) != sSet.contains(VersionIndicator.SLOWED)) {
            penalties.put("SLOWED", SLOWED_PENALTY);
        }
        // Live
        if (cSet.contains(VersionIndicator.LIVE) != sSet.contains(VersionIndicator.LIVE)) {
            penalties.put("LIVE_MISMATCH", LIVE_MISMATCH_PENALTY);
        }
        // Remix
        if (cSet.contains(VersionIndicator.REMIX) != sSet.contains(VersionIndicator.REMIX)) {
            penalties.put("REMIX_MISMATCH", REMIX_MISMATCH_PENALTY);
        }
        // Acoustic
        if (cSet.contains(VersionIndicator.ACOUSTIC) != sSet.contains(VersionIndicator.ACOUSTIC)) {
            penalties.put("ACOUSTIC_MISMATCH", ACOUSTIC_MISMATCH_PENALTY);
        }
        // Instrumental
        if (cSet.contains(VersionIndicator.INSTRUMENTAL) != sSet.contains(VersionIndicator.INSTRUMENTAL)) {
            penalties.put("INSTRUMENTAL_MISMATCH", INSTRUMENTAL_MISMATCH_PENALTY);
        }

        return Collections.unmodifiableMap(penalties);
    }

    private double roundToFourDecimals(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}
