package com.spotifyyoutube.migrator.matching.normalization;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ArtistNormalizerTest {

    private ArtistNormalizer normalizer;

    @BeforeEach
    void setUp() {
        normalizer = new ArtistNormalizer();
    }

    @Nested
    @DisplayName("Basic Casing and Whitespace")
    class BasicCasingAndWhitespaceTests {

        @Test
        @DisplayName("Should normalize standard artist names to lowercase")
        void shouldNormalizeStandardArtistNameToLowerCase() {
            assertThat(normalizer.normalize("Queen")).isEqualTo("queen");
            assertThat(normalizer.normalize("Coldplay")).isEqualTo("coldplay");
        }

        @Test
        @DisplayName("Should normalize mixed and upper case")
        void shouldNormalizeMixedAndUpperCase() {
            assertThat(normalizer.normalize("pOSt maLOnE")).isEqualTo("post malone");
            assertThat(normalizer.normalize("THE BEATLES")).isEqualTo("the beatles");
        }

        @Test
        @DisplayName("Should trim leading and trailing whitespace")
        void shouldTrimLeadingAndTrailingWhitespace() {
            assertThat(normalizer.normalize("   Coldplay   ")).isEqualTo("coldplay");
        }

        @Test
        @DisplayName("Should collapse consecutive internal whitespace")
        void shouldCollapseConsecutiveWhitespace() {
            assertThat(normalizer.normalize("The     Beatles")).isEqualTo("the beatles");
        }

        @Test
        @DisplayName("Should normalize tabs, newlines, and carriage returns")
        void shouldNormalizeTabsAndNewlines() {
            assertThat(normalizer.normalize("Daft\t\nPunk\r\n")).isEqualTo("daft punk");
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

        @Test
        @DisplayName("Should handle null or empty list in normalizeArtistList safely")
        void shouldHandleNullOrEmptyListSafely() {
            assertThat(normalizer.normalizeArtistList(null)).isEmpty();
            assertThat(normalizer.normalizeArtistList(List.of())).isEmpty();
            assertThat(normalizer.normalizeArtistList(List.of("  ", "\t"))).isEmpty();
        }

        @Test
        @DisplayName("Should return empty record for null or blank in parseArtistCredit")
        void shouldReturnEmptyRecordForNullOrBlankArtistCredit() {
            NormalizedArtistCredit credit = normalizer.parseArtistCredit(null);
            assertThat(credit.primaryArtist()).isEmpty();
            assertThat(credit.featuredArtists()).isEmpty();

            NormalizedArtistCredit blankCredit = normalizer.parseArtistCredit("   ");
            assertThat(blankCredit.primaryArtist()).isEmpty();
            assertThat(blankCredit.featuredArtists()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Unicode and Non-English Artists")
    class UnicodeTests {

        @Test
        @DisplayName("Should canonically compose decomposed Unicode accents (NFC)")
        void shouldCanonicallyComposeDecomposedAccents() {
            String decomposed = "Bjo\u0308rk"; // 'o' + combining diaeresis
            String precomposed = "Björk";

            assertThat(normalizer.normalize(decomposed))
                    .isEqualTo("björk")
                    .isEqualTo(normalizer.normalize(precomposed));
        }

        @Test
        @DisplayName("Should preserve Latin diacritics and accented European characters")
        void shouldPreserveAccentedCharacters() {
            assertThat(normalizer.normalize("Beyoncé")).isEqualTo("beyoncé");
            assertThat(normalizer.normalize("Sigur Rós")).isEqualTo("sigur rós");
            assertThat(normalizer.normalize("Mötley Crüe")).isEqualTo("mötley crüe");
        }

        @Test
        @DisplayName("Should preserve non-Latin alphabets without corruption")
        void shouldPreserveNonLatinAlphabets() {
            assertThat(normalizer.normalize("Кино")).isEqualTo("кино");
            assertThat(normalizer.normalize("宇多田ヒカル")).isEqualTo("宇多田ヒカル");
            assertThat(normalizer.normalize("방탄소년단")).isEqualTo("방탄소년단");
            assertThat(normalizer.normalize("عمرو دياب")).isEqualTo("عمرو دياب");
        }
    }

    @Nested
    @DisplayName("Featured Artist Recognition")
    class FeaturedArtistTests {

        @Test
        @DisplayName("Should recognize 'feat.' marker and extract guest")
        void shouldRecognizeFeatDotMarker() {
            NormalizedArtistCredit credit = normalizer.parseArtistCredit("Drake feat. Rihanna");
            assertThat(credit.primaryArtist()).isEqualTo("drake");
            assertThat(credit.featuredArtists()).containsExactly("rihanna");
        }

        @Test
        @DisplayName("Should recognize 'ft.' marker and extract guest")
        void shouldRecognizeFtDotMarker() {
            NormalizedArtistCredit credit = normalizer.parseArtistCredit("Calvin Harris ft. Ellie Goulding");
            assertThat(credit.primaryArtist()).isEqualTo("calvin harris");
            assertThat(credit.featuredArtists()).containsExactly("ellie goulding");
        }

        @Test
        @DisplayName("Should recognize 'featuring' marker and extract guest")
        void shouldRecognizeFeaturingMarker() {
            NormalizedArtistCredit credit = normalizer.parseArtistCredit("Mark Ronson featuring Bruno Mars");
            assertThat(credit.primaryArtist()).isEqualTo("mark ronson");
            assertThat(credit.featuredArtists()).containsExactly("bruno mars");
        }

        @Test
        @DisplayName("Should recognize case variations of featured markers")
        void shouldRecognizeCaseVariationsOfFeaturedMarkers() {
            NormalizedArtistCredit credit1 = normalizer.parseArtistCredit("Artist FEAT. Guest");
            assertThat(credit1.primaryArtist()).isEqualTo("artist");
            assertThat(credit1.featuredArtists()).containsExactly("guest");

            NormalizedArtistCredit credit2 = normalizer.parseArtistCredit("Artist Ft. Guest");
            assertThat(credit2.primaryArtist()).isEqualTo("artist");
            assertThat(credit2.featuredArtists()).containsExactly("guest");

            NormalizedArtistCredit credit3 = normalizer.parseArtistCredit("Artist FEATURING Guest");
            assertThat(credit3.primaryArtist()).isEqualTo("artist");
            assertThat(credit3.featuredArtists()).containsExactly("guest");
        }

        @Test
        @DisplayName("Should recognize parenthetical and bracketed featured markers")
        void shouldRecognizeParentheticalAndBracketedFeaturedMarkers() {
            NormalizedArtistCredit credit1 = normalizer.parseArtistCredit("Dua Lipa (feat. DaBaby)");
            assertThat(credit1.primaryArtist()).isEqualTo("dua lipa");
            assertThat(credit1.featuredArtists()).containsExactly("dababy");

            NormalizedArtistCredit credit2 = normalizer.parseArtistCredit("Dua Lipa [ft. DaBaby]");
            assertThat(credit2.primaryArtist()).isEqualTo("dua lipa");
            assertThat(credit2.featuredArtists()).containsExactly("dababy");
        }

        @Test
        @DisplayName("Should parse multiple featured artists separated by comma, ampersand, or and")
        void shouldParseMultipleFeaturedArtists() {
            NormalizedArtistCredit credit = normalizer.parseArtistCredit("Artist feat. Guest 1, Guest 2 & Guest 3");
            assertThat(credit.primaryArtist()).isEqualTo("artist");
            assertThat(credit.featuredArtists()).containsExactly("guest 1", "guest 2", "guest 3");

            NormalizedArtistCredit creditAnd = normalizer.parseArtistCredit("Artist ft. Guest 1 and Guest 2");
            assertThat(creditAnd.primaryArtist()).isEqualTo("artist");
            assertThat(creditAnd.featuredArtists()).containsExactly("guest 1", "guest 2");
        }
    }

    @Nested
    @DisplayName("Token Boundary Correctness & Identity Preservation")
    class TokenBoundaryAndIdentityTests {

        @Test
        @DisplayName("Should not misinterpret legitimate names containing 'ft' or 'feat' as featured markers")
        void shouldNotMisinterpretLegitimateNames() {
            // "Soft Cell" ends in 'ft' before space
            NormalizedArtistCredit credit1 = normalizer.parseArtistCredit("Soft Cell");
            assertThat(credit1.primaryArtist()).isEqualTo("soft cell");
            assertThat(credit1.featuredArtists()).isEmpty();

            // "Kraftwerk" has internal 'ft'
            NormalizedArtistCredit credit2 = normalizer.parseArtistCredit("Kraftwerk");
            assertThat(credit2.primaryArtist()).isEqualTo("kraftwerk");
            assertThat(credit2.featuredArtists()).isEmpty();

            // "Leftfield" has internal 'ft'
            NormalizedArtistCredit credit3 = normalizer.parseArtistCredit("Leftfield");
            assertThat(credit3.primaryArtist()).isEqualTo("leftfield");
            assertThat(credit3.featuredArtists()).isEmpty();

            // "Afterlife" has internal 'ft'
            NormalizedArtistCredit credit4 = normalizer.parseArtistCredit("Afterlife");
            assertThat(credit4.primaryArtist()).isEqualTo("afterlife");
            assertThat(credit4.featuredArtists()).isEmpty();

            // "Future" contains 'ft' letters
            NormalizedArtistCredit credit5 = normalizer.parseArtistCredit("Future");
            assertThat(credit5.primaryArtist()).isEqualTo("future");
            assertThat(credit5.featuredArtists()).isEmpty();

            // "Def Leppard" contains 'feat'-like consonants
            NormalizedArtistCredit credit6 = normalizer.parseArtistCredit("Def Leppard");
            assertThat(credit6.primaryArtist()).isEqualTo("def leppard");
            assertThat(credit6.featuredArtists()).isEmpty();

            // "Aerosmith"
            NormalizedArtistCredit credit7 = normalizer.parseArtistCredit("Aerosmith");
            assertThat(credit7.primaryArtist()).isEqualTo("aerosmith");
            assertThat(credit7.featuredArtists()).isEmpty();

            // "The Feat" (no following guest)
            NormalizedArtistCredit credit8 = normalizer.parseArtistCredit("The Feat");
            assertThat(credit8.primaryArtist()).isEqualTo("the feat");
            assertThat(credit8.featuredArtists()).isEmpty();
        }

        @Test
        @DisplayName("Should preserve bands with ampersands, commas, and plus signs without naive splitting")
        void shouldPreserveBandsWithSymbols() {
            NormalizedArtistCredit credit1 = normalizer.parseArtistCredit("Simon & Garfunkel");
            assertThat(credit1.primaryArtist()).isEqualTo("simon & garfunkel");
            assertThat(credit1.featuredArtists()).isEmpty();

            NormalizedArtistCredit credit2 = normalizer.parseArtistCredit("Earth, Wind & Fire");
            assertThat(credit2.primaryArtist()).isEqualTo("earth, wind & fire");
            assertThat(credit2.featuredArtists()).isEmpty();

            NormalizedArtistCredit credit3 = normalizer.parseArtistCredit("AC/DC");
            assertThat(credit3.primaryArtist()).isEqualTo("ac/dc");
            assertThat(credit3.featuredArtists()).isEmpty();

            NormalizedArtistCredit credit4 = normalizer.parseArtistCredit("Florence + the Machine");
            assertThat(credit4.primaryArtist()).isEqualTo("florence + the machine");
            assertThat(credit4.featuredArtists()).isEmpty();

            NormalizedArtistCredit credit5 = normalizer.parseArtistCredit("Crosby, Stills, Nash & Young");
            assertThat(credit5.primaryArtist()).isEqualTo("crosby, stills, nash & young");
            assertThat(credit5.featuredArtists()).isEmpty();
        }

        @Test
        @DisplayName("Should preserve primary band with ampersand when featured artist is present")
        void shouldPreservePrimaryBandWithAmpersandWhenFeaturedArtistPresent() {
            NormalizedArtistCredit credit = normalizer.parseArtistCredit("Simon & Garfunkel feat. Guest");
            assertThat(credit.primaryArtist()).isEqualTo("simon & garfunkel");
            assertThat(credit.featuredArtists()).containsExactly("guest");
        }

        @Test
        @DisplayName("Should remove separator preceding featured credit without corrupting primary artist")
        void shouldRemoveSeparatorPrecedingFeaturedCredit() {
            NormalizedArtistCredit creditDash = normalizer.parseArtistCredit("Artist - feat. Guest");
            assertThat(creditDash.primaryArtist()).isEqualTo("artist");
            assertThat(creditDash.featuredArtists()).containsExactly("guest");

            NormalizedArtistCredit creditParenDash = normalizer.parseArtistCredit("Artist - (feat. Guest)");
            assertThat(creditParenDash.primaryArtist()).isEqualTo("artist");
            assertThat(creditParenDash.featuredArtists()).containsExactly("guest");
        }

        @Test
        @DisplayName("Should handle unbalanced or malformed annotations safely")
        void shouldHandleMalformedAnnotationsSafely() {
            NormalizedArtistCredit credit1 = normalizer.parseArtistCredit("Artist (feat. Guest");
            assertThat(credit1.primaryArtist()).isEqualTo("artist (feat. guest");
            assertThat(credit1.featuredArtists()).isEmpty();

            NormalizedArtistCredit credit2 = normalizer.parseArtistCredit("Artist feat. Guest)");
            assertThat(credit2.primaryArtist()).isEqualTo("artist");
            assertThat(credit2.featuredArtists()).containsExactly("guest)");
        }

        @Test
        @DisplayName("Should preserve special characters in artist names")
        void shouldPreserveSpecialCharactersInArtistNames() {
            assertThat(normalizer.normalize("Ke$ha")).isEqualTo("ke$ha");
            assertThat(normalizer.normalize("A$AP Rocky")).isEqualTo("a$ap rocky");
            assertThat(normalizer.normalize("Guns N' Roses")).isEqualTo("guns n' roses");
            assertThat(normalizer.normalize("Panic! At The Disco")).isEqualTo("panic! at the disco");
        }
    }

    @Nested
    @DisplayName("Artist List Normalization and Order")
    class ArtistListNormalizationTests {

        @Test
        @DisplayName("Should normalize list of artists preserving original order and removing blanks")
        void shouldNormalizeArtistListPreservingOrder() {
            List<String> rawList = List.of("  Queen  ", "David Bowie", "  ", "Mick Jagger");
            List<String> normalizedList = normalizer.normalizeArtistList(rawList);

            assertThat(normalizedList).containsExactly("queen", "david bowie", "mick jagger");
        }
    }

    @Nested
    @DisplayName("Determinism and Immutability")
    class DeterminismAndImmutabilityTests {

        @Test
        @DisplayName("Should be idempotent: normalize(normalize(x)) == normalize(x)")
        void shouldBeIdempotent() {
            String name = "  Beyoncé (feat. Jay-Z)  ";
            String normalizedOnce = normalizer.normalize(name);
            String normalizedTwice = normalizer.normalize(normalizedOnce);

            assertThat(normalizedTwice).isEqualTo(normalizedOnce);
        }

        @Test
        @DisplayName("Should not mutate source strings")
        void shouldNotMutateSourceStrings() {
            String original = "  Simon & Garfunkel  ";
            String originalCopy = String.copyValueOf(original.toCharArray());

            String normalized = normalizer.normalize(original);

            assertThat(original).isEqualTo(originalCopy);
            assertThat(normalized).isEqualTo("simon & garfunkel");
        }
    }
}
