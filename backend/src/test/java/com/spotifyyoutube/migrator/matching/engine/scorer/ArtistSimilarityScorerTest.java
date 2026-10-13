package com.spotifyyoutube.migrator.matching.engine.scorer;

import com.spotifyyoutube.migrator.matching.engine.feature.CandidateTrackFeatures;
import com.spotifyyoutube.migrator.matching.engine.feature.CandidateVersionEvidence;
import com.spotifyyoutube.migrator.matching.engine.feature.OfficialSignalEvidence;
import com.spotifyyoutube.migrator.matching.engine.feature.SourceTrackFeatures;
import com.spotifyyoutube.migrator.matching.normalization.NormalizedArtistCredit;
import com.spotifyyoutube.migrator.matching.normalization.NormalizedTrackMetadata;
import com.spotifyyoutube.migrator.matching.normalization.RawTrackMetadata;
import com.spotifyyoutube.migrator.matching.normalization.TokenizedTitle;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationTrackCandidate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ArtistSimilarityScorer Tests (Phase 7D)")
class ArtistSimilarityScorerTest {

    private ArtistSimilarityScorer scorer;

    @BeforeEach
    void setUp() {
        scorer = new DefaultArtistSimilarityScorer();
    }

    private SourceTrackFeatures createSource(String primaryArtist, List<String> normalizedArtists, List<String> featuredArtists) {
        String cleanPrimary = primaryArtist != null ? primaryArtist.trim().toLowerCase(Locale.ROOT) : "";
        NormalizedTrackMetadata metadata = new NormalizedTrackMetadata(
                RawTrackMetadata.of("Song Title", primaryArtist, "Album", 200000L),
                "song title",
                new NormalizedArtistCredit(cleanPrimary, featuredArtists != null ? featuredArtists : List.of()),
                normalizedArtists != null ? normalizedArtists : List.of(cleanPrimary),
                featuredArtists != null ? featuredArtists : List.of(),
                "album",
                Duration.ofMillis(200000),
                new TokenizedTitle("Song Title", "Song Title", false, Set.of(), null, null, Set.of(), List.of(), List.of(), List.of())
        );
        return new SourceTrackFeatures(
                "src-1", "song title", "Song Title", cleanPrimary,
                normalizedArtists != null ? normalizedArtists : List.of(cleanPrimary),
                featuredArtists != null ? featuredArtists : List.of(),
                "album", Duration.ofMillis(200000), 200000L, true, true, !cleanPrimary.isEmpty(),
                Set.of(), true, false, false, false, metadata
        );
    }

    private CandidateTrackFeatures createCandidate(String channelOrArtist, OfficialSignalEvidence officialSignal) {
        String cleanChannel = channelOrArtist != null ? channelOrArtist.trim() : "";
        DestinationTrackCandidate candidate = new DestinationTrackCandidate(
                "cand-1", "Song Title", cleanChannel, "Description", "https://thumb.jpg", "2021-01-01", Duration.ofMillis(200000)
        );
        return new CandidateTrackFeatures(
                "cand-1", "Song Title", "song title", "Song Title",
                cleanChannel, cleanChannel.toLowerCase(Locale.ROOT),
                Duration.ofMillis(200000), 200000L, true, true, "Description", "2021-01-01",
                CandidateVersionEvidence.empty(),
                officialSignal != null ? officialSignal : OfficialSignalEvidence.none(),
                candidate
        );
    }

    @Nested
    @DisplayName("Exact Primary Artist Matches")
    class ExactPrimaryArtistMatches {

        @Test
        @DisplayName("should award 1.0 for exact primary artist match")
        void shouldAwardOneForExactPrimaryMatch() {
            SourceTrackFeatures source = createSource("Daft Punk", List.of("daft punk"), List.of());
            CandidateTrackFeatures candidate = createCandidate("Daft Punk", null);

            Double score = scorer.score(source, candidate);

            assertThat(score).isEqualTo(1.0);
        }

