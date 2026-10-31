package com.spotifyyoutube.migrator.migration.execution.domain;

import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationContext;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Phase 11E: Migration Execution Domain Entity Invariants Test")
class MigrationExecutionDomainTest {

    private User testUser;
    private ReviewSession testSession;
    private ApprovedMigrationContext sampleContext;

    @BeforeEach
    void setUp() {
        testUser = new User("tester@example.com");
        testUser.setId(UUID.randomUUID());

        testSession = new ReviewSession(UUID.randomUUID(), testUser, "sp_playlist_1", "My Spotify Playlist");
        testSession.setId(UUID.randomUUID());

        ApprovedTrack track0 = new ApprovedTrack(
                0,
                "sp_t0",
                "Song Zero",
                "Artist A",
                "yt_t0",
                "Song Zero (Audio)",
                "Channel A",
                Duration.ofMinutes(3),
                ReviewDecisionSource.CONFIRMED_EXACT,
                0
        );

        ApprovedTrack track1 = new ApprovedTrack(
                1,
                "sp_t1",
                "Song One",
                "Artist B",
                "yt_t1",
                "Song One (Audio)",
                "Channel B",
                Duration.ofMinutes(4),
                ReviewDecisionSource.USER_ACCEPTED,
                1
        );

        sampleContext = new ApprovedMigrationContext(
                testSession.getId(),
                UUID.randomUUID(),
                "sp_playlist_1",
                "My Spotify Playlist",
                "My Target Playlist",
                "Description",
                false,
                2,
                2,
                0,
                Instant.now(),
                List.of(track0, track1)
        );
    }

    @Nested
    @DisplayName("Entity Construction and Invariant Validations")
    class ConstructionTests {

        @Test
        @DisplayName("Valid instantiation initializes status to INITIALIZED and sets metadata")
        void validInstantiation() {
            MigrationExecution execution = new MigrationExecution(
                    testSession,
                    testUser,
                    Platform.YOUTUBE.name(),
                    "PL_youtube_123",
                    "https://music.youtube.com/playlist?list=PL_youtube_123",
                    "My Spotify Playlist",
                    sampleContext.planId()
            );

            assertThat(execution.getStatus()).isEqualTo(MigrationExecutionStatus.INITIALIZED);
            assertThat(execution.getDestinationPlatform()).isEqualTo("YOUTUBE");
            assertThat(execution.getDestinationPlaylistId()).isEqualTo("PL_youtube_123");
            assertThat(execution.getDestinationPlaylistUrl()).contains("https://music.youtube.com/playlist?list=PL_youtube_123");
            assertThat(execution.getSourcePlaylistTitle()).isEqualTo("My Spotify Playlist");
            assertThat(execution.getPlanId()).contains(sampleContext.planId());
            assertThat(execution.getTotalApprovedTracks()).isZero();
            assertThat(execution.canResume()).isTrue();
            assertThat(execution.isTerminal()).isFalse();
        }

