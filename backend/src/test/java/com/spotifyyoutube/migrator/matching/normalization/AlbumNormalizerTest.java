package com.spotifyyoutube.migrator.matching.normalization;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.text.Normalizer;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AlbumNormalizer Unit Tests")
class AlbumNormalizerTest {

    private AlbumNormalizer normalizer;

    @BeforeEach
    void setUp() {
        normalizer = new AlbumNormalizer();
    }

    @Nested
    @DisplayName("Null and Blank Handling")
    class NullAndBlankTests {

        @ParameterizedTest(name = "Input \"{0}\" should normalize to empty string")
        @NullAndEmptySource
        @ValueSource(strings = {" ", "   ", "\t", "\n", "\r\n", "  \t \n  "})
        void shouldReturnEmptyStringForNullOrBlankInputs(String input) {
            assertThat(normalizer.normalize(input)).isEmpty();
        }
    }

    @Nested
    @DisplayName("Basic Casing and Whitespace Normalization")
    class BasicCasingAndWhitespaceTests {

        @Test
        @DisplayName("Should convert uppercase and mixed case strings to lowercase using Locale.ROOT")
        void shouldConvertToLowerCaseRoot() {
            assertThat(normalizer.normalize("AFTER HOURS")).isEqualTo("after hours");
            assertThat(normalizer.normalize("After Hours")).isEqualTo("after hours");
            assertThat(normalizer.normalize("aFtEr HoUrS")).isEqualTo("after hours");
            assertThat(normalizer.normalize("THRILLER")).isEqualTo("thriller");
            assertThat(normalizer.normalize("ISTANBUL")).isEqualTo("istanbul");
        }

        @Test
        @DisplayName("Should trim leading and trailing whitespace")
        void shouldTrimWhitespace() {
            assertThat(normalizer.normalize("  After Hours  ")).isEqualTo("after hours");
            assertThat(normalizer.normalize("\tAfter Hours\n")).isEqualTo("after hours");
        }

        @Test
        @DisplayName("Should collapse internal repeated whitespace to single space")
        void shouldCollapseRepeatedWhitespace() {
            assertThat(normalizer.normalize("After    Hours")).isEqualTo("after hours");
            assertThat(normalizer.normalize("After \t  \n  Hours")).isEqualTo("after hours");
            assertThat(normalizer.normalize("Live  at   Wembley")).isEqualTo("live at wembley");
        }
    }

    @Nested
    @DisplayName("Unicode Normalization and Script Support")
    class UnicodeTests {

        @Test
        @DisplayName("Should compose decomposed Unicode (NFD) to canonical precomposed form (NFC)")
        void shouldNormalizeNfdToNfcEquivalence() {
            // "Café del Mar": decomposed 'e' + combining acute accent (\u0065\u0301)
            String nfd = "Cafe\u0301 del Mar";
            // precomposed 'é' (\u00E9)
            String nfc = "Caf\u00E9 del Mar";

            assertThat(Normalizer.isNormalized(nfd, Normalizer.Form.NFD)).isTrue();
            assertThat(normalizer.normalize(nfd)).isEqualTo("café del mar");
            assertThat(normalizer.normalize(nfc)).isEqualTo("café del mar");
            assertThat(normalizer.normalize(nfd)).isEqualTo(normalizer.normalize(nfc));
        }

        @Test
        @DisplayName("Should preserve European accented characters")
        void shouldPreserveEuropeanAccents() {
            assertThat(normalizer.normalize("Ágætis byrjun")).isEqualTo("ágætis byrjun");
            assertThat(normalizer.normalize("Le Fabuleux Destin d'Amélie Poulain"))
                    .isEqualTo("le fabuleux destin d'amélie poulain");
            assertThat(normalizer.normalize("Motomami +")).isEqualTo("motomami +");
            assertThat(normalizer.normalize("El Mal Querer")).isEqualTo("el mal querer");
        }

        @Test
        @DisplayName("Should preserve non-Latin scripts without corruption")
        void shouldPreserveNonLatinScripts() {
            // Japanese
            assertThat(normalizer.normalize("宇多田ヒカル - 初恋")).isEqualTo("宇多田ヒカル - 初恋");
            assertThat(normalizer.normalize("RADWIMPS - 君の名は。")).isEqualTo("radwimps - 君の名は。");

            // Russian / Cyrillic
            assertThat(normalizer.normalize("Кино - Звезда по имени Солнце"))
                    .isEqualTo("кино - звезда по имени солнце");

            // Korean
            assertThat(normalizer.normalize("방탄소년단 - 러브 유어셀프"))
                    .isEqualTo("방탄소년단 - 러브 유어셀프");
            assertThat(normalizer.normalize("아이유 - 조각집"))
                    .isEqualTo("아이유 - 조각집");

            // Arabic
            assertThat(normalizer.normalize("عمرو دياب - كل حياتي"))
                    .isEqualTo("عمرو دياب - كل حياتي");
        }
    }

    @Nested
    @DisplayName("Punctuation and Typography Standardization")
    class PunctuationTests {

