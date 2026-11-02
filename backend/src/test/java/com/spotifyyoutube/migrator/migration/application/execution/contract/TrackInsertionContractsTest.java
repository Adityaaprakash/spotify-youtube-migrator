package com.spotifyyoutube.migrator.migration.application.execution.contract;

import com.spotifyyoutube.migrator.playlist.domain.Platform;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Phase 11D: Track Insertion Domain Contracts Test")
class TrackInsertionContractsTest {

    private final UUID sessionId = UUID.randomUUID();
    private final UUID planId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final Instant now = Instant.now();

    @Nested
    @DisplayName("TrackInsertionStatus Enum Tests")
    class StatusEnumTests {
        @Test
        @DisplayName("Enum contains all expected lifecycle statuses")
        void enumContainsExpectedStatuses() {
            assertThat(TrackInsertionStatus.values()).containsExactly(
                    TrackInsertionStatus.SUCCESS,
                    TrackInsertionStatus.FAILED,
                    TrackInsertionStatus.UNCERTAIN,
                    TrackInsertionStatus.SKIPPED
            );
        }
    }

    @Nested
    @DisplayName("TrackInsertionOutcome Record & Factory Tests")
    class OutcomeTests {

        @Test
        @DisplayName("Success factory creates valid SUCCESS outcome")
        void successFactory() {
            TrackInsertionOutcome outcome = TrackInsertionOutcome.success(
                    0, 5, "sp-123", "Song Title", "Artist Name", "yt-vid-999", "yt-item-001"
            );

            assertThat(outcome.targetPosition()).isEqualTo(0);
            assertThat(outcome.sourcePosition()).isEqualTo(5);
            assertThat(outcome.sourceTrackId()).isEqualTo("sp-123");
            assertThat(outcome.sourceTitle()).isEqualTo("Song Title");
            assertThat(outcome.sourceArtist()).isEqualTo("Artist Name");
            assertThat(outcome.destinationTrackId()).isEqualTo("yt-vid-999");
            assertThat(outcome.destinationPlaylistItemId()).isEqualTo("yt-item-001");
            assertThat(outcome.status()).isEqualTo(TrackInsertionStatus.SUCCESS);
            assertThat(outcome.message()).isEqualTo("Successfully added to destination playlist");
        }

        @Test
        @DisplayName("Failed factory creates valid FAILED outcome with sanitized message")
        void failedFactory() {
            TrackInsertionOutcome outcome = TrackInsertionOutcome.failed(
                    1, 6, "sp-456", "Song 2", "Artist 2", "yt-vid-888", "Video unavailable in target country"
            );

            assertThat(outcome.targetPosition()).isEqualTo(1);
            assertThat(outcome.sourcePosition()).isEqualTo(6);
            assertThat(outcome.status()).isEqualTo(TrackInsertionStatus.FAILED);
            assertThat(outcome.destinationPlaylistItemId()).isNull();
            assertThat(outcome.message()).isEqualTo("Video unavailable in target country");
        }

        @Test
        @DisplayName("Uncertain factory creates valid UNCERTAIN outcome")
        void uncertainFactory() {
            TrackInsertionOutcome outcome = TrackInsertionOutcome.uncertain(
                    2, 7, "sp-789", "Song 3", "Artist 3", "yt-vid-777", "Timeout awaiting provider response"
            );

            assertThat(outcome.targetPosition()).isEqualTo(2);
            assertThat(outcome.sourcePosition()).isEqualTo(7);
            assertThat(outcome.status()).isEqualTo(TrackInsertionStatus.UNCERTAIN);
            assertThat(outcome.destinationPlaylistItemId()).isNull();
            assertThat(outcome.message()).isEqualTo("Timeout awaiting provider response");
        }

        @Test
        @DisplayName("Skipped factory creates valid SKIPPED outcome")
        void skippedFactory() {
            TrackInsertionOutcome outcome = TrackInsertionOutcome.skipped(
                    3, 8, "sp-000", "Song 4", "Artist 4", "yt-vid-666", "Skipped due to prior failure"
            );

            assertThat(outcome.targetPosition()).isEqualTo(3);
            assertThat(outcome.sourcePosition()).isEqualTo(8);
            assertThat(outcome.status()).isEqualTo(TrackInsertionStatus.SKIPPED);
            assertThat(outcome.destinationPlaylistItemId()).isNull();
            assertThat(outcome.message()).isEqualTo("Skipped due to prior failure");
        }

