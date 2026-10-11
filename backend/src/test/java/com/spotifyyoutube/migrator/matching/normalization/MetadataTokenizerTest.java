package com.spotifyyoutube.migrator.matching.normalization;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MetadataTokenizer Tests")
class MetadataTokenizerTest {

    private MetadataTokenizer tokenizer;

    @BeforeEach
    void setUp() {
        tokenizer = new MetadataTokenizer();
    }

    @Nested
    @DisplayName("A. Official-Content Detection")
    class OfficialContentDetectionTests {

        @Test
        @DisplayName("Should detect Official Audio and mark isOfficial true with AUDIO format")
        void shouldDetectOfficialAudio() {
            TokenizedTitle result = tokenizer.tokenize("The Weeknd - Blinding Lights (Official Audio)");

            assertThat(result.isOfficial()).isTrue();
            assertThat(result.mediaFormats()).containsExactly(MediaFormat.AUDIO);
            assertThat(result.cleanTitle()).isEqualTo("The Weeknd - Blinding Lights");
            assertThat(result.tokens()).anyMatch(t -> t.category() == TokenCategory.OFFICIAL_CONTENT
                    && t.value().equals("official"));
            assertThat(result.tokens()).anyMatch(t -> t.category() == TokenCategory.MEDIA_FORMAT
                    && t.value().equals("audio"));
        }

        @Test
        @DisplayName("Should detect Official Video and mark isOfficial true with VIDEO format")
        void shouldDetectOfficialVideo() {
            TokenizedTitle result = tokenizer.tokenize("Dua Lipa - Levitating [Official Video]");

            assertThat(result.isOfficial()).isTrue();
            assertThat(result.mediaFormats()).containsExactly(MediaFormat.VIDEO);
            assertThat(result.cleanTitle()).isEqualTo("Dua Lipa - Levitating");
        }

        @Test
        @DisplayName("Should detect Official Music Video")
        void shouldDetectOfficialMusicVideo() {
            TokenizedTitle result = tokenizer.tokenize("Adele - Hello (Official Music Video)");

            assertThat(result.isOfficial()).isTrue();
            assertThat(result.mediaFormats()).containsExactly(MediaFormat.MUSIC_VIDEO);
            assertThat(result.cleanTitle()).isEqualTo("Adele - Hello");
        }

        @Test
        @DisplayName("Should detect Lyric Video without claiming it is official unless specified")
        void shouldDetectLyricVideo() {
            TokenizedTitle result = tokenizer.tokenize("Coldplay - Yellow (Lyric Video)");

            assertThat(result.isOfficial()).isFalse();
            assertThat(result.mediaFormats()).containsExactly(MediaFormat.LYRIC_VIDEO);
            assertThat(result.cleanTitle()).isEqualTo("Coldplay - Yellow");
        }

        @Test
        @DisplayName("Should detect Official Lyric Video")
        void shouldDetectOfficialLyricVideo() {
            TokenizedTitle result = tokenizer.tokenize("Coldplay - Yellow (Official Lyric Video)");

            assertThat(result.isOfficial()).isTrue();
            assertThat(result.mediaFormats()).containsExactly(MediaFormat.LYRIC_VIDEO);
            assertThat(result.cleanTitle()).isEqualTo("Coldplay - Yellow");
        }

        @Test
        @DisplayName("Should handle case-insensitive variations and extra whitespace")
        void shouldHandleCaseAndWhitespaceVariations() {
            TokenizedTitle result = tokenizer.tokenize("Song   [   oFfIcIaL    vIdEo   ]  ");

            assertThat(result.isOfficial()).isTrue();
            assertThat(result.mediaFormats()).containsExactly(MediaFormat.VIDEO);
            assertThat(result.cleanTitle()).isEqualTo("Song");
        }

        @Test
        @DisplayName("Should preserve ordinary title containing the word official without brackets")
        void shouldPreserveOfficialInOrdinaryTitle() {
            TokenizedTitle result1 = tokenizer.tokenize("Official Secrets");
            assertThat(result1.isOfficial()).isFalse();
            assertThat(result1.mediaFormats()).isEmpty();
            assertThat(result1.cleanTitle()).isEqualTo("Official Secrets");

            TokenizedTitle result2 = tokenizer.tokenize("The Official Song");
            assertThat(result2.isOfficial()).isFalse();
            assertThat(result2.mediaFormats()).isEmpty();
            assertThat(result2.cleanTitle()).isEqualTo("The Official Song");
        }

