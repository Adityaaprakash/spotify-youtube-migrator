package com.spotifyyoutube.migrator.migration.reliability.reconciliation;

import com.spotifyyoutube.migrator.migration.application.destination.MusicDestination;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationPlaylistItemDetails;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationPlaylistInspection;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedTrack;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackRecord;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecution;
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

@DisplayName("TrackInsertionReconciliationService Unit Tests (Phase 13F)")
class TrackInsertionReconciliationServiceTest {

    private MusicDestination musicDestination;
    private TrackInsertionReconciliationService reconciliationService;
    private final MigrationExecution mockExecution = Mockito.mock(MigrationExecution.class);

    private final UUID userId = UUID.randomUUID();
    private final String playlistId = "test_playlist_123";

    @BeforeEach
    void setUp() {
        musicDestination = Mockito.mock(MusicDestination.class);
        reconciliationService = new TrackInsertionReconciliationService(musicDestination);
        reconciliationService.setGracePeriod(Duration.ofMillis(10)); // fast test duration
    }

    private ExecutionTrackRecord createTrack(UUID id, int position, String videoId) {
        ApprovedTrack approvedTrack = new ApprovedTrack(
                "spotify-" + position,
                position,
                position,
                "Title " + position,
                "Artist " + position,
                "album-art",
                videoId,
                "confidence-high",
                "custom",
                "source-custom"
        );
        ExecutionTrackRecord record = new ExecutionTrackRecord(mockExecution, approvedTrack);
        record.setId(id);
        return record;
    }

    @Nested
    @DisplayName("Stage 2: First Read Verification")
    class FirstReadTests {

        @Test
        @DisplayName("Returns VERIFIED_ADDED on first read when remote item matches")
        void verifiedAddedOnFirstRead() {
            UUID trackId = UUID.randomUUID();
            ExecutionTrackRecord track = createTrack(trackId, 0, "video_1");
            track.markAdding();

            DestinationPlaylistInspection inspection = DestinationPlaylistInspection.success(
                    playlistId,
                    List.of(new DestinationPlaylistItemDetails("item_1", "video_1", 0)),
                    1
            );

            when(musicDestination.inspectPlaylist(eq(userId), eq(playlistId)))
                    .thenReturn(inspection);

            TrackInsertionReconciliationResult result = reconciliationService.reconcileTrackInsertion(
                    userId, playlistId, trackId, List.of(track), () -> false
            );

            assertThat(result.isVerifiedAdded()).isTrue();
            assertThat(result.verifiedPlaylistItemId()).isEqualTo("item_1");
            assertThat(result.outcome()).isEqualTo(TrackInsertionReconciliationOutcome.VERIFIED_ADDED);
            // Exactly 1 inspection call
            verify(musicDestination, times(1)).inspectPlaylist(eq(userId), eq(playlistId));
        }

        @Test
        @DisplayName("Returns INTEGRITY_VIOLATION on first read when duplicate remote IDs discovered")
        void integrityViolationOnFirstRead() {
            UUID trackId = UUID.randomUUID();
            ExecutionTrackRecord track = createTrack(trackId, 0, "video_1");
            track.markAdding();

            DestinationPlaylistInspection corrupted = DestinationPlaylistInspection.success(
                    playlistId,
                    List.of(
                            new DestinationPlaylistItemDetails("dup_id", "video_1", 0),
                            new DestinationPlaylistItemDetails("dup_id", "video_2", 1)
                    ),
                    2
            );

            when(musicDestination.inspectPlaylist(eq(userId), eq(playlistId)))
                    .thenReturn(corrupted);

            TrackInsertionReconciliationResult result = reconciliationService.reconcileTrackInsertion(
                    userId, playlistId, trackId, List.of(track), () -> false
            );

            assertThat(result.isIntegrityViolation()).isTrue();
            assertThat(result.outcome()).isEqualTo(TrackInsertionReconciliationOutcome.INTEGRITY_VIOLATION);
            assertThat(result.diagnosticMessage()).contains("Duplicate remote playlist item ID");
        }
    }

    @Nested
    @DisplayName("Stage 4: Second Confirmation Read")
    class SecondReadTests {

