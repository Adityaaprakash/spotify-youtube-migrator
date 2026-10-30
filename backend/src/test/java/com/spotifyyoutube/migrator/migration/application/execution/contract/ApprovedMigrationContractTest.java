package com.spotifyyoutube.migrator.migration.application.execution.contract;

import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
import com.spotifyyoutube.migrator.migration.application.plan.MigrationPlan;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewDecisionSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Phase 11A: Approved Migration Execution Contracts Test")
class ApprovedMigrationContractTest {

    @Nested
    @DisplayName("ApprovedTrack Contract Tests")
    class ApprovedTrackTests {

        @Test
        @DisplayName("Valid construction preserves all metadata and target positioning")
        void validConstruction() {
            ApprovedTrack track = new ApprovedTrack(
                    0,
                    "spotify-track-1",
                    "Blinding Lights",
                    "The Weeknd",
                    "yt-video-1",
                    "The Weeknd - Blinding Lights (Official Audio)",
                    "TheWeekndVEVO",
                    Duration.ofSeconds(200),
                    ReviewDecisionSource.AUTOMATIC,
                    0
            );

            assertThat(track.sourcePosition()).isEqualTo(0);
            assertThat(track.sourceTrackId()).isEqualTo("spotify-track-1");
            assertThat(track.sourceTitle()).isEqualTo("Blinding Lights");
            assertThat(track.sourceArtist()).isEqualTo("The Weeknd");
            assertThat(track.destinationTrackId()).isEqualTo("yt-video-1");
            assertThat(track.getDestinationTitle()).contains("The Weeknd - Blinding Lights (Official Audio)");
            assertThat(track.getDestinationChannel()).contains("TheWeekndVEVO");
            assertThat(track.getDestinationDuration()).contains(Duration.ofSeconds(200));
            assertThat(track.decisionSource()).isEqualTo(ReviewDecisionSource.AUTOMATIC);
            assertThat(track.targetPosition()).isEqualTo(0);
            assertThat(track.isAutomatic()).isTrue();
            assertThat(track.isUserSelected()).isFalse();
        }

        @Test
        @DisplayName("User-selected provenance is recognized")
        void userSelectedProvenance() {
            ApprovedTrack track = new ApprovedTrack(
                    3,
                    "spotify-track-4",
                    "Custom Track",
                    "Artist",
                    "yt-video-4",
                    "Custom Video",
                    "Channel",
                    Duration.ofMinutes(3),
                    ReviewDecisionSource.USER_SELECTED,
                    1
            );

            assertThat(track.isUserSelected()).isTrue();
            assertThat(track.isAutomatic()).isFalse();
        }