        @Test
        @DisplayName("Should detect official marker alongside a meaningful recording variant")
        void shouldDetectOfficialMarkerAlongsideVariant() {
            TokenizedTitle result = tokenizer.tokenize("Queen - Radio Ga Ga (Official Video) (Live at Wembley)");

            assertThat(result.isOfficial()).isTrue();
            assertThat(result.mediaFormats()).containsExactly(MediaFormat.VIDEO);
            assertThat(result.isLive()).isTrue();
            assertThat(result.liveDetails()).isNotNull();
            assertThat(result.liveDetails().venueOrEvent()).isEqualTo("Wembley");
            assertThat(result.cleanTitle()).isEqualTo("Queen - Radio Ga Ga");
        }

        @Test
        @DisplayName("Should detect Visualizer and Official Visualizer")
        void shouldDetectVisualizer() {
            TokenizedTitle visualizer = tokenizer.tokenize("Artist - Song (Visualizer)");
            assertThat(visualizer.isOfficial()).isFalse();
            assertThat(visualizer.mediaFormats()).containsExactly(MediaFormat.VISUALIZER);

            TokenizedTitle officialVisualizer = tokenizer.tokenize("Artist - Song [Official Visualizer]");
            assertThat(officialVisualizer.isOfficial()).isTrue();
            assertThat(officialVisualizer.mediaFormats()).containsExactly(MediaFormat.VISUALIZER);
        }
    }

    @Nested
    @DisplayName("B. Remaster Detection")
    class RemasterDetectionTests {

        @Test
        @DisplayName("Should detect plain Remastered without year")
        void shouldDetectPlainRemastered() {
            TokenizedTitle result = tokenizer.tokenize("The Beatles - Yesterday (Remastered)");

            assertThat(result.isRemaster()).isTrue();
            assertThat(result.hasVariant(VariantType.REMASTER)).isTrue();
            assertThat(result.remasterDetails()).isNotNull();
            assertThat(result.remasterDetails().year()).isNull();
            assertThat(result.cleanTitle()).isEqualTo("The Beatles - Yesterday");
        }

        @Test
        @DisplayName("Should detect trailing year in Remastered 2014")
        void shouldDetectTrailingYear() {
            TokenizedTitle result = tokenizer.tokenize("Daft Punk - One More Time (Remastered 2014)");

            assertThat(result.isRemaster()).isTrue();
            assertThat(result.remasterDetails()).isNotNull();
            assertThat(result.remasterDetails().year()).isEqualTo(2014);
            assertThat(result.cleanTitle()).isEqualTo("Daft Punk - One More Time");
        }

        @Test
        @DisplayName("Should detect leading year in 2011 Remaster")
        void shouldDetectLeadingYear() {
            TokenizedTitle result = tokenizer.tokenize("Pink Floyd - Time (2011 Remaster)");

            assertThat(result.isRemaster()).isTrue();
            assertThat(result.remasterDetails()).isNotNull();
            assertThat(result.remasterDetails().year()).isEqualTo(2011);
            assertThat(result.cleanTitle()).isEqualTo("Pink Floyd - Time");
        }

        @Test
        @DisplayName("Should detect 2024 Remastered Version")
        void shouldDetectRemasteredVersionWithYear() {
            TokenizedTitle result = tokenizer.tokenize("Led Zeppelin - Kashmir [2024 Remastered Version]");

            assertThat(result.isRemaster()).isTrue();
            assertThat(result.remasterDetails()).isNotNull();
            assertThat(result.remasterDetails().year()).isEqualTo(2024);
            assertThat(result.cleanTitle()).isEqualTo("Led Zeppelin - Kashmir");
        }