        @Test
        @DisplayName("Should standardize typographic apostrophes and curly single quotes to ASCII single quote")
        void shouldStandardizeApostrophes() {
            assertThat(normalizer.normalize("Sgt. Pepper’s Lonely Hearts Club Band"))
                    .isEqualTo("sgt. pepper's lonely hearts club band");
            assertThat(normalizer.normalize("Sgt. Pepper‘s Lonely Hearts Club Band"))
                    .isEqualTo("sgt. pepper's lonely hearts club band");
            assertThat(normalizer.normalize("Who`s Next")).isEqualTo("who's next");
            assertThat(normalizer.normalize("What´s Going On")).isEqualTo("what's going on");
        }

        @Test
        @DisplayName("Should standardize curly and stylized double quotes to ASCII double quote")
        void shouldStandardizeDoubleQuotes() {
            assertThat(normalizer.normalize("“Heroes”")).isEqualTo("\"heroes\"");
            assertThat(normalizer.normalize("«Discovery»")).isEqualTo("\"discovery\"");
            assertThat(normalizer.normalize("„Bad”")).isEqualTo("\"bad\"");
        }

        @Test
        @DisplayName("Should standardize dash variants to ASCII hyphen-minus")
        void shouldStandardizeDashVariants() {
            // En-dash (\u2013), Em-dash (\u2014), Minus (\u2212)
            assertThat(normalizer.normalize("The Wall \u2013 Pink Floyd")).isEqualTo("the wall - pink floyd");
            assertThat(normalizer.normalize("The Wall \u2014 Pink Floyd")).isEqualTo("the wall - pink floyd");
            assertThat(normalizer.normalize("The Wall \u2212 Pink Floyd")).isEqualTo("the wall - pink floyd");
        }

        @Test
        @DisplayName("Should preserve punctuation inside legitimate album names")
        void shouldPreservePunctuationInTitles() {
            assertThat(normalizer.normalize("Help!")).isEqualTo("help!");
            assertThat(normalizer.normalize("Who's Next?")).isEqualTo("who's next?");
            assertThat(normalizer.normalize("Abbey Road: 50th Anniversary")).isEqualTo("abbey road: 50th anniversary");
            assertThat(normalizer.normalize("Blood, Sweat & Tears")).isEqualTo("blood, sweat & tears");
        }

        @Test
        @DisplayName("Should preserve special symbols, currency signs, and backslashes")
        void shouldPreserveSpecialSymbolsAndBackslashes() {
            assertThat(normalizer.normalize("Ke$ha - Animal")).isEqualTo("ke$ha - animal");
            assertThat(normalizer.normalize("Money ($1,000,000)")).isEqualTo("money ($1,000,000)");
            assertThat(normalizer.normalize("Album \\ Disc 1")).isEqualTo("album \\ disc 1");
            assertThat(normalizer.normalize("Simon & Garfunkel - Bridge Over Troubled Water"))
                    .isEqualTo("simon & garfunkel - bridge over troubled water");
            assertThat(normalizer.normalize("Florence + The Machine - Lungs"))
                    .isEqualTo("florence + the machine - lungs");
            assertThat(normalizer.normalize("AC/DC - Back in Black")).isEqualTo("ac/dc - back in black");
        }
    }

    @Nested
    @DisplayName("Meaningful Album Editions Preservation")
    class MeaningfulAlbumEditionsTests {

        @Test
        @DisplayName("Should preserve Deluxe Edition distinction and distinguish from standard album")
        void shouldPreserveDeluxeEditionDistinction() {
            String standard = normalizer.normalize("After Hours");
            String deluxe = normalizer.normalize("After Hours (Deluxe Edition)");

            assertThat(standard).isEqualTo("after hours");
            assertThat(deluxe).isEqualTo("after hours (deluxe edition)");
            assertThat(deluxe).isNotEqualTo(standard);
        }

        @Test
        @DisplayName("Should preserve Expanded Edition distinction")
        void shouldPreserveExpandedEditionDistinction() {
            String standard = normalizer.normalize("Rumours");
            String expanded = normalizer.normalize("Rumours (Expanded Edition)");

            assertThat(standard).isEqualTo("rumours");
            assertThat(expanded).isEqualTo("rumours (expanded edition)");
            assertThat(expanded).isNotEqualTo(standard);
        }

        @Test
        @DisplayName("Should preserve Anniversary Edition distinction")
        void shouldPreserveAnniversaryEditionDistinction() {
            String standard = normalizer.normalize("Abbey Road");
            String anniversary = normalizer.normalize("Abbey Road (50th Anniversary Edition)");

            assertThat(standard).isEqualTo("abbey road");
            assertThat(anniversary).isEqualTo("abbey road (50th anniversary edition)");
            assertThat(anniversary).isNotEqualTo(standard);
        }

