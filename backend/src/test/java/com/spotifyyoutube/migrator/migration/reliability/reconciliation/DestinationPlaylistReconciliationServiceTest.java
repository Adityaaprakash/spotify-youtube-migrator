package com.spotifyyoutube.migrator.migration.reliability.reconciliation;

import com.spotifyyoutube.migrator.common.exception.ExternalProviderException;
import com.spotifyyoutube.migrator.migration.application.destination.MusicDestination;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationPlaylist;
import com.spotifyyoutube.migrator.playlist.domain.Platform;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("DestinationPlaylistReconciliationService Unit Tests (Phase 13F)")
class DestinationPlaylistReconciliationServiceTest {

    private MusicDestination musicDestination;
    private DestinationPlaylistReconciliationService reconciliationService;

    private final UUID userId = UUID.randomUUID();
    private final UUID opId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        musicDestination = Mockito.mock(MusicDestination.class);
        reconciliationService = new DestinationPlaylistReconciliationService(musicDestination);
        // Fast test grace period
        reconciliationService.setVerificationGracePeriod(Duration.ofMillis(10));
    }

    @Nested
    @DisplayName("Case A: Exact Marker Match")
    class CaseAExactMatchTests {

        @Test
        @DisplayName("Returns EXACT_MATCH_FOUND when remote playlist has the correlation marker")
        void exactMatchFound() {
            String marker = PlaylistCorrelationMarker.formatMarker(opId);
            DestinationPlaylist playlist = new DestinationPlaylist(
                    "yt_pl_123",
                    "My Playlist",
                    "Description\n\n" + marker,
                    "https://music.youtube.com/playlist?list=yt_pl_123",
                    Platform.YOUTUBE
            );

            when(musicDestination.getUserPlaylists(eq(userId)))
                    .thenReturn(List.of(playlist));

            PlaylistReconciliationResult result = reconciliationService.reconcilePlaylistCreation(
                    userId, opId, "My Playlist"
            );

            assertThat(result.isExactMatch()).isTrue();
            assertThat(result.outcome()).isEqualTo(PlaylistReconciliationOutcome.EXACT_MATCH_FOUND);
            assertThat(result.matchedPlaylist()).isNotNull();
            assertThat(result.matchedPlaylist().id()).isEqualTo("yt_pl_123");
            // Only 1 inspection read needed when exact match is found
            verify(musicDestination, times(1)).getUserPlaylists(eq(userId));
        }
    }

    @Nested
    @DisplayName("Case B: Conclusive Absence")
    class CaseBConclusiveAbsenceTests {

        @Test
        @DisplayName("Returns CONCLUSIVELY_ABSENT after two reads confirm absence")
        void conclusivelyAbsentAfterTwoReads() {
            when(musicDestination.getUserPlaylists(eq(userId)))
                    .thenReturn(Collections.emptyList()) // first read
                    .thenReturn(Collections.emptyList()); // second confirmation read

            PlaylistReconciliationResult result = reconciliationService.reconcilePlaylistCreation(
                    userId, opId, "Non Existent Playlist"
            );

            assertThat(result.isAbsent()).isTrue();
            assertThat(result.outcome()).isEqualTo(PlaylistReconciliationOutcome.CONCLUSIVELY_ABSENT);
            assertThat(result.matchedPlaylist()).isNull();
            // Verifies two-stage inspection policy
            verify(musicDestination, times(2)).getUserPlaylists(eq(userId));
        }

        @Test
        @DisplayName("Finds playlist on second confirmation read after grace period (eventual consistency)")
        void foundOnSecondReadAfterGracePeriod() {
            String marker = PlaylistCorrelationMarker.formatMarker(opId);
            DestinationPlaylist playlist = new DestinationPlaylist(
                    "yt_pl_delayed",
                    "My Playlist",
                    "Description " + marker,
                    "https://music.youtube.com/playlist?list=yt_pl_delayed",
                    Platform.YOUTUBE
            );

            when(musicDestination.getUserPlaylists(eq(userId)))
                    .thenReturn(Collections.emptyList()) // first read: empty
                    .thenReturn(List.of(playlist)); // second read: discovered!

            PlaylistReconciliationResult result = reconciliationService.reconcilePlaylistCreation(
                    userId, opId, "My Playlist"
            );

            assertThat(result.isExactMatch()).isTrue();
            assertThat(result.outcome()).isEqualTo(PlaylistReconciliationOutcome.EXACT_MATCH_FOUND);
            assertThat(result.matchedPlaylist().id()).isEqualTo("yt_pl_delayed");
            verify(musicDestination, times(2)).getUserPlaylists(eq(userId));
        }
    }

    @Nested
    @DisplayName("Case C: Title Collision")
    class CaseCTitleCollisionTests {

        @Test
        @DisplayName("Returns TITLE_COLLISION when remote playlist has identical title but no marker")
        void titleCollisionDetected() {
            DestinationPlaylist collision = new DestinationPlaylist(
                    "yt_existing_pl",
                    "Workout Hits",
                    "User created playlist without marker",
                    "https://music.youtube.com/playlist?list=yt_existing_pl",
                    Platform.YOUTUBE
            );

            when(musicDestination.getUserPlaylists(eq(userId)))
                    .thenReturn(List.of(collision));

            PlaylistReconciliationResult result = reconciliationService.reconcilePlaylistCreation(
                    userId, opId, "Workout Hits"
            );

            assertThat(result.isCollision()).isTrue();
            assertThat(result.outcome()).isEqualTo(PlaylistReconciliationOutcome.TITLE_COLLISION);
            assertThat(result.conflictingPlaylists()).hasSize(1);
            assertThat(result.conflictingPlaylists().get(0).id()).isEqualTo("yt_existing_pl");
        }
    }

    @Nested
    @DisplayName("Case D: Multiple Matching Markers")
    class CaseDMultipleMarkersTests {

        @Test
        @DisplayName("Returns MULTIPLE_MATCHING_MARKERS when multiple playlists carry the exact same marker")
        void multipleMatchingMarkersDetected() {
            String marker = PlaylistCorrelationMarker.formatMarker(opId);
            DestinationPlaylist pl1 = new DestinationPlaylist(
                    "yt_1", "PL 1", "Desc " + marker, "url1", Platform.YOUTUBE
            );
            DestinationPlaylist pl2 = new DestinationPlaylist(
                    "yt_2", "PL 2", "Desc " + marker, "url2", Platform.YOUTUBE
            );

            when(musicDestination.getUserPlaylists(eq(userId)))
                    .thenReturn(List.of(pl1, pl2));

            PlaylistReconciliationResult result = reconciliationService.reconcilePlaylistCreation(
                    userId, opId, "Any Title"
            );

            assertThat(result.outcome()).isEqualTo(PlaylistReconciliationOutcome.MULTIPLE_MATCHING_MARKERS);
            assertThat(result.conflictingPlaylists()).hasSize(2);
            assertThat(result.diagnosticMessage()).contains("Multiple (2) remote playlists");
        }
    }

    @Nested
    @DisplayName("Case E: Provider Error")
    class CaseEProviderErrorTests {

        @Test
        @DisplayName("Returns PROVIDER_ERROR when musicDestination inspection throws ExternalProviderException")
        void providerErrorHandledGracefully() {
            when(musicDestination.getUserPlaylists(eq(userId)))
                    .thenThrow(new ExternalProviderException("YouTube 503 Service Unavailable"));

            PlaylistReconciliationResult result = reconciliationService.reconcilePlaylistCreation(
                    userId, opId, "My Title"
            );

            assertThat(result.outcome()).isEqualTo(PlaylistReconciliationOutcome.PROVIDER_ERROR);
            assertThat(result.diagnosticMessage()).contains("YouTube 503 Service Unavailable");
        }
    }
}