        @Test
        @DisplayName("Should detect Digital Remaster and Anniversary Remaster")
        void shouldDetectDigitalAndAnniversaryRemaster() {
            TokenizedTitle digital = tokenizer.tokenize("Queen - Bohemian Rhapsody (Digital Remaster)");
            assertThat(digital.isRemaster()).isTrue();

            TokenizedTitle anniversary = tokenizer.tokenize("Nirvana - In Bloom (Anniversary Remaster)");
            assertThat(anniversary.isRemaster()).isTrue();
        }

        @Test
        @DisplayName("Should not extract unrelated numeric title text into remaster year")
        void shouldNotExtractUnrelatedNumericTitleText() {
            TokenizedTitle result = tokenizer.tokenize("Prince - 1999 (Remastered)");

            assertThat(result.isRemaster()).isTrue();
            assertThat(result.remasterDetails()).isNotNull();
            assertThat(result.remasterDetails().year()).isNull();
            assertThat(result.cleanTitle()).isEqualTo("Prince - 1999");
        }
    }

    @Nested
    @DisplayName("C. Edit and Mix Detection")
    class EditAndMixDetectionTests {

        @Test
        @DisplayName("Should detect Radio Edit")
        void shouldDetectRadioEdit() {
            TokenizedTitle result = tokenizer.tokenize("Stardust - Music Sounds Better With You (Radio Edit)");

            assertThat(result.hasVariant(VariantType.RADIO_EDIT)).isTrue();
            assertThat(result.cleanTitle()).isEqualTo("Stardust - Music Sounds Better With You");
        }

        @Test
        @DisplayName("Should detect Extended Mix")
        void shouldDetectExtendedMix() {
            TokenizedTitle result = tokenizer.tokenize("Eric Prydz - Opus (Extended Mix)");

            assertThat(result.hasVariant(VariantType.EXTENDED_MIX)).isTrue();
            assertThat(result.cleanTitle()).isEqualTo("Eric Prydz - Opus");
        }

        @Test
        @DisplayName("Should detect Original Mix")
        void shouldDetectOriginalMix() {
            TokenizedTitle result = tokenizer.tokenize("Deadmau5 - Strobe (Original Mix)");

            assertThat(result.hasVariant(VariantType.ORIGINAL_MIX)).isTrue();
            assertThat(result.cleanTitle()).isEqualTo("Deadmau5 - Strobe");
        }

        @Test
        @DisplayName("Should detect Club Mix")
        void shouldDetectClubMix() {
            TokenizedTitle result = tokenizer.tokenize("Armin van Buuren - Shivers (Club Mix)");

            assertThat(result.hasVariant(VariantType.CLUB_MIX)).isTrue();
            assertThat(result.cleanTitle()).isEqualTo("Armin van Buuren - Shivers");
        }

        @Test
        @DisplayName("Should detect Instrumental")
        void shouldDetectInstrumental() {
            TokenizedTitle result = tokenizer.tokenize("Dua Lipa - Levitating (Instrumental Version)");

            assertThat(result.isInstrumental()).isTrue();
            assertThat(result.hasVariant(VariantType.INSTRUMENTAL)).isTrue();
            assertThat(result.cleanTitle()).isEqualTo("Dua Lipa - Levitating");
        }

        @Test
        @DisplayName("Should detect Demo Version")
        void shouldDetectDemo() {
            TokenizedTitle result = tokenizer.tokenize("The Beatles - Strawberry Fields Forever (Demo Version)");

            assertThat(result.hasVariant(VariantType.DEMO)).isTrue();
            assertThat(result.cleanTitle()).isEqualTo("The Beatles - Strawberry Fields Forever");
        }

        @Test
        @DisplayName("Should keep distinct mix categories strictly distinct")
        void shouldKeepDistinctMixCategoriesDistinct() {
            TokenizedTitle radio = tokenizer.tokenize("Track (Radio Edit)");
            TokenizedTitle extended = tokenizer.tokenize("Track (Extended Mix)");
            TokenizedTitle club = tokenizer.tokenize("Track (Club Mix)");
            TokenizedTitle original = tokenizer.tokenize("Track (Original Mix)");

            assertThat(radio.variants()).containsExactly(VariantType.RADIO_EDIT);
            assertThat(extended.variants()).containsExactly(VariantType.EXTENDED_MIX);
            assertThat(club.variants()).containsExactly(VariantType.CLUB_MIX);
            assertThat(original.variants()).containsExactly(VariantType.ORIGINAL_MIX);
        }
    }

