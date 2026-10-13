package com.spotifyyoutube.migrator.matching.engine.feature;

import com.spotifyyoutube.migrator.matching.normalization.ArtistNormalizer;
import com.spotifyyoutube.migrator.matching.normalization.MetadataTokenizer;
import com.spotifyyoutube.migrator.matching.normalization.NormalizationOrchestrator;
import com.spotifyyoutube.migrator.matching.normalization.NormalizedTrackMetadata;
import com.spotifyyoutube.migrator.matching.normalization.RawTrackMetadata;
import com.spotifyyoutube.migrator.matching.normalization.TitleNormalizer;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationTrackCandidate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DefaultTrackFeatureExtractor Tests")
class DefaultTrackFeatureExtractorTest {

    private DefaultTrackFeatureExtractor extractor;
    private NormalizationOrchestrator normalizationOrchestrator;

    @BeforeEach
    void setUp() {
        TitleNormalizer titleNormalizer = new TitleNormalizer();
        ArtistNormalizer artistNormalizer = new ArtistNormalizer();
        MetadataTokenizer tokenizer = new MetadataTokenizer();
        extractor = new DefaultTrackFeatureExtractor(titleNormalizer, artistNormalizer, tokenizer);
        normalizationOrchestrator = new NormalizationOrchestrator();
    }

    private NormalizedTrackMetadata normalize(String title, String artist, String album, Long durationMs) {
        return normalizationOrchestrator.normalize(RawTrackMetadata.of(title, artist, album, durationMs));
    }

    private DestinationTrackCandidate candidate(String id, String title, String channel, String description, String durationIso) {
        return new DestinationTrackCandidate(id, title, channel, description, "https://thumb.jpg", "2021-01-01", durationIso);
    }

    @Nested
    @DisplayName("Source Track Feature Extraction")
    class SourceTrackFeatureExtraction {

        @Test
        @DisplayName("should preserve exact normalized source metadata, clean title, and variants")
        void shouldPreserveExactNormalizedSourceMetadata() {
            NormalizedTrackMetadata source = normalize(
                    "Hotel California - Live at Wembley [Remastered 2024]",
                    "Eagles feat. Guest",
                    "Hell Freezes Over",
                    391000L
            );

            SourceTrackFeatures features = extractor.extractSourceFeatures("spotify:track:123", source);

            assertThat(features.sourceTrackId()).isEqualTo("spotify:track:123");
            assertThat(features.normalizedTitle()).isEqualTo("hotel california - live at wembley [remastered 2024]");
            assertThat(features.cleanTitle()).isEqualTo("hotel california");
            assertThat(features.primaryArtist()).isEqualTo("eagles");
            assertThat(features.featuredArtists()).contains("guest");
            assertThat(features.normalizedAlbum()).isEqualTo("hell freezes over");
            assertThat(features.duration()).isEqualTo(Duration.ofMillis(391000));
            assertThat(features.durationMs()).isEqualTo(391000L);
            assertThat(features.hasAlbum()).isTrue();
            assertThat(features.hasDuration()).isTrue();
            assertThat(features.hasPrimaryArtist()).isTrue();
            assertThat(features.isLive()).isTrue();
            assertThat(features.isRemaster()).isTrue();
            assertThat(features.versionIndicators()).contains(VersionIndicator.LIVE, VersionIndicator.REMASTER);
            assertThat(features.sourceMetadata()).isSameAs(source);
        }

        @Test
        @DisplayName("should handle missing album, duration, and artist in source metadata honestly")
        void shouldHandleMissingSourceMetadataHonestly() {
            NormalizedTrackMetadata source = normalize(
                    "Acoustic Song",
                    "",
                    null,
                    null
            );

            SourceTrackFeatures features = extractor.extractSourceFeatures(source);

            assertThat(features.sourceTrackId()).isNull();
            assertThat(features.hasAlbum()).isFalse();
            assertThat(features.hasDuration()).isFalse();
            assertThat(features.hasPrimaryArtist()).isFalse();
            assertThat(features.duration()).isNull();
            assertThat(features.durationMs()).isNull();
            assertThat(features.normalizedAlbum()).isEmpty();
            assertThat(features.primaryArtist()).isEmpty();
        }

