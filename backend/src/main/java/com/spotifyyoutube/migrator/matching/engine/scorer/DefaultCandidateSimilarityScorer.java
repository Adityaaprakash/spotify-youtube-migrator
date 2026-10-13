package com.spotifyyoutube.migrator.matching.engine.scorer;

import com.spotifyyoutube.migrator.matching.engine.MatchScoreBreakdown;
import com.spotifyyoutube.migrator.matching.engine.feature.CandidateTrackFeatures;
import com.spotifyyoutube.migrator.matching.engine.feature.SourceTrackFeatures;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;

/**
 * Default implementation of {@link CandidateSimilarityScorer}.
 * <p>
 * Coordinates {@link TitleSimilarityScorer}, {@link ArtistSimilarityScorer},
 * {@link DurationSimilarityScorer}, {@link AlbumSimilarityScorer}, {@link VersionPenaltyScorer},
 * and {@link OfficialSignalScorer} to populate the corresponding component scores and penalties
 * in a {@link MatchScoreBreakdown}.
 * Preserves missing-data semantics by keeping absent scores as {@code null} without synthetic coercion.
 */
@Component
public class DefaultCandidateSimilarityScorer implements CandidateSimilarityScorer {

    private final TitleSimilarityScorer titleSimilarityScorer;
    private final ArtistSimilarityScorer artistSimilarityScorer;
    private final DurationSimilarityScorer durationSimilarityScorer;
    private final AlbumSimilarityScorer albumSimilarityScorer;
    private final VersionPenaltyScorer versionPenaltyScorer;
    private final OfficialSignalScorer officialSignalScorer;

    public DefaultCandidateSimilarityScorer(
            TitleSimilarityScorer titleSimilarityScorer,
            ArtistSimilarityScorer artistSimilarityScorer,
            DurationSimilarityScorer durationSimilarityScorer,
            AlbumSimilarityScorer albumSimilarityScorer,
            VersionPenaltyScorer versionPenaltyScorer,
            OfficialSignalScorer officialSignalScorer
    ) {
        this.titleSimilarityScorer = Objects.requireNonNull(titleSimilarityScorer, "titleSimilarityScorer must not be null");
        this.artistSimilarityScorer = Objects.requireNonNull(artistSimilarityScorer, "artistSimilarityScorer must not be null");
        this.durationSimilarityScorer = Objects.requireNonNull(durationSimilarityScorer, "durationSimilarityScorer must not be null");
        this.albumSimilarityScorer = Objects.requireNonNull(albumSimilarityScorer, "albumSimilarityScorer must not be null");
        this.versionPenaltyScorer = Objects.requireNonNull(versionPenaltyScorer, "versionPenaltyScorer must not be null");
        this.officialSignalScorer = Objects.requireNonNull(officialSignalScorer, "officialSignalScorer must not be null");
    }

    public DefaultCandidateSimilarityScorer(
            TitleSimilarityScorer titleSimilarityScorer,
            ArtistSimilarityScorer artistSimilarityScorer,
            DurationSimilarityScorer durationSimilarityScorer,
            AlbumSimilarityScorer albumSimilarityScorer,
            VersionPenaltyScorer versionPenaltyScorer
    ) {
        this(titleSimilarityScorer, artistSimilarityScorer, durationSimilarityScorer, albumSimilarityScorer,
                versionPenaltyScorer, new DefaultOfficialSignalScorer());
    }

    public DefaultCandidateSimilarityScorer(
            TitleSimilarityScorer titleSimilarityScorer,
            ArtistSimilarityScorer artistSimilarityScorer,
            DurationSimilarityScorer durationSimilarityScorer,
            AlbumSimilarityScorer albumSimilarityScorer
    ) {
        this(titleSimilarityScorer, artistSimilarityScorer, durationSimilarityScorer, albumSimilarityScorer,
                new DefaultVersionPenaltyScorer(), new DefaultOfficialSignalScorer());
    }

    public DefaultCandidateSimilarityScorer(
            TitleSimilarityScorer titleSimilarityScorer,
            ArtistSimilarityScorer artistSimilarityScorer
    ) {
        this(titleSimilarityScorer, artistSimilarityScorer,
                new DefaultDurationSimilarityScorer(), new DefaultAlbumSimilarityScorer(),
                new DefaultVersionPenaltyScorer(), new DefaultOfficialSignalScorer());
    }

    public DefaultCandidateSimilarityScorer() {
        this(new DefaultTitleSimilarityScorer(), new DefaultArtistSimilarityScorer(),
                new DefaultDurationSimilarityScorer(), new DefaultAlbumSimilarityScorer(),
                new DefaultVersionPenaltyScorer(), new DefaultOfficialSignalScorer());
    }

    @Override
    public MatchScoreBreakdown scoreCandidate(SourceTrackFeatures source, CandidateTrackFeatures candidate) {
        Objects.requireNonNull(source, "SourceTrackFeatures must not be null");
        Objects.requireNonNull(candidate, "CandidateTrackFeatures must not be null");

        Double titleScore = titleSimilarityScorer.score(source, candidate);
        Double artistScore = artistSimilarityScorer.score(source, candidate);
        Double durationScore = durationSimilarityScorer.score(source, candidate);
        Double albumScore = albumSimilarityScorer.score(source, candidate);
        Double officialSignalScore = officialSignalScorer.score(source, candidate);
        Double versionPenalty = versionPenaltyScorer.calculatePenalty(source, candidate);
        Map<String, Double> detailedPenalties = versionPenaltyScorer.calculateDetailedPenalties(source, candidate);

        MatchScoreBreakdown.Builder builder = MatchScoreBreakdown.builder();
        if (titleScore != null) {
            builder.titleScore(titleScore);
        }
        if (artistScore != null) {
            builder.artistScore(artistScore);
        }
        if (durationScore != null) {
            builder.durationScore(durationScore);
        }
        if (albumScore != null) {
            builder.albumScore(albumScore);
        }
        if (officialSignalScore != null) {
            builder.officialSignalScore(officialSignalScore);
        }
        if (versionPenalty != null) {
            builder.versionPenalty(versionPenalty);
        }
        if (detailedPenalties != null && !detailedPenalties.isEmpty()) {
            builder.detailedPenalties(detailedPenalties);
        }

        return builder.build();
    }
}