    @Nested
    @DisplayName("D. Recording and Performance Detection")
    class PerformanceDetectionTests {

        @Test
        @DisplayName("Should detect plain Live and Live Version")
        void shouldDetectPlainLive() {
            TokenizedTitle result1 = tokenizer.tokenize("Nirvana - Smells Like Teen Spirit (Live)");
            assertThat(result1.isLive()).isTrue();
            assertThat(result1.liveDetails()).isNotNull();
            assertThat(result1.liveDetails().venueOrEvent()).isNull();
            assertThat(result1.cleanTitle()).isEqualTo("Nirvana - Smells Like Teen Spirit");

            TokenizedTitle result2 = tokenizer.tokenize("Nirvana - Smells Like Teen Spirit (Live Version)");
            assertThat(result2.isLive()).isTrue();
        }

        @Test
        @DisplayName("Should detect Live with venue e.g. Live at Wembley")
        void shouldDetectLiveWithVenue() {
            TokenizedTitle result = tokenizer.tokenize("Queen - Under Pressure (Live at Wembley)");

            assertThat(result.isLive()).isTrue();
            assertThat(result.liveDetails()).isNotNull();
            assertThat(result.liveDetails().venueOrEvent()).isEqualTo("Wembley");
            assertThat(result.cleanTitle()).isEqualTo("Queen - Under Pressure");
        }

        @Test
        @DisplayName("Should detect Acoustic and Acoustic Version")
        void shouldDetectAcoustic() {
            TokenizedTitle result = tokenizer.tokenize("Foo Fighters - Everlong (Acoustic Version)");

            assertThat(result.isAcoustic()).isTrue();
            assertThat(result.hasVariant(VariantType.ACOUSTIC)).isTrue();
            assertThat(result.cleanTitle()).isEqualTo("Foo Fighters - Everlong");
        }

        @Test
        @DisplayName("Should detect Unplugged and MTV Unplugged")
        void shouldDetectUnplugged() {
            TokenizedTitle result1 = tokenizer.tokenize("Eric Clapton - Layla (Unplugged)");
            assertThat(result1.hasVariant(VariantType.UNPLUGGED)).isTrue();

            TokenizedTitle result2 = tokenizer.tokenize("Nirvana - About a Girl (MTV Unplugged)");
            assertThat(result2.hasVariant(VariantType.UNPLUGGED)).isTrue();
        }

        @Test
        @DisplayName("Should detect Studio Version")
        void shouldDetectStudioVersion() {
            TokenizedTitle result = tokenizer.tokenize("Artist - Song (Studio Version)");

            assertThat(result.hasVariant(VariantType.STUDIO)).isTrue();
            assertThat(result.cleanTitle()).isEqualTo("Artist - Song");
        }

        @Test
        @DisplayName("Should not classify ordinary titles starting with Live as live recordings")
        void shouldNotMisclassifyLiveInOrdinaryTitles() {
            TokenizedTitle liveAndLetDie = tokenizer.tokenize("Live and Let Die");
            assertThat(liveAndLetDie.isLive()).isFalse();
            assertThat(liveAndLetDie.cleanTitle()).isEqualTo("Live and Let Die");

            TokenizedTitle longLiveRock = tokenizer.tokenize("Long Live Rock");
            assertThat(longLiveRock.isLive()).isFalse();
            assertThat(longLiveRock.cleanTitle()).isEqualTo("Long Live Rock");

            TokenizedTitle stayinAlive = tokenizer.tokenize("Stayin' Alive");
            assertThat(stayinAlive.isLive()).isFalse();
            assertThat(stayinAlive.cleanTitle()).isEqualTo("Stayin' Alive");
        }
    }

    @Nested
    @DisplayName("E. Alternate-Version Detection")
    class AlternateVersionDetectionTests {

