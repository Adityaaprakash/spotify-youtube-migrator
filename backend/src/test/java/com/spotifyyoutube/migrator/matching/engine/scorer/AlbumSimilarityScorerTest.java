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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AlbumSimilarityScorer Tests (Phase 7E)")
class AlbumSimilarityScorerTest {

    private AlbumSimilarityScorer scorer;

    @BeforeEach
    void setUp() {
        scorer = new DefaultAlbumSimilarityScorer();
    }

    private SourceTrackFeatures createSourceWithAlbum(String rawAlbum) {
        String normalizedAlbum = rawAlbum != null ? rawAlbum.trim().toLowerCase() : "";
        NormalizedTrackMetadata metadata = new NormalizedTrackMetadata(
                RawTrackMetadata.of("Song Title", "Artist", rawAlbum, 200000L),
                "song title",
                new NormalizedArtistCredit("artist", List.of()),
                List.of("artist"),
                List.of(),
                normalizedAlbum,
                Duration.ofMillis(200000),
                new TokenizedTitle("Song Title", "Song Title", false, Set.of(), null, null, Set.of(), List.of(), List.of(), List.of())
        );
        return new SourceTrackFeatures(
                "src-1", "song title", "Song Title", "artist",
                List.of("artist"), List.of(),
                normalizedAlbum, Duration.ofMillis(200000), 200000L,
                !normalizedAlbum.isEmpty(), true, true,
                Set.of(), true, false, false, false, metadata
        );
    }

    private CandidateTrackFeatures createCandidateWithDescription(String description) {
        DestinationTrackCandidate candidate = new DestinationTrackCandidate(
                "cand-1", "Song Title", "Artist", description, "https://thumb.jpg", "2021-01-01", Duration.ofMillis(200000)
        );
        return new CandidateTrackFeatures(
                "cand-1", "Song Title", "song title", "Song Title",
                "Artist", "artist",
                Duration.ofMillis(200000), 200000L, true, description != null, description, "2021-01-01",
                CandidateVersionEvidence.empty(), OfficialSignalEvidence.none(), candidate
        );
    }

    @Nested
    @DisplayName("Exact and Normalized Matches")
    class ExactAndNormalizedMatches {

        @Test
        @DisplayName("should award 1.0 for exact album title match")
        void shouldAwardOneForExactMatch() {
            Double score = scorer.score("Random Access Memories", "Random Access Memories");
            assertThat(score).isEqualTo(1.0);
        }

        @Test
        @DisplayName("should award 1.0 for case-insensitive album match")
        void shouldAwardOneForCaseInsensitiveMatch() {
            Double score = scorer.score("random access memories", "RANDOM ACCESS MEMORIES");
            assertThat(score).isEqualTo(1.0);
        }

        @Test
        @DisplayName("should award 1.0 across quotation and punctuation variants")
        void shouldAwardOneForPunctuationVariants() {
            // Smart quotes vs straight quotes
            Double quoteScore = scorer.score("Sgt. Pepper’s Lonely Hearts Club Band", "Sgt. Pepper's Lonely Hearts Club Band");
            assertThat(quoteScore).isEqualTo(1.0);

            // En-dash vs standard hyphen
            Double dashScore = scorer.score("The Best of 1980–1990", "The Best of 1980-1990");
            assertThat(dashScore).isEqualTo(1.0);
        }

