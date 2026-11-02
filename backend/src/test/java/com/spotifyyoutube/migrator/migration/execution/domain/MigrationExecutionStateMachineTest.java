package com.spotifyyoutube.migrator.migration.execution.domain;

import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedTrack;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewDecisionSource;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSession;
import com.spotifyyoutube.migrator.playlist.domain.Platform;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Phase 11H: Complete Migration Execution & Track State Machine Audit Test")
class MigrationExecutionStateMachineTest {

    private User testUser;
    private ReviewSession testSession;
    private MigrationExecution execution;

    @BeforeEach
    void setUp() {
        testUser = new User("architect@example.com");
        testUser.setId(UUID.randomUUID());

        testSession = new ReviewSession(UUID.randomUUID(), testUser, "sp_pl_1", "Source Playlist");
        testSession.setId(UUID.randomUUID());

        execution = new MigrationExecution(
                testSession,
                testUser,
                Platform.YOUTUBE.name(),
                "PL_target_yt",
                "https://music.youtube.com/playlist?list=PL_target_yt",
                "Target Playlist",
                UUID.randomUUID()
        );
    }

    private ExecutionTrackRecord createTrackRecord(int targetPos, String sourceTrackId, String ytVideoId) {
        ApprovedTrack approvedTrack = new ApprovedTrack(
                targetPos,
                sourceTrackId,
                "Title " + targetPos,
                "Artist",
                ytVideoId,
                "Title (Audio)",
                "Channel",
                Duration.ofMinutes(3),
                ReviewDecisionSource.CONFIRMED_EXACT,
                targetPos
        );
        return new ExecutionTrackRecord(execution, approvedTrack);
    }

    @Nested
    @DisplayName("Execution Status Transitions and Claim Lifecycle")
    class ExecutionStatusTransitions {

        @Test
        @DisplayName("INITIALIZED -> RUNNING via claimExecution()")
        void initializedToRunning() {
            assertThat(execution.getStatus()).isEqualTo(MigrationExecutionStatus.INITIALIZED);
            execution.claimExecution();
            assertThat(execution.getStatus()).isEqualTo(MigrationExecutionStatus.RUNNING);
        }

        @Test
        @DisplayName("RUNNING cannot be re-claimed (throws InvalidStateException)")
        void runningCannotBeReclaimed() {
            execution.claimExecution();
            assertThatThrownBy(() -> execution.claimExecution())
                    .isInstanceOf(InvalidStateException.class)
                    .hasMessageContaining("already in progress");
        }

        @Test
        @DisplayName("COMPLETED execution cannot be claimed (terminal)")
        void completedCannotBeClaimed() {
            ExecutionTrackRecord tr = createTrackRecord(0, "s0", "y0");
            tr.markAdding();
            tr.markAdded("item0");
            execution.addTrackRecord(tr);
            execution.releaseExecution();

            assertThat(execution.getStatus()).isEqualTo(MigrationExecutionStatus.COMPLETED);
            assertThat(execution.isTerminal()).isTrue();

            assertThatThrownBy(() -> execution.claimExecution())
                    .isInstanceOf(InvalidStateException.class)
                    .hasMessageContaining("terminal status");
        }

        @Test
        @DisplayName("ROLLED_BACK execution cannot be claimed (terminal)")
        void rolledBackCannotBeClaimed() {
            ExecutionTrackRecord tr = createTrackRecord(0, "s0", "y0");
            tr.markAdding();
            tr.markAdded("item0");
            tr.markRollingBack();
            tr.markRolledBack("reverted");
            execution.addTrackRecord(tr);
            execution.releaseExecution();

            assertThat(execution.getStatus()).isEqualTo(MigrationExecutionStatus.ROLLED_BACK);
            assertThat(execution.isTerminal()).isTrue();

            assertThatThrownBy(() -> execution.claimExecution())
                    .isInstanceOf(InvalidStateException.class)
                    .hasMessageContaining("terminal status");
        }

        @Test
        @DisplayName("RECOVERY_REQUIRED execution can be claimed for recovery/reconciliation")
        void recoveryRequiredCanBeClaimed() {
            ExecutionTrackRecord tr = createTrackRecord(0, "s0", "y0");
            tr.markAdding();
            tr.markUncertain("Socket timeout");
            execution.addTrackRecord(tr);
            execution.releaseExecution();

            assertThat(execution.getStatus()).isEqualTo(MigrationExecutionStatus.RECOVERY_REQUIRED);
            assertThat(execution.isTerminal()).isFalse();

            execution.claimExecution();
            assertThat(execution.getStatus()).isEqualTo(MigrationExecutionStatus.RUNNING);
        }