        @Test
        @DisplayName("Should preserve Remastered and Remaster distinctions")
        void shouldPreserveRemasterDistinctions() {
            String standard = normalizer.normalize("The Dark Side of the Moon");
            String remastered = normalizer.normalize("The Dark Side of the Moon (Remastered)");
            String remaster2014 = normalizer.normalize("Led Zeppelin IV (2014 Remaster)");

            assertThat(standard).isEqualTo("the dark side of the moon");
            assertThat(remastered).isEqualTo("the dark side of the moon (remastered)");
            assertThat(remaster2014).isEqualTo("led zeppelin iv (2014 remaster)");
            assertThat(remastered).isNotEqualTo(standard);
        }

        @Test
        @DisplayName("Should preserve Live album distinctions and venue names")
        void shouldPreserveLiveAlbumDistinctions() {
            String studio = normalizer.normalize("Pulse");
            String live = normalizer.normalize("Pulse (Live)");
            String wembley = normalizer.normalize("Live at Wembley");
            String unplugged = normalizer.normalize("MTV Unplugged in New York");

            assertThat(studio).isEqualTo("pulse");
            assertThat(live).isEqualTo("pulse (live)");
            assertThat(wembley).isEqualTo("live at wembley");
            assertThat(unplugged).isEqualTo("mtv unplugged in new york");
            assertThat(live).isNotEqualTo(studio);
        }

        @Test
        @DisplayName("Should preserve Acoustic release distinctions")
        void shouldPreserveAcousticDistinction() {
            String standard = normalizer.normalize("Jagged Little Pill");
            String acoustic = normalizer.normalize("Jagged Little Pill (Acoustic)");

            assertThat(standard).isEqualTo("jagged little pill");
            assertThat(acoustic).isEqualTo("jagged little pill (acoustic)");
            assertThat(acoustic).isNotEqualTo(standard);
        }

        @Test
        @DisplayName("Should preserve Soundtrack and Score release distinctions")
        void shouldPreserveSoundtrackDistinctions() {
            assertThat(normalizer.normalize("Tron: Legacy (Original Motion Picture Soundtrack)"))
                    .isEqualTo("tron: legacy (original motion picture soundtrack)");
            assertThat(normalizer.normalize("The Lion King (Soundtrack)"))
                    .isEqualTo("the lion king (soundtrack)");
        }

        @Test
        @DisplayName("Should preserve Special Edition distinctions")
        void shouldPreserveSpecialEditionDistinction() {
            assertThat(normalizer.normalize("Achtung Baby (Special Edition)"))
                    .isEqualTo("achtung baby (special edition)");
        }

        @Test
        @DisplayName("Should preserve Explicit and Clean distinctions when encoded in metadata")
        void shouldPreserveExplicitAndCleanDistinctions() {
            String standard = normalizer.normalize("Damn.");
            String explicit = normalizer.normalize("Damn. [Explicit]");
            String clean = normalizer.normalize("Damn. [Clean]");

            assertThat(standard).isEqualTo("damn.");
            assertThat(explicit).isEqualTo("damn. [explicit]");
            assertThat(clean).isEqualTo("damn. [clean]");
            assertThat(explicit).isNotEqualTo(clean);
            assertThat(explicit).isNotEqualTo(standard);
        }

        @Test
        @DisplayName("Should not strip parenthetical or bracketed album content indiscriminately")
        void shouldNotStripParentheticalContentIndiscriminately() {
            assertThat(normalizer.normalize("The Black Album (Disc 1)")).isEqualTo("the black album (disc 1)");
            assertThat(normalizer.normalize("1989 (Taylor's Version)")).isEqualTo("1989 (taylor's version)");
            assertThat(normalizer.normalize("Red (Taylor's Version) [Deluxe Edition]"))
                    .isEqualTo("red (taylor's version) [deluxe edition]");
        }

        @Test
        @DisplayName("Should not strip promotional phrases when they appear in album titles")
        void shouldNotStripPromotionalPhrasesInAlbumTitles() {
            assertThat(normalizer.normalize("Official Bootleg")).isEqualTo("official bootleg");
            assertThat(normalizer.normalize("The Official Soundtrack")).isEqualTo("the official soundtrack");
            assertThat(normalizer.normalize("Live at Wembley (Official Audio)"))
                    .isEqualTo("live at wembley (official audio)");
        }
    }

    @Nested
    @DisplayName("Determinism and Immutability Invariants")
    class DeterminismAndImmutabilityTests {

        @Test
        @DisplayName("Normalizing multiple times with same input should produce identical output")
        void shouldBeDeterministic() {
            String input = "  After Hours (Deluxe Edition)  ";
            String firstRun = normalizer.normalize(input);
            String secondRun = normalizer.normalize(input);
            String thirdRun = normalizer.normalize(input);

            assertThat(firstRun).isEqualTo(secondRun);
            assertThat(secondRun).isEqualTo(thirdRun);
            assertThat(firstRun).isEqualTo("after hours (deluxe edition)");
        }

        @Test
        @DisplayName("Normalization should be idempotent")
        void shouldBeIdempotent() {
            String input = "Rumours (Expanded Edition)";
            String normalizedOnce = normalizer.normalize(input);
            String normalizedTwice = normalizer.normalize(normalizedOnce);

            assertThat(normalizedTwice).isEqualTo(normalizedOnce);
        }
    }
}
