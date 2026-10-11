package com.spotifyyoutube.migrator.matching.normalization;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NormalizationOrchestratorTest {

    @Nested
    @DisplayName("Component Reuse & Delegation Tests (Unit)")
    class ComponentReuseAndDelegationTests {

        @Mock
        private TitleNormalizer titleNormalizer;
        @Mock
        private ArtistNormalizer artistNormalizer;
        @Mock
        private AlbumNormalizer albumNormalizer;
        @Mock
        private DurationNormalizer durationNormalizer;
        @Mock
        private MetadataTokenizer metadataTokenizer;

        private NormalizationOrchestrator orchestrator;

        @BeforeEach
        void setUp() {
            orchestrator = new NormalizationOrchestrator(
                    titleNormalizer,
                    artistNormalizer,
                    albumNormalizer,
                    durationNormalizer,
                    metadataTokenizer
            );
        }

        @Test
        @DisplayName("Should invoke all normalizer components with provided raw inputs and assemble result")
        void shouldInvokeAllNormalizersAndAssembleResult() {
            String rawTitle = "Song Title";
            String rawArtist = "Artist Name";
            String rawAlbum = "Album Name";
            Long rawDurationMs = 240000L;

            when(titleNormalizer.normalize(rawTitle)).thenReturn("song title");
            when(metadataTokenizer.tokenize(rawTitle)).thenReturn(
                    new TokenizedTitle(rawTitle, "Song Title", true, Set.of(MediaFormat.AUDIO),
                            null, null, Set.of(VariantType.STUDIO), List.of(), List.of(), List.of("Guest"))
            );
            when(artistNormalizer.parseArtistCredit(rawArtist)).thenReturn(
                    new NormalizedArtistCredit("artist name", List.of("credit guest"))
            );
            when(artistNormalizer.normalize("Guest")).thenReturn("guest");
            when(albumNormalizer.normalize(rawAlbum)).thenReturn("album name");
            when(durationNormalizer.fromMilliseconds(rawDurationMs)).thenReturn(Duration.ofMillis(240000L));

            RawTrackMetadata raw = RawTrackMetadata.of(rawTitle, rawArtist, rawAlbum, rawDurationMs);
            NormalizedTrackMetadata result = orchestrator.normalize(raw);

            verify(titleNormalizer).normalize(rawTitle);
            verify(metadataTokenizer).tokenize(rawTitle);
            verify(artistNormalizer).parseArtistCredit(rawArtist);
            verify(albumNormalizer).normalize(rawAlbum);
            verify(durationNormalizer).fromMilliseconds(rawDurationMs);

            assertThat(result.rawMetadata()).isSameAs(raw);
            assertThat(result.normalizedTitle()).isEqualTo("song title");
            assertThat(result.primaryArtist()).isEqualTo("artist name");
            assertThat(result.normalizedAlbum()).isEqualTo("album name");
            assertThat(result.duration()).isEqualTo(Duration.ofMillis(240000L));
            assertThat(result.cleanTitle()).isEqualTo("Song Title");
            assertThat(result.isOfficial()).isTrue();
            assertThat(result.mediaFormats()).containsExactly(MediaFormat.AUDIO);
            assertThat(result.variants()).containsExactly(VariantType.STUDIO);
            assertThat(result.featuredArtists()).containsExactly("credit guest", "guest");
        }

        @Test
        @DisplayName("Should invoke ISO-8601 duration parser when duration string is provided")
        void shouldInvokeIso8601DurationParser() {
            when(titleNormalizer.normalize(anyString())).thenReturn("song");
            when(metadataTokenizer.tokenize(anyString())).thenReturn(
                    new TokenizedTitle("Song", "Song", false, Set.of(), null, null, Set.of(), List.of(), List.of(), List.of())
            );
            when(artistNormalizer.parseArtistCredit(anyString())).thenReturn(new NormalizedArtistCredit("artist", List.of()));
            when(albumNormalizer.normalize(anyString())).thenReturn("album");
            when(durationNormalizer.fromIso8601("PT3M30S")).thenReturn(Duration.ofSeconds(210));

            RawTrackMetadata raw = RawTrackMetadata.of("Song", "Artist", "Album", "PT3M30S");
            NormalizedTrackMetadata result = orchestrator.normalize(raw);

            verify(durationNormalizer).fromIso8601("PT3M30S");
            assertThat(result.duration()).isEqualTo(Duration.ofSeconds(210));
        }

        @Test
        @DisplayName("Should invoke artist list normalizer when discrete artist list is provided")
        void shouldInvokeArtistListNormalizerWhenListProvided() {
            when(titleNormalizer.normalize(anyString())).thenReturn("song");
            when(metadataTokenizer.tokenize(anyString())).thenReturn(
                    new TokenizedTitle("Song", "Song", false, Set.of(), null, null, Set.of(), List.of(), List.of(), List.of())
            );
            List<String> rawArtists = List.of("Artist 1", "Artist 2");
            when(artistNormalizer.normalizeArtistList(rawArtists)).thenReturn(List.of("artist 1", "artist 2"));
            when(albumNormalizer.normalize(anyString())).thenReturn("album");

            RawTrackMetadata raw = RawTrackMetadata.of("Song", rawArtists, "Album", 120000L);
            NormalizedTrackMetadata result = orchestrator.normalize(raw);

            verify(artistNormalizer).normalizeArtistList(rawArtists);
            assertThat(result.primaryArtist()).isEqualTo("artist 1");
            assertThat(result.normalizedArtists()).containsExactly("artist 1", "artist 2");
            assertThat(result.featuredArtists()).containsExactly("artist 2");
        }
    }

    @Nested
    @DisplayName("Full Pipeline Integration Tests (Real Components)")
    class FullPipelineIntegrationTests {

        private NormalizationOrchestrator orchestrator;

        @BeforeEach
        void setUp() {
            orchestrator = new NormalizationOrchestrator(
                    new TitleNormalizer(),
                    new ArtistNormalizer(),
                    new AlbumNormalizer(),
                    new DurationNormalizer(),
                    new MetadataTokenizer()
            );
        }

        @Test
        @DisplayName("Should normalize standard Spotify track completely and accurately")
        void shouldNormalizeStandardSpotifyTrack() {
            NormalizedTrackMetadata result = orchestrator.normalize(
                    "Blinding Lights",
                    "The Weeknd",
                    "After Hours",
                    200000L
            );

            assertThat(result.normalizedTitle()).isEqualTo("blinding lights");
            assertThat(result.cleanTitle()).isEqualTo("Blinding Lights");
            assertThat(result.primaryArtist()).isEqualTo("the weeknd");
            assertThat(result.normalizedAlbum()).isEqualTo("after hours");
            assertThat(result.duration()).isEqualTo(Duration.ofMillis(200000L));
            assertThat(result.variants()).isEmpty();
            assertThat(result.featuredArtists()).isEmpty();
            assertThat(result.isOfficial()).isFalse();
        }

        @Test
        @DisplayName("Should handle complex multi-variant title with venue, remaster year, and featured artist")
        void shouldHandleComplexMultiVariantTrack() {
            // Regression scenario specified in prompt Section 4F:
            // "Song Name (Live at Wembley, Remastered 2024) feat. Guest Artist"
            NormalizedTrackMetadata result = orchestrator.normalize(
                    "Song Name (Live at Wembley, Remastered 2024) feat. Guest Artist",
                    "Main Artist",
                    "Live Album",
                    "PT4M15S"
            );

            assertThat(result.normalizedTitle()).isEqualTo("song name (live at wembley, remastered 2024) feat. guest artist");
            assertThat(result.cleanTitle()).isEqualTo("Song Name");
            assertThat(result.primaryArtist()).isEqualTo("main artist");
            assertThat(result.normalizedAlbum()).isEqualTo("live album");
            assertThat(result.duration()).isEqualTo(Duration.ofSeconds(255));

            assertThat(result.isLive()).isTrue();
            assertThat(result.liveDetails()).isNotNull();
            assertThat(result.liveDetails().venueOrEvent()).isEqualTo("Wembley");

            assertThat(result.isRemaster()).isTrue();
            assertThat(result.remasterDetails()).isNotNull();
            assertThat(result.remasterDetails().year()).isEqualTo(2024);

            assertThat(result.featuredArtists()).containsExactly("guest artist");
        }

        @Test
        @DisplayName("Should consolidate featured artists from both artist credit and track title without duplication")
        void shouldConsolidateFeaturedArtistsFromCreditAndTitle() {
            NormalizedTrackMetadata result = orchestrator.normalize(
                    "Levitating (feat. DaBaby)",
                    "Dua Lipa feat. DaBaby",
                    "Future Nostalgia",
                    203000L
            );

            assertThat(result.primaryArtist()).isEqualTo("dua lipa");
            assertThat(result.cleanTitle()).isEqualTo("Levitating");
            // DaBaby appears in both artist credit and parenthetical title: should be deduplicated
            assertThat(result.featuredArtists()).containsExactly("dababy");
        }

        @Test
        @DisplayName("Should remove promotional suffix while preserving media format and official status")
        void shouldRemovePromotionalSuffixWhilePreservingMetadata() {
            NormalizedTrackMetadata result = orchestrator.normalize(
                    "Bohemian Rhapsody - Official Video",
                    "Queen",
                    "A Night at the Opera",
                    354000L
            );

            assertThat(result.normalizedTitle()).isEqualTo("bohemian rhapsody");
            assertThat(result.cleanTitle()).isEqualTo("Bohemian Rhapsody");
            assertThat(result.primaryArtist()).isEqualTo("queen");
            assertThat(result.isOfficial()).isTrue();
            assertThat(result.mediaFormats()).containsExactly(MediaFormat.VIDEO);
        }

        @Test
        @DisplayName("Should support RawTrackMetadata builder input representation")
        void shouldSupportRawTrackMetadataBuilder() {
            RawTrackMetadata raw = RawTrackMetadata.builder()
                    .title("Hotel California (Live at The Forum)")
                    .artistCredit("Eagles")
                    .album("Hell Freezes Over")
                    .durationMs(432000L)
                    .build();

            NormalizedTrackMetadata result = orchestrator.normalize(raw);

            assertThat(result.normalizedTitle()).isEqualTo("hotel california (live at the forum)");
            assertThat(result.cleanTitle()).isEqualTo("Hotel California");
            assertThat(result.primaryArtist()).isEqualTo("eagles");
            assertThat(result.isLive()).isTrue();
            assertThat(result.liveDetails()).isNotNull();
            assertThat(result.liveDetails().venueOrEvent()).isEqualTo("The Forum");
            assertThat(result.duration()).isEqualTo(Duration.ofMillis(432000L));
        }

        @Test
        @DisplayName("Should preserve discrete artist array and identify primary vs featured artists")
        void shouldPreserveDiscreteArtistArray() {
            NormalizedTrackMetadata result = orchestrator.normalize(
                    "STAY",
                    List.of("The Kid LAROI", "Justin Bieber"),
                    "F*CK LOVE 3+: OVER YOU",
                    141000L
            );

            assertThat(result.normalizedTitle()).isEqualTo("stay");
            assertThat(result.primaryArtist()).isEqualTo("the kid laroi");
            assertThat(result.normalizedArtists()).containsExactly("the kid laroi", "justin bieber");
            assertThat(result.featuredArtists()).containsExactly("justin bieber");
            assertThat(result.normalizedAlbum()).isEqualTo("f*ck love 3+: over you");
        }

        @Test
        @DisplayName("Should preserve unknown annotations without losing identity in clean title")
        void shouldPreserveUnknownAnnotations() {
            NormalizedTrackMetadata result = orchestrator.normalize(
                    "Danger Zone [From The Motion Picture Top Gun]",
                    "Kenny Loggins",
                    "Top Gun Soundtrack",
                    215000L
            );

            assertThat(result.cleanTitle()).isEqualTo("Danger Zone [From The Motion Picture Top Gun]");
            assertThat(result.unknownAnnotations()).containsExactly("From The Motion Picture Top Gun");
            assertThat(result.primaryArtist()).isEqualTo("kenny loggins");
        }

        @Test
        @DisplayName("Should consolidate featured artists from credit, artist list, and title in encounter order")
        void shouldConsolidateFeaturedArtistsFromCreditListAndTitleInEncounterOrder() {
            // Track where featured artists appear in credit, artist list, and title
            RawTrackMetadata raw = RawTrackMetadata.builder()
                    .title("Levitating (feat. Madonna)")
                    .artistCredit("Dua Lipa feat. DaBaby")
                    .artists(List.of("Dua Lipa", "Missy Elliott"))
                    .album("Future Nostalgia")
                    .durationMs(203000L)
                    .build();

            NormalizedTrackMetadata result = orchestrator.normalize(raw);

            assertThat(result.primaryArtist()).isEqualTo("dua lipa");
            // Encounter order: credit ("dababy") -> list ("missy elliott") -> title ("madonna")
            assertThat(result.featuredArtists()).containsExactly("dababy", "missy elliott", "madonna");
            // Primary artist ("dua lipa") is excluded from featured artists
            assertThat(result.featuredArtists()).doesNotContain("dua lipa");
        }

        @Test
        @DisplayName("Should report isClean as true for standard unmodified track")
        void shouldReportIsCleanTrueForStandardTrack() {
            NormalizedTrackMetadata result = orchestrator.normalize(
                    "Blinding Lights",
                    "The Weeknd",
                    "After Hours",
                    200000L
            );

            assertThat(result.isClean()).isTrue();
        }

        @Test
        @DisplayName("Should report isClean as false when variants or promotional markers are present")
        void shouldReportIsCleanFalseWhenVariantsOrPromotionalMarkersPresent() {
            NormalizedTrackMetadata liveTrack = orchestrator.normalize(
                    "Hotel California (Live at The Forum)",
                    "Eagles",
                    "Hell Freezes Over",
                    432000L
            );
            assertThat(liveTrack.isClean()).isFalse();

            NormalizedTrackMetadata promoVideo = orchestrator.normalize(
                    "Bohemian Rhapsody - Official Video",
                    "Queen",
                    "A Night at the Opera",
                    354000L
            );
            assertThat(promoVideo.isClean()).isFalse();

            NormalizedTrackMetadata remixTrack = orchestrator.normalize(
                    "Levitating (The Blessed Madonna Remix)",
                    "Dua Lipa",
                    "Club Future Nostalgia",
                    250000L
            );
            assertThat(remixTrack.isClean()).isFalse();
        }

        @Test
        @DisplayName("Should verify isClean semantics does not confuse explicit content advisory with title cleanliness")
        void shouldVerifyIsCleanSemanticsDoesNotConfuseExplicitAdvisoryWithCleanliness() {
            NormalizedTrackMetadata result = orchestrator.normalize(
                    "Song Title [Explicit]",
                    "Artist Name",
                    "Album Name",
                    180000L
            );

            // [Explicit] is treated as an unknown annotation (parental advisory rating), not a recording variant
            assertThat(result.unknownAnnotations()).containsExactly("Explicit");
            assertThat(result.variants()).isEmpty();
            assertThat(result.mediaFormats()).isEmpty();
            assertThat(result.isOfficial()).isFalse();
            // Syntactically clean of recording variants
            assertThat(result.isClean()).isTrue();
        }
    }

    @Nested
    @DisplayName("Null & Optional Field Handling Tests")
    class NullAndOptionalHandlingTests {

        private NormalizationOrchestrator orchestrator;

        @BeforeEach
        void setUp() {
            orchestrator = new NormalizationOrchestrator(
                    new TitleNormalizer(),
                    new ArtistNormalizer(),
                    new AlbumNormalizer(),
                    new DurationNormalizer(),
                    new MetadataTokenizer()
            );
        }

        @Test
        @DisplayName("Should reject null RawTrackMetadata with IllegalArgumentException")
        void shouldRejectNullRawTrackMetadata() {
            assertThatThrownBy(() -> orchestrator.normalize((RawTrackMetadata) null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("RawTrackMetadata cannot be null");
        }

        @Test
        @DisplayName("Should gracefully handle null or blank track fields producing empty normalized values")
        void shouldGracefullyHandleNullOrBlankFields() {
            RawTrackMetadata raw = RawTrackMetadata.of(null, (String) null, null, (Long) null);
            NormalizedTrackMetadata result = orchestrator.normalize(raw);

            assertThat(result.normalizedTitle()).isEmpty();
            assertThat(result.cleanTitle()).isEmpty();
            assertThat(result.primaryArtist()).isEmpty();
            assertThat(result.normalizedAlbum()).isEmpty();
            assertThat(result.duration()).isNull();
            assertThat(result.variants()).isEmpty();
            assertThat(result.featuredArtists()).isEmpty();
            assertThat(result.mediaFormats()).isEmpty();
            assertThat(result.isOfficial()).isFalse();
        }

        @Test
        @DisplayName("Should preserve valid fields when optional fields (album, duration) are absent")
        void shouldPreserveValidFieldsWhenOptionalFieldsAbsent() {
            NormalizedTrackMetadata result = orchestrator.normalize(
                    "Yesterday",
                    "The Beatles",
                    null,
                    (Long) null
            );

            assertThat(result.normalizedTitle()).isEqualTo("yesterday");
            assertThat(result.primaryArtist()).isEqualTo("the beatles");
            assertThat(result.normalizedAlbum()).isEmpty();
            assertThat(result.duration()).isNull();
        }

        @Test
        @DisplayName("Should distinguish legitimate Duration.ZERO from absent null duration")
        void shouldDistinguishZeroDurationFromAbsentNull() {
            NormalizedTrackMetadata withZeroMs = orchestrator.normalize("Intro", "Artist", "Album", 0L);
            assertThat(withZeroMs.duration()).isEqualTo(Duration.ZERO);

            NormalizedTrackMetadata withZeroIso = orchestrator.normalize("Intro", "Artist", "Album", "PT0S");
            assertThat(withZeroIso.duration()).isEqualTo(Duration.ZERO);

            NormalizedTrackMetadata withNull = orchestrator.normalize("Intro", "Artist", "Album", (Long) null);
            assertThat(withNull.duration()).isNull();
        }
    }

    @Nested
    @DisplayName("Error Semantics & Validation Tests")
    class ErrorSemanticsAndValidationTests {

        private NormalizationOrchestrator orchestrator;

        @BeforeEach
        void setUp() {
            orchestrator = new NormalizationOrchestrator(
                    new TitleNormalizer(),
                    new ArtistNormalizer(),
                    new AlbumNormalizer(),
                    new DurationNormalizer(),
                    new MetadataTokenizer()
            );
        }

        @Test
        @DisplayName("Should propagate IllegalArgumentException on negative duration milliseconds")
        void shouldPropagateExceptionOnNegativeDurationMs() {
            assertThatThrownBy(() -> orchestrator.normalize("Song", "Artist", "Album", -500L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("negative");
        }

        @Test
        @DisplayName("Should propagate IllegalArgumentException on malformed ISO-8601 duration string")
        void shouldPropagateExceptionOnMalformedIsoDuration() {
            assertThatThrownBy(() -> orchestrator.normalize("Song", "Artist", "Album", "not-a-duration"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Should reject negative direct Duration with IllegalArgumentException")
        void shouldRejectNegativeDirectDuration() {
            RawTrackMetadata raw = RawTrackMetadata.builder()
                    .title("Song")
                    .artistCredit("Artist")
                    .album("Album")
                    .duration(Duration.ofSeconds(-10))
                    .build();

            assertThatThrownBy(() -> orchestrator.normalize(raw))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("negative");
        }

        @Test
        @DisplayName("Should reject conflicting duration representations with IllegalArgumentException")
        void shouldRejectConflictingDurations() {
            // durationMs (240000ms = 4m) vs durationIso8601 ("PT3M" = 3m)
            RawTrackMetadata conflictingMsAndIso = RawTrackMetadata.builder()
                    .title("Song")
                    .artistCredit("Artist")
                    .album("Album")
                    .durationMs(240000L)
                    .durationIso8601("PT3M")
                    .build();

            assertThatThrownBy(() -> orchestrator.normalize(conflictingMsAndIso))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Conflicting durations");

            // direct duration (2m) vs durationMs (4m)
            RawTrackMetadata conflictingDirectAndMs = RawTrackMetadata.builder()
                    .title("Song")
                    .artistCredit("Artist")
                    .album("Album")
                    .duration(Duration.ofMinutes(2))
                    .durationMs(240000L)
                    .build();

            assertThatThrownBy(() -> orchestrator.normalize(conflictingDirectAndMs))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Conflicting durations");

            // direct duration (2m) vs durationIso8601 ("PT4M")
            RawTrackMetadata conflictingDirectAndIso = RawTrackMetadata.builder()
                    .title("Song")
                    .artistCredit("Artist")
                    .album("Album")
                    .duration(Duration.ofMinutes(2))
                    .durationIso8601("PT4M")
                    .build();

            assertThatThrownBy(() -> orchestrator.normalize(conflictingDirectAndIso))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Conflicting durations");
        }

        @Test
        @DisplayName("Should succeed when multiple duration representations match identically")
        void shouldSucceedWhenMultipleDurationsMatchIdentically() {
            RawTrackMetadata matching = RawTrackMetadata.builder()
                    .title("Song")
                    .artistCredit("Artist")
                    .album("Album")
                    .duration(Duration.ofMinutes(4))
                    .durationMs(240000L)
                    .durationIso8601("PT4M")
                    .build();

            NormalizedTrackMetadata result = orchestrator.normalize(matching);
            assertThat(result.duration()).isEqualTo(Duration.ofMinutes(4));
        }

        @Test
        @DisplayName("Should reject null dependencies in constructor with NullPointerException")
        void shouldRejectNullDependenciesInConstructor() {
            TitleNormalizer tn = new TitleNormalizer();
            ArtistNormalizer arn = new ArtistNormalizer();
            AlbumNormalizer aln = new AlbumNormalizer();
            DurationNormalizer dn = new DurationNormalizer();
            MetadataTokenizer mt = new MetadataTokenizer();

            assertThatThrownBy(() -> new NormalizationOrchestrator(null, arn, aln, dn, mt))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new NormalizationOrchestrator(tn, null, aln, dn, mt))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new NormalizationOrchestrator(tn, arn, null, dn, mt))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new NormalizationOrchestrator(tn, arn, aln, null, mt))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new NormalizationOrchestrator(tn, arn, aln, dn, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("Immutability & Determinism Tests")
    class ImmutabilityAndDeterminismTests {

        private NormalizationOrchestrator orchestrator;

        @BeforeEach
        void setUp() {
            orchestrator = new NormalizationOrchestrator(
                    new TitleNormalizer(),
                    new ArtistNormalizer(),
                    new AlbumNormalizer(),
                    new DurationNormalizer(),
                    new MetadataTokenizer()
            );
        }

        @Test
        @DisplayName("Should guarantee that exposed collection accessors are unmodifiable")
        void shouldGuaranteeImmutabilityOfExposedCollections() {
            NormalizedTrackMetadata result = orchestrator.normalize(
                    "Track (feat. Guest) (Live at Venue)",
                    "Main Artist",
                    "Album",
                    180000L
            );

            assertThatThrownBy(() -> result.featuredArtists().add("hacker"))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> result.variants().add(VariantType.ACOUSTIC))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> result.normalizedArtists().add("hacker"))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> result.mediaFormats().add(MediaFormat.VIDEO))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> result.unknownAnnotations().add("injection"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("Should guarantee complete deterministic repeatability across repeated calls")
        void shouldGuaranteeDeterministicRepeatability() {
            RawTrackMetadata raw = RawTrackMetadata.builder()
                    .title("Track Name (feat. Artist 2) [Remastered 2020]")
                    .artistCredit("Artist 1")
                    .album("Greatest Hits")
                    .durationMs(215000L)
                    .build();

            NormalizedTrackMetadata first = orchestrator.normalize(raw);

            for (int i = 0; i < 50; i++) {
                NormalizedTrackMetadata subsequent = orchestrator.normalize(raw);
                assertThat(subsequent).isEqualTo(first);
                assertThat(subsequent.hashCode()).isEqualTo(first.hashCode());
            }
        }
    }
}