        @Test
        @DisplayName("Returns VERIFIED_ADDED on second read after eventual consistency delay")
        void verifiedAddedOnSecondRead() {
            UUID trackId = UUID.randomUUID();
            ExecutionTrackRecord track = createTrack(trackId, 0, "video_delayed");
            track.markAdding();

            DestinationPlaylistInspection emptyInspection = DestinationPlaylistInspection.success(
                    playlistId, Collections.emptyList(), 0
            );
            DestinationPlaylistInspection discoveredInspection = DestinationPlaylistInspection.success(
                    playlistId,
                    List.of(new DestinationPlaylistItemDetails("item_delayed", "video_delayed", 0)),
                    1
            );

            when(musicDestination.inspectPlaylist(eq(userId), eq(playlistId)))
                    .thenReturn(emptyInspection)      // first read: replication lag
                    .thenReturn(discoveredInspection); // second read: item replicated

            TrackInsertionReconciliationResult result = reconciliationService.reconcileTrackInsertion(
                    userId, playlistId, trackId, List.of(track), () -> false
            );

            assertThat(result.isVerifiedAdded()).isTrue();
            assertThat(result.verifiedPlaylistItemId()).isEqualTo("item_delayed");
            assertThat(result.outcome()).isEqualTo(TrackInsertionReconciliationOutcome.VERIFIED_ADDED);
            // Two inspection calls made
            verify(musicDestination, times(2)).inspectPlaylist(eq(userId), eq(playlistId));
        }

        @Test
        @DisplayName("Returns CONFIRMED_ABSENT after two consecutive empty reads")
        void confirmedAbsentAfterTwoReads() {
            UUID trackId = UUID.randomUUID();
            ExecutionTrackRecord track = createTrack(trackId, 0, "video_not_added");
            track.markAdding();

            DestinationPlaylistInspection emptyInspection = DestinationPlaylistInspection.success(
                    playlistId, Collections.emptyList(), 0
            );

            when(musicDestination.inspectPlaylist(eq(userId), eq(playlistId)))
                    .thenReturn(emptyInspection)
                    .thenReturn(emptyInspection);

            TrackInsertionReconciliationResult result = reconciliationService.reconcileTrackInsertion(
                    userId, playlistId, trackId, List.of(track), () -> false
            );

            assertThat(result.isConfirmedAbsent()).isTrue();
            assertThat(result.outcome()).isEqualTo(TrackInsertionReconciliationOutcome.CONFIRMED_ABSENT);
            assertThat(result.verifiedPlaylistItemId()).isNull();
            verify(musicDestination, times(2)).inspectPlaylist(eq(userId), eq(playlistId));
        }
    }

    @Nested
    @DisplayName("Inspection Failure & Cancellation Handling")
    class FailureAndCancellationTests {

        @Test
        @DisplayName("Returns INSPECTION_FAILED when cancellation detected during grace period")
        void inspectionFailedOnCancellation() {
            UUID trackId = UUID.randomUUID();
            ExecutionTrackRecord track = createTrack(trackId, 0, "video_1");

            TrackInsertionReconciliationResult result = reconciliationService.reconcileTrackInsertion(
                    userId, playlistId, trackId, List.of(track), () -> true // cancelled
            );

            assertThat(result.outcome()).isEqualTo(TrackInsertionReconciliationOutcome.INSPECTION_FAILED);
            assertThat(result.diagnosticMessage()).contains("cancelled during initial grace period");
        }

        @Test
        @DisplayName("Returns INSPECTION_FAILED when destination provider inspection fails")
        void inspectionFailedOnProviderError() {
            UUID trackId = UUID.randomUUID();
            ExecutionTrackRecord track = createTrack(trackId, 0, "video_1");

            DestinationPlaylistInspection failedInspection = DestinationPlaylistInspection.failure(
                    playlistId, "YouTube 500 Internal Server Error"
            );

            when(musicDestination.inspectPlaylist(eq(userId), eq(playlistId)))
                    .thenReturn(failedInspection);

            TrackInsertionReconciliationResult result = reconciliationService.reconcileTrackInsertion(
                    userId, playlistId, trackId, List.of(track), () -> false
            );

            assertThat(result.outcome()).isEqualTo(TrackInsertionReconciliationOutcome.INSPECTION_FAILED);
            assertThat(result.diagnosticMessage()).contains("YouTube 500 Internal Server Error");
        }
    }
}