        @Test
        @DisplayName("should reject null source track")
        void shouldRejectNullSourceTrack() {
            assertThatThrownBy(() -> extractor.extractSourceFeatures(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("NormalizedTrackMetadata must not be null");
        }
    }

    @Nested
    @DisplayName("Destination Candidate Metadata Extraction")
    class DestinationCandidateMetadataExtraction {

        @Test
        @DisplayName("should extract candidate metadata, normalized channel, duration, and description")
        void shouldExtractCandidateMetadata() {
            DestinationTrackCandidate c = candidate(
                    "vid-abc",
                    "Daft Punk - Get Lucky (Official Video)",
                    "Daft Punk",
                    "Official music video for Get Lucky",
                    "PT4M8S"
            );

            NormalizedTrackMetadata source = normalize("Get Lucky", "Daft Punk", "Random Access Memories", 248000L);
            CandidateTrackFeatures features = extractor.extractCandidateFeatures(c, source);

            assertThat(features.candidateId()).isEqualTo("vid-abc");
            assertThat(features.rawTitle()).isEqualTo("Daft Punk - Get Lucky (Official Video)");
            assertThat(features.normalizedTitle()).isEqualTo("daft punk - get lucky (official video)");
            assertThat(features.cleanTitle()).isEqualTo("daft punk - get lucky");
            assertThat(features.channelOrArtist()).isEqualTo("Daft Punk");
            assertThat(features.normalizedChannelOrArtist()).isEqualTo("daft punk");
            assertThat(features.duration()).isEqualTo(Duration.ofSeconds(248));
            assertThat(features.durationMs()).isEqualTo(248000L);
            assertThat(features.hasDuration()).isTrue();
            assertThat(features.hasDescription()).isTrue();
            assertThat(features.description()).isEqualTo("Official music video for Get Lucky");
            assertThat(features.publishedAt()).isEqualTo("2021-01-01");
            assertThat(features.candidate()).isSameAs(c);
        }

        @Test
        @DisplayName("should handle missing duration, description, and publication date honestly")
        void shouldHandleMissingCandidateMetadataHonestly() {
            DestinationTrackCandidate c = new DestinationTrackCandidate(
                    "vid-xyz",
                    "Song Title",
                    "Artist Channel",
                    null,
                    null,
                    null,
                    (Duration) null
            );

            CandidateTrackFeatures features = extractor.extractCandidateFeatures(c);

            assertThat(features.candidateId()).isEqualTo("vid-xyz");
            assertThat(features.hasDuration()).isFalse();
            assertThat(features.duration()).isNull();
            assertThat(features.durationMs()).isNull();
            assertThat(features.hasDescription()).isFalse();
            assertThat(features.description()).isNull();
            assertThat(features.publishedAt()).isNull();
        }

        @Test
        @DisplayName("should reject null candidate and blank candidateId")
        void shouldRejectNullOrBlankCandidate() {
            assertThatThrownBy(() -> extractor.extractCandidateFeatures(null))
                    .isInstanceOf(NullPointerException.class);

            DestinationTrackCandidate blankIdCandidate = new DestinationTrackCandidate(
                    "   ", "Title", "Channel", null, null, null, (Duration) null
            );
            assertThatThrownBy(() -> extractor.extractCandidateFeatures(blankIdCandidate))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("id must not be null or blank");
        }
    }

    @Nested
    @DisplayName("Version Indicator Detection")
    class VersionIndicatorDetection {

        @Test
        @DisplayName("should detect cover indicators in brackets, trailing separators, and prefixes")
        void shouldDetectCoverIndicators() {
            CandidateTrackFeatures c1 = extractor.extractCandidateFeatures(
                    candidate("1", "Song Title (Cover)", "Artist", null, "PT3M")
            );
            assertThat(c1.versionEvidence().isCover()).isTrue();
            assertThat(c1.hasIndicator(VersionIndicator.COVER)).isTrue();

            CandidateTrackFeatures c2 = extractor.extractCandidateFeatures(
                    candidate("2", "Song Title [Acoustic Cover]", "Artist", null, "PT3M")
            );
            assertThat(c2.versionEvidence().isCover()).isTrue();

            CandidateTrackFeatures c3 = extractor.extractCandidateFeatures(
                    candidate("3", "Song Title - Cover", "Artist", null, "PT3M")
            );
            assertThat(c3.versionEvidence().isCover()).isTrue();

            CandidateTrackFeatures c4 = extractor.extractCandidateFeatures(
                    candidate("4", "Cover by John Doe - Song Title", "John Doe", null, "PT3M")
            );
            assertThat(c4.versionEvidence().isCover()).isTrue();
        }

        @Test
        @DisplayName("should detect live indicators in brackets and trailing separators")
        void shouldDetectLiveIndicators() {
            CandidateTrackFeatures c1 = extractor.extractCandidateFeatures(
                    candidate("1", "Hotel California (Live at Wembley)", "Eagles", null, "PT6M")
            );
            assertThat(c1.versionEvidence().isLive()).isTrue();

            CandidateTrackFeatures c2 = extractor.extractCandidateFeatures(
                    candidate("2", "Hotel California - Live", "Eagles", null, "PT6M")
            );
            assertThat(c2.versionEvidence().isLive()).isTrue();

            CandidateTrackFeatures c3 = extractor.extractCandidateFeatures(
                    candidate("3", "Song Title [Live in Tokyo]", "Artist", null, "PT4M")
            );
            assertThat(c3.versionEvidence().isLive()).isTrue();
        }

        @Test
        @DisplayName("should detect remix indicators in brackets and trailing separators")
        void shouldDetectRemixIndicators() {
            CandidateTrackFeatures c1 = extractor.extractCandidateFeatures(
                    candidate("1", "Levitating (Steve Aoki Remix)", "Dua Lipa", null, "PT3M")
            );
            assertThat(c1.versionEvidence().isRemix()).isTrue();

            CandidateTrackFeatures c2 = extractor.extractCandidateFeatures(
                    candidate("2", "Levitating - Club Remix", "Dua Lipa", null, "PT3M")
            );
            assertThat(c2.versionEvidence().isRemix()).isTrue();
        }

        @Test
        @DisplayName("should detect karaoke indicators")
        void shouldDetectKaraokeIndicators() {
            CandidateTrackFeatures c1 = extractor.extractCandidateFeatures(
                    candidate("1", "Bohemian Rhapsody (Karaoke Version)", "Karaoke Channel", null, "PT6M")
            );
            assertThat(c1.versionEvidence().isKaraoke()).isTrue();

            CandidateTrackFeatures c2 = extractor.extractCandidateFeatures(
                    candidate("2", "Bohemian Rhapsody - Karaoke", "Karaoke Channel", null, "PT6M")
            );
            assertThat(c2.versionEvidence().isKaraoke()).isTrue();
        }

        @Test
        @DisplayName("should detect reaction indicators")
        void shouldDetectReactionIndicators() {
            CandidateTrackFeatures c1 = extractor.extractCandidateFeatures(
                    candidate("1", "Dua Lipa - Levitating (Reaction)", "ReactChannel", null, "PT10M")
            );
            assertThat(c1.versionEvidence().isReaction()).isTrue();

            CandidateTrackFeatures c2 = extractor.extractCandidateFeatures(
                    candidate("2", "Dua Lipa - Levitating [REACTION]", "ReactChannel", null, "PT10M")
            );
            assertThat(c2.versionEvidence().isReaction()).isTrue();

            CandidateTrackFeatures c3 = extractor.extractCandidateFeatures(
                    candidate("3", "Dua Lipa - Levitating (First Time Hearing Reaction)", "ReactChannel", null, "PT10M")
            );
            assertThat(c3.versionEvidence().isReaction()).isTrue();

            CandidateTrackFeatures c4 = extractor.extractCandidateFeatures(
                    candidate("4", "Dua Lipa - Levitating - Producer Reacts", "Producer", null, "PT10M")
            );
            assertThat(c4.versionEvidence().isReaction()).isTrue();
        }

        @Test
        @DisplayName("should detect nightcore indicators")
        void shouldDetectNightcoreIndicators() {
            CandidateTrackFeatures c1 = extractor.extractCandidateFeatures(
                    candidate("1", "Song Title (Nightcore)", "Nightcore Uploader", null, "PT2M")
            );
            assertThat(c1.versionEvidence().isNightcore()).isTrue();

            CandidateTrackFeatures c2 = extractor.extractCandidateFeatures(
                    candidate("2", "Nightcore - Song Title", "Nightcore Uploader", null, "PT2M")
            );
            assertThat(c2.versionEvidence().isNightcore()).isTrue();
        }

        @Test
        @DisplayName("should detect sped-up indicators")
        void shouldDetectSpedUpIndicators() {
            CandidateTrackFeatures c1 = extractor.extractCandidateFeatures(
                    candidate("1", "Song Title (Sped Up)", "TikTok Edits", null, "PT2M")
            );
            assertThat(c1.versionEvidence().isSpedUp()).isTrue();

            CandidateTrackFeatures c2 = extractor.extractCandidateFeatures(
                    candidate("2", "Song Title - Speed Up", "TikTok Edits", null, "PT2M")
            );
            assertThat(c2.versionEvidence().isSpedUp()).isTrue();
        }

        @Test
        @DisplayName("should detect slowed indicators")
        void shouldDetectSlowedIndicators() {
            CandidateTrackFeatures c1 = extractor.extractCandidateFeatures(
                    candidate("1", "Song Title (Slowed + Reverb)", "Aesthetic Channel", null, "PT4M")
            );
            assertThat(c1.versionEvidence().isSlowed()).isTrue();

            CandidateTrackFeatures c2 = extractor.extractCandidateFeatures(
                    candidate("2", "Song Title - Slowed Down", "Aesthetic Channel", null, "PT4M")
            );
            assertThat(c2.versionEvidence().isSlowed()).isTrue();

            CandidateTrackFeatures c3 = extractor.extractCandidateFeatures(
                    candidate("3", "Song Title (Chopped and Screwed)", "DJ", null, "PT5M")
            );
            assertThat(c3.versionEvidence().isSlowed()).isTrue();
        }
    }

    @Nested
    @DisplayName("False Positive Prevention in Unrelated Contexts")
    class FalsePositivePrevention {

        @Test
        @DisplayName("should not false positive on legitimate song titles containing 'Live'")
        void shouldNotFalsePositiveOnLiveSongTitles() {
            CandidateTrackFeatures c1 = extractor.extractCandidateFeatures(
                    candidate("1", "Madonna - Live to Tell", "Madonna", null, "PT5M")
            );
            assertThat(c1.versionEvidence().isLive()).isFalse();

            CandidateTrackFeatures c2 = extractor.extractCandidateFeatures(
                    candidate("2", "Paul McCartney - Live and Let Die", "Paul McCartney", null, "PT3M")
            );
            assertThat(c2.versionEvidence().isLive()).isFalse();

            CandidateTrackFeatures c3 = extractor.extractCandidateFeatures(
                    candidate("3", "Oasis - Live Forever", "Oasis", null, "PT4M")
            );
            assertThat(c3.versionEvidence().isLive()).isFalse();

            CandidateTrackFeatures c4 = extractor.extractCandidateFeatures(
                    candidate("4", "Bee Gees - Stayin' Alive", "Bee Gees", null, "PT4M")
            );
            assertThat(c4.versionEvidence().isLive()).isFalse();
        }

        @Test
        @DisplayName("should not false positive on legitimate song titles containing 'Cover'")
        void shouldNotFalsePositiveOnCoverSongTitles() {
            CandidateTrackFeatures c1 = extractor.extractCandidateFeatures(
                    candidate("1", "The Strokes - Under Cover of Darkness", "The Strokes", null, "PT4M")
            );
            assertThat(c1.versionEvidence().isCover()).isFalse();

            CandidateTrackFeatures c2 = extractor.extractCandidateFeatures(
                    candidate("2", "Bruce Springsteen - Cover Me", "Bruce Springsteen", null, "PT3M")
            );
            assertThat(c2.versionEvidence().isCover()).isFalse();

            CandidateTrackFeatures c3 = extractor.extractCandidateFeatures(
                    candidate("3", "Eminem - Recovery", "Eminem", null, "PT4M")
            );
            assertThat(c3.versionEvidence().isCover()).isFalse();
        }

        @Test
        @DisplayName("should not false positive on legitimate song titles containing 'Reaction'")
        void shouldNotFalsePositiveOnReactionSongTitles() {
            CandidateTrackFeatures c1 = extractor.extractCandidateFeatures(
                    candidate("1", "Diana Ross - Chain Reaction", "Diana Ross", null, "PT4M")
            );
            assertThat(c1.versionEvidence().isReaction()).isFalse();

            CandidateTrackFeatures c2 = extractor.extractCandidateFeatures(
                    candidate("2", "Chemical Reaction", "Band", null, "PT3M")
            );
            assertThat(c2.versionEvidence().isReaction()).isFalse();
        }
    }

    @Nested
    @DisplayName("Multiple, Conflicting, and Ambiguous Indicators")
    class MultipleAndConflictingIndicators {

        @Test
        @DisplayName("should capture multiple indicators without loss of information")
        void shouldCaptureMultipleIndicators() {
            CandidateTrackFeatures c = extractor.extractCandidateFeatures(
                    candidate("1", "Song Title (Live Acoustic Cover)", "Artist", null, "PT4M")
            );

            assertThat(c.versionEvidence().isLive()).isTrue();
            assertThat(c.versionEvidence().isAcoustic()).isTrue();
            assertThat(c.versionEvidence().isCover()).isTrue();
            assertThat(c.versionEvidence().indicators()).contains(
                    VersionIndicator.LIVE,
                    VersionIndicator.ACOUSTIC,
                    VersionIndicator.COVER
            );
        }

        @Test
        @DisplayName("should detect conflicting indicators when mutually exclusive modifiers coexist")
        void shouldDetectConflictingIndicators() {
            CandidateTrackFeatures c = extractor.extractCandidateFeatures(
                    candidate("1", "Song Title (Sped Up / Slowed Down)", "Channel", null, "PT3M")
            );

            assertThat(c.versionEvidence().isSpedUp()).isTrue();
            assertThat(c.versionEvidence().isSlowed()).isTrue();
            assertThat(c.versionEvidence().hasConflictingIndicators()).isTrue();
            assertThat(c.versionEvidence().hasAmbiguity()).isTrue();
        }
    }

    @Nested
    @DisplayName("Official Provenance Evidence")
    class OfficialProvenanceEvidence {

        @Test
        @DisplayName("should extract official evidence for topic channels and VEVO")
        void shouldExtractOfficialEvidenceForTopicAndVevo() {
            NormalizedTrackMetadata source = normalize("Get Lucky", "Daft Punk", "RAM", 248000L);

            CandidateTrackFeatures c1 = extractor.extractCandidateFeatures(
                    candidate("1", "Get Lucky", "Daft Punk - Topic", "Provided to YouTube by...", "PT4M8S"),
                    source
            );
            assertThat(c1.officialSignal().isTopicChannel()).isTrue();
            assertThat(c1.officialSignal().isAutoGeneratedRelease()).isTrue();
            assertThat(c1.officialSignal().hasOfficialEvidence()).isTrue();

            CandidateTrackFeatures c2 = extractor.extractCandidateFeatures(
                    candidate("2", "Levitating", "DuaLipaVEVO", "", "PT3M23S"),
                    normalize("Levitating", "Dua Lipa", "FN", 203000L)
            );
            assertThat(c2.officialSignal().isVevoChannel()).isTrue();
            assertThat(c2.officialSignal().hasOfficialEvidence()).isTrue();
        }

        @Test
        @DisplayName("channel name containing 'official' is recorded conservatively and DOES NOT confirm authenticity alone")
        void channelClaimingOfficialDoesNotConfirmAuthenticityAlone() {
            NormalizedTrackMetadata source = normalize("Song", "Band", "Album", 200000L);

            CandidateTrackFeatures c = extractor.extractCandidateFeatures(
                    candidate("1", "Song Title", "Band Official Fan Community", "Fan edits", "PT3M20S"),
                    source
            );

            assertThat(c.officialSignal().channelClaimsOfficial()).isTrue();
            // Critical rule: channelClaimsOfficial alone MUST NOT confirm authenticity!
            assertThat(c.officialSignal().hasOfficialEvidence()).isFalse();
        }
    }

    @Nested
    @DisplayName("Batch Extraction and Determinism")
    class BatchExtractionAndDeterminism {

        @Test
        @DisplayName("batch extraction should process candidates in encounter order and filter invalid IDs")
        void batchExtractionShouldFilterInvalidIds() {
            DestinationTrackCandidate c1 = candidate("id-1", "Song 1", "Artist", null, "PT3M");
            DestinationTrackCandidate c2 = candidate("   ", "Blank ID", "Artist", null, "PT3M");
            DestinationTrackCandidate c3 = candidate("id-3", "Song 3", "Artist", null, "PT4M");

            List<CandidateTrackFeatures> batch = extractor.extractCandidateFeatures(
                    List.of(c1, c2, c3),
                    normalize("Song", "Artist", "Album", 200000L)
            );

            assertThat(batch).hasSize(2);
            assertThat(batch.get(0).candidateId()).isEqualTo("id-1");
            assertThat(batch.get(1).candidateId()).isEqualTo("id-3");
        }

        @Test
        @DisplayName("extractor should be strictly deterministic across repeated invocations")
        void extractorShouldBeDeterministic() {
            NormalizedTrackMetadata source = normalize("Song", "Artist", "Album", 200000L);
            DestinationTrackCandidate c = candidate("id-1", "Song (Remix)", "Artist - Topic", "Description", "PT3M");

            CandidateTrackFeatures f1 = extractor.extractCandidateFeatures(c, source);
            CandidateTrackFeatures f2 = extractor.extractCandidateFeatures(c, source);

            assertThat(f1).isEqualTo(f2);
            assertThat(f1.hashCode()).isEqualTo(f2.hashCode());
        }
    }
}
