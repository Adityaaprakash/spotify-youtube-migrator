package com.spotifyyoutube.migrator.migration.execution.domain;

import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationContext;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedTrack;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewDecisionSource;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Phase 11G: ExecutionTrackRecord Reconciliation Domain Transitions Test")
class ExecutionTrackRecordReconciliationTest {

    private MigrationExecution execution;
    private ExecutionTrackRecord record;

    @BeforeEach
    void setUp() {
        User user = new User("user@example.com");
        user.setId(UUID.randomUUID());
        user.setDisplayName("User");
        ReviewSession session = new ReviewSession(user, "sp_pl_1", "Source Playlist", UUID.randomUUID());
        session.setId(UUID.randomUUID());
        ApprovedTrack approvedTrack = new ApprovedTrack(
                0,
                "sp_track_1",
                "Song Title",
                "Artist Name",
                "yt_vid_1",
                "YT Title",
                "YT Channel",
                Duration.ofSeconds(210),
                ReviewDecisionSource.AUTOMATIC,
                0
        );
        ApprovedMigrationContext context = new ApprovedMigrationContext(
                session.getId(),
                UUID.randomUUID(),
                user.getId(),
                "sp_pl_1",
                "Source Playlist",
                "Target Playlist",
                "Description",
                false,
                List.of(approvedTrack),
                1,
                1,
                0,
                Instant.now()
        );
        execution = new MigrationExecution(user, session, context, "PL_dest_123", "https://youtube.com/playlist?list=PL_dest_123");
        record = execution.getTrackRecords().get(0);
    }

    @Nested
    @DisplayName("Insertion Reconciliation: Confirmed Added")
    class ConfirmedAdded {

        @Test
        @DisplayName("Reconciles UNCERTAIN to ADDED with verified playlist item ID")
        void reconcilesUncertainToAdded() {
            record.markAdding();
            record.markUncertain("Timed out");
            assertThat(record.getStatus()).isEqualTo(ExecutionTrackStatus.UNCERTAIN);

            record.reconcileConfirmedAdded("item_verified_99", "Verified in remote inspection");

            assertThat(record.getStatus()).isEqualTo(ExecutionTrackStatus.ADDED);
            assertThat(record.getDestinationPlaylistItemId()).isEqualTo("item_verified_99");
            assertThat(record.getErrorMessage()).isNull();
            assertThat(record.getCompletedAt()).isNotNull();
        }

        @Test
        @DisplayName("Reconciles stale in-flight ADDING to ADDED directly")
        void reconcilesStaleAddingToAdded() {
            record.markAdding();
            assertThat(record.getStatus()).isEqualTo(ExecutionTrackStatus.ADDING);

            record.reconcileConfirmedAdded("item_stale_recovered", "Verified present");

            assertThat(record.getStatus()).isEqualTo(ExecutionTrackStatus.ADDED);
            assertThat(record.getDestinationPlaylistItemId()).isEqualTo("item_stale_recovered");
        }

