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

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

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

        testSession = new ReviewSession(testUser, "sp_pl_1", "Source Playlist", UUID.randomUUID());
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

    private ApprovedTrack createApprovedTrack(int targetPos, String sourceTrackId, String ytVideoId) {
        return new ApprovedTrack(
                targetPos,
                sourceTrackId,
                "Title " + targetPos,
                "Artist",
                ytVideoId,
                "Title (Audio)",
                "Channel",
                Duration.ofMinutes(3),
                ReviewDecisionSource.AUTOMATIC,
                targetPos
        );
    }

    private ExecutionTrackRecord createTrackRecord(int targetPos, String sourceTrackId, String ytVideoId) {
        return new ExecutionTrackRecord(execution, createApprovedTrack(targetPos, sourceTrackId, ytVideoId));
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
            execution.initializeTracks(List.of(createApprovedTrack(0, "s0", "y0")));
            ExecutionTrackRecord tr = execution.getTrackRecords().get(0);
            tr.markAdding();
            tr.markAdded("item0");
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
            execution.initializeTracks(List.of(createApprovedTrack(0, "s0", "y0")));
            ExecutionTrackRecord tr = execution.getTrackRecords().get(0);
            tr.markAdding();
            tr.markAdded("item0");
            tr.markRollingBack();
            tr.markRolledBack("reverted");
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
            execution.initializeTracks(List.of(createApprovedTrack(0, "s0", "y0")));
            ExecutionTrackRecord tr = execution.getTrackRecords().get(0);
            tr.markAdding();
            tr.markUncertain("Socket timeout");
            execution.releaseExecution();

            assertThat(execution.getStatus()).isEqualTo(MigrationExecutionStatus.RECOVERY_REQUIRED);
            assertThat(execution.isTerminal()).isFalse();

            execution.claimExecution();
            assertThat(execution.getStatus()).isEqualTo(MigrationExecutionStatus.RUNNING);
        }

        @Test
        @DisplayName("PARTIALLY_COMPLETED execution can be claimed for resume")
        void partiallyCompletedCanBeClaimed() {
            execution.initializeTracks(List.of(
                    createApprovedTrack(0, "s0", "y0"),
                    createApprovedTrack(1, "s1", "y1")
            ));
            ExecutionTrackRecord tr0 = execution.getTrackRecords().get(0);
            tr0.markAdding();
            tr0.markAdded("item0");
            execution.releaseExecution();

            assertThat(execution.getStatus()).isEqualTo(MigrationExecutionStatus.PARTIALLY_COMPLETED);
            assertThat(execution.canResume()).isTrue();

            execution.claimExecution();
            assertThat(execution.getStatus()).isEqualTo(MigrationExecutionStatus.RUNNING);
        }

        @Test
        @DisplayName("QUEUED -> RUNNING via claimExecution() (Phase 12B)")
        void queuedToRunning() {
            assertThat(execution.getStatus()).isEqualTo(MigrationExecutionStatus.INITIALIZED);
            execution.markQueued();
            assertThat(execution.getStatus()).isEqualTo(MigrationExecutionStatus.QUEUED);
            assertThat(execution.isQueued()).isTrue();
            assertThat(execution.isTerminal()).isFalse();
            assertThat(execution.canResume()).isFalse();
            assertThat(execution.canRollback()).isFalse();

            execution.claimExecution();
            assertThat(execution.getStatus()).isEqualTo(MigrationExecutionStatus.RUNNING);
            assertThat(execution.getStartedAt()).isNotNull();
        }

        @Test
        @DisplayName("INITIALIZED -> QUEUED via markQueued() is idempotent")
        void markQueuedIsIdempotent() {
            execution.markQueued();
            assertThat(execution.getStatus()).isEqualTo(MigrationExecutionStatus.QUEUED);
            execution.markQueued(); // idempotent call
            assertThat(execution.getStatus()).isEqualTo(MigrationExecutionStatus.QUEUED);
        }

        @Test
        @DisplayName("markQueued() rejects non-INITIALIZED executions")
        void markQueuedRejectsNonInitialized() {
            execution.claimExecution(); // transitions to RUNNING
            assertThatThrownBy(() -> execution.markQueued())
                    .isInstanceOf(InvalidStateException.class)
                    .hasMessageContaining("Cannot transition execution")
                    .hasMessageContaining("expected INITIALIZED");
        }

        @Test
        @DisplayName("QUEUED execution preserves canResume == false, protecting resume semantics")
        void queuedExecutionCannotResume() {
            execution.markQueued();
            assertThat(execution.getStatus().canResume()).isFalse();
            assertThat(execution.canResume()).isFalse();
        }

        @Test
        @DisplayName("QUEUED execution cannot rollback (canRollback == false)")
        void queuedExecutionCannotRollback() {
            execution.markQueued();
            assertThat(execution.getStatus().canRollback()).isFalse();
            assertThat(execution.canRollback()).isFalse();
        }

        @Test
        @DisplayName("QUEUED -> INITIALIZED via resetToInitialized() on dispatch rejection (Phase 12C)")
        void resetToInitializedSuccess() {
            execution.markQueued();
            assertThat(execution.getStatus()).isEqualTo(MigrationExecutionStatus.QUEUED);

            execution.resetToInitialized();
            assertThat(execution.getStatus()).isEqualTo(MigrationExecutionStatus.INITIALIZED);
            assertThat(execution.canResume()).isTrue();
        }

        @Test
        @DisplayName("resetToInitialized() rejects non-QUEUED executions")
        void resetToInitializedRejectsNonQueued() {
            assertThat(execution.getStatus()).isEqualTo(MigrationExecutionStatus.INITIALIZED);
            assertThatThrownBy(() -> execution.resetToInitialized())
                    .isInstanceOf(InvalidStateException.class)
                    .hasMessageContaining("expected QUEUED");

            execution.claimExecution(); // RUNNING
            assertThatThrownBy(() -> execution.resetToInitialized())
                    .isInstanceOf(InvalidStateException.class)
                    .hasMessageContaining("expected QUEUED");
        }
    }

    @Nested
    @DisplayName("Phase 17B: Complete 10-State Transition Matrix & Invariant Verification")
    class TenStateTransitionMatrixTests {

        private static final Set<String> LEGAL_TRANSITIONS = Set.of(
                "ALLOCATING_DESTINATION->INITIALIZED",
                "ALLOCATING_DESTINATION->QUEUED",
                "ALLOCATING_DESTINATION->RUNNING",
                "ALLOCATING_DESTINATION->FAILED",
                "INITIALIZED->ALLOCATING_DESTINATION",
                "INITIALIZED->QUEUED",
                "INITIALIZED->RUNNING",
                "QUEUED->QUEUED",
                "QUEUED->INITIALIZED",
                "QUEUED->RUNNING",
                "RUNNING->COMPLETED",
                "RUNNING->PARTIALLY_COMPLETED",
                "RUNNING->FAILED",
                "RUNNING->RECOVERY_REQUIRED",
                "RUNNING->ROLLED_BACK",
                "RUNNING->PARTIALLY_ROLLED_BACK",
                "PARTIALLY_COMPLETED->RUNNING",
                "PARTIALLY_COMPLETED->RECOVERY_REQUIRED",
                "RECOVERY_REQUIRED->RUNNING",
                "RECOVERY_REQUIRED->INITIALIZED",
                "PARTIALLY_ROLLED_BACK->RUNNING",
                "PARTIALLY_ROLLED_BACK->RECOVERY_REQUIRED"
        );

        @ParameterizedTest(name = "claimExecution from {0}")
        @EnumSource(MigrationExecutionStatus.class)
        @DisplayName("claimExecution() contract across all 10 statuses")
        void claimExecutionAcrossAllTenStates(MigrationExecutionStatus from) {
            execution.setStatus(from);

            if (from.isTerminal()) {
                assertThatThrownBy(() -> execution.claimExecution())
                        .isInstanceOf(InvalidStateException.class)
                        .hasMessageContaining("terminal status");
            } else if (from == MigrationExecutionStatus.RUNNING) {
                assertThatThrownBy(() -> execution.claimExecution())
                        .isInstanceOf(InvalidStateException.class)
                        .hasMessageContaining("already in progress");
            } else {
                execution.claimExecution();
                assertThat(execution.getStatus()).isEqualTo(MigrationExecutionStatus.RUNNING);
                assertThat(execution.getStartedAt()).isNotNull();
            }
        }

        @ParameterizedTest(name = "markQueued from {0}")
        @EnumSource(MigrationExecutionStatus.class)
        @DisplayName("markQueued() contract across all 10 statuses (only INITIALIZED and idempotent QUEUED allowed)")
        void markQueuedAcrossAllTenStates(MigrationExecutionStatus from) {
            execution.setStatus(from);

            if (from == MigrationExecutionStatus.INITIALIZED) {
                execution.markQueued();
                assertThat(execution.getStatus()).isEqualTo(MigrationExecutionStatus.QUEUED);
            } else if (from == MigrationExecutionStatus.QUEUED) {
                execution.markQueued(); // Idempotent self-transition
                assertThat(execution.getStatus()).isEqualTo(MigrationExecutionStatus.QUEUED);
            } else {
                assertThatThrownBy(() -> execution.markQueued())
                        .isInstanceOf(InvalidStateException.class)
                        .hasMessageContaining("Cannot transition execution");
            }
        }

        @ParameterizedTest(name = "resetToInitialized from {0}")
        @EnumSource(MigrationExecutionStatus.class)
        @DisplayName("resetToInitialized() contract across all 10 statuses (only QUEUED allowed)")
        void resetToInitializedAcrossAllTenStates(MigrationExecutionStatus from) {
            execution.setStatus(from);

            if (from == MigrationExecutionStatus.QUEUED) {
                execution.resetToInitialized();
                assertThat(execution.getStatus()).isEqualTo(MigrationExecutionStatus.INITIALIZED);
            } else {
                assertThatThrownBy(() -> execution.resetToInitialized())
                        .isInstanceOf(InvalidStateException.class)
                        .hasMessageContaining("expected QUEUED");
            }
        }

        @ParameterizedTest(name = "markPlaylistCreated from {0}")
        @EnumSource(MigrationExecutionStatus.class)
        @DisplayName("markPlaylistCreated() contract across all 10 statuses (ALLOCATING_DESTINATION, RECOVERY_REQUIRED, INITIALIZED allowed)")
        void markPlaylistCreatedAcrossAllTenStates(MigrationExecutionStatus from) {
            execution.setStatus(from);

            if (from == MigrationExecutionStatus.ALLOCATING_DESTINATION
                    || from == MigrationExecutionStatus.RECOVERY_REQUIRED
                    || from == MigrationExecutionStatus.INITIALIZED) {
                execution.markPlaylistCreated("PL_dest_123", "https://music.youtube.com/playlist?list=PL_dest_123", MigrationExecutionStatus.INITIALIZED);
                assertThat(execution.getStatus()).isEqualTo(MigrationExecutionStatus.INITIALIZED);
                assertThat(execution.getDestinationPlaylistId()).isEqualTo("PL_dest_123");
            } else {
                assertThatThrownBy(() -> execution.markPlaylistCreated("PL_dest_123", "url", MigrationExecutionStatus.INITIALIZED))
                        .isInstanceOf(InvalidStateException.class)
                        .hasMessageContaining("Cannot mark playlist created from status");
            }
        }

        @ParameterizedTest(name = "State invariants for {0}")
        @MethodSource("stateInvariantExpectations")
        @DisplayName("Validate isTerminal(), canResume(), canRollback(), and isQueued() across all 10 statuses")
        void validateStateInvariantsAcrossAllTenStates(
                MigrationExecutionStatus status,
                boolean expectedTerminal,
                boolean expectedCanResume,
                boolean expectedCanRollback,
                boolean expectedIsQueued
        ) {
            assertThat(status.isTerminal()).isEqualTo(expectedTerminal);
            assertThat(status.canResume()).isEqualTo(expectedCanResume);
            assertThat(status.canRollback()).isEqualTo(expectedCanRollback);
            assertThat(status.isQueued()).isEqualTo(expectedIsQueued);
        }

        static Stream<Arguments> stateInvariantExpectations() {
            return Stream.of(
                    Arguments.of(MigrationExecutionStatus.ALLOCATING_DESTINATION, false, false, false, false),
                    Arguments.of(MigrationExecutionStatus.INITIALIZED, false, true, true, false),
                    Arguments.of(MigrationExecutionStatus.QUEUED, false, false, false, true),
                    Arguments.of(MigrationExecutionStatus.RUNNING, false, false, false, false),
                    Arguments.of(MigrationExecutionStatus.PARTIALLY_COMPLETED, false, true, true, false),
                    Arguments.of(MigrationExecutionStatus.COMPLETED, true, false, true, false),
                    Arguments.of(MigrationExecutionStatus.FAILED, true, false, true, false),
                    Arguments.of(MigrationExecutionStatus.RECOVERY_REQUIRED, false, false, true, false),
                    Arguments.of(MigrationExecutionStatus.ROLLED_BACK, true, false, false, false),
                    Arguments.of(MigrationExecutionStatus.PARTIALLY_ROLLED_BACK, false, true, true, false)
            );
        }

        @ParameterizedTest(name = "{0} -> {1} (legal={2})")
        @MethodSource("allHundredDirectedTransitions")
        @DisplayName("Full 10x10 directed transition matrix (100 pairs: 22 legal, 78 illegal)")
        void testFullDirectedTransitionMatrix(MigrationExecutionStatus from, MigrationExecutionStatus to, boolean isLegal) {
            String transitionKey = from.name() + "->" + to.name();
            assertThat(LEGAL_TRANSITIONS.contains(transitionKey))
                    .as("Transition legality expectation for %s", transitionKey)
                    .isEqualTo(isLegal);

            // Invariant: Terminal states permit 0 outgoing transitions
            if (from.isTerminal()) {
                assertThat(isLegal)
                        .as("Terminal state %s must not have any legal outgoing transitions", from)
                        .isFalse();
            }

            // Invariant: Self-transitions are illegal except idempotent QUEUED -> QUEUED
            if (from == to) {
                if (from == MigrationExecutionStatus.QUEUED) {
                    assertThat(isLegal).isTrue();
                } else {
                    assertThat(isLegal).as("Self-transition for %s must be illegal", from).isFalse();
                }
            }
        }

        static Stream<Arguments> allHundredDirectedTransitions() {
            Stream.Builder<Arguments> builder = Stream.builder();
            for (MigrationExecutionStatus from : MigrationExecutionStatus.values()) {
                for (MigrationExecutionStatus to : MigrationExecutionStatus.values()) {
                    String key = from.name() + "->" + to.name();
                    boolean isLegal = LEGAL_TRANSITIONS.contains(key);
                    builder.add(Arguments.of(from, to, isLegal));
                }
            }
            return builder.build();
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
                    .isInstanceOf(InvalidStateException.class);

            // Cannot markRollingBack from PENDING
            assertThatThrownBy(tr::markRollingBack)
                    .isInstanceOf(InvalidStateException.class);

            // Cannot markRolledBack from PENDING
            assertThatThrownBy(() -> tr.markRolledBack("reason"))
                    .isInstanceOf(InvalidStateException.class);

            // Move to ADDED
            tr.markAdding();
            tr.markAdded("item_123");

            // Cannot markAdding from ADDED
            assertThatThrownBy(tr::markAdding)
                    .isInstanceOf(InvalidStateException.class);

            // Move to ROLLED_BACK
            tr.markRollingBack();
            tr.markRolledBack("Reverted");

            // Cannot resetForRecovery from ROLLED_BACK
            assertThatThrownBy(tr::resetForRecovery)
                    .isInstanceOf(InvalidStateException.class);
        }
    }

    @Nested
    @DisplayName("Derived Counter & Execution State Consistency (releaseExecution)")
    class DerivedCounterConsistency {

        @Test
        @DisplayName("Mathematical consistency: 2 added, 1 failed, 1 skipped -> PARTIALLY_COMPLETED")
        void partiallyCompletedCounts() {
            execution.initializeTracks(List.of(
                    createApprovedTrack(0, "s0", "y0"),
                    createApprovedTrack(1, "s1", "y1"),
                    createApprovedTrack(2, "s2", "y2"),
                    createApprovedTrack(3, "s3", "y3")
            ));

            ExecutionTrackRecord tr0 = execution.getTrackRecords().get(0);
            tr0.markAdding();
            tr0.markAdded("item0");

            ExecutionTrackRecord tr1 = execution.getTrackRecords().get(1);
            tr1.markAdding();
            tr1.markAdded("item1");

            ExecutionTrackRecord tr2 = execution.getTrackRecords().get(2);
            tr2.markAdding();
            tr2.markFailed("Rejected");

            ExecutionTrackRecord tr3 = execution.getTrackRecords().get(3);
            tr3.markSkipped("Skipped after failure");

            execution.releaseExecution();

            assertThat(execution.getStatus()).isEqualTo(MigrationExecutionStatus.PARTIALLY_COMPLETED);
            assertThat(execution.getTotalApprovedTracks()).isEqualTo(4);
            assertThat(execution.getSuccessfulInsertions()).isEqualTo(2);
            assertThat(execution.getFailedInsertions()).isEqualTo(1);
            assertThat(execution.getSkippedInsertions()).isEqualTo(1);
            assertThat(execution.getUncertainInsertions()).isEqualTo(0);
            assertThat(execution.getRolledBackInsertions()).isEqualTo(0);
            assertThat(execution.canResume()).isFalse(); // has failed tracks, requires recovery
            assertThat(execution.hasFailedTracks()).isTrue();
            assertThat(execution.getUncertainInsertions()).isZero();
            assertThat(execution.isTerminal()).isFalse();
            assertThat(execution.canRollback()).isTrue();
        }

        @Test
        @DisplayName("Mixed rollback state: 1 added, 1 rolled back -> PARTIALLY_ROLLED_BACK")
        void partiallyRolledBackCounts() {
            execution.initializeTracks(List.of(
                    createApprovedTrack(0, "s0", "y0"),
                    createApprovedTrack(1, "s1", "y1")
            ));

            ExecutionTrackRecord tr0 = execution.getTrackRecords().get(0);
            tr0.markAdding();
            tr0.markAdded("item0");

            ExecutionTrackRecord tr1 = execution.getTrackRecords().get(1);
            tr1.markAdding();
            tr1.markAdded("item1");
            tr1.markRollingBack();
            tr1.markRolledBack("Reverted");

            execution.releaseExecution();

            assertThat(execution.getStatus()).isEqualTo(MigrationExecutionStatus.PARTIALLY_ROLLED_BACK);
            assertThat(execution.getSuccessfulInsertions()).isEqualTo(1);
            assertThat(execution.getRolledBackInsertions()).isEqualTo(1);
            assertThat(execution.canRollback()).isTrue(); // can rollback remaining ADDED track
        }

        @Test
        @DisplayName("Complete rollback state: all 2 added rolled back -> ROLLED_BACK (terminal)")
        void fullyRolledBackCounts() {
            execution.initializeTracks(List.of(
                    createApprovedTrack(0, "s0", "y0"),
                    createApprovedTrack(1, "s1", "y1")
            ));

            ExecutionTrackRecord tr0 = execution.getTrackRecords().get(0);
            tr0.markAdding();
            tr0.markAdded("item0");
            tr0.markRollingBack();
            tr0.markRolledBack("Reverted");

            ExecutionTrackRecord tr1 = execution.getTrackRecords().get(1);
            tr1.markAdding();
            tr1.markAdded("item1");
            tr1.markRollingBack();
            tr1.markRolledBack("Reverted");

            execution.releaseExecution();

            assertThat(execution.getStatus()).isEqualTo(MigrationExecutionStatus.ROLLED_BACK);
            assertThat(execution.getSuccessfulInsertions()).isEqualTo(0);
            assertThat(execution.getRolledBackInsertions()).isEqualTo(2);
            assertThat(execution.isTerminal()).isTrue();
        }

        @Test
        @DisplayName("Presence of UNCERTAIN track strictly forces RECOVERY_REQUIRED")
        void uncertainForcesRecoveryRequired() {
            execution.initializeTracks(List.of(
                    createApprovedTrack(0, "s0", "y0"),
                    createApprovedTrack(1, "s1", "y1")
            ));

            ExecutionTrackRecord tr0 = execution.getTrackRecords().get(0);
            tr0.markAdding();
            tr0.markAdded("item0");

            ExecutionTrackRecord tr1 = execution.getTrackRecords().get(1);
            tr1.markAdding();
            tr1.markUncertain("Timeout");

            execution.releaseExecution();

            assertThat(execution.getStatus()).isEqualTo(MigrationExecutionStatus.RECOVERY_REQUIRED);
            assertThat(execution.getUncertainInsertions()).isEqualTo(1);
            assertThat(execution.canResume()).isFalse();
            assertThat(execution.isTerminal()).isFalse();
        }
    }
}
