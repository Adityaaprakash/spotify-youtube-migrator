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

@DisplayName("TitleSimilarityScorer Tests (Phase 7C)")
class TitleSimilarityScorerTest {

    private TitleSimilarityScorer scorer;

    @BeforeEach
    void setUp() {
        scorer = new DefaultTitleSimilarityScorer();
    }

    private SourceTrackFeatures createSource(String normalizedTitle, String cleanTitle, String primaryArtist) {
        NormalizedTrackMetadata metadata = new NormalizedTrackMetadata(
                RawTrackMetadata.of(cleanTitle, primaryArtist, "Album", 200000L),
                normalizedTitle,
                new NormalizedArtistCredit(primaryArtist.toLowerCase(Locale.ROOT), List.of()),
                List.of(primaryArtist.toLowerCase(Locale.ROOT)),
                List.of(),
                "album",
                Duration.ofMillis(200000),
                new TokenizedTitle(cleanTitle, cleanTitle, false, Set.of(), null, null, Set.of(), List.of(), List.of(), List.of())
        );
        return new SourceTrackFeatures(
                "src-1", normalizedTitle, cleanTitle, primaryArtist.toLowerCase(Locale.ROOT),
                List.of(primaryArtist.toLowerCase(Locale.ROOT)), List.of(),
                "album", Duration.ofMillis(200000), 200000L, true, true, true,
                Set.of(), true, false, false, false, metadata
        );
    }

    private CandidateTrackFeatures createCandidate(String rawTitle, String normalizedTitle, String cleanTitle, String channel) {
        DestinationTrackCandidate candidate = new DestinationTrackCandidate(
                "cand-1", rawTitle, channel, "Description", "https://thumb.jpg", "2021-01-01", Duration.ofMillis(200000)
        );
        return new CandidateTrackFeatures(
                "cand-1", rawTitle, normalizedTitle, cleanTitle, channel, channel.toLowerCase(Locale.ROOT),
                Duration.ofMillis(200000), 200000L, true, true, "Description", "2021-01-01",
                CandidateVersionEvidence.empty(), OfficialSignalEvidence.none(), candidate
        );
    }

    @Nested
    @DisplayName("Exact Matches and Case Insensitivity")
    class ExactMatchesAndCaseInsensitivity {

        @Test
        @DisplayName("should award 1.0 for exact normalized title match")
        void shouldAwardOneForExactNormalizedMatch() {
            SourceTrackFeatures source = createSource("get lucky", "Get Lucky", "Daft Punk");
            CandidateTrackFeatures candidate = createCandidate("Get Lucky", "get lucky", "Get Lucky", "Daft Punk");

            Double score = scorer.score(source, candidate);

            assertThat(score).isEqualTo(1.0);
        }