        @Test
        @DisplayName("Rejects negative positions")
        void rejectsNegativePositions() {
            assertThatThrownBy(() -> new TrackInsertionOutcome(
                    -1, 0, "sp-1", "Title", "Artist", "yt-1", null, TrackInsertionStatus.SUCCESS, "OK"
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("targetPosition must be non-negative");

            assertThatThrownBy(() -> new TrackInsertionOutcome(
                    0, -1, "sp-1", "Title", "Artist", "yt-1", null, TrackInsertionStatus.SUCCESS, "OK"
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sourcePosition must be non-negative");
        }

        @Test
        @DisplayName("Rejects null destinationTrackId or status")
        void rejectsNullRequiredFields() {
            assertThatThrownBy(() -> new TrackInsertionOutcome(
                    0, 0, "sp-1", "Title", "Artist", null, null, TrackInsertionStatus.SUCCESS, "OK"
            )).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("destinationTrackId");

            assertThatThrownBy(() -> new TrackInsertionOutcome(
                    0, 0, "sp-1", "Title", "Artist", "yt-1", null, null, "OK"
            )).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("status");
        }
    }

    @Nested
    @DisplayName("PlaylistTrackInsertionSummary Invariant & Reconciliation Tests")
    class SummaryTests {

        @Test
        @DisplayName("Valid summary computes fullySuccessful and executionStatus correctly")
        void validSummaryInstantiation() {
            TrackInsertionOutcome o0 = TrackInsertionOutcome.success(0, 0, "s0", "T0", "A0", "y0", "it0");
            TrackInsertionOutcome o1 = TrackInsertionOutcome.success(1, 1, "s1", "T1", "A1", "y1", "it1");

            PlaylistTrackInsertionSummary summary = new PlaylistTrackInsertionSummary(
                    sessionId, planId, userId, Platform.YOUTUBE, "PL_dest_1",
                    2, 2, 0, 0, 0,
                    List.of(o0, o1),
                    now.minusSeconds(2), now
            );

            assertThat(summary.reviewSessionId()).isEqualTo(sessionId);
            assertThat(summary.getPlanId()).contains(planId);
            assertThat(summary.destinationPlaylistId()).isEqualTo("PL_dest_1");
            assertThat(summary.totalApprovedTracks()).isEqualTo(2);
            assertThat(summary.successfulInsertions()).isEqualTo(2);
            assertThat(summary.failedInsertions()).isEqualTo(0);
            assertThat(summary.uncertainInsertions()).isEqualTo(0);
            assertThat(summary.skippedInsertions()).isEqualTo(0);
            assertThat(summary.fullySuccessful()).isTrue();
            assertThat(summary.getExecutionStatus()).isEqualTo("COMPLETED");
            assertThat(summary.trackOutcomes()).hasSize(2);
        }

        @Test
        @DisplayName("Summary with failures classifies as PARTIALLY_FAILED")
        void partiallyFailedSummary() {
            TrackInsertionOutcome o0 = TrackInsertionOutcome.success(0, 0, "s0", "T0", "A0", "y0", "it0");
            TrackInsertionOutcome o1 = TrackInsertionOutcome.failed(1, 1, "s1", "T1", "A1", "y1", "Rejected");
            TrackInsertionOutcome o2 = TrackInsertionOutcome.skipped(2, 2, "s2", "T2", "A2", "y2", "Skipped");

            PlaylistTrackInsertionSummary summary = new PlaylistTrackInsertionSummary(
                    sessionId, planId, userId, Platform.YOUTUBE, "PL_dest_1",
                    3, 1, 1, 0, 1,
                    List.of(o0, o1, o2),
                    now.minusSeconds(2), now
            );

            assertThat(summary.fullySuccessful()).isFalse();
            assertThat(summary.getExecutionStatus()).isEqualTo("PARTIALLY_FAILED");
        }

        @Test
        @DisplayName("Summary with uncertain outcome classifies as STOPPED_ON_UNCERTAIN_OUTCOME")
        void uncertainSummary() {
            TrackInsertionOutcome o0 = TrackInsertionOutcome.success(0, 0, "s0", "T0", "A0", "y0", "it0");
            TrackInsertionOutcome o1 = TrackInsertionOutcome.uncertain(1, 1, "s1", "T1", "A1", "y1", "Timed out");

            PlaylistTrackInsertionSummary summary = new PlaylistTrackInsertionSummary(
                    sessionId, planId, userId, Platform.YOUTUBE, "PL_dest_1",
                    2, 1, 0, 1, 0,
                    List.of(o0, o1),
                    now.minusSeconds(2), now
            );

            assertThat(summary.fullySuccessful()).isFalse();
            assertThat(summary.getExecutionStatus()).isEqualTo("STOPPED_ON_UNCERTAIN_OUTCOME");
        }

        @Test
        @DisplayName("Summary with zero successful insertions classifies as FAILED")
        void completelyFailedSummary() {
            TrackInsertionOutcome o0 = TrackInsertionOutcome.failed(0, 0, "s0", "T0", "A0", "y0", "Rejected");

            PlaylistTrackInsertionSummary summary = new PlaylistTrackInsertionSummary(
                    sessionId, planId, userId, Platform.YOUTUBE, "PL_dest_1",
                    1, 0, 1, 0, 0,
                    List.of(o0),
                    now.minusSeconds(2), now
            );

            assertThat(summary.fullySuccessful()).isFalse();
            assertThat(summary.getExecutionStatus()).isEqualTo("FAILED");
        }

        @Test
        @DisplayName("Reconciliation invariant: sum of outcomes must equal totalApprovedTracks")
        void enforcesReconciliationInvariant() {
            TrackInsertionOutcome o0 = TrackInsertionOutcome.success(0, 0, "s0", "T0", "A0", "y0", "it0");

            assertThatThrownBy(() -> new PlaylistTrackInsertionSummary(
                    sessionId, planId, userId, Platform.YOUTUBE, "PL_dest_1",
                    3, 1, 0, 0, 0, // 1 != 3
                    List.of(o0),
                    now.minusSeconds(2), now
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does not match totalApprovedTracks");
        }

        @Test
        @DisplayName("Outcomes count must match totalApprovedTracks")
        void enforcesOutcomesSizeMatchesTotal() {
            TrackInsertionOutcome o0 = TrackInsertionOutcome.success(0, 0, "s0", "T0", "A0", "y0", "it0");

            assertThatThrownBy(() -> new PlaylistTrackInsertionSummary(
                    sessionId, planId, userId, Platform.YOUTUBE, "PL_dest_1",
                    2, 2, 0, 0, 0, // total says 2, but list has 1
                    List.of(o0),
                    now.minusSeconds(2), now
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("trackOutcomes size (1) does not match totalApprovedTracks (2)");
        }

        @Test
        @DisplayName("Monotonicity invariant: outcomes must be strictly ordered by targetPosition")
        void enforcesTargetPositionMonotonicity() {
            TrackInsertionOutcome o1 = TrackInsertionOutcome.success(1, 1, "s1", "T1", "A1", "y1", "it1");
            TrackInsertionOutcome o0 = TrackInsertionOutcome.success(0, 0, "s0", "T0", "A0", "y0", "it0");

            assertThatThrownBy(() -> new PlaylistTrackInsertionSummary(
                    sessionId, planId, userId, Platform.YOUTUBE, "PL_dest_1",
                    2, 2, 0, 0, 0,
                    List.of(o1, o0), // inverted order!
                    now.minusSeconds(2), now
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("out-of-sequence targetPosition");
        }

        @Test
        @DisplayName("List defensive copy prevents mutation after construction")
        void enforcesListImmutability() {
            TrackInsertionOutcome o0 = TrackInsertionOutcome.success(0, 0, "s0", "T0", "A0", "y0", "it0");
            List<TrackInsertionOutcome> list = new ArrayList<>();
            list.add(o0);

            PlaylistTrackInsertionSummary summary = new PlaylistTrackInsertionSummary(
                    sessionId, planId, userId, Platform.YOUTUBE, "PL_dest_1",
                    1, 1, 0, 0, 0,
                    list,
                    now.minusSeconds(2), now
            );

            assertThatThrownBy(() -> summary.trackOutcomes().add(o0))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("InsertTracksExecutionRequest Tests")
    class RequestTests {

        @Test
        @DisplayName("Valid request creation and factories")
        void validRequestCreation() {
            InsertTracksExecutionRequest req = InsertTracksExecutionRequest.of(sessionId, "PL_dest_999", userId);

            assertThat(req.reviewSessionId()).isEqualTo(sessionId);
            assertThat(req.destinationPlaylistId()).isEqualTo("PL_dest_999");
            assertThat(req.userId()).isEqualTo(userId);
            assertThat(req.getUserId()).contains(userId);
        }

        @Test
        @DisplayName("Request without explicit user has null userId and hasExplicitUser() false")
        void requestWithoutUser() {
            InsertTracksExecutionRequest req = InsertTracksExecutionRequest.of(sessionId, "PL_dest_999");

            assertThat(req.userId()).isNull();
            assertThat(req.getUserId()).isEmpty();
        }

        @Test
        @DisplayName("Rejects null or blank destinationPlaylistId")
        void rejectsInvalidDestinationPlaylistId() {
            assertThatThrownBy(() -> new InsertTracksExecutionRequest(sessionId, null, userId))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("destinationPlaylistId");

            assertThatThrownBy(() -> new InsertTracksExecutionRequest(sessionId, "   ", userId))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("destinationPlaylistId must not be blank");
        }

        @Test
        @DisplayName("Rejects null reviewSessionId")
        void rejectsNullReviewSessionId() {
            assertThatThrownBy(() -> new InsertTracksExecutionRequest(null, "PL_dest_999", userId))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("reviewSessionId");
        }
    }
}
