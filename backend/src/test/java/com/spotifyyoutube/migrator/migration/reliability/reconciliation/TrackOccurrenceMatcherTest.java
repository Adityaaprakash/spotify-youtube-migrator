package com.spotifyyoutube.migrator.migration.reliability.reconciliation;

import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationPlaylistItemDetails;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedTrack;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackRecord;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecution;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TrackOccurrenceMatcher Unit Tests (Phase 13F)")
class TrackOccurrenceMatcherTest {

    private final MigrationExecution mockExecution = Mockito.mock(MigrationExecution.class);

    private ExecutionTrackRecord createTrack(UUID id, int position, String videoId) {
        ApprovedTrack approvedTrack = new ApprovedTrack(
                "spotify-track-" + position,
                position,
                position,
                "Track " + position,
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
    @DisplayName("Single Track Matching")
    class SingleTrackTests {

        @Test
        @DisplayName("Track is confirmed absent when remote list is empty")
        void trackConfirmedAbsentWhenRemoteEmpty() {
            UUID trackId = UUID.randomUUID();
            ExecutionTrackRecord track = createTrack(trackId, 0, "video_123");
            track.markAdding();

            TrackOccurrenceMatchResult result = TrackOccurrenceMatcher.matchTrack(
                    List.of(track),
                    Collections.emptyList(),
                    trackId
            );

            assertThat(result.isConfirmedAbsent()).isTrue();
            assertThat(result.isVerifiedAdded()).isFalse();
            assertThat(result.isIntegrityViolation()).isFalse();
            assertThat(result.verifiedPlaylistItemId()).isNull();
        }

        @Test
        @DisplayName("Track is verified added when remote item is present")
        void trackVerifiedAddedWhenRemoteMatches() {
            UUID trackId = UUID.randomUUID();
            ExecutionTrackRecord track = createTrack(trackId, 0, "video_123");
            track.markAdding();

            DestinationPlaylistItemDetails remoteItem = new DestinationPlaylistItemDetails(
                    "item_abc", "video_123", 0
            );

            TrackOccurrenceMatchResult result = TrackOccurrenceMatcher.matchTrack(
                    List.of(track),
                    List.of(remoteItem),
                    trackId
            );

            assertThat(result.isVerifiedAdded()).isTrue();
            assertThat(result.verifiedPlaylistItemId()).isEqualTo("item_abc");
            assertThat(result.isConfirmedAbsent()).isFalse();
            assertThat(result.isIntegrityViolation()).isFalse();
        }
    }

    @Nested
    @DisplayName("Intentional Duplicates Preservation (1-to-1 Mapping)")
    class IntentionalDuplicatesTests {

        @Test
        @DisplayName("Preserves intentional duplicate tracks: first occurrence added, second absent")
        void duplicateTracksFirstAddedSecondAbsent() {
            // Source playlist has the same track twice: pos 0 and pos 1 both have video_A
            UUID track1Id = UUID.randomUUID();
            UUID track2Id = UUID.randomUUID();

            ExecutionTrackRecord track1 = createTrack(track1Id, 0, "video_A");
            track1.markAdding();
            track1.markAdded("remote_item_1");

            ExecutionTrackRecord track2 = createTrack(track2Id, 1, "video_A");
            track2.markAdding(); // ambiguous attempt for second occurrence

            List<ExecutionTrackRecord> allTracks = List.of(track1, track2);

            // Remote playlist currently has only 1 occurrence of video_A (the first one)
            List<DestinationPlaylistItemDetails> remoteItems = List.of(
                    new DestinationPlaylistItemDetails("remote_item_1", "video_A", 0)
            );

            // Match second occurrence: should be confirmed absent because occurrence 2 is not present remotely
            TrackOccurrenceMatchResult result = TrackOccurrenceMatcher.matchTrack(allTracks, remoteItems, track2Id);

            assertThat(result.isConfirmedAbsent()).isTrue();
            assertThat(result.isVerifiedAdded()).isFalse();
            assertThat(result.isIntegrityViolation()).isFalse();
        }

        @Test
        @DisplayName("Preserves intentional duplicate tracks: second occurrence verified added")
        void duplicateTracksSecondAddedSuccessfully() {
            UUID track1Id = UUID.randomUUID();
            UUID track2Id = UUID.randomUUID();

            ExecutionTrackRecord track1 = createTrack(track1Id, 0, "video_A");
            track1.markAdding();
            track1.markAdded("remote_item_1");

            ExecutionTrackRecord track2 = createTrack(track2Id, 1, "video_A");
            track2.markAdding();

            List<ExecutionTrackRecord> allTracks = List.of(track1, track2);

            // Remote playlist has 2 occurrences of video_A
            List<DestinationPlaylistItemDetails> remoteItems = List.of(
                    new DestinationPlaylistItemDetails("remote_item_1", "video_A", 0),
                    new DestinationPlaylistItemDetails("remote_item_2", "video_A", 1)
            );

            TrackOccurrenceMatchResult result = TrackOccurrenceMatcher.matchTrack(allTracks, remoteItems, track2Id);

            assertThat(result.isVerifiedAdded()).isTrue();
            assertThat(result.verifiedPlaylistItemId()).isEqualTo("remote_item_2");
            assertThat(result.isConfirmedAbsent()).isFalse();
            assertThat(result.isIntegrityViolation()).isFalse();
        }

        @Test
        @DisplayName("Preserves intentional duplicate tracks: 3 occurrences sequentially mapped")
        void threeOccurrencesSequentialMapping() {
            UUID id1 = UUID.randomUUID();
            UUID id2 = UUID.randomUUID();
            UUID id3 = UUID.randomUUID();

            ExecutionTrackRecord t1 = createTrack(id1, 0, "video_X");
            t1.markAdding();
            t1.markAdded("item_1");

            ExecutionTrackRecord t2 = createTrack(id2, 1, "video_X");
            t2.markAdding();
            t2.markAdded("item_2");

            ExecutionTrackRecord t3 = createTrack(id3, 2, "video_X");
            t3.markAdding(); // ambiguous attempt for 3rd occurrence

            List<ExecutionTrackRecord> all = List.of(t1, t2, t3);

            // Remote has 3 items
            List<DestinationPlaylistItemDetails> remote = List.of(
                    new DestinationPlaylistItemDetails("item_1", "video_X", 0),
                    new DestinationPlaylistItemDetails("item_2", "video_X", 1),
                    new DestinationPlaylistItemDetails("item_3", "video_X", 2)
            );

            TrackOccurrenceMatchResult res3 = TrackOccurrenceMatcher.matchTrack(all, remote, id3);
            assertThat(res3.isVerifiedAdded()).isTrue();
            assertThat(res3.verifiedPlaylistItemId()).isEqualTo("item_3");
        }
    }

    @Nested
    @DisplayName("Integrity Violation Guards")
    class IntegrityViolationTests {

        @Test
        @DisplayName("Fails closed with integrity violation on duplicate remote playlist item IDs")
        void duplicateRemoteItemIdsViolation() {
            UUID trackId = UUID.randomUUID();
            ExecutionTrackRecord track = createTrack(trackId, 0, "video_123");
            track.markAdding();

            List<DestinationPlaylistItemDetails> corruptedRemote = List.of(
                    new DestinationPlaylistItemDetails("duplicate_id", "video_123", 0),
                    new DestinationPlaylistItemDetails("duplicate_id", "video_456", 1)
            );

            TrackOccurrenceMatchResult result = TrackOccurrenceMatcher.matchTrack(
                    List.of(track),
                    corruptedRemote,
                    trackId
            );

            assertThat(result.isIntegrityViolation()).isTrue();
            assertThat(result.message()).contains("Duplicate remote playlist item ID");
        }

        @Test
        @DisplayName("Fails closed with integrity violation when extra unexpected remote occurrences exist")
        void extraRemoteOccurrencesViolation() {
            // Source only has 1 occurrence of video_A dispatched
            UUID trackId = UUID.randomUUID();
            ExecutionTrackRecord track = createTrack(trackId, 0, "video_A");
            track.markAdding();

            // But remote unexpectedly contains 2 occurrences of video_A
            List<DestinationPlaylistItemDetails> remote = List.of(
                    new DestinationPlaylistItemDetails("item_1", "video_A", 0),
                    new DestinationPlaylistItemDetails("item_2", "video_A", 1)
            );

            TrackOccurrenceMatchResult result = TrackOccurrenceMatcher.matchTrack(
                    List.of(track),
                    remote,
                    trackId
            );

            assertThat(result.isIntegrityViolation()).isTrue();
            assertThat(result.message()).contains("Observed 2 remote occurrences of video video_A, but only 1 were dispatched");
        }

        @Test
        @DisplayName("Fails closed when remote item ID was already claimed by a prior track")
        void remoteItemAlreadyClaimedViolation() {
            UUID track1Id = UUID.randomUUID();
            UUID track2Id = UUID.randomUUID();

            ExecutionTrackRecord track1 = createTrack(track1Id, 0, "video_A");
            track1.markAdding();
            track1.markAdded("item_1");

            ExecutionTrackRecord track2 = createTrack(track2Id, 1, "video_A");
            track2.markAdding();

            // Remote returns "item_1" at index 1 instead of a new ID
            List<DestinationPlaylistItemDetails> remote = List.of(
                    new DestinationPlaylistItemDetails("item_1", "video_A", 0),
                    new DestinationPlaylistItemDetails("item_1", "video_A", 1) // duplicate ID handled earlier, but if item 0 had different id:
            );

            // If item 0 had "item_0" and track1 erroneously claimed "item_1":
            track1 = createTrack(track1Id, 0, "video_A");
            track1.markAdding();
            track1.markAdded("reused_item");

            track2 = createTrack(track2Id, 1, "video_A");
            track2.markAdding();

            List<DestinationPlaylistItemDetails> remote2 = List.of(
                    new DestinationPlaylistItemDetails("item_alpha", "video_A", 0),
                    new DestinationPlaylistItemDetails("reused_item", "video_A", 1)
            );

            TrackOccurrenceMatchResult result = TrackOccurrenceMatcher.matchTrack(
                    List.of(track1, track2),
                    remote2,
                    track2Id
            );

            assertThat(result.isIntegrityViolation()).isTrue();
            assertThat(result.message()).contains("already assigned");
        }

        @Test
        @DisplayName("Returns integrity violation if target track ID is not found in source tracks")
        void targetTrackNotFoundViolation() {
            UUID trackId = UUID.randomUUID();
            UUID nonExistent = UUID.randomUUID();
            ExecutionTrackRecord track = createTrack(trackId, 0, "video_A");

            TrackOccurrenceMatchResult result = TrackOccurrenceMatcher.matchTrack(
                    List.of(track),
                    Collections.emptyList(),
                    nonExistent
            );

            assertThat(result.isIntegrityViolation()).isTrue();
            assertThat(result.message()).contains("not found in execution track list");
        }
    }
}