        @Test
        @DisplayName("should award 1.0 for exact clean title match regardless of case")
        void shouldAwardOneForCleanTitleMatch() {
            SourceTrackFeatures source = createSource("hotel california - live", "Hotel California", "Eagles");
            CandidateTrackFeatures candidate = createCandidate("HOTEL CALIFORNIA", "hotel california", "hotel california", "Eagles");

            Double score = scorer.score(source, candidate);

            assertThat(score).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("Typographical and Minor Differences")
    class TypographicalAndMinorDifferences {

        @Test
        @DisplayName("should award high score (>0.90) for single character typo in long title")
        void shouldAwardHighScoreForMinorTypo() {
            SourceTrackFeatures source = createSource("hotel california", "Hotel California", "Eagles");
            CandidateTrackFeatures candidate = createCandidate("Hotel Californis", "hotel californis", "Hotel Californis", "Eagles");

            Double score = scorer.score(source, candidate);

            assertThat(score).isNotNull();
            assertThat(score).isGreaterThanOrEqualTo(0.90);
        }

        @Test
        @DisplayName("should award high score (>0.90) for minor punctuation variations")
        void shouldAwardHighScoreForPunctuationVariations() {
            SourceTrackFeatures source = createSource("dont stop believin", "Dont Stop Believin", "Journey");
            CandidateTrackFeatures candidate = createCandidate("Don't Stop Believin'", "don't stop believin'", "Don't Stop Believin'", "Journey");

            Double score = scorer.score(source, candidate);

            assertThat(score).isNotNull();
            assertThat(score).isGreaterThanOrEqualTo(0.90);
        }
    }

    @Nested
    @DisplayName("Word Order Reordering and Permutations")
    class WordOrderReordering {

        @Test
        @DisplayName("should award high score (>=0.95) for word order permutation")
        void shouldAwardHighScoreForWordOrderPermutation() {
            SourceTrackFeatures source = createSource("get lucky", "Get Lucky", "Daft Punk");
            CandidateTrackFeatures candidate = createCandidate("Lucky Get", "lucky get", "Lucky Get", "Daft Punk");

            Double score = scorer.score(source, candidate);

            assertThat(score).isNotNull();
            assertThat(score).isGreaterThanOrEqualTo(0.95);
        }
    }

    @Nested
    @DisplayName("Delimiter Segment Alignment (YouTube Artist - Title format)")
    class DelimiterSegmentAlignment {

        @Test
        @DisplayName("should extract and match title segment from 'Artist - Title' candidate (>=0.95)")
        void shouldExtractAndMatchTitleSegment() {
            SourceTrackFeatures source = createSource("get lucky", "Get Lucky", "Daft Punk");
            CandidateTrackFeatures candidate = createCandidate(
                    "Daft Punk - Get Lucky", "daft punk - get lucky", "Daft Punk - Get Lucky", "Daft Punk"
            );

            Double score = scorer.score(source, candidate);

            assertThat(score).isNotNull();
            assertThat(score).isGreaterThanOrEqualTo(0.95);
        }

        @Test
        @DisplayName("should extract title segment with pipe delimiter (>=0.95)")
        void shouldExtractTitleSegmentWithPipeDelimiter() {
            SourceTrackFeatures source = createSource("blinding lights", "Blinding Lights", "The Weeknd");
            CandidateTrackFeatures candidate = createCandidate(
                    "The Weeknd | Blinding Lights", "the weeknd | blinding lights", "The Weeknd | Blinding Lights", "The Weeknd"
            );

            Double score = scorer.score(source, candidate);

            assertThat(score).isNotNull();
            assertThat(score).isGreaterThanOrEqualTo(0.95);
        }

        @Test
        @DisplayName("should match title segment containing bracketed variant annotation (>=0.95)")
        void shouldMatchTitleSegmentWithBracketedVariantAnnotation() {
            SourceTrackFeatures source = createSource("billie jean", "Billie Jean", "Michael Jackson");
            CandidateTrackFeatures candidate = createCandidate(
                    "Michael Jackson - Billie Jean (Acoustic Cover)",
                    "michael jackson - billie jean (acoustic cover)",
                    "Michael Jackson - Billie Jean (Acoustic Cover)",
                    "Acoustic Covers Channel"
            );

            Double score = scorer.score(source, candidate);

            assertThat(score).isNotNull();
            assertThat(score).isGreaterThanOrEqualTo(0.95);
        }

        @Test
        @DisplayName("should match title with bracketed variant annotation without delimiters (>=0.95)")
        void shouldMatchTitleWithBracketedVariantWithoutDelimiters() {
            SourceTrackFeatures source = createSource("billie jean", "Billie Jean", "Michael Jackson");
            CandidateTrackFeatures candidate = createCandidate(
                    "Billie Jean (Acoustic Cover)",
                    "billie jean (acoustic cover)",
                    "Billie Jean (Acoustic Cover)",
                    "Acoustic Covers Channel"
            );

            Double score = scorer.score(source, candidate);

            assertThat(score).isNotNull();
            assertThat(score).isGreaterThanOrEqualTo(0.95);
        }
    }

    @Nested
    @DisplayName("Short Title and Substring Safeguards")
    class ShortTitleSafeguards {

        @Test
        @DisplayName("should award 1.0 for exact short title match ('XO')")
        void shouldAwardOneForExactShortTitle() {
            SourceTrackFeatures source = createSource("xo", "XO", "The Weeknd");
            CandidateTrackFeatures candidate = createCandidate("XO", "xo", "XO", "The Weeknd");

            Double score = scorer.score(source, candidate);

            assertThat(score).isEqualTo(1.0);
        }

        @Test
        @DisplayName("should match short title when isolated in delimiter segment ('The Weeknd - XO')")
        void shouldMatchShortTitleInDelimiterSegment() {
            SourceTrackFeatures source = createSource("xo", "XO", "The Weeknd");
            CandidateTrackFeatures candidate = createCandidate("The Weeknd - XO", "the weeknd - xo", "The Weeknd - XO", "The Weeknd");

            Double score = scorer.score(source, candidate);

            assertThat(score).isNotNull();
            assertThat(score).isGreaterThanOrEqualTo(0.90);
        }

        @Test
        @DisplayName("should NOT award high score to short title embedded in different word ('XO' vs 'XOXO')")
        void shouldNotOverrewardShortSubstringInWord() {
            SourceTrackFeatures source = createSource("xo", "XO", "The Weeknd");
            CandidateTrackFeatures candidate = createCandidate("XOXO", "xoxo", "XOXO", "Jeon Somi");

            Double score = scorer.score(source, candidate);

            assertThat(score).isNotNull();
            assertThat(score).isLessThan(0.60);
        }

        @Test
        @DisplayName("should award near zero score when short title does not appear in candidate ('XO' vs unrelated)")
        void shouldAwardNearZeroWhenShortTitleAbsent() {
            SourceTrackFeatures source = createSource("xo", "XO", "The Weeknd");
            CandidateTrackFeatures candidate = createCandidate("Beautiful Things", "beautiful things", "Beautiful Things", "Benson Boone");

            Double score = scorer.score(source, candidate);

            assertThat(score).isNotNull();
            assertThat(score).isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("Unrelated Titles and Partial Stop-word Collisions")
    class UnrelatedTitlesAndCollisions {

        @Test
        @DisplayName("should award low score (<0.15) for completely unrelated titles")
        void shouldAwardLowScoreForUnrelatedTitles() {
            SourceTrackFeatures source = createSource("bohemian rhapsody", "Bohemian Rhapsody", "Queen");
            CandidateTrackFeatures candidate = createCandidate("Shape of You", "shape of you", "Shape of You", "Ed Sheeran");

            Double score = scorer.score(source, candidate);

            assertThat(score).isNotNull();
            assertThat(score).isLessThan(0.15);
        }

        @Test
        @DisplayName("should not award high score (<0.50) when only common stop words overlap")
        void shouldNotOverrewardCommonStopWords() {
            SourceTrackFeatures source = createSource(
                    "the dark side of the moon", "The Dark Side of the Moon", "Pink Floyd"
            );
            CandidateTrackFeatures candidate = createCandidate(
                    "The Other Side of Midnight", "the other side of midnight", "The Other Side of Midnight", "Urge Overkill"
            );

            Double score = scorer.score(source, candidate);

            assertThat(score).isNotNull();
            assertThat(score).isLessThan(0.50);
        }

        @Test
        @DisplayName("repeated token should not inflate score ('Yeah Yeah Yeah' vs 'Yeah')")
        void repeatedTokenShouldNotInflateScore() {
            SourceTrackFeatures source = createSource("yeah yeah yeah", "Yeah Yeah Yeah", "Artist");
            CandidateTrackFeatures candidate = createCandidate("Yeah", "yeah", "Yeah", "Artist");

            Double score = scorer.score(source, candidate);

            assertThat(score).isNotNull();
            assertThat(score).isLessThan(0.70);
            assertThat(score).isGreaterThan(0.20);
        }
    }

    @Nested
    @DisplayName("Unicode and Non-Latin Scripts")
    class UnicodeAndNonLatinScripts {

        @Test
        @DisplayName("should award 1.0 for identical Cyrillic titles")
        void shouldSupportCyrillicTitles() {
            SourceTrackFeatures source = createSource("группа крови", "Группа крови", "Кино");
            CandidateTrackFeatures candidate = createCandidate("Группа крови", "группа крови", "Группа крови", "Кино");

            Double score = scorer.score(source, candidate);

            assertThat(score).isEqualTo(1.0);
        }

        @Test
        @DisplayName("should award 1.0 for identical Korean titles")
        void shouldSupportKoreanTitles() {
            SourceTrackFeatures source = createSource("강남스타일", "강남스타일", "싸이");
            CandidateTrackFeatures candidate = createCandidate("강남스타일", "강남스타일", "강남스타일", "싸이");

            Double score = scorer.score(source, candidate);

            assertThat(score).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("Missing Metadata and Validation Policy")
    class MissingMetadataAndValidation {

        @Test
        @DisplayName("should return null when source normalized title is blank")
        void shouldReturnNullWhenSourceTitleBlank() {
            SourceTrackFeatures source = createSource("", "", "Artist");
            CandidateTrackFeatures candidate = createCandidate("Title", "title", "Title", "Artist");

            Double score = scorer.score(source, candidate);

            assertThat(score).isNull();
        }

        @Test
        @DisplayName("should return null when candidate normalized title is blank")
        void shouldReturnNullWhenCandidateTitleBlank() {
            SourceTrackFeatures source = createSource("title", "Title", "Artist");
            CandidateTrackFeatures candidate = createCandidate("", "", "", "Artist");

            Double score = scorer.score(source, candidate);

            assertThat(score).isNull();
        }

        @Test
        @DisplayName("should reject null source with NullPointerException")
        void shouldRejectNullSource() {
            CandidateTrackFeatures candidate = createCandidate("Title", "title", "Title", "Artist");
            assertThatThrownBy(() -> scorer.score(null, candidate))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("SourceTrackFeatures must not be null");
        }

        @Test
        @DisplayName("should reject null candidate with NullPointerException")
        void shouldRejectNullCandidate() {
            SourceTrackFeatures source = createSource("title", "Title", "Artist");
            assertThatThrownBy(() -> scorer.score(source, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("CandidateTrackFeatures must not be null");
        }

        @Test
        @DisplayName("scorer should be strictly deterministic across repeated invocations")
        void scorerShouldBeDeterministic() {
            SourceTrackFeatures source = createSource("hotel california", "Hotel California", "Eagles");
            CandidateTrackFeatures candidate = createCandidate("Hotel California", "hotel california", "Hotel California", "Eagles");

            Double score1 = scorer.score(source, candidate);
            Double score2 = scorer.score(source, candidate);

            assertThat(score1).isEqualTo(score2);
        }
    }
}