        @Test
        @DisplayName("should award 1.0 for whitespace-collapsed matches")
        void shouldAwardOneForWhitespaceVariants() {
            Double score = scorer.score("The  Dark   Side  of  the  Moon", "The Dark Side of the Moon");
            assertThat(score).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("Unicode and Accent Folding")
    class UnicodeAndAccentFolding {

        @Test
        @DisplayName("should award 1.0 for canonically equivalent accented Latin album names")
        void shouldSupportAccentedAlbumNames() {
            Double score = scorer.score("Ágætis byrjun", "Agaetis byrjun");
            assertThat(score).isEqualTo(1.0);

            Double dejaVu = scorer.score("Déjà Vu", "Deja Vu");
            assertThat(dejaVu).isEqualTo(1.0);

            Double hotel = scorer.score("Hôtel de Ville", "Hotel de Ville");
            assertThat(hotel).isEqualTo(1.0);
        }

        @Test
        @DisplayName("should award 1.0 for Cyrillic album titles")
        void shouldSupportCyrillicAlbumTitles() {
            Double score = scorer.score("Группа крови", "Группа крови");
            assertThat(score).isEqualTo(1.0);
        }

        @Test
        @DisplayName("should award 1.0 for Japanese album titles")
        void shouldSupportJapaneseAlbumTitles() {
            Double score = scorer.score("新宝島", "新宝島");
            assertThat(score).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("Typographical Tolerances and Unrelated Mismatches")
    class TyposAndUnrelatedMismatches {

        @Test
        @DisplayName("should award high score (>=0.88) for minor typographical variance on long album title")
        void shouldSupportMinorTypoOnLongAlbum() {
            // "Parachutes" (10 chars) vs "Parachute" (9 chars) -> 1 char diff -> 0.90
            Double score = scorer.score("Parachutes", "Parachute");
            assertThat(score).isNotNull();
            assertThat(score).isGreaterThanOrEqualTo(0.88);
            assertThat(score).isLessThan(1.0);
        }

        @Test
        @DisplayName("should award 0.0 for clearly unrelated albums")
        void shouldAwardZeroForUnrelatedAlbums() {
            Double score = scorer.score("Thriller", "Bad");
            assertThat(score).isNotNull();
            assertThat(score).isEqualTo(0.0);
        }

        @Test
        @DisplayName("should award 0.0 for substring collisions")
        void shouldAwardZeroForSubstringCollisions() {
            // "War" (3 chars) vs "Star Wars" (9 chars)
            Double score = scorer.score("War", "Star Wars");
            assertThat(score).isNotNull();
            assertThat(score).isEqualTo(0.0);
        }

        @Test
        @DisplayName("should award 0.0 for generic title collisions across different artists")
        void shouldAwardZeroForGenericTitleCollisions() {
            Double score = scorer.score("Queen: Greatest Hits", "Blink-182: Greatest Hits");
            assertThat(score).isNotNull();
            assertThat(score).isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("Candidate Feature Description Extraction")
    class CandidateDescriptionExtraction {

        @Test
        @DisplayName("should extract album from explicit 'Album: <name>' description line")
        void shouldExtractAlbumFromExplicitLabelLine() {
            SourceTrackFeatures source = createSourceWithAlbum("Random Access Memories");
            CandidateTrackFeatures candidate = createCandidateWithDescription(
                    "Provided to YouTube by Columbia\n\nAlbum: Random Access Memories\n\n℗ 2013 Daft Life Limited"
            );

            Double score = scorer.score(source, candidate);

            assertThat(score).isEqualTo(1.0);
        }

        @Test
        @DisplayName("should extract album from standard YouTube topic delivery layout")
        void shouldExtractAlbumFromStandardTopicLayout() {
            SourceTrackFeatures source = createSourceWithAlbum("Random Access Memories");
            CandidateTrackFeatures candidate = createCandidateWithDescription(
                    "Provided to YouTube by Columbia\n\nGet Lucky · Daft Punk\n\nRandom Access Memories\n\n℗ 2013 Daft Life Limited"
            );

            Double score = scorer.score(source, candidate);

            assertThat(score).isEqualTo(1.0);
        }

        @Test
        @DisplayName("should return null when candidate description lacks album evidence")
        void shouldReturnNullWhenDescriptionLacksAlbum() {
            SourceTrackFeatures source = createSourceWithAlbum("Random Access Memories");
            CandidateTrackFeatures candidate = createCandidateWithDescription(
                    "Official music video directed by Daft Punk. Subscribe for more!"
            );

            Double score = scorer.score(source, candidate);

            assertThat(score).isNull();
        }
    }

    @Nested
    @DisplayName("Missing Metadata and Validation Policy")
    class MissingMetadataAndValidation {

        @Test
        @DisplayName("should return null when source album is null or blank")
        void shouldReturnNullWhenSourceAlbumMissing() {
            assertThat(scorer.score((String) null, "Random Access Memories")).isNull();
            assertThat(scorer.score("", "Random Access Memories")).isNull();
            assertThat(scorer.score("   ", "Random Access Memories")).isNull();

            SourceTrackFeatures source = createSourceWithAlbum("");
            CandidateTrackFeatures candidate = createCandidateWithDescription("Album: Random Access Memories");
            assertThat(scorer.score(source, candidate)).isNull();
        }

        @Test
        @DisplayName("should return null when candidate album is null or blank")
        void shouldReturnNullWhenCandidateAlbumMissing() {
            assertThat(scorer.score("Random Access Memories", (String) null)).isNull();
            assertThat(scorer.score("Random Access Memories", "")).isNull();
            assertThat(scorer.score("Random Access Memories", "   ")).isNull();

            SourceTrackFeatures source = createSourceWithAlbum("Random Access Memories");
            CandidateTrackFeatures candidate = createCandidateWithDescription(null);
            assertThat(scorer.score(source, candidate)).isNull();
        }

        @Test
        @DisplayName("should return null when both albums are missing")
        void shouldReturnNullWhenBothAlbumsMissing() {
            assertThat(scorer.score((String) null, (String) null)).isNull();
            assertThat(scorer.score("", "")).isNull();
        }

        @Test
        @DisplayName("should reject null feature objects with NullPointerException")
        void shouldRejectNullFeatureObjects() {
            SourceTrackFeatures source = createSourceWithAlbum("Album");
            CandidateTrackFeatures candidate = createCandidateWithDescription("Album: Album");

            assertThatThrownBy(() -> scorer.score(null, candidate))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("SourceTrackFeatures must not be null");

            assertThatThrownBy(() -> scorer.score(source, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("CandidateTrackFeatures must not be null");
        }

        @Test
        @DisplayName("scorer should be strictly deterministic across repeated invocations")
        void shouldBeDeterministic() {
            Double score1 = scorer.score("Random Access Memories", "Random Access Memories");
            Double score2 = scorer.score("Random Access Memories", "Random Access Memories");

            assertThat(score1).isEqualTo(score2);
        }
    }
}