        @Test
        @DisplayName("Should detect generic and named Remixes")
        void shouldDetectRemix() {
            TokenizedTitle result1 = tokenizer.tokenize("Dua Lipa - Levitating (Remix)");
            assertThat(result1.isRemix()).isTrue();
            assertThat(result1.cleanTitle()).isEqualTo("Dua Lipa - Levitating");

            TokenizedTitle result2 = tokenizer.tokenize("Dua Lipa - Levitating (The Blessed Madonna Remix)");
            assertThat(result2.isRemix()).isTrue();
            assertThat(result2.cleanTitle()).isEqualTo("Dua Lipa - Levitating");
        }

        @Test
        @DisplayName("Should detect Cover")
        void shouldDetectCover() {
            TokenizedTitle result = tokenizer.tokenize("Johnny Cash - Hurt (Cover)");

            assertThat(result.isCover()).isTrue();
            assertThat(result.hasVariant(VariantType.COVER)).isTrue();
            assertThat(result.cleanTitle()).isEqualTo("Johnny Cash - Hurt");
        }

        @Test
        @DisplayName("Should detect Karaoke")
        void shouldDetectKaraoke() {
            TokenizedTitle result = tokenizer.tokenize("Abba - Dancing Queen (Karaoke Version)");

            assertThat(result.isKaraoke()).isTrue();
            assertThat(result.hasVariant(VariantType.KARAOKE)).isTrue();
            assertThat(result.cleanTitle()).isEqualTo("Abba - Dancing Queen");
        }

        @Test
        @DisplayName("Should detect Nightcore")
        void shouldDetectNightcore() {
            TokenizedTitle result = tokenizer.tokenize("Cascada - Everytime We Touch (Nightcore)");

            assertThat(result.hasVariant(VariantType.NIGHTCORE)).isTrue();
            assertThat(result.cleanTitle()).isEqualTo("Cascada - Everytime We Touch");
        }

        @Test
        @DisplayName("Should detect Sped Up and variations")
        void shouldDetectSpedUp() {
            TokenizedTitle result1 = tokenizer.tokenize("Steve Lacy - Bad Habit (Sped Up)");
            assertThat(result1.hasVariant(VariantType.SPED_UP)).isTrue();

            TokenizedTitle result2 = tokenizer.tokenize("Steve Lacy - Bad Habit (Speed Up Version)");
            assertThat(result2.hasVariant(VariantType.SPED_UP)).isTrue();
        }

        @Test
        @DisplayName("Should detect Slowed and Slowed + Reverb")
        void shouldDetectSlowedAndSlowedReverb() {
            TokenizedTitle slowed = tokenizer.tokenize("Joji - Glimpse of Us (Slowed)");
            assertThat(slowed.hasVariant(VariantType.SLOWED)).isTrue();
            assertThat(slowed.hasVariant(VariantType.SLOWED_REVERB)).isFalse();

            TokenizedTitle slowedReverb = tokenizer.tokenize("Joji - Glimpse of Us (Slowed + Reverb)");
            assertThat(slowedReverb.hasVariant(VariantType.SLOWED_REVERB)).isTrue();
            assertThat(slowedReverb.hasVariant(VariantType.SLOWED)).isFalse();
        }

        @Test
        @DisplayName("Should detect plain Reverb")
        void shouldDetectReverb() {
            TokenizedTitle result = tokenizer.tokenize("Artist - Song (Reverb Version)");

            assertThat(result.hasVariant(VariantType.REVERB)).isTrue();
            assertThat(result.cleanTitle()).isEqualTo("Artist - Song");
        }

        @Test
        @DisplayName("Should not classify ordinary song titled 'The Remix' as remix variant")
        void shouldNotMisclassifyTheRemix() {
            TokenizedTitle result = tokenizer.tokenize("The Remix");

            assertThat(result.isRemix()).isFalse();
            assertThat(result.cleanTitle()).isEqualTo("The Remix");
        }
    }

    @Nested
    @DisplayName("F. Preservation and Ambiguity")
    class PreservationAndAmbiguityTests {

