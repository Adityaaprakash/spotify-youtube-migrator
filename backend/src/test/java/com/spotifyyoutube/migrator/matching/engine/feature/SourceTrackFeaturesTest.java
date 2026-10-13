package com.spotifyyoutube.migrator.matching.engine.feature;

import com.spotifyyoutube.migrator.matching.normalization.NormalizedArtistCredit;
import com.spotifyyoutube.migrator.matching.normalization.NormalizedTrackMetadata;
import com.spotifyyoutube.migrator.matching.normalization.RawTrackMetadata;
import com.spotifyyoutube.migrator.matching.normalization.TokenizedTitle;
import com.spotifyyoutube.migrator.matching.normalization.VariantType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SourceTrackFeatures Tests")
class SourceTrackFeaturesTest {

    private NormalizedTrackMetadata sampleSourceTrack(
            String title,
            String artist,
            String album,
            Duration duration,
            Set<VariantType> variants
    ) {
        return new NormalizedTrackMetadata(
                RawTrackMetadata.of(title, artist, album, duration),
                title.toLowerCase(),
                new NormalizedArtistCredit(artist.toLowerCase(), List.of()),
                List.of(artist.toLowerCase()),
                List.of(),
                album != null ? album.toLowerCase() : "",
                duration,
                new TokenizedTitle(title, title.toLowerCase(), false, variants != null ? variants : Set.of(), null, null, Set.of(), List.of(), List.of(), List.of())
        );
    }

    @Nested
    @DisplayName("Metadata Preservation")
    class MetadataPreservation {

        @Test
        @DisplayName("should accurately extract and preserve all metadata and indicators")
        void shouldPreserveAllMetadata() {
            NormalizedTrackMetadata metadata = sampleSourceTrack(
                    "Hotel California",
                    "Eagles",
                    "Hotel California",
                    Duration.ofSeconds(391),
                    Set.of(VariantType.LIVE, VariantType.REMASTER)
            );

            SourceTrackFeatures features = new SourceTrackFeatures(
                    "spotify:track:abc",
                    metadata.normalizedTitle(),
                    metadata.cleanTitle(),
                    metadata.primaryArtist(),
                    metadata.normalizedArtists(),
                    metadata.featuredArtists(),
                    metadata.normalizedAlbum(),
                    metadata.duration(),
                    391000L,
                    true,
                    true,
                    true,
                    Set.of(VersionIndicator.LIVE, VersionIndicator.REMASTER),
                    false,
                    true,
                    true,
                    false,
                    metadata
            );

            assertThat(features.sourceTrackId()).isEqualTo("spotify:track:abc");
            assertThat(features.normalizedTitle()).isEqualTo("hotel california");
            assertThat(features.cleanTitle()).isEqualTo("hotel california");
            assertThat(features.primaryArtist()).isEqualTo("eagles");
            assertThat(features.normalizedArtists()).containsExactly("eagles");
            assertThat(features.normalizedAlbum()).isEqualTo("hotel california");
            assertThat(features.duration()).isEqualTo(Duration.ofSeconds(391));
            assertThat(features.durationMs()).isEqualTo(391000L);
            assertThat(features.hasAlbum()).isTrue();
            assertThat(features.hasDuration()).isTrue();
            assertThat(features.hasPrimaryArtist()).isTrue();
            assertThat(features.isLive()).isTrue();
            assertThat(features.isRemaster()).isTrue();
            assertThat(features.isAcoustic()).isFalse();
            assertThat(features.versionIndicators()).containsExactlyInAnyOrder(
                    VersionIndicator.LIVE,
                    VersionIndicator.REMASTER
            );
            assertThat(features.sourceMetadata()).isSameAs(metadata);
        }

        @Test
        @DisplayName("should handle missing album, duration, and artist honestly")
        void shouldHandleMissingMetadataHonestly() {
            NormalizedTrackMetadata metadata = sampleSourceTrack(
                    "Unknown Song",
                    "",
                    "",
                    null,
                    Set.of()
            );

            SourceTrackFeatures features = new SourceTrackFeatures(
                    null,
                    metadata.normalizedTitle(),
                    metadata.cleanTitle(),
                    "",
                    List.of(),
                    List.of(),
                    "",
                    null,
                    null,
                    false,
                    false,
                    false,
                    Set.of(),
                    true,
                    false,
                    false,
                    false,
                    metadata
            );

            assertThat(features.sourceTrackId()).isNull();
            assertThat(features.duration()).isNull();
            assertThat(features.durationMs()).isNull();
            assertThat(features.hasDuration()).isFalse();
            assertThat(features.hasAlbum()).isFalse();
            assertThat(features.hasPrimaryArtist()).isFalse();
            assertThat(features.normalizedAlbum()).isEmpty();
            assertThat(features.getDuration()).isEmpty();
            assertThat(features.getDurationMs()).isEmpty();
            assertThat(features.getSourceTrackId()).isEmpty();
        }

        @Test
        @DisplayName("should reject null sourceMetadata with NullPointerException")
        void shouldRejectNullSourceMetadata() {
            assertThatThrownBy(() -> new SourceTrackFeatures(
                    null, "title", "title", "artist", List.of(), List.of(), "",
                    null, null, false, false, true, Set.of(), true, false, false, false, null
            )).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("NormalizedTrackMetadata must not be null");
        }
    }
}
