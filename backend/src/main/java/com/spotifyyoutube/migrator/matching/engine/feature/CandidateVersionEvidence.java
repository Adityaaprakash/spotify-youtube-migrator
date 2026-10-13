package com.spotifyyoutube.migrator.matching.engine.feature;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Immutable record capturing detected version, recording, and content indicators from candidate metadata.
 * Preserves detected indicators, the raw text tokens that triggered them, and any unclassified annotations
 * without prematurely applying numerical scoring deductions.
 *
 * @param indicators detected version indicators in encounter order
 * @param rawTokens the exact text tokens triggering the indicator classifications
 * @param unmatchedAnnotations parenthetical or bracketed annotations that did not match known indicators
 * @param hasConflictingIndicators true if contradictory indicators coexist (e.g. SPED_UP and SLOWED)
 * @param hasAmbiguity true if classification exhibits unresolved ambiguity
 */
public record CandidateVersionEvidence(
        Set<VersionIndicator> indicators,
        List<String> rawTokens,
        List<String> unmatchedAnnotations,
        boolean hasConflictingIndicators,
        boolean hasAmbiguity
) {
    public CandidateVersionEvidence {
        indicators = indicators != null
                ? Collections.unmodifiableSet(new LinkedHashSet<>(indicators))
                : Collections.emptySet();
        rawTokens = rawTokens != null
                ? List.copyOf(rawTokens)
                : List.of();
        unmatchedAnnotations = unmatchedAnnotations != null
                ? List.copyOf(unmatchedAnnotations)
                : List.of();
    }

    /**
     * Creates an empty evidence instance representing no detected indicators.
     */
    public static CandidateVersionEvidence empty() {
        return new CandidateVersionEvidence(Collections.emptySet(), List.of(), List.of(), false, false);
    }

    public boolean hasIndicator(VersionIndicator indicator) {
        return indicators.contains(indicator);
    }

    public boolean isCover() {
        return hasIndicator(VersionIndicator.COVER);
    }

    public boolean isLive() {
        return hasIndicator(VersionIndicator.LIVE);
    }

    public boolean isRemix() {
        return hasIndicator(VersionIndicator.REMIX);
    }

    public boolean isKaraoke() {
        return hasIndicator(VersionIndicator.KARAOKE);
    }

    public boolean isReaction() {
        return hasIndicator(VersionIndicator.REACTION);
    }

    public boolean isNightcore() {
        return hasIndicator(VersionIndicator.NIGHTCORE);
    }

    public boolean isSpedUp() {
        return hasIndicator(VersionIndicator.SPED_UP);
    }

    public boolean isSlowed() {
        return hasIndicator(VersionIndicator.SLOWED);
    }

    public boolean isAcoustic() {
        return hasIndicator(VersionIndicator.ACOUSTIC);
    }

    public boolean isInstrumental() {
        return hasIndicator(VersionIndicator.INSTRUMENTAL);
    }

    public boolean isRemaster() {
        return hasIndicator(VersionIndicator.REMASTER);
    }

    public boolean isEmpty() {
        return indicators.isEmpty();
    }
}