        @Test
        @DisplayName("PARTIALLY_COMPLETED execution can be claimed for resume")
        void partiallyCompletedCanBeClaimed() {
            ExecutionTrackRecord tr0 = createTrackRecord(0, "s0", "y0");
            tr0.markAdding();
            tr0.markAdded("item0");
            ExecutionTrackRecord tr1 = createTrackRecord(1, "s1", "y1");
            execution.addTrackRecord(tr0);
            execution.addTrackRecord(tr1);
            execution.releaseExecution();

            assertThat(execution.getStatus()).isEqualTo(MigrationExecutionStatus.PARTIALLY_COMPLETED);
            assertThat(execution.canResume()).isTrue();

            execution.claimExecution();
            assertThat(execution.getStatus()).isEqualTo(MigrationExecutionStatus.RUNNING);
        }
    }

    @Nested
    @DisplayName("Track Status Lifecycle and Invalid Transition Rejection")
    class TrackStatusLifecycle {

        @Test
        @DisplayName("Insertion happy path: PENDING -> ADDING -> ADDED")
        void insertionHappyPath() {
            ExecutionTrackRecord tr = createTrackRecord(0, "s0", "y0");
            assertThat(tr.getStatus()).isEqualTo(ExecutionTrackStatus.PENDING);

            tr.markAdding();
            assertThat(tr.getStatus()).isEqualTo(ExecutionTrackStatus.ADDING);
            assertThat(tr.getAttemptCount()).isEqualTo(1);
            assertThat(tr.getStartedAt()).isNotNull();

            tr.markAdded("yt_item_123");
            assertThat(tr.getStatus()).isEqualTo(ExecutionTrackStatus.ADDED);
            assertThat(tr.getDestinationPlaylistItemId()).isEqualTo("yt_item_123");
            assertThat(tr.getCompletedAt()).isNotNull();
        }

        @Test
        @DisplayName("Insertion failure path: PENDING -> ADDING -> FAILED -> PENDING (reset for recovery)")
        void insertionFailurePath() {
            ExecutionTrackRecord tr = createTrackRecord(0, "s0", "y0");
            tr.markAdding();
            tr.markFailed("Video not found");
            assertThat(tr.getStatus()).isEqualTo(ExecutionTrackStatus.FAILED);
            assertThat(tr.getErrorMessage()).isEqualTo("Video not found");

            tr.resetForRecovery();
            assertThat(tr.getStatus()).isEqualTo(ExecutionTrackStatus.PENDING);
            assertThat(tr.getErrorMessage()).isNull();
        }

        @Test
        @DisplayName("Insertion skipped path: PENDING -> SKIPPED -> PENDING (reset for recovery)")
        void insertionSkippedPath() {
            ExecutionTrackRecord tr = createTrackRecord(1, "s1", "y1");
            tr.markSkipped("Preceding track failed");
            assertThat(tr.getStatus()).isEqualTo(ExecutionTrackStatus.SKIPPED);

            tr.resetForRecovery();
            assertThat(tr.getStatus()).isEqualTo(ExecutionTrackStatus.PENDING);
        }

        @Test
        @DisplayName("Insertion timeout path: PENDING -> ADDING -> UNCERTAIN")
        void insertionTimeoutPath() {
            ExecutionTrackRecord tr = createTrackRecord(0, "s0", "y0");
            tr.markAdding();
            tr.markUncertain("Gateway timeout 504");
            assertThat(tr.getStatus()).isEqualTo(ExecutionTrackStatus.UNCERTAIN);
            assertThat(tr.getErrorMessage()).contains("Gateway timeout 504");
        }

        @Test
        @DisplayName("Rollback happy path: ADDED -> ROLLING_BACK -> ROLLED_BACK")
        void rollbackHappyPath() {
            ExecutionTrackRecord tr = createTrackRecord(0, "s0", "y0");
            tr.markAdding();
            tr.markAdded("yt_item_123");

            tr.markRollingBack();
            assertThat(tr.getStatus()).isEqualTo(ExecutionTrackStatus.ROLLING_BACK);

            tr.markRolledBack("Compensating deletion confirmed");
            assertThat(tr.getStatus()).isEqualTo(ExecutionTrackStatus.ROLLED_BACK);
            assertThat(tr.getErrorMessage()).contains("Compensating deletion confirmed");
        }

        @Test
        @DisplayName("Rollback revert path: ADDED -> ROLLING_BACK -> ADDED (on provider rejection)")
        void rollbackRevertPath() {
            ExecutionTrackRecord tr = createTrackRecord(0, "s0", "y0");
            tr.markAdding();
            tr.markAdded("yt_item_123");

            tr.markRollingBack();
            tr.revertRollingBackToAdded("Provider 403 quota exceeded");
            assertThat(tr.getStatus()).isEqualTo(ExecutionTrackStatus.ADDED);
            assertThat(tr.getErrorMessage()).contains("Provider 403 quota exceeded");
        }