        @Test
        @DisplayName("Should preserve unknown bracketed annotations in unknownAnnotations list")
        void shouldPreserveUnknownBracketedAnnotations() {
            TokenizedTitle result = tokenizer.tokenize("Kenny Loggins - Danger Zone [From The Motion Picture Top Gun]");

            assertThat(result.unknownAnnotations()).containsExactly("From The Motion Picture Top Gun");
            assertThat(result.variants()).isEmpty();
            assertThat(result.cleanTitle()).isEqualTo("Kenny Loggins - Danger Zone [From The Motion Picture Top Gun]");
        }

        @Test
        @DisplayName("Should handle multiple annotations in one title")
        void shouldHandleMultipleAnnotations() {
            TokenizedTitle result = tokenizer.tokenize(
                    "Coldplay - Fix You (Live at Wembley) [Official Video] (Remastered 2021)"
            );

            assertThat(result.isLive()).isTrue();
            assertThat(result.liveDetails().venueOrEvent()).isEqualTo("Wembley");
            assertThat(result.isOfficial()).isTrue();
            assertThat(result.mediaFormats()).containsExactly(MediaFormat.VIDEO);
            assertThat(result.isRemaster()).isTrue();
            assertThat(result.remasterDetails().year()).isEqualTo(2021);
            assertThat(result.cleanTitle()).isEqualTo("Coldplay - Fix You");
        }

        @Test
        @DisplayName("Should preserve conflicting variants without dropping either")
        void shouldPreserveConflictingVariants() {
            TokenizedTitle result = tokenizer.tokenize("Track (Acoustic) (Remix)");

            assertThat(result.isAcoustic()).isTrue();
            assertThat(result.isRemix()).isTrue();
            assertThat(result.variants()).containsExactly(VariantType.ACOUSTIC, VariantType.REMIX);
            assertThat(result.cleanTitle()).isEqualTo("Track");
        }

        @Test
        @DisplayName("Should recognize featured-artist syntax without misclassifying it as a variant")
        void shouldRecognizeFeaturedArtistSyntax() {
            TokenizedTitle result = tokenizer.tokenize("Dua Lipa - Levitating (feat. DaBaby) (Official Video)");

            assertThat(result.featuredArtists()).containsExactly("DaBaby");
            assertThat(result.variants()).isEmpty();
            assertThat(result.isOfficial()).isTrue();
            assertThat(result.mediaFormats()).containsExactly(MediaFormat.VIDEO);
            assertThat(result.cleanTitle()).isEqualTo("Dua Lipa - Levitating");
            assertThat(result.unknownAnnotations()).isEmpty();
        }

        @Test
        @DisplayName("Should handle trailing separator annotations")
        void shouldHandleTrailingSeparatorAnnotations() {
            TokenizedTitle result = tokenizer.tokenize("Queen - Bohemian Rhapsody - Official Video");

            assertThat(result.isOfficial()).isTrue();
            assertThat(result.mediaFormats()).containsExactly(MediaFormat.VIDEO);
            assertThat(result.cleanTitle()).isEqualTo("Queen - Bohemian Rhapsody");
        }

        @Test
        @DisplayName("Should handle comma-separated internal annotations inside one bracket")
        void shouldHandleCommaSeparatedInternalAnnotations() {
            TokenizedTitle result = tokenizer.tokenize("Artist - Song (Live at Wembley, Remastered 2024)");

            assertThat(result.isLive()).isTrue();
            assertThat(result.liveDetails().venueOrEvent()).isEqualTo("Wembley");
            assertThat(result.isRemaster()).isTrue();
            assertThat(result.remasterDetails().year()).isEqualTo(2024);
            assertThat(result.cleanTitle()).isEqualTo("Artist - Song");
        }

        @Test
        @DisplayName("Should recognize multiple featured artists with delimiters")
        void shouldRecognizeMultipleFeaturedArtistsWithDelimiters() {
            TokenizedTitle result1 = tokenizer.tokenize("Song (feat. DaBaby & Roddy Ricch)");
            assertThat(result1.featuredArtists()).containsExactly("DaBaby", "Roddy Ricch");
            assertThat(result1.cleanTitle()).isEqualTo("Song");

            TokenizedTitle result2 = tokenizer.tokenize("Track (ft. Artist 1, Artist 2 and Artist 3)");
            assertThat(result2.featuredArtists()).containsExactly("Artist 1", "Artist 2", "Artist 3");
            assertThat(result2.cleanTitle()).isEqualTo("Track");
        }