        @Test
        @DisplayName("Rejects negative source position")
        void rejectsNegativeSourcePosition() {
            assertThatThrownBy(() -> new ApprovedTrack(
                    -1, "sp-1", "Title", "Artist", "yt-1", "Title", "Channel", null, ReviewDecisionSource.AUTOMATIC, 0
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sourcePosition must be non-negative");
        }

        @Test
        @DisplayName("Rejects blank or null source track ID")
        void rejectsBlankSourceTrackId() {
            assertThatThrownBy(() -> new ApprovedTrack(
                    0, "   ", "Title", "Artist", "yt-1", "Title", "Channel", null, ReviewDecisionSource.AUTOMATIC, 0
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sourceTrackId must not be blank");
        }

        @Test
        @DisplayName("Rejects blank or null destination track ID")
        void rejectsBlankDestinationTrackId() {
            assertThatThrownBy(() -> new ApprovedTrack(
                    0, "sp-1", "Title", "Artist", "", "Title", "Channel", null, ReviewDecisionSource.AUTOMATIC, 0
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("destinationTrackId must not be blank");
        }

        @Test
        @DisplayName("Rejects negative destination duration")
        void rejectsNegativeDuration() {
            assertThatThrownBy(() -> new ApprovedTrack(
                    0, "sp-1", "Title", "Artist", "yt-1", "Title", "Channel", Duration.ofSeconds(-10), ReviewDecisionSource.AUTOMATIC, 0
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("destinationDuration must not be negative");
        }

        @Test
        @DisplayName("Rejects negative target position")
        void rejectsNegativeTargetPosition() {
            assertThatThrownBy(() -> new ApprovedTrack(
                    0, "sp-1", "Title", "Artist", "yt-1", "Title", "Channel", null, ReviewDecisionSource.AUTOMATIC, -1
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("targetPosition must be non-negative");
        }
    }

    @Nested
    @DisplayName("ApprovedMigrationContext Contract Tests")
    class ApprovedMigrationContextTests {

        private ApprovedTrack track0;
        private ApprovedTrack track1;
        private UUID sessionId;
        private UUID userId;

        void setupTracks() {
            sessionId = UUID.randomUUID();
            userId = UUID.randomUUID();
            track0 = new ApprovedTrack(
                    0, "sp-0", "Title 0", "Artist 0", "yt-0", "Title 0", "Channel", Duration.ofSeconds(180), ReviewDecisionSource.AUTOMATIC, 0
            );
            track1 = new ApprovedTrack(
                    2, "sp-2", "Title 2", "Artist 2", "yt-2", "Title 2", "Channel", Duration.ofSeconds(210), ReviewDecisionSource.USER_SELECTED, 1
            );
        }

        @Test
        @DisplayName("Valid construction preserves immutability and order")
        void validConstruction() {
            setupTracks();
            List<ApprovedTrack> mutableList = new ArrayList<>(List.of(track0, track1));
            Instant now = Instant.now();

            ApprovedMigrationContext context = new ApprovedMigrationContext(
                    sessionId,
                    UUID.randomUUID(),
                    userId,
                    "spotify-pl-1",
                    "My Playlist",
                    "Migrated - My Playlist",
                    "Custom Description",
                    true,
                    mutableList,
                    3,
                    2,
                    1,
                    now
            );

            assertThat(context.reviewSessionId()).isEqualTo(sessionId);
            assertThat(context.userId()).isEqualTo(userId);
            assertThat(context.sourcePlaylistId()).isEqualTo("spotify-pl-1");
            assertThat(context.sourcePlaylistTitle()).isEqualTo("My Playlist");
            assertThat(context.targetPlaylistTitle()).isEqualTo("Migrated - My Playlist");
            assertThat(context.getTargetPlaylistDescription()).contains("Custom Description");
            assertThat(context.isPrivate()).isTrue();
            assertThat(context.approvedTracks()).hasSize(2);
            assertThat(context.totalSourceTracks()).isEqualTo(3);
            assertThat(context.approvedTrackCount()).isEqualTo(2);
            assertThat(context.excludedTrackCount()).isEqualTo(1);
            assertThat(context.validatedAt()).isEqualTo(now);
            assertThat(context.hasApprovedTracks()).isTrue();

            // Immutability: modifying original list does not alter context
            mutableList.clear();
            assertThat(context.approvedTracks()).hasSize(2);

            // Defensive copying: returned collection is unmodifiable
            assertThatThrownBy(() -> context.approvedTracks().add(track0))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("Lookup helpers find tracks by target and source position")
        void lookupHelpers() {
            setupTracks();
            ApprovedMigrationContext context = new ApprovedMigrationContext(
                    sessionId, null, userId, "sp-pl", "Title", "Migrated", null, false,
                    List.of(track0, track1), 3, 2, 1, Instant.now()
            );

            assertThat(context.getTrackAtTargetPosition(0)).contains(track0);
            assertThat(context.getTrackAtTargetPosition(1)).contains(track1);
            assertThat(context.getTrackAtTargetPosition(2)).isEmpty();
            assertThat(context.getTrackAtTargetPosition(-1)).isEmpty();

            assertThat(context.getTrackAtSourcePosition(0)).contains(track0);
            assertThat(context.getTrackAtSourcePosition(2)).contains(track1);
            assertThat(context.getTrackAtSourcePosition(1)).isEmpty();
        }

        @Test
        @DisplayName("Rejects non-gapless target positions")
        void rejectsNonGaplessTargetPositions() {
            setupTracks();
            ApprovedTrack badTargetTrack = new ApprovedTrack(
                    1, "sp-1", "T", "A", "yt-1", "T", "C", null, ReviewDecisionSource.AUTOMATIC, 5
            );

            assertThatThrownBy(() -> new ApprovedMigrationContext(
                    sessionId, null, userId, "sp-pl", "Title", "Migrated", null, false,
                    List.of(track0, badTargetTrack), 2, 2, 0, Instant.now()
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("out-of-sequence targetPosition");
        }

        @Test
        @DisplayName("Rejects non-monotonic source positions")
        void rejectsNonMonotonicSourcePositions() {
            setupTracks();
            ApprovedTrack backwardsTrack = new ApprovedTrack(
                    0, "sp-0b", "T", "A", "yt-b", "T", "C", null, ReviewDecisionSource.AUTOMATIC, 1
            );

            assertThatThrownBy(() -> new ApprovedMigrationContext(
                    sessionId, null, userId, "sp-pl", "Title", "Migrated", null, false,
                    List.of(track0, backwardsTrack), 2, 2, 0, Instant.now()
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("non-monotonic sourcePosition");
        }

        @Test
        @DisplayName("Rejects approved + excluded exceeding total source tracks")
        void rejectsCountInconsistency() {
            setupTracks();
            assertThatThrownBy(() -> new ApprovedMigrationContext(
                    sessionId, null, userId, "sp-pl", "Title", "Migrated", null, false,
                    List.of(track0, track1), 2, 2, 2, Instant.now()
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot exceed totalSourceTracks");
        }
    }

    @Nested
    @DisplayName("ApprovedMigrationExecutionRequest Tests")
    class ApprovedMigrationExecutionRequestTests {

        @Test
        @DisplayName("Convenience factories and defaults work as expected")
        void convenienceFactories() {
            UUID sessionId = UUID.randomUUID();
            UUID userId = UUID.randomUUID();

            ApprovedMigrationExecutionRequest req1 = ApprovedMigrationExecutionRequest.of(sessionId);
            assertThat(req1.reviewSessionId()).isEqualTo(sessionId);
            assertThat(req1.getUserId()).isEmpty();
            assertThat(req1.isPrivatePlaylist()).isTrue();

            ApprovedMigrationExecutionRequest req2 = ApprovedMigrationExecutionRequest.of(sessionId, userId);
            assertThat(req2.getUserId()).contains(userId);

            ApprovedMigrationExecutionRequest req3 = ApprovedMigrationExecutionRequest.of(sessionId, userId, "Custom Playlist Title");
            assertThat(req3.getTargetPlaylistTitle()).contains("Custom Playlist Title");
        }

        @Test
        @DisplayName("Factory with MigrationPlan extracts metadata correctly")
        void ofSessionAndPlan() {
            UUID sessionId = UUID.randomUUID();
            UUID userId = UUID.randomUUID();
            MigrationPlan plan = MigrationPlan.empty(UUID.randomUUID(), "spotify-123", "Cool Songs", Instant.now());

            ApprovedMigrationExecutionRequest req = ApprovedMigrationExecutionRequest.ofSessionAndPlan(sessionId, userId, plan);
            assertThat(req.reviewSessionId()).isEqualTo(sessionId);
            assertThat(req.getUserId()).contains(userId);
            assertThat(req.getSourcePlaylistId()).contains("spotify-123");
            assertThat(req.getPlanId()).contains(plan.planId());
            assertThat(req.getTargetPlaylistTitle()).contains("Cool Songs");
            assertThat(req.getPlan()).contains(plan);
        }
    }

    @Nested
    @DisplayName("ExecutionValidationResult & Exception Tests")
    class ExecutionValidationResultTests {

        @Test
        @DisplayName("Valid result invariants enforced")
        void validResultInvariants() {
            UUID sessionId = UUID.randomUUID();
            ApprovedMigrationContext context = new ApprovedMigrationContext(
                    sessionId, null, UUID.randomUUID(), "sp-pl", "Title", "Migrated", null, false,
                    List.of(), 0, 0, 0, Instant.now()
            );

            ExecutionValidationResult result = ExecutionValidationResult.valid(context);
            assertThat(result.isValid()).isTrue();
            assertThat(result.getApprovedContext()).contains(context);
            assertThat(result.hasErrors()).isFalse();
            assertThat(result.firstErrorMessage()).isEmpty();
        }

        @Test
        @DisplayName("Invalid result captures errors properly")
        void invalidResult() {
            ExecutionValidationResult result = ExecutionValidationResult.invalid(
                    ValidationFailureCode.INCOMPLETE_REVIEW,
                    "Session is in progress"
            );

            assertThat(result.isValid()).isFalse();
            assertThat(result.getApprovedContext()).isEmpty();
            assertThat(result.hasErrors()).isTrue();
            assertThat(result.firstErrorMessage()).isEqualTo("Session is in progress");
            assertThat(result.errors()).hasSize(1);
            assertThat(result.errors().getFirst().code()).isEqualTo(ValidationFailureCode.INCOMPLETE_REVIEW);
        }

        @Test
        @DisplayName("MigrationExecutionValidationException formats single and multiple errors")
        void exceptionFormatting() {
            ExecutionValidationResult singleErr = ExecutionValidationResult.invalid(
                    ValidationFailureCode.UNAUTHORIZED, "User not authorized"
            );
            MigrationExecutionValidationException ex1 = new MigrationExecutionValidationException(singleErr);
            assertThat(ex1).isInstanceOf(InvalidStateException.class);
            assertThat(ex1.getMessage()).isEqualTo("User not authorized");
            assertThat(ex1.getValidationResult()).isEqualTo(singleErr);

            ExecutionValidationResult multiErr = ExecutionValidationResult.invalid(List.of(
                    ExecutionValidationError.sessionLevel(ValidationFailureCode.INCOMPLETE_REVIEW, "Review in progress"),
                    ExecutionValidationError.trackLevel(ValidationFailureCode.UNRESOLVED_TRACK, 1, "sp-1", "Track 1 awaiting review")
            ));
            MigrationExecutionValidationException ex2 = new MigrationExecutionValidationException(multiErr);
            assertThat(ex2.getMessage()).contains("Migration execution validation failed with 2 errors");
            assertThat(ex2.getMessage()).contains("Review in progress");
            assertThat(ex2.getMessage()).contains("Track 1 awaiting review");
        }
    }
}