        @Test
        @DisplayName("Rejects blank playlist item ID")
        void rejectsBlankPlaylistItemId() {
            record.markAdding();
            record.markUncertain("Timed out");

            assertThatThrownBy(() -> record.reconcileConfirmedAdded(null, "reason"))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> record.reconcileConfirmedAdded("   ", "reason"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Rejects transition from illegal statuses (PENDING, FAILED, SKIPPED, ROLLED_BACK)")
        void rejectsIllegalStatuses() {
            assertThat(record.getStatus()).isEqualTo(ExecutionTrackStatus.PENDING);
            assertThatThrownBy(() -> record.reconcileConfirmedAdded("item_1", "reason"))
                    .isInstanceOf(InvalidStateException.class);
        }
    }

    @Nested
    @DisplayName("Insertion Reconciliation: Confirmed Not Inserted")
    class ConfirmedNotInserted {

        @Test
        @DisplayName("Reconciles UNCERTAIN to FAILED for safe subsequent Phase 11F recovery")
        void reconcilesUncertainToFailed() {
            record.markAdding();
            record.markUncertain("Socket dropped");

            record.reconcileConfirmedNotInserted("Confirmed absent from remote playlist");

            assertThat(record.getStatus()).isEqualTo(ExecutionTrackStatus.FAILED);
            assertThat(record.getErrorMessage()).isEqualTo("Confirmed absent from remote playlist");
            assertThat(record.getCompletedAt()).isNotNull();
        }

        @Test
        @DisplayName("Reconciles stale in-flight ADDING to FAILED")
        void reconcilesStaleAddingToFailed() {
            record.markAdding();

            record.reconcileConfirmedNotInserted("Confirmed absent");

            assertThat(record.getStatus()).isEqualTo(ExecutionTrackStatus.FAILED);
        }

        @Test
        @DisplayName("Rejects transition from non-uncertain/non-adding status")
        void rejectsFromSettledStatus() {
            record.markSkipped("Preceding track failed");

            assertThatThrownBy(() -> record.reconcileConfirmedNotInserted("reason"))
                    .isInstanceOf(InvalidStateException.class);
        }
    }

    @Nested
    @DisplayName("Deletion Reconciliation: Confirmed Deleted")
    class ConfirmedDeleted {

        @Test
        @DisplayName("Reconciles UNCERTAIN to ROLLED_BACK when item is confirmed absent")
        void reconcilesUncertainToRolledBack() {
            record.markAdding();
            record.markAdded("item_to_delete");
            record.markRollingBack();
            record.markUncertain("Delete timed out");

            record.reconcileConfirmedDeleted("Item absent from remote playlist");

            assertThat(record.getStatus()).isEqualTo(ExecutionTrackStatus.ROLLED_BACK);
            assertThat(record.getErrorMessage()).isEqualTo("Item absent from remote playlist");
            assertThat(record.getCompletedAt()).isNotNull();
        }

        @Test
        @DisplayName("Reconciles stale ROLLING_BACK directly to ROLLED_BACK")
        void reconcilesStaleRollingBackToRolledBack() {
            record.markAdding();
            record.markAdded("item_to_delete");
            record.markRollingBack();

            record.reconcileConfirmedDeleted("Confirmed absent");

            assertThat(record.getStatus()).isEqualTo(ExecutionTrackStatus.ROLLED_BACK);
        }

        @Test
        @DisplayName("Rejects transition from PENDING")
        void rejectsFromPending() {
            assertThatThrownBy(() -> record.reconcileConfirmedDeleted("reason"))
                    .isInstanceOf(InvalidStateException.class);
        }
    }

    @Nested
    @DisplayName("Deletion Reconciliation: Confirmed Present")
    class ConfirmedPresent {

        @Test
        @DisplayName("Restores UNCERTAIN back to ADDED when deletion was not applied")
        void restoresUncertainToAdded() {
            record.markAdding();
            record.markAdded("item_still_there");
            record.markRollingBack();
            record.markUncertain("Delete connection reset");

            record.reconcileConfirmedPresent("Item confirmed still present");

            assertThat(record.getStatus()).isEqualTo(ExecutionTrackStatus.ADDED);
            assertThat(record.getDestinationPlaylistItemId()).isEqualTo("item_still_there");
            assertThat(record.getErrorMessage()).isEqualTo("Item confirmed still present");
        }

        @Test
        @DisplayName("Restores stale ROLLING_BACK back to ADDED")
        void restoresStaleRollingBackToAdded() {
            record.markAdding();
            record.markAdded("item_still_there");
            record.markRollingBack();

            record.reconcileConfirmedPresent("Item confirmed present");

            assertThat(record.getStatus()).isEqualTo(ExecutionTrackStatus.ADDED);
        }
    }

    @Nested
    @DisplayName("Ambiguity Preservation")
    class AmbiguityPreservation {

        @Test
        @DisplayName("reconcileAmbiguous preserves UNCERTAIN status and records reason")
        void preservesUncertainOnAmbiguity() {
            record.markAdding();
            record.markUncertain("Original timeout");

            record.reconcileAmbiguous("Multiple matching items found; inconclusive");

            assertThat(record.getStatus()).isEqualTo(ExecutionTrackStatus.UNCERTAIN);
            assertThat(record.getErrorMessage()).isEqualTo("Multiple matching items found; inconclusive");
        }
    }
}