        @Test
        @DisplayName("Should recognize unbracketed trailing featured artists")
        void shouldRecognizeUnbracketedTrailingFeaturedArtists() {
            TokenizedTitle result1 = tokenizer.tokenize("Song feat. DaBaby");
            assertThat(result1.featuredArtists()).containsExactly("DaBaby");
            assertThat(result1.cleanTitle()).isEqualTo("Song");

            TokenizedTitle result2 = tokenizer.tokenize("They Don't Love You Like I Love You featuring The Moon");
            assertThat(result2.featuredArtists()).containsExactly("The Moon");
            assertThat(result2.cleanTitle()).isEqualTo("They Don't Love You Like I Love You");
        }

        @Test
        @DisplayName("Should recognize composite annotation with variant and featured artist")
        void shouldRecognizeCompositeAnnotationWithVariantAndFeaturedArtist() {
            TokenizedTitle result = tokenizer.tokenize("Song [Live at Wembley, feat. DaBaby]");
            assertThat(result.isLive()).isTrue();
            assertThat(result.liveDetails().venueOrEvent()).isEqualTo("Wembley");
            assertThat(result.featuredArtists()).containsExactly("DaBaby");
            assertThat(result.cleanTitle()).isEqualTo("Song");
        }

        @Test
        @DisplayName("Should handle empty or delimiter-only brackets without polluting unknown annotations")
        void shouldHandleEmptyOrDelimiterOnlyBrackets() {
            TokenizedTitle result1 = tokenizer.tokenize("Song ()");
            assertThat(result1.cleanTitle()).isEqualTo("Song");
            assertThat(result1.unknownAnnotations()).isEmpty();

            TokenizedTitle result2 = tokenizer.tokenize("Song ( - )");
            assertThat(result2.cleanTitle()).isEqualTo("Song");
            assertThat(result2.unknownAnnotations()).isEmpty();
        }

        @Test
        @DisplayName("Should verify prompt edge cases accurately")
        void shouldVerifyPromptEdgeCases() {
            TokenizedTitle liveAndLetDie = tokenizer.tokenize("Live and Let Die");
            assertThat(liveAndLetDie.cleanTitle()).isEqualTo("Live and Let Die");
            assertThat(liveAndLetDie.isLive()).isFalse();
            assertThat(liveAndLetDie.variants()).isEmpty();

            TokenizedTitle officialSecrets = tokenizer.tokenize("Official Secrets");
            assertThat(officialSecrets.cleanTitle()).isEqualTo("Official Secrets");
            assertThat(officialSecrets.isOfficial()).isFalse();
            assertThat(officialSecrets.variants()).isEmpty();

            TokenizedTitle unknownEdition = tokenizer.tokenize("Song (Unknown Edition)");
            assertThat(unknownEdition.cleanTitle()).isEqualTo("Song (Unknown Edition)");
            assertThat(unknownEdition.unknownAnnotations()).containsExactly("Unknown Edition");

            TokenizedTitle liveRemaster = tokenizer.tokenize("Song (Live at Wembley) [Remastered 2024]");
            assertThat(liveRemaster.cleanTitle()).isEqualTo("Song");
            assertThat(liveRemaster.isLive()).isTrue();
            assertThat(liveRemaster.liveDetails().venueOrEvent()).isEqualTo("Wembley");
            assertThat(liveRemaster.isRemaster()).isTrue();
            assertThat(liveRemaster.remasterDetails().year()).isEqualTo(2024);

            TokenizedTitle radioAudio = tokenizer.tokenize("Song (Radio Edit) (Official Audio)");
            assertThat(radioAudio.cleanTitle()).isEqualTo("Song");
            assertThat(radioAudio.hasVariant(VariantType.RADIO_EDIT)).isTrue();
            assertThat(radioAudio.isOfficial()).isTrue();
            assertThat(radioAudio.mediaFormats()).containsExactly(MediaFormat.AUDIO);

            TokenizedTitle unknownAnnotation = tokenizer.tokenize("Song [Unknown Annotation]");
            assertThat(unknownAnnotation.cleanTitle()).isEqualTo("Song [Unknown Annotation]");
            assertThat(unknownAnnotation.unknownAnnotations()).containsExactly("Unknown Annotation");

            TokenizedTitle featAudio = tokenizer.tokenize("Song (feat. DaBaby) (Official Audio)");
            assertThat(featAudio.cleanTitle()).isEqualTo("Song");
            assertThat(featAudio.featuredArtists()).containsExactly("DaBaby");
            assertThat(featAudio.isOfficial()).isTrue();
            assertThat(featAudio.mediaFormats()).containsExactly(MediaFormat.AUDIO);
        }