        @Test
        @DisplayName("Rollback timeout path: ADDED -> ROLLING_BACK -> UNCERTAIN")
        void rollbackTimeoutPath() {
            ExecutionTrackRecord tr = createTrackRecord(0, "s0", "y0");
            tr.markAdding();
            tr.markAdded("yt_item_123");

            tr.markRollingBack();
            tr.markUncertain("Socket timeout during DELETE");
            assertThat(tr.getStatus()).isEqualTo(ExecutionTrackStatus.UNCERTAIN);
        }

        @Test
        @DisplayName("Reconciliation resolutions from UNCERTAIN")
        void reconciliationFromUncertain() {
            // UNCERTAIN -> ADDED (confirmed inserted)
            ExecutionTrackRecord tr1 = createTrackRecord(0, "s0", "y0");
            tr1.markAdding();
            tr1.markUncertain("Timeout");
            tr1.reconcileConfirmedAdded("verified_item_1", "Verified remote item exists");
            assertThat(tr1.getStatus()).isEqualTo(ExecutionTrackStatus.ADDED);
            assertThat(tr1.getDestinationPlaylistItemId()).isEqualTo("verified_item_1");

            // UNCERTAIN -> FAILED (confirmed not inserted)
            ExecutionTrackRecord tr2 = createTrackRecord(1, "s1", "y1");
            tr2.markAdding();
            tr2.markUncertain("Timeout");
            tr2.reconcileConfirmedNotInserted("Verified absence in remote playlist");
            assertThat(tr2.getStatus()).isEqualTo(ExecutionTrackStatus.FAILED);

            // UNCERTAIN -> ROLLED_BACK (confirmed deleted)
            ExecutionTrackRecord tr3 = createTrackRecord(2, "s2", "y2");
            tr3.markAdding();
            tr3.markAdded("item_del");
            tr3.markRollingBack();
            tr3.markUncertain("Delete timeout");
            tr3.reconcileConfirmedDeleted("Item absent from remote playlist");
            assertThat(tr3.getStatus()).isEqualTo(ExecutionTrackStatus.ROLLED_BACK);

            // UNCERTAIN -> ADDED (confirmed present)
            ExecutionTrackRecord tr4 = createTrackRecord(3, "s3", "y3");
            tr4.markAdding();
            tr4.markAdded("item_pres");
            tr4.markRollingBack();
            tr4.markUncertain("Delete timeout");
            tr4.reconcileConfirmedPresent("Item still present in remote playlist");
            assertThat(tr4.getStatus()).isEqualTo(ExecutionTrackStatus.ADDED);

            // UNCERTAIN -> UNCERTAIN (ambiguous)
            ExecutionTrackRecord tr5 = createTrackRecord(4, "s4", "y4");
            tr5.markAdding();
            tr5.markUncertain("Timeout");
            tr5.reconcileAmbiguous("Multiple matching videos found");
            assertThat(tr5.getStatus()).isEqualTo(ExecutionTrackStatus.UNCERTAIN);
        }

