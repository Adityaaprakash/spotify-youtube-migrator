package com.spotifyyoutube.migrator.matching.normalization;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class TitleNormalizerTest {

    private TitleNormalizer normalizer;

    @BeforeEach
    void setUp() {
        normalizer = new TitleNormalizer();
    }

    @Nested
    @DisplayName("Basic Casing and Whitespace")
    class BasicCasingAndWhitespaceTests {

        @Test
        @DisplayName("Should normalize standard title to lowercase")
        void shouldNormalizeStandardTitleToLowercase() {
            assertThat(normalizer.normalize("Bohemian Rhapsody")).isEqualTo("bohemian rhapsody");
        }

        @Test
        @DisplayName("Should normalize mixed and upper case")
        void shouldNormalizeMixedAndUpperCase() {
            assertThat(normalizer.normalize("bOhEmiAn RhApSoDy")).isEqualTo("bohemian rhapsody");
            assertThat(normalizer.normalize("HOTEL CALIFORNIA")).isEqualTo("hotel california");
        }

        @Test
        @DisplayName("Should trim leading and trailing whitespace")
        void shouldTrimLeadingAndTrailingWhitespace() {
            assertThat(normalizer.normalize("   Bohemian Rhapsody   ")).isEqualTo("bohemian rhapsody");
        }

        @Test
        @DisplayName("Should collapse consecutive internal whitespace")
        void shouldCollapseConsecutiveInternalWhitespace() {
            assertThat(normalizer.normalize("Bohemian     Rhapsody")).isEqualTo("bohemian rhapsody");
        }

        @Test
        @DisplayName("Should normalize tabs, newlines, and carriage returns to single space")
        void shouldNormalizeTabsAndNewlines() {
            assertThat(normalizer.normalize("Bohemian\t\nRhapsody\r\n")).isEqualTo("bohemian rhapsody");
        }
    }

    @Nested
    @DisplayName("Null and Blank Handling")
    class NullAndBlankTests {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t", "\n", " \t \r\n "})
        @DisplayName("Should return empty string for null, empty, or whitespace-only inputs")
        void shouldReturnEmptyStringForNullOrBlank(String input) {
            assertThat(normalizer.normalize(input)).isEmpty();
        }
    }

    @Nested
    @DisplayName("Punctuation and Separator Normalization")
    class PunctuationTests {

        @Test
        @DisplayName("Should convert curly and smart apostrophes to standard ASCII single quote")
        void shouldNormalizeSmartApostrophes() {
            assertThat(normalizer.normalize("Don’t Stop Believin’")).isEqualTo("don't stop believin'");
            assertThat(normalizer.normalize("Don‘t Stop")).isEqualTo("don't stop");
        }

        @Test
        @DisplayName("Should convert smart double quotes to standard ASCII quote")
        void shouldNormalizeSmartDoubleQuotes() {
            assertThat(normalizer.normalize("“Heroes”")).isEqualTo("\"heroes\"");
            assertThat(normalizer.normalize("„Heroes“")).isEqualTo("\"heroes\"");
        }

        @Test
        @DisplayName("Should convert various dash characters (en-dash, em-dash, minus) to standard hyphen")
        void shouldNormalizeDashes() {
            // en-dash \u2013
            assertThat(normalizer.normalize("Song \u2013 Subtitle")).isEqualTo("song - subtitle");
            // em-dash \u2014
            assertThat(normalizer.normalize("Song \u2014 Subtitle")).isEqualTo("song - subtitle");
            // minus sign \u2212
            assertThat(normalizer.normalize("Song \u2212 Subtitle")).isEqualTo("song - subtitle");
        }
    }

    @Nested
    @DisplayName("Unicode and Non-English Titles")
    class UnicodeTests {

        @Test
        @DisplayName("Should canonically compose decomposed Unicode accents (NFC)")
        void shouldCanonicallyComposeDecomposedAccents() {
            String decomposed = "Cafe\u0301 del Mar"; // 'e' + combining acute accent
            String precomposed = "Café del Mar";

            assertThat(normalizer.normalize(decomposed))
                    .isEqualTo("café del mar")
                    .isEqualTo(normalizer.normalize(precomposed));
        }

        @Test
        @DisplayName("Should preserve non-Latin alphabets without corruption")
        void shouldPreserveNonLatinAlphabets() {
            assertThat(normalizer.normalize("夜に駆ける")).isEqualTo("夜に駆ける");
            assertThat(normalizer.normalize("Группа крови")).isEqualTo("группа крови");
            assertThat(normalizer.normalize("강남스타일")).isEqualTo("강남스타일");
            assertThat(normalizer.normalize("حبيبي")).isEqualTo("حبيبي");
        }

        @Test
        @DisplayName("Should preserve Latin diacritics and accented European characters")
        void shouldPreserveAccentedEuropeanCharacters() {
            assertThat(normalizer.normalize("Über den Wolken")).isEqualTo("über den wolken");
            assertThat(normalizer.normalize("Coup de Grâce")).isEqualTo("coup de grâce");
            assertThat(normalizer.normalize("Sigur Rós")).isEqualTo("sigur rós");
        }
    }

    @Nested
    @DisplayName("Promotional Annotation Removal")
    class PromotionalAnnotationTests {

        @Test
        @DisplayName("Should remove Official Audio in parentheses or square brackets")
        void shouldRemoveOfficialAudioInBrackets() {
            assertThat(normalizer.normalize("Blinding Lights (Official Audio)"))
                    .isEqualTo("blinding lights");
            assertThat(normalizer.normalize("Blinding Lights [Official Audio]"))
                    .isEqualTo("blinding lights");
        }

        @Test
        @DisplayName("Should remove Official Video variations in parentheses or square brackets")
        void shouldRemoveOfficialVideoInBrackets() {
            assertThat(normalizer.normalize("Blinding Lights (Official Video)"))
                    .isEqualTo("blinding lights");
            assertThat(normalizer.normalize("Blinding Lights [Official Video]"))
                    .isEqualTo("blinding lights");
            assertThat(normalizer.normalize("Blinding Lights (Official Music Video)"))
                    .isEqualTo("blinding lights");
            assertThat(normalizer.normalize("Blinding Lights (Official Lyric Video)"))
                    .isEqualTo("blinding lights");
            assertThat(normalizer.normalize("Blinding Lights (Official Visualizer)"))
                    .isEqualTo("blinding lights");
        }

        @Test
        @DisplayName("Should remove trailing Official Video separated by dash or pipe")
        void shouldRemoveTrailingSeparatedPromotionalSuffix() {
            assertThat(normalizer.normalize("Blinding Lights - Official Video"))
                    .isEqualTo("blinding lights");
            assertThat(normalizer.normalize("Blinding Lights | Official Audio"))
                    .isEqualTo("blinding lights");
            assertThat(normalizer.normalize("Blinding Lights - Official Music Video"))
                    .isEqualTo("blinding lights");
        }

        @Test
        @DisplayName("Should remove promotional brackets preceded by separator without leaving dangling delimiter")
        void shouldRemovePromotionalBracketsPrecededBySeparator() {
            assertThat(normalizer.normalize("Song - (Official Video)")).isEqualTo("song");
            assertThat(normalizer.normalize("Song | [Official Audio]")).isEqualTo("song");
            assertThat(normalizer.normalize("Song (Live) - (Official Audio)")).isEqualTo("song (live)");
            assertThat(normalizer.normalize("Song (Live) - Official Audio")).isEqualTo("song (live)");
        }

        @Test
        @DisplayName("Should preserve special characters, currency symbols, and backslashes in titles")
        void shouldPreserveSpecialCharactersAndBackslashes() {
            assertThat(normalizer.normalize("Ke$ha - TiK ToK")).isEqualTo("ke$ha - tik tok");
            assertThat(normalizer.normalize("Track \\ 01")).isEqualTo("track \\ 01");
            assertThat(normalizer.normalize("Money ($1,000,000) (Official Video)")).isEqualTo("money ($1,000,000)");
            assertThat(normalizer.normalize("Song?! (Remix)")).isEqualTo("song?! (remix)");
            assertThat(normalizer.normalize("A$AP Rocky - Praise The Lord (Da Shine)")).isEqualTo("a$ap rocky - praise the lord (da shine)");
        }

        @Test
        @DisplayName("Should not strip promotional words when they are part of a legitimate title")
        void shouldPreservePromotionalWordsInLegitimateTitles() {
            assertThat(normalizer.normalize("Official Secrets")).isEqualTo("official secrets");
            assertThat(normalizer.normalize("Song - Official Secrets")).isEqualTo("song - official secrets");
            assertThat(normalizer.normalize("The Official Song")).isEqualTo("the official song");
            assertThat(normalizer.normalize("Audio Video Disco")).isEqualTo("audio video disco");
        }

        @Test
        @DisplayName("Should not strip into blank when title is only promotional phrase without parentheses")
        void shouldHandleTitleBeingPromotionalPhraseItself() {
            assertThat(normalizer.normalize("Official Video")).isEqualTo("official video");
            assertThat(normalizer.normalize("Official Audio")).isEqualTo("official audio");
            assertThat(normalizer.normalize("(Official Video)")).isEqualTo("(official video)");
        }
    }

    @Nested
    @DisplayName("Meaningful Track Versions Preservation")
    class MeaningfulVersionsTests {

        @Test
        @DisplayName("Should strictly preserve Live versions")
        void shouldPreserveLiveVersions() {
            assertThat(normalizer.normalize("Hotel California (Live)"))
                    .isEqualTo("hotel california (live)");
            assertThat(normalizer.normalize("Comfortably Numb - Live at Pompeii"))
                    .isEqualTo("comfortably numb - live at pompeii");
        }

        @Test
        @DisplayName("Should strictly preserve Acoustic versions")
        void shouldPreserveAcousticVersions() {
            assertThat(normalizer.normalize("Layla (Acoustic)"))
                    .isEqualTo("layla (acoustic)");
        }

        @Test
        @DisplayName("Should strictly preserve Remix versions")
        void shouldPreserveRemixVersions() {
            assertThat(normalizer.normalize("Levitating (The Blessed Madonna Remix)"))
                    .isEqualTo("levitating (the blessed madonna remix)");
            assertThat(normalizer.normalize("Starboy - Kygo Remix"))
                    .isEqualTo("starboy - kygo remix");
        }

        @Test
        @DisplayName("Should strictly preserve Karaoke, Nightcore, Sped Up, and Slowed versions")
        void shouldPreserveSpecializedVersions() {
            assertThat(normalizer.normalize("Bad Guy (Karaoke Version)"))
                    .isEqualTo("bad guy (karaoke version)");
            assertThat(normalizer.normalize("Astronaut in the Ocean (Nightcore)"))
                    .isEqualTo("astronaut in the ocean (nightcore)");
            assertThat(normalizer.normalize("Heat Waves (Sped Up)"))
                    .isEqualTo("heat waves (sped up)");
            assertThat(normalizer.normalize("Sweater Weather (Slowed)"))
                    .isEqualTo("sweater weather (slowed)");
        }
    }

    @Nested
    @DisplayName("Determinism and Immutability")
    class DeterminismAndImmutabilityTests {

        @Test
        @DisplayName("Should be idempotent: normalize(normalize(x)) == normalize(x)")
        void shouldBeIdempotent() {
            String title = "Blinding Lights (Official Audio) [2020]";
            String normalizedOnce = normalizer.normalize(title);
            String normalizedTwice = normalizer.normalize(normalizedOnce);

            assertThat(normalizedTwice).isEqualTo(normalizedOnce);
        }

        @Test
        @DisplayName("Should not mutate source title string")
        void shouldNotMutateSourceTitle() {
            String original = "  Blinding Lights (Official Video)  ";
            String originalCopy = String.copyValueOf(original.toCharArray());

            String normalized = normalizer.normalize(original);

            assertThat(original).isEqualTo(originalCopy);
            assertThat(normalized).isEqualTo("blinding lights");
        }
    }
}