        @Test
        @DisplayName("Should preserve original raw title untouched")
        void shouldPreserveRawTitle() {
            String raw = "  The Weeknd - Blinding Lights (Official Audio)  ";
            TokenizedTitle result = tokenizer.tokenize(raw);

            assertThat(result.rawTitle()).isEqualTo(raw);
        }
    }

    @Nested
    @DisplayName("G. Input Handling, Determinism & Edge Cases")
    class InputHandlingAndDeterminismTests {

        @Test
        @DisplayName("Should safely handle null input")
        void shouldHandleNullInput() {
            TokenizedTitle result = tokenizer.tokenize(null);

            assertThat(result).isNotNull();
            assertThat(result.rawTitle()).isEmpty();
            assertThat(result.cleanTitle()).isEmpty();
            assertThat(result.isOfficial()).isFalse();
            assertThat(result.mediaFormats()).isEmpty();
            assertThat(result.variants()).isEmpty();
            assertThat(result.tokens()).isEmpty();
            assertThat(result.unknownAnnotations()).isEmpty();
            assertThat(result.featuredArtists()).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   ", "\t", "\n", " \t \n "})
        @DisplayName("Should safely handle empty and whitespace-only input")
        void shouldHandleBlankInput(String input) {
            TokenizedTitle result = tokenizer.tokenize(input);

            assertThat(result).isNotNull();
            assertThat(result.rawTitle()).isEqualTo(input);
            assertThat(result.cleanTitle()).isEmpty();
            assertThat(result.variants()).isEmpty();
        }

        @Test
        @DisplayName("Should return unchanged clean title when no markers are recognized")
        void shouldHandlePlainTitleWithoutMarkers() {
            TokenizedTitle result = tokenizer.tokenize("Queen - Bohemian Rhapsody");

            assertThat(result.cleanTitle()).isEqualTo("Queen - Bohemian Rhapsody");
            assertThat(result.isOfficial()).isFalse();
            assertThat(result.variants()).isEmpty();
            assertThat(result.tokens()).isEmpty();
            assertThat(result.unknownAnnotations()).isEmpty();
        }

        @Test
        @DisplayName("Should deduplicate repeated markers in variants set")
        void shouldDeduplicateRepeatedMarkers() {
            TokenizedTitle result = tokenizer.tokenize("Song (Remix) [Remix]");

            assertThat(result.variants()).containsExactly(VariantType.REMIX);
            assertThat(result.cleanTitle()).isEqualTo("Song");
        }

        @Test
        @DisplayName("Should produce deterministic and repeatable output")
        void shouldProduceDeterministicOutput() {
            String title = "Artist - Song (Live at Wembley) [Official Video]";
            TokenizedTitle first = tokenizer.tokenize(title);
            TokenizedTitle second = tokenizer.tokenize(title);

            assertThat(first).isEqualTo(second);
        }

        @Test
        @DisplayName("Should handle non-Latin Unicode characters properly")
        void shouldHandleNonLatinUnicode() {
            TokenizedTitle result = tokenizer.tokenize("宇多田ヒカル - One Last Kiss (Official Video)");

            assertThat(result.isOfficial()).isTrue();
            assertThat(result.mediaFormats()).containsExactly(MediaFormat.VIDEO);
            assertThat(result.cleanTitle()).isEqualTo("宇多田ヒカル - One Last Kiss");
        }
    }
}