        @Test
        @DisplayName("Rejection of illegal transitions on ExecutionTrackRecord")
        void rejectsIllegalTransitions() {
            ExecutionTrackRecord tr = createTrackRecord(0, "s0", "y0");

            // Cannot markAdded from PENDING directly
            assertThatThrownBy(() -> tr.markAdded("item_id"))
                    .isInstanceOf(IllegalStateException.class);

            // Cannot markRollingBack from PENDING
            assertThatThrownBy(tr::markRollingBack)
                    .isInstanceOf(IllegalStateException.class);

            // Cannot markRolledBack from PENDING
            assertThatThrownBy(() -> tr.markRolledBack("reason"))
                    .isInstanceOf(IllegalStateException.class);

            // Move to ADDED
            tr.markAdding();
            tr.markAdded("item_123");

            // Cannot markAdding from ADDED
            assertThatThrownBy(tr::markAdding)
                    .isInstanceOf(IllegalStateException.class);

            // Move to ROLLED_BACK
            tr.markRollingBack();
            tr.markRolledBack("Reverted");

            // Cannot resetForRecovery from ROLLED_BACK
            assertThatThrownBy(tr::resetForRecovery)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("Derived Counter & Execution State Consistency (releaseExecution)")
    class DerivedCounterConsistency {

        @Test
        @DisplayName("Mathematical consistency: 2 added, 1 failed, 1 skipped -> PARTIALLY_COMPLETED")
        void partiallyCompletedCounts() {
            ExecutionTrackRecord tr0 = createTrackRecord(0, "s0", "y0");
            tr0.markAdding();
            tr0.markAdded("item0");

            ExecutionTrackRecord tr1 = createTrackRecord(1, "s1", "y1");
            tr1.markAdding();
            tr1.markAdded("item1");

            ExecutionTrackRecord tr2 = createTrackRecord(2, "s2", "y2");
            tr2.markAdding();
            tr2.markFailed("Rejected");

            ExecutionTrackRecord tr3 = createTrackRecord(3, "s3", "y3");
            tr3.markSkipped("Skipped after failure");

            execution.addTrackRecord(tr0);
            execution.addTrackRecord(tr1);
            execution.addTrackRecord(tr2);
            execution.addTrackRecord(tr3);

            execution.releaseExecution();

            assertThat(execution.getStatus()).isEqualTo(MigrationExecutionStatus.PARTIALLY_COMPLETED);
            assertThat(execution.getTotalApprovedTracks()).isEqualTo(4);
            assertThat(execution.getSuccessfulInsertions()).isEqualTo(2);
            assertThat(execution.getFailedInsertions()).isEqualTo(1);
            assertThat(execution.getSkippedInsertions()).isEqualTo(1);
            assertThat(execution.getUncertainInsertions()).isEqualTo(0);
            assertThat(execution.getRolledBackInsertions()).isEqualTo(0);
            assertThat(execution.canResume()).isFalse(); // has failed tracks, requires recovery
            assertThat(execution.canRecover()).isTrue();
            assertThat(execution.canRollback()).isTrue();
        }

        @Test
        @DisplayName("Mixed rollback state: 1 added, 1 rolled back -> PARTIALLY_ROLLED_BACK")
        void partiallyRolledBackCounts() {
            ExecutionTrackRecord tr0 = createTrackRecord(0, "s0", "y0");
            tr0.markAdding();
            tr0.markAdded("item0");

            ExecutionTrackRecord tr1 = createTrackRecord(1, "s1", "y1");
            tr1.markAdding();
            tr1.markAdded("item1");
            tr1.markRollingBack();
            tr1.markRolledBack("Reverted");

            execution.addTrackRecord(tr0);
            execution.addTrackRecord(tr1);

            execution.releaseExecution();

            assertThat(execution.getStatus()).isEqualTo(MigrationExecutionStatus.PARTIALLY_ROLLED_BACK);
            assertThat(execution.getSuccessfulInsertions()).isEqualTo(1);
            assertThat(execution.getRolledBackInsertions()).isEqualTo(1);
            assertThat(execution.canRollback()).isTrue(); // can rollback remaining ADDED track
        }

        @Test
        @DisplayName("Complete rollback state: all 2 added rolled back -> ROLLED_BACK (terminal)")
        void fullyRolledBackCounts() {
            ExecutionTrackRecord tr0 = createTrackRecord(0, "s0", "y0");
            tr0.markAdding();
            tr0.markAdded("item0");
            tr0.markRollingBack();
            tr0.markRolledBack("Reverted");

            ExecutionTrackRecord tr1 = createTrackRecord(1, "s1", "y1");
            tr1.markAdding();
            tr1.markAdded("item1");
            tr1.markRollingBack();
            tr1.markRolledBack("Reverted");

            execution.addTrackRecord(tr0);
            execution.addTrackRecord(tr1);

            execution.releaseExecution();

            assertThat(execution.getStatus()).isEqualTo(MigrationExecutionStatus.ROLLED_BACK);
            assertThat(execution.getSuccessfulInsertions()).isEqualTo(0);
            assertThat(execution.getRolledBackInsertions()).isEqualTo(2);
            assertThat(execution.isTerminal()).isTrue();
        }

        @Test
        @DisplayName("Presence of UNCERTAIN track strictly forces RECOVERY_REQUIRED")
        void uncertainForcesRecoveryRequired() {
            ExecutionTrackRecord tr0 = createTrackRecord(0, "s0", "y0");
            tr0.markAdding();
            tr0.markAdded("item0");

            ExecutionTrackRecord tr1 = createTrackRecord(1, "s1", "y1");
            tr1.markAdding();
            tr1.markUncertain("Timeout");

            execution.addTrackRecord(tr0);
            execution.addTrackRecord(tr1);

            execution.releaseExecution();

            assertThat(execution.getStatus()).isEqualTo(MigrationExecutionStatus.RECOVERY_REQUIRED);
            assertThat(execution.getUncertainInsertions()).isEqualTo(1);
            assertThat(execution.canRecover()).isFalse(); // recovery blocked until reconciled
            assertThat(execution.canResume()).isFalse();
        }
    }
}