        @Test
        @DisplayName("Rejects null or blank mandatory fields")
        void rejectsInvalidFields() {
            assertThatThrownBy(() -> new MigrationExecution(null, testUser, "YOUTUBE", "PL_123", null, "Title", null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("ReviewSession");

            assertThatThrownBy(() -> new MigrationExecution(testSession, null, "YOUTUBE", "PL_123", null, "Title", null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("User");

            assertThatThrownBy(() -> new MigrationExecution(testSession, testUser, " ", "PL_123", null, "Title", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Destination platform");

            assertThatThrownBy(() -> new MigrationExecution(testSession, testUser, "YOUTUBE", " ", null, "Title", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Destination playlist ID");
        }
    }

    @Nested
    @DisplayName("Track Initialization and Ordering Invariants")
    class TrackInitializationTests {

        @Test
        @DisplayName("initializeTracks creates ordered ExecutionTrackRecords in canonical order")
        void initializeTracksSuccess() {
            MigrationExecution execution = new MigrationExecution(
                    testSession,
                    testUser,
                    Platform.YOUTUBE.name(),
                    "PL_youtube_123",
                    null,
                    "My Spotify Playlist",
                    sampleContext.planId()
            );

            execution.initializeTracks(sampleContext.approvedTracks());

            assertThat(execution.getTotalApprovedTracks()).isEqualTo(2);
            assertThat(execution.getTrackRecords()).hasSize(2);

            ExecutionTrackRecord r0 = execution.getTrackRecords().get(0);
            assertThat(r0.getTargetPosition()).isEqualTo(0);
            assertThat(r0.getSourceTrackId()).isEqualTo("sp_t0");
            assertThat(r0.getDestinationTrackId()).isEqualTo("yt_t0");
            assertThat(r0.getStatus()).isEqualTo(ExecutionTrackStatus.PENDING);

            ExecutionTrackRecord r1 = execution.getTrackRecords().get(1);
            assertThat(r1.getTargetPosition()).isEqualTo(1);
            assertThat(r1.getSourceTrackId()).isEqualTo("sp_t1");
            assertThat(r1.getDestinationTrackId()).isEqualTo("yt_t1");
            assertThat(r1.getStatus()).isEqualTo(ExecutionTrackStatus.PENDING);
        }

        @Test
        @DisplayName("initializeTracks rejects null or empty track list")
        void initializeTracksRejectsEmpty() {
            MigrationExecution execution = new MigrationExecution(
                    testSession,
                    testUser,
                    Platform.YOUTUBE.name(),
                    "PL_youtube_123",
                    null,
                    "Title",
                    null
            );

            assertThatThrownBy(() -> execution.initializeTracks(List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Approved tracks must not be empty");
        }

        @Test
        @DisplayName("initializeTracks cannot be called twice")
        void initializeTracksCannotBeCalledTwice() {
            MigrationExecution execution = new MigrationExecution(
                    testSession,
                    testUser,
                    Platform.YOUTUBE.name(),
                    "PL_youtube_123",
                    null,
                    "Title",
                    null
            );
            execution.initializeTracks(sampleContext.approvedTracks());

            assertThatThrownBy(() -> execution.initializeTracks(sampleContext.approvedTracks()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already initialized");
        }
    }

    @Nested
    @DisplayName("Execution Claiming and Concurrency Protection")
    class ClaimingTests {

        @Test
        @DisplayName("claimExecution transitions status to RUNNING and sets startedAt")
        void claimTransitionsToRunning() {
            MigrationExecution execution = new MigrationExecution(
                    testSession,
                    testUser,
                    Platform.YOUTUBE.name(),
                    "PL_youtube_123",
                    null,
                    "Title",
                    null
            );

            execution.claimExecution();

            assertThat(execution.getStatus()).isEqualTo(MigrationExecutionStatus.RUNNING);
            assertThat(execution.getStartedAt()).isNotNull();
        }

        @Test
        @DisplayName("claimExecution throws InvalidStateException if already RUNNING")
        void claimThrowsWhenAlreadyRunning() {
            MigrationExecution execution = new MigrationExecution(
                    testSession,
                    testUser,
                    Platform.YOUTUBE.name(),
                    "PL_youtube_123",
                    null,
                    "Title",
                    null
            );
            execution.claimExecution();

            assertThatThrownBy(execution::claimExecution)
                    .isInstanceOf(InvalidStateException.class)
                    .hasMessageContaining("already in progress");
        }

        @Test
        @DisplayName("claimExecution throws InvalidStateException if execution is COMPLETED")
        void claimThrowsWhenTerminal() {
            MigrationExecution execution = new MigrationExecution(
                    testSession,
                    testUser,
                    Platform.YOUTUBE.name(),
                    "PL_youtube_123",
                    null,
                    "Title",
                    null
            );
            execution.initializeTracks(sampleContext.approvedTracks());
            execution.getTrackRecords().forEach(r -> {
                r.markAdding();
                r.markAdded("item_" + r.getTargetPosition());
            });
            execution.releaseExecution();

            assertThat(execution.getStatus()).isEqualTo(MigrationExecutionStatus.COMPLETED);
            assertThat(execution.isTerminal()).isTrue();

            assertThatThrownBy(execution::claimExecution)
                    .isInstanceOf(InvalidStateException.class)
                    .hasMessageContaining("terminal status");
        }
    }

    @Nested
    @DisplayName("Status Transitions and Summary Reconciliation")
    class TransitionAndReconciliationTests {

        @Test
        @DisplayName("Full success resolves status to COMPLETED and reconciles counts")
        void fullSuccessReconciliation() {
            MigrationExecution execution = new MigrationExecution(
                    testSession,
                    testUser,
                    Platform.YOUTUBE.name(),
                    "PL_youtube_123",
                    null,
                    "Title",
                    null
            );
            execution.initializeTracks(sampleContext.approvedTracks());
            execution.claimExecution();

            for (ExecutionTrackRecord r : execution.getTrackRecords()) {
                r.markAdding();
                r.markAdded("item_" + r.getTargetPosition());
            }

            execution.releaseExecution();

            assertThat(execution.getStatus()).isEqualTo(MigrationExecutionStatus.COMPLETED);
            assertThat(execution.getSuccessfulInsertions()).isEqualTo(2);
            assertThat(execution.getFailedInsertions()).isZero();
            assertThat(execution.getUncertainInsertions()).isZero();
            assertThat(execution.getSkippedInsertions()).isZero();
            assertThat(execution.getCompletedAt()).isNotNull();
            assertThat(execution.isFullySuccessful()).isTrue();
            assertThat(execution.canResume()).isFalse();
        }

        @Test
        @DisplayName("Partial completion with failed and pending tracks resolves to PARTIALLY_COMPLETED")
        void partialCompletionWithFailure() {
            MigrationExecution execution = new MigrationExecution(
                    testSession,
                    testUser,
                    Platform.YOUTUBE.name(),
                    "PL_youtube_123",
                    null,
                    "Title",
                    null
            );
            execution.initializeTracks(sampleContext.approvedTracks());
            execution.claimExecution();

            ExecutionTrackRecord r0 = execution.getTrackRecords().get(0);
            r0.markAdding();
            r0.markAdded("item_0");

            ExecutionTrackRecord r1 = execution.getTrackRecords().get(1);
            r1.markAdding();
            r1.markFailed("Provider quota exceeded");

            execution.releaseExecution();

            assertThat(execution.getStatus()).isEqualTo(MigrationExecutionStatus.PARTIALLY_COMPLETED);
            assertThat(execution.getSuccessfulInsertions()).isEqualTo(1);
            assertThat(execution.getFailedInsertions()).isEqualTo(1);
            assertThat(execution.isFullySuccessful()).isFalse();
            assertThat(execution.canResume()).isFalse(); // No pending tracks left
        }

        @Test
        @DisplayName("All tracks failing resolves status to FAILED")
        void allTracksFailing() {
            MigrationExecution execution = new MigrationExecution(
                    testSession,
                    testUser,
                    Platform.YOUTUBE.name(),
                    "PL_youtube_123",
                    null,
                    "Title",
                    null
            );
            execution.initializeTracks(sampleContext.approvedTracks());
            execution.claimExecution();

            for (ExecutionTrackRecord r : execution.getTrackRecords()) {
                r.markAdding();
                r.markFailed("Permission denied");
            }

            execution.releaseExecution();

            assertThat(execution.getStatus()).isEqualTo(MigrationExecutionStatus.FAILED);
            assertThat(execution.getSuccessfulInsertions()).isZero();
            assertThat(execution.getFailedInsertions()).isEqualTo(2);
            assertThat(execution.isFullySuccessful()).isFalse();
        }

        @Test
        @DisplayName("Uncertain track outcome transitions execution to RECOVERY_REQUIRED")
        void uncertainOutcomeRequiresRecovery() {
            MigrationExecution execution = new MigrationExecution(
                    testSession,
                    testUser,
                    Platform.YOUTUBE.name(),
                    "PL_youtube_123",
                    null,
                    "Title",
                    null
            );
            execution.initializeTracks(sampleContext.approvedTracks());
            execution.claimExecution();

            ExecutionTrackRecord r0 = execution.getTrackRecords().get(0);
            r0.markAdding();
            r0.markAdded("item_0");

            ExecutionTrackRecord r1 = execution.getTrackRecords().get(1);
            r1.markAdding();
            r1.markUncertain("Socket timeout during POST to YouTube");

            execution.releaseExecution();

            assertThat(execution.getStatus()).isEqualTo(MigrationExecutionStatus.RECOVERY_REQUIRED);
            assertThat(execution.getUncertainInsertions()).isEqualTo(1);
            assertThat(execution.canResume()).isFalse(); // Uncertain tracks cannot be blindly resumed
        }

        @Test
        @DisplayName("Crash recovery: reconcileStaleCrashState transitions stale ADDING to UNCERTAIN and status to RECOVERY_REQUIRED")
        void crashRecoveryReconciliation() {
            MigrationExecution execution = new MigrationExecution(
                    testSession,
                    testUser,
                    Platform.YOUTUBE.name(),
                    "PL_youtube_123",
                    null,
                    "Title",
                    null
            );
            execution.initializeTracks(sampleContext.approvedTracks());
            execution.claimExecution();

            ExecutionTrackRecord r0 = execution.getTrackRecords().get(0);
            r0.markAdding();
            r0.markAdded("item_0");

            // Server crashed while r1 was in ADDING state
            ExecutionTrackRecord r1 = execution.getTrackRecords().get(1);
            r1.markAdding();

            // When application restarts, stale crash state is reconciled:
            boolean reconciled = execution.reconcileStaleCrashState();

            assertThat(reconciled).isTrue();
            assertThat(r1.getStatus()).isEqualTo(ExecutionTrackStatus.UNCERTAIN);
            assertThat(r1.getErrorMessage()).contains("interrupted");
            assertThat(execution.getStatus()).isEqualTo(MigrationExecutionStatus.RECOVERY_REQUIRED);
            assertThat(execution.getUncertainInsertions()).isEqualTo(1);
            assertThat(execution.canResume()).isFalse();
        }

        @Test
        @DisplayName("reconcileCounts verifies mathematical identity: approved = added + failed + skipped + uncertain + pending + adding")
        void mathematicalReconciliationIdentity() {
            MigrationExecution execution = new MigrationExecution(
                    testSession,
                    testUser,
                    Platform.YOUTUBE.name(),
                    "PL_youtube_123",
                    null,
                    "Title",
                    null
            );
            execution.initializeTracks(sampleContext.approvedTracks());

            execution.reconcileCounts();

            long sum = execution.getSuccessfulInsertions()
                    + execution.getFailedInsertions()
                    + execution.getSkippedInsertions()
                    + execution.getUncertainInsertions();

            assertThat(execution.getTotalApprovedTracks()).isEqualTo(2);
            assertThat(sum).isZero(); // All 2 are currently PENDING
        }
    }
}
