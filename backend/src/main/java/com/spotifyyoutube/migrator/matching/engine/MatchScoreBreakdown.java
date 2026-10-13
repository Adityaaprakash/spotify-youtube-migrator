package com.spotifyyoutube.migrator.matching.engine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Provider-neutral, immutable breakdown of component similarity scores and penalties.
 * Represents component evidence honestly: unavailable evidence is preserved as null rather
 * than defaulting to zero, preventing missing data from being treated as a known mismatch.
 * <p>
 * Does not implement weights, thresholds, or composite scoring (deferred to Phase 7C+).
 *
 * @param titleScore similarity score for track title (0.0 to 1.0), or null if uncomputed
 * @param artistScore similarity score for track artists (0.0 to 1.0), or null if uncomputed
 * @param durationScore similarity score for duration alignment (0.0 to 1.0), or null if duration is absent
 * @param albumScore similarity score for album name (0.0 to 1.0), or null if album metadata is absent
 * @param officialSignalScore confidence score derived from official provenance signals (0.0 to 1.0), or null if absent
 * @param versionPenalty aggregate deduction for version discrepancies, or null if uncalculated
 * @param detailedPenalties unmodifiable mapping of specific penalty names to deduction values
 */
public record MatchScoreBreakdown(
        Double titleScore,
        Double artistScore,
        Double durationScore,
        Double albumScore,
        Double officialSignalScore,
        Double versionPenalty,
        Map<String, Double> detailedPenalties
) {
    public MatchScoreBreakdown {
        detailedPenalties = detailedPenalties != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(detailedPenalties))
                : Collections.emptyMap();
    }

    /**
     * Creates an empty breakdown where all component scores and penalties are unavailable.
     */
    public static MatchScoreBreakdown empty() {
        return new MatchScoreBreakdown(null, null, null, null, null, null, Collections.emptyMap());
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean hasTitleScore() {
        return titleScore != null;
    }

    public boolean hasArtistScore() {
        return artistScore != null;
    }

    public boolean hasDurationScore() {
        return durationScore != null;
    }

    public boolean hasAlbumScore() {
        return albumScore != null;
    }

    public boolean hasOfficialSignalScore() {
        return officialSignalScore != null;
    }

    public boolean hasVersionPenalty() {
        return versionPenalty != null;
    }

    public boolean isEmpty() {
        return titleScore == null
                && artistScore == null
                && durationScore == null
                && albumScore == null
                && officialSignalScore == null
                && versionPenalty == null
                && detailedPenalties.isEmpty();
    }

    public Optional<Double> getTitleScore() {
        return Optional.ofNullable(titleScore);
    }

    public Optional<Double> getArtistScore() {
        return Optional.ofNullable(artistScore);
    }

    public Optional<Double> getDurationScore() {
        return Optional.ofNullable(durationScore);
    }

    public Optional<Double> getAlbumScore() {
        return Optional.ofNullable(albumScore);
    }

    public Optional<Double> getOfficialSignalScore() {
        return Optional.ofNullable(officialSignalScore);
    }

    public Optional<Double> getVersionPenalty() {
        return Optional.ofNullable(versionPenalty);
    }

    public static final class Builder {
        private Double titleScore;
        private Double artistScore;
        private Double durationScore;
        private Double albumScore;
        private Double officialSignalScore;
        private Double versionPenalty;
        private final Map<String, Double> detailedPenalties = new LinkedHashMap<>();

        public Builder titleScore(Double titleScore) {
            this.titleScore = titleScore;
            return this;
        }

        public Builder artistScore(Double artistScore) {
            this.artistScore = artistScore;
            return this;
        }

        public Builder durationScore(Double durationScore) {
            this.durationScore = durationScore;
            return this;
        }

        public Builder albumScore(Double albumScore) {
            this.albumScore = albumScore;
            return this;
        }

        public Builder officialSignalScore(Double officialSignalScore) {
            this.officialSignalScore = officialSignalScore;
            return this;
        }

        public Builder versionPenalty(Double versionPenalty) {
            this.versionPenalty = versionPenalty;
            return this;
        }

        public Builder addPenalty(String penaltyName, Double penaltyValue) {
            if (penaltyName != null && !penaltyName.isBlank() && penaltyValue != null) {
                this.detailedPenalties.put(penaltyName.trim(), penaltyValue);
            }
            return this;
        }

        public Builder detailedPenalties(Map<String, Double> detailedPenalties) {
            this.detailedPenalties.clear();
            if (detailedPenalties != null) {
                this.detailedPenalties.putAll(detailedPenalties);
            }
            return this;
        }

        public MatchScoreBreakdown build() {
            return new MatchScoreBreakdown(
                    titleScore,
                    artistScore,
                    durationScore,
                    albumScore,
                    officialSignalScore,
                    versionPenalty,
                    detailedPenalties
            );
        }
    }
}
