package com.spotifyyoutube.migrator.migration.application.execution.contract;

import com.spotifyyoutube.migrator.playlist.domain.Platform;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Phase 11C: Destination Playlist Creation Result Contract Test")
class DestinationPlaylistCreationResultTest {

    private final UUID sessionId = UUID.randomUUID();
    private final UUID planId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final Instant now = Instant.now();

    @Test
    @DisplayName("Valid result instantiation populates all accessors correctly")
    void validInstantiation() {
        DestinationPlaylistCreationResult result = new DestinationPlaylistCreationResult(
                sessionId,
                planId,
                userId,
                Platform.YOUTUBE,
                "PL_test_12345",
                "https://www.youtube.com/playlist?list=PL_test_12345",
                "My Migrated Playlist",
                "Created by Spotify-to-YouTube migrator",
                true,
                25,
                now
        );

        assertThat(result.reviewSessionId()).isEqualTo(sessionId);
        assertThat(result.getPlanId()).contains(planId);
        assertThat(result.userId()).isEqualTo(userId);
        assertThat(result.destinationPlatform()).isEqualTo(Platform.YOUTUBE);
        assertThat(result.destinationPlaylistId()).isEqualTo("PL_test_12345");
        assertThat(result.getDestinationPlaylistUrl()).contains("https://www.youtube.com/playlist?list=PL_test_12345");
        assertThat(result.playlistTitle()).isEqualTo("My Migrated Playlist");
        assertThat(result.getPlaylistDescription()).contains("Created by Spotify-to-YouTube migrator");
        assertThat(result.isPrivate()).isTrue();
        assertThat(result.approvedTrackCount()).isEqualTo(25);
        assertThat(result.createdAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("Result allows null planId, url, and description via Optionals")
    void nullableFields() {
        DestinationPlaylistCreationResult result = new DestinationPlaylistCreationResult(
                sessionId,
                null,
                userId,
                Platform.YOUTUBE,
                "PL_test_12345",
                null,
                "My Playlist",
                null,
                false,
                1,
                now
        );

        assertThat(result.getPlanId()).isEmpty();
        assertThat(result.getDestinationPlaylistUrl()).isEmpty();
        assertThat(result.getPlaylistDescription()).isEmpty();
        assertThat(result.isPrivate()).isFalse();
    }

    @Test
    @DisplayName("Rejects null reviewSessionId")
    void rejectsNullReviewSessionId() {
        assertThatThrownBy(() -> new DestinationPlaylistCreationResult(
                null, planId, userId, Platform.YOUTUBE, "PL_123", "http://yt.com", "Title", null, true, 5, now
        )).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("reviewSessionId");
    }

    @Test
    @DisplayName("Rejects null userId")
    void rejectsNullUserId() {
        assertThatThrownBy(() -> new DestinationPlaylistCreationResult(
                sessionId, planId, null, Platform.YOUTUBE, "PL_123", "http://yt.com", "Title", null, true, 5, now
        )).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("userId");
    }

    @Test
    @DisplayName("Rejects null destinationPlatform")
    void rejectsNullPlatform() {
        assertThatThrownBy(() -> new DestinationPlaylistCreationResult(
                sessionId, planId, userId, null, "PL_123", "http://yt.com", "Title", null, true, 5, now
        )).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("destinationPlatform");
    }

    @Test
    @DisplayName("Rejects null or blank destinationPlaylistId")
    void rejectsInvalidPlaylistId() {
        assertThatThrownBy(() -> new DestinationPlaylistCreationResult(
                sessionId, planId, userId, Platform.YOUTUBE, null, "http://yt.com", "Title", null, true, 5, now
        )).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("destinationPlaylistId");

        assertThatThrownBy(() -> new DestinationPlaylistCreationResult(
                sessionId, planId, userId, Platform.YOUTUBE, "   ", "http://yt.com", "Title", null, true, 5, now
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("destinationPlaylistId must not be blank");
    }

    @Test
    @DisplayName("Rejects null or blank playlistTitle")
    void rejectsInvalidTitle() {
        assertThatThrownBy(() -> new DestinationPlaylistCreationResult(
                sessionId, planId, userId, Platform.YOUTUBE, "PL_123", "http://yt.com", null, null, true, 5, now
        )).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("playlistTitle");

        assertThatThrownBy(() -> new DestinationPlaylistCreationResult(
                sessionId, planId, userId, Platform.YOUTUBE, "PL_123", "http://yt.com", "   ", null, true, 5, now
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("playlistTitle must not be blank");
    }

    @Test
    @DisplayName("Rejects non-positive approvedTrackCount")
    void rejectsNonPositiveTrackCount() {
        assertThatThrownBy(() -> new DestinationPlaylistCreationResult(
                sessionId, planId, userId, Platform.YOUTUBE, "PL_123", "http://yt.com", "Title", null, true, 0, now
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("approvedTrackCount must be positive: 0");

        assertThatThrownBy(() -> new DestinationPlaylistCreationResult(
                sessionId, planId, userId, Platform.YOUTUBE, "PL_123", "http://yt.com", "Title", null, true, -5, now
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("approvedTrackCount must be positive: -5");
    }

    @Test
    @DisplayName("Rejects null createdAt timestamp")
    void rejectsNullCreatedAt() {
        assertThatThrownBy(() -> new DestinationPlaylistCreationResult(
                sessionId, planId, userId, Platform.YOUTUBE, "PL_123", "http://yt.com", "Title", null, true, 5, null
        )).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("createdAt");
    }
}
