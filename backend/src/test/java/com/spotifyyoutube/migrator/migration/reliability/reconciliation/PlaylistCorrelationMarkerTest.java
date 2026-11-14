package com.spotifyyoutube.migrator.migration.reliability.reconciliation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PlaylistCorrelationMarker Unit Tests (Phase 13F)")
class PlaylistCorrelationMarkerTest {

    private final UUID testOpId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    @Nested
    @DisplayName("Marker Formatting & Parsing")
    class FormatAndParseTests {

        @Test
        @DisplayName("formatMarker generates deterministic bracketed tag")
        void formatMarkerGeneratesDeterministicTag() {
            String marker = PlaylistCorrelationMarker.formatMarker(testOpId);
            assertThat(marker).isEqualTo("[ref: migrator-op:550e8400-e29b-41d4-a716-446655440000]");
        }

        @Test
        @DisplayName("formatMarker throws on null UUID")
        void formatMarkerThrowsOnNull() {
            assertThatThrownBy(() -> PlaylistCorrelationMarker.formatMarker(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("extractMarker successfully extracts UUID from marked text")
        void extractMarkerSuccess() {
            String text = "My Awesome Playlist\n\n[ref: migrator-op:550e8400-e29b-41d4-a716-446655440000]";
            Optional<UUID> extracted = PlaylistCorrelationMarker.extractMarker(text);

            assertThat(extracted).isPresent().contains(testOpId);
        }

        @Test
        @DisplayName("extractMarker returns empty on text without marker")
        void extractMarkerReturnsEmptyWhenNoMarker() {
            assertThat(PlaylistCorrelationMarker.extractMarker("Regular description without marker")).isEmpty();
            assertThat(PlaylistCorrelationMarker.extractMarker(null)).isEmpty();
            assertThat(PlaylistCorrelationMarker.extractMarker("")).isEmpty();
        }

        @Test
        @DisplayName("extractMarker returns empty on malformed marker")
        void extractMarkerReturnsEmptyOnMalformed() {
            assertThat(PlaylistCorrelationMarker.extractMarker("[ref: migrator-op:not-a-uuid]")).isEmpty();
            assertThat(PlaylistCorrelationMarker.extractMarker("[ref: other-op:550e8400-e29b-41d4-a716-446655440000]")).isEmpty();
            assertThat(PlaylistCorrelationMarker.extractMarker("ref: migrator-op:550e8400-e29b-41d4-a716-446655440000")).isEmpty();
        }
    }

    @Nested
    @DisplayName("Marker Application & Idempotency")
    class ApplyMarkerTests {

        @Test
        @DisplayName("applyMarker appends marker to null or empty base description")
        void applyMarkerToEmptyBase() {
            String appliedNull = PlaylistCorrelationMarker.applyMarker(null, testOpId);
            assertThat(appliedNull).isEqualTo("[ref: migrator-op:550e8400-e29b-41d4-a716-446655440000]");

            String appliedBlank = PlaylistCorrelationMarker.applyMarker("   ", testOpId);
            assertThat(appliedBlank).isEqualTo("[ref: migrator-op:550e8400-e29b-41d4-a716-446655440000]");
        }

        @Test
        @DisplayName("applyMarker preserves existing user description and appends marker")
        void applyMarkerPreservesExistingDescription() {
            String base = "Migrated from Spotify playlist 'Favorites'";
            String result = PlaylistCorrelationMarker.applyMarker(base, testOpId);

            assertThat(result).isEqualTo("Migrated from Spotify playlist 'Favorites'\n\n[ref: migrator-op:550e8400-e29b-41d4-a716-446655440000]");
            assertThat(PlaylistCorrelationMarker.extractMarker(result)).contains(testOpId);
        }

        @Test
        @DisplayName("applyMarker is idempotent when marker already present")
        void applyMarkerIsIdempotent() {
            String base = "My Playlist";
            String first = PlaylistCorrelationMarker.applyMarker(base, testOpId);
            String second = PlaylistCorrelationMarker.applyMarker(first, testOpId);

            assertThat(second).isEqualTo(first);
            List<UUID> markers = PlaylistCorrelationMarker.extractAllMarkers(second);
            assertThat(markers).hasSize(1);
        }

        @Test
        @DisplayName("applyMarker respects max length of 5000 characters")
        void applyMarkerTruncatesToFit5000Chars() {
            String marker = PlaylistCorrelationMarker.formatMarker(testOpId);
            int markerLenWithSeparators = marker.length() + 2; // "\n\n"
            int allowedBaseLen = 5000 - markerLenWithSeparators;

            String hugeBase = "A".repeat(6000);
            String result = PlaylistCorrelationMarker.applyMarker(hugeBase, testOpId);

            assertThat(result.length()).isLessThanOrEqualTo(5000);
            assertThat(result).endsWith(marker);
            assertThat(PlaylistCorrelationMarker.extractMarker(result)).contains(testOpId);
        }
    }

    @Nested
    @DisplayName("Multiple Markers & Matching Guards")
    class MultipleMarkerTests {

        @Test
        @DisplayName("hasSingleMarker returns true for exactly one marker")
        void hasSingleMarkerTrue() {
            String desc = "Some desc\n\n[ref: migrator-op:550e8400-e29b-41d4-a716-446655440000]";
            assertThat(PlaylistCorrelationMarker.hasSingleMarker(desc)).isTrue();
        }

        @Test
        @DisplayName("hasSingleMarker returns false when multiple markers present")
        void hasSingleMarkerFalseOnMultiple() {
            UUID op2 = UUID.randomUUID();
            String desc = "Desc\n\n[ref: migrator-op:" + testOpId + "]\n\n[ref: migrator-op:" + op2 + "]";

            assertThat(PlaylistCorrelationMarker.hasSingleMarker(desc)).isFalse();
            List<UUID> all = PlaylistCorrelationMarker.extractAllMarkers(desc);
            assertThat(all).containsExactly(testOpId, op2);
        }

        @Test
        @DisplayName("matchesOperation returns true only for exact matching UUID")
        void matchesOperationVerification() {
            String desc = "Desc [ref: migrator-op:550e8400-e29b-41d4-a716-446655440000]";
            assertThat(PlaylistCorrelationMarker.matchesOperation(desc, testOpId)).isTrue();

            UUID other = UUID.randomUUID();
            assertThat(PlaylistCorrelationMarker.matchesOperation(desc, other)).isFalse();
        }

        @Test
        @DisplayName("matchesOperation returns false if multiple markers present even if one matches")
        void matchesOperationFalseOnMultipleMarkers() {
            UUID op2 = UUID.randomUUID();
            String desc = "Desc [ref: migrator-op:" + testOpId + "] [ref: migrator-op:" + op2 + "]";
            assertThat(PlaylistCorrelationMarker.matchesOperation(desc, testOpId)).isFalse();
        }
    }
}