        @Test
        @DisplayName("should award 1.0 for case-insensitive primary artist match")
        void shouldAwardOneForCaseInsensitiveMatch() {
            SourceTrackFeatures source = createSource("daft punk", List.of("daft punk"), List.of());
            CandidateTrackFeatures candidate = createCandidate("DAFT PUNK", null);

            Double score = scorer.score(source, candidate);

            assertThat(score).isEqualTo(1.0);
        }

        @Test
        @DisplayName("should award 1.0 when candidate channel is verified official artist channel (Topic / VEVO)")
        void shouldAwardOneForVerifiedArtistChannel() {
            SourceTrackFeatures source = createSource("Dua Lipa", List.of("dua lipa"), List.of());
            OfficialSignalEvidence vevoEvidence = new OfficialSignalEvidence(
                    false, true, true, false, false, false, false
            );
            CandidateTrackFeatures candidate = createCandidate("DuaLipaVEVO", vevoEvidence);

            Double score = scorer.score(source, candidate);

            assertThat(score).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("Primary and Featured Artist Alignment")
    class PrimaryAndFeaturedAlignment {

        @Test
        @DisplayName("should award 1.0 when both primary and featured artists match")
        void shouldAwardOneWhenPrimaryAndFeaturedMatch() {
            SourceTrackFeatures source = createSource("Dua Lipa", List.of("dua lipa"), List.of("dababy"));
            CandidateTrackFeatures candidate = createCandidate("Dua Lipa feat. DaBaby", null);

            Double score = scorer.score(source, candidate);

            assertThat(score).isEqualTo(1.0);
        }

        @Test
        @DisplayName("should award high score (>=0.90) when primary matches but candidate omits featured artist")
        void shouldAwardHighScoreWhenFeaturedOmittedByCandidate() {
            SourceTrackFeatures source = createSource("Dua Lipa", List.of("dua lipa"), List.of("dababy"));
            CandidateTrackFeatures candidate = createCandidate("Dua Lipa", null);

            Double score = scorer.score(source, candidate);

            assertThat(score).isNotNull();
            assertThat(score).isGreaterThanOrEqualTo(0.90);
        }

        @Test
        @DisplayName("should award high score (>=0.95) when primary matches and candidate has additional featured credit")
        void shouldAwardHighScoreWhenCandidateHasExtraFeatured() {
            SourceTrackFeatures source = createSource("Daft Punk", List.of("daft punk"), List.of());
            CandidateTrackFeatures candidate = createCandidate("Daft Punk feat. Pharrell Williams", null);

            Double score = scorer.score(source, candidate);

            assertThat(score).isNotNull();
            assertThat(score).isGreaterThanOrEqualTo(0.95);
        }

        @Test
        @DisplayName("should award low score (<=0.40) when primary artists differ and only featured artist matches")
        void shouldAwardLowScoreWhenOnlyFeaturedArtistMatches() {
            // Source: Drake feat. Future
            SourceTrackFeatures source = createSource("Drake", List.of("drake"), List.of("future"));
            // Candidate: Lil Uzi Vert feat. Future
            CandidateTrackFeatures candidate = createCandidate("Lil Uzi Vert feat. Future", null);

            Double score = scorer.score(source, candidate);

            assertThat(score).isNotNull();
            assertThat(score).isLessThanOrEqualTo(0.40);
            assertThat(score).isGreaterThan(0.20);
        }

        @Test
        @DisplayName("should award low score (0.35) when candidate channel is ONLY the featured artist")
        void shouldAwardLowScoreWhenChannelIsOnlyFeaturedArtist() {
            // Source: Drake feat. Future
            SourceTrackFeatures source = createSource("Drake", List.of("drake"), List.of("future"));
            // Candidate channel: Future (guest artist channel, primary differs!)
            CandidateTrackFeatures candidate = createCandidate("Future", null);

            Double score = scorer.score(source, candidate);

            assertThat(score).isNotNull();
            assertThat(score).isEqualTo(0.35);
        }
    }

    @Nested
    @DisplayName("Multiple Credited Artists and Reordering")
    class MultiArtistAndReordering {

        @Test
        @DisplayName("should award high score (>=0.85) for inverted artist order ('Calvin Harris & Dua Lipa' vs 'Dua Lipa & Calvin Harris')")
        void shouldSupportInvertedArtistOrder() {
            SourceTrackFeatures source = createSource("Calvin Harris", List.of("calvin harris", "dua lipa"), List.of());
            CandidateTrackFeatures candidate = createCandidate("Dua Lipa feat. Calvin Harris", null);

            Double score = scorer.score(source, candidate);

            assertThat(score).isNotNull();
            assertThat(score).isGreaterThanOrEqualTo(0.85);
        }

        @Test
        @DisplayName("should prevent duplicate credit inflation when single candidate matches multiple source entries")
        void shouldPreventDuplicateCreditInflation() {
            // Source has two artists: Daft Punk and Pharrell Williams
            SourceTrackFeatures source = createSource("Daft Punk", List.of("daft punk", "pharrell williams"), List.of());
            // Candidate lists only Daft Punk
            CandidateTrackFeatures candidate = createCandidate("Daft Punk", null);

            Double score = scorer.score(source, candidate);

            assertThat(score).isNotNull();
            // Single candidate artist only matches 1 of 2 source artists -> score must be capped under 0.65
            assertThat(score).isLessThanOrEqualTo(0.65);
            assertThat(score).isGreaterThanOrEqualTo(0.45);
        }

        @Test
        @DisplayName("should prevent duplicate candidate artist from matching multiple source credits")
        void shouldPreventDuplicateCandidateTokensMatchingMultipleSources() {
            SourceTrackFeatures source = createSource("Daft Punk", List.of("daft punk", "pharrell williams"), List.of());
            CandidateTrackFeatures candidate = createCandidate("Daft Punk feat. Daft Punk", null);

            Double score = scorer.score(source, candidate);

            assertThat(score).isNotNull();
            assertThat(score).isLessThanOrEqualTo(0.65);
            assertThat(score).isGreaterThanOrEqualTo(0.45);
        }
    }

    @Nested
    @DisplayName("Substring Collisions and Distinct Similar Names")
    class SubstringCollisionsAndDistinctNames {

        @Test
        @DisplayName("should not match unrelated artists sharing a word ('Iron Maiden' vs 'Iron & Wine')")
        void shouldNotMatchIronMaidenVsIronAndWine() {
            SourceTrackFeatures source = createSource("Iron Maiden", List.of("iron maiden"), List.of());
            CandidateTrackFeatures candidate = createCandidate("Iron & Wine", null);

            Double score = scorer.score(source, candidate);

            assertThat(score).isNotNull();
            assertThat(score).isLessThan(0.50);
        }

        @Test
        @DisplayName("should award 0.0 for substring collision in single word ('Future' vs 'Afterlife')")
        void shouldAwardZeroForSubstringCollision() {
            SourceTrackFeatures source = createSource("Future", List.of("future"), List.of());
            CandidateTrackFeatures candidate = createCandidate("Afterlife", null);

            Double score = scorer.score(source, candidate);

            assertThat(score).isNotNull();
            assertThat(score).isEqualTo(0.0);
        }

        @Test
        @DisplayName("should award 0.0 for distinct bands with word-prefix collision ('The Who' vs 'Wham!')")
        void shouldAwardZeroForWordPrefixCollision() {
            SourceTrackFeatures source = createSource("The Who", List.of("the who"), List.of());
            CandidateTrackFeatures candidate = createCandidate("Wham!", null);

            Double score = scorer.score(source, candidate);

            assertThat(score).isNotNull();
            assertThat(score).isEqualTo(0.0);
        }

        @Test
        @DisplayName("should not award high score for similar but distinct names ('Michael Jackson' vs 'Michael Bolton')")
        void shouldNotAwardHighScoreForDistinctSimilarNames() {
            SourceTrackFeatures source = createSource("Michael Jackson", List.of("michael jackson"), List.of());
            CandidateTrackFeatures candidate = createCandidate("Michael Bolton", null);

            Double score = scorer.score(source, candidate);

            assertThat(score).isNotNull();
            assertThat(score).isLessThan(0.50);
        }
    }

    @Nested
    @DisplayName("Unicode and Non-Latin Artists")
    class UnicodeAndNonLatinArtists {

        @Test
        @DisplayName("should award 1.0 for accented artist names ('Beyoncé')")
        void shouldSupportAccentedArtistNames() {
            SourceTrackFeatures source = createSource("Beyoncé", List.of("beyoncé"), List.of());
            CandidateTrackFeatures candidate = createCandidate("Beyonce", null);

            Double score = scorer.score(source, candidate);

            assertThat(score).isNotNull();
            assertThat(score).isGreaterThanOrEqualTo(0.90);
        }

        @Test
        @DisplayName("should award 1.0 for multi-accent artist names ('Sigur Rós' vs 'Sigur Ros' and 'Björk' vs 'Bjork')")
        void shouldSupportOtherAccentedArtists() {
            SourceTrackFeatures source1 = createSource("Sigur Rós", List.of("sigur rós"), List.of());
            CandidateTrackFeatures candidate1 = createCandidate("Sigur Ros", null);
            assertThat(scorer.score(source1, candidate1)).isEqualTo(1.0);

            SourceTrackFeatures source2 = createSource("Björk", List.of("björk"), List.of());
            CandidateTrackFeatures candidate2 = createCandidate("Bjork", null);
            assertThat(scorer.score(source2, candidate2)).isEqualTo(1.0);
        }

        @Test
        @DisplayName("should award 1.0 for Cyrillic artist names ('Кино')")
        void shouldSupportCyrillicArtistNames() {
            SourceTrackFeatures source = createSource("Кино", List.of("кино"), List.of());
            CandidateTrackFeatures candidate = createCandidate("Кино", null);

            Double score = scorer.score(source, candidate);

            assertThat(score).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("Missing Metadata and Validation Policy")
    class MissingMetadataAndValidation {

        @Test
        @DisplayName("should return null when source primary artist is blank")
        void shouldReturnNullWhenSourceArtistBlank() {
            SourceTrackFeatures source = createSource("", List.of(), List.of());
            CandidateTrackFeatures candidate = createCandidate("Daft Punk", null);

            Double score = scorer.score(source, candidate);

            assertThat(score).isNull();
        }

        @Test
        @DisplayName("should return null when candidate channel/artist is blank")
        void shouldReturnNullWhenCandidateArtistBlank() {
            SourceTrackFeatures source = createSource("Daft Punk", List.of("daft punk"), List.of());
            CandidateTrackFeatures candidate = createCandidate("", null);

            Double score = scorer.score(source, candidate);

            assertThat(score).isNull();
        }

        @Test
        @DisplayName("should reject null source with NullPointerException")
        void shouldRejectNullSource() {
            CandidateTrackFeatures candidate = createCandidate("Daft Punk", null);
            assertThatThrownBy(() -> scorer.score(null, candidate))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("SourceTrackFeatures must not be null");
        }

        @Test
        @DisplayName("should reject null candidate with NullPointerException")
        void shouldRejectNullCandidate() {
            SourceTrackFeatures source = createSource("Daft Punk", List.of("daft punk"), List.of());
            assertThatThrownBy(() -> scorer.score(source, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("CandidateTrackFeatures must not be null");
        }

        @Test
        @DisplayName("scorer should be strictly deterministic across repeated invocations")
        void scorerShouldBeDeterministic() {
            SourceTrackFeatures source = createSource("Daft Punk", List.of("daft punk"), List.of("pharrell williams"));
            CandidateTrackFeatures candidate = createCandidate("Daft Punk feat. Pharrell Williams", null);

            Double score1 = scorer.score(source, candidate);
            Double score2 = scorer.score(source, candidate);

            assertThat(score1).isEqualTo(score2);
        }
    }
}
