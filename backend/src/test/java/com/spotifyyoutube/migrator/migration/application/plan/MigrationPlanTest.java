package com.spotifyyoutube.migrator.migration.application.plan;

import com.spotifyyoutube.migrator.matching.engine.MatchDecision;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationTrackCandidate;
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

@DisplayName("MigrationPlan and Planning Contracts Tests")
class MigrationPlanTest {

    private final UUID planId = UUID.randomUUID();
    private final String playlistId = "spotify-pl-123";
    private final String playlistTitle = "My Roadtrip Playlist";
    private final Instant now = Instant.now();

    private DestinationTrackCandidate createCandidate(String id, String title, String artist) {
        return new DestinationTrackCandidate(
                id,
                title,
                artist,
                "Description for " + title,
                "https://i.ytimg.com/vi/" + id + "/default.jpg",
                "2024-01-01T00:00:00Z",
                Duration.ofSeconds(210)
        );
    }

    @Nested
    @DisplayName("MigrationPlan Root Invariant Tests")
    class PlanInvariantTests {

        @Test
        @DisplayName("Valid plan construction succeeds and preserves attributes")
        void validPlanConstructsCorrectly() {
            var cand1 = createCandidate("yt-1", "Song A", "Artist A");
            var cand2 = createCandidate("yt-2", "Song B", "Artist B");

            var track0 = MigrationPlanTrack.autoMatch(
                    0, "sp-1", "Song A", "Artist A", cand1, 0.95, 0.98, List.of("Exact match")
            );
            var track1 = MigrationPlanTrack.reviewRequired(
                    1, "sp-2", "Song B", "Artist B", cand2, 0.72, 0.65, List.of("Duration variance")
            );

            var tracks = List.of(track0, track1);
            var plan = new MigrationPlan(planId, playlistId, playlistTitle, now, tracks);

            assertThat(plan.planId()).isEqualTo(planId);
            assertThat(plan.sourcePlaylistId()).isEqualTo(playlistId);
            assertThat(plan.sourcePlaylistTitle()).isEqualTo(playlistTitle);
            assertThat(plan.createdAt()).isEqualTo(now);
            assertThat(plan.tracks()).hasSize(2);
            assertThat(plan.summary().totalTracks()).isEqualTo(2);
            assertThat(plan.summary().autoMatchCount()).isEqualTo(1);
            assertThat(plan.summary().reviewRequiredCount()).isEqualTo(1);
            assertThat(plan.summary().unmatchedCount()).isZero();
            assertThat(plan.summary().duplicateCount()).isZero();
            assertThat(plan.summary().failedCount()).isZero();
            assertThat(plan.summary().excludedCount()).isZero();
        }

        @Test
        @DisplayName("Empty plan factory creates valid empty plan with zero summary")
        void emptyPlanFactoryCreatesValidPlan() {
            var plan = MigrationPlan.empty(planId, playlistId, playlistTitle, now);

            assertThat(plan.tracks()).isEmpty();
            assertThat(plan.summary().totalTracks()).isZero();
            assertThat(plan.summary().isEmpty()).isTrue();
            assertThat(plan.summary().getAutoMatchRate()).isEqualTo(0.0);
            assertThat(plan.summary().getReviewRate()).isEqualTo(0.0);
            assertThat(plan.summary().getUnmatchedRate()).isEqualTo(0.0);
            assertThat(plan.summary().getFailureRate()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("Tracks collection is defensively copied and unmodifiable")
        void tracksCollectionIsDefensivelyCopied() {
            var track0 = MigrationPlanTrack.unmatched(0, "sp-1", "Song A", "Artist A", List.of("No candidate"));
            var mutableList = new ArrayList<MigrationPlanTrack>();
            mutableList.add(track0);

            var plan = new MigrationPlan(planId, playlistId, playlistTitle, now, mutableList);

            // Modifying original list must not affect plan
            var track1 = MigrationPlanTrack.unmatched(1, "sp-2", "Song B", "Artist B", List.of("No candidate"));
            mutableList.add(track1);
            assertThat(plan.tracks()).hasSize(1);

            // Plan's tracks list must reject modification
            assertThatThrownBy(() -> plan.tracks().add(track1))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("Source playlist order must be strictly preserved without gaps or reordering")
        void outOfOrderTracksRejected() {
            var track0 = MigrationPlanTrack.unmatched(1, "sp-1", "Song A", "Artist A", List.of()); // position 1 at index 0

            assertThatThrownBy(() -> new MigrationPlan(planId, playlistId, playlistTitle, now, List.of(track0)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("out-of-order position");
        }

        @Test
        @DisplayName("Summary count must reconcile with tracks list size")
        void mismatchedSummaryRejected() {
            var track0 = MigrationPlanTrack.unmatched(0, "sp-1", "Song A", "Artist A", List.of());
            var mismatchedSummary = new MigrationPlanSummary(2, 0, 0, 2, 0, 0, 0); // total 2, but only 1 track

            assertThatThrownBy(() -> new MigrationPlan(planId, playlistId, playlistTitle, now, List.of(track0), mismatchedSummary))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does not match tracks count");
        }

        @Test
        @DisplayName("Mandatory fields reject nulls and blanks")
        void mandatoryFieldsValidated() {
            var summary = MigrationPlanSummary.empty();

            assertThatThrownBy(() -> new MigrationPlan(null, playlistId, playlistTitle, now, List.of(), summary))
                    .isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> new MigrationPlan(planId, null, playlistTitle, now, List.of(), summary))
                    .isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> new MigrationPlan(planId, "   ", playlistTitle, now, List.of(), summary))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> new MigrationPlan(planId, playlistId, null, now, List.of(), summary))
                    .isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> new MigrationPlan(planId, playlistId, playlistTitle, null, List.of(), summary))
                    .isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> new MigrationPlan(planId, playlistId, playlistTitle, now, List.of(), null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("MigrationPlanTrack Invariant & Classification Tests")
    class TrackInvariantTests {

        @Test
        @DisplayName("Negative position is rejected")
        void negativePositionRejected() {
            assertThatThrownBy(() -> MigrationPlanTrack.unmatched(-1, "sp-1", "Title", "Artist", List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("non-negative");
        }

        @Test
        @DisplayName("Blank or null source track id is rejected")
        void blankSourceTrackIdRejected() {
            assertThatThrownBy(() -> MigrationPlanTrack.unmatched(0, "  ", "Title", "Artist", List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("blank");

            assertThatThrownBy(() -> MigrationPlanTrack.unmatched(0, null, "Title", "Artist", List.of()))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Null title or artist rejected")
        void nullMetadataRejected() {
            assertThatThrownBy(() -> MigrationPlanTrack.unmatched(0, "sp-1", null, "Artist", List.of()))
                    .isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> MigrationPlanTrack.unmatched(0, "sp-1", "Title", null, List.of()))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Scores out of [0.0, 1.0] range are rejected")
        void outOfBoundsScoresRejected() {
            var cand = createCandidate("yt-1", "Title", "Artist");

            assertThatThrownBy(() -> new MigrationPlanTrack(
                    0, "sp-1", "Title", "Artist", PlanTrackClassification.AUTO_MATCH,
                    cand, MatchDecision.MATCH, 1.05, 0.9, List.of(), null
            )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("matchScore");

            assertThatThrownBy(() -> new MigrationPlanTrack(
                    0, "sp-1", "Title", "Artist", PlanTrackClassification.AUTO_MATCH,
                    cand, MatchDecision.MATCH, 0.9, -0.01, List.of(), null
            )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("calibratedProbability");
        }

        @Test
        @DisplayName("Match reasons collection is defensively copied and unmodifiable")
        void matchReasonsDefensivelyCopied() {
            var reasons = new ArrayList<String>();
            reasons.add("Reason 1");

            var track = MigrationPlanTrack.unmatched(0, "sp-1", "Title", "Artist", reasons);

            reasons.add("Reason 2");
            assertThat(track.matchReasons()).containsExactly("Reason 1");

            assertThatThrownBy(() -> track.matchReasons().add("Reason 3"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("Sensitive credentials in error messages are sanitized")
        void errorMessagesSanitized() {
            String rawError = "Request failed with Bearer ya29.a0AfH6SM... and access_token=secret123&api_key=AIzaSyD...";
            var track = MigrationPlanTrack.failed(0, "sp-1", "Title", "Artist", rawError);

            assertThat(track.errorMessage()).doesNotContain("ya29.a0AfH6SM");
            assertThat(track.errorMessage()).doesNotContain("secret123");
            assertThat(track.errorMessage()).doesNotContain("AIzaSyD");
            assertThat(track.errorMessage()).contains("Bearer [REDACTED]");
            assertThat(track.errorMessage()).contains("access_token=[REDACTED]");
            assertThat(track.errorMessage()).contains("api_key=[REDACTED]");
        }

        @Test
        @DisplayName("Classification predicates correctly identify status")
        void classificationPredicates() {
            var cand = createCandidate("yt-1", "Title", "Artist");

            var auto = MigrationPlanTrack.autoMatch(0, "sp-1", "T", "A", cand, 0.9, 0.9, List.of());
            assertThat(auto.isAutoMatch()).isTrue();
            assertThat(auto.isReviewRequired()).isFalse();

            var review = MigrationPlanTrack.reviewRequired(0, "sp-1", "T", "A", cand, 0.7, 0.6, List.of());
            assertThat(review.isReviewRequired()).isTrue();
            assertThat(review.isAutoMatch()).isFalse();

            var dup = MigrationPlanTrack.duplicate(0, "sp-1", "T", "A", cand, 0.9, 0.9, "Duplicate");
            assertThat(dup.isDuplicate()).isTrue();

            var unmatched = MigrationPlanTrack.unmatched(0, "sp-1", "T", "A", List.of());
            assertThat(unmatched.isUnmatched()).isTrue();

            var failed = MigrationPlanTrack.failed(0, "sp-1", "T", "A", "API timeout");
            assertThat(failed.isFailed()).isTrue();

            var excluded = MigrationPlanTrack.excluded(0, "sp-1", "T", "A", "User excluded");
            assertThat(excluded.isExcluded()).isTrue();
        }
    }

    @Nested
    @DisplayName("MigrationPlanSummary Invariant & Reconciliation Tests")
    class SummaryInvariantTests {

        @Test
        @DisplayName("Valid summary reconciles sum of all categories")
        void validSummaryReconciles() {
            var summary = new MigrationPlanSummary(10, 5, 2, 1, 1, 1, 0);

            assertThat(summary.totalTracks()).isEqualTo(10);
            assertThat(summary.autoMatchCount()).isEqualTo(5);
            assertThat(summary.reviewRequiredCount()).isEqualTo(2);
            assertThat(summary.unmatchedCount()).isEqualTo(1);
            assertThat(summary.duplicateCount()).isEqualTo(1);
            assertThat(summary.failedCount()).isEqualTo(1);
            assertThat(summary.excludedCount()).isZero();

            assertThat(summary.getAutoMatchRate()).isEqualTo(0.5);
            assertThat(summary.getReviewRate()).isEqualTo(0.2);
            assertThat(summary.getUnmatchedRate()).isEqualTo(0.1);
            assertThat(summary.getDuplicateRate()).isEqualTo(0.1);
            assertThat(summary.getFailureRate()).isEqualTo(0.1);
            assertThat(summary.getExcludedRate()).isEqualTo(0.0);
            assertThat(summary.hasIssues()).isTrue();
            assertThat(summary.isActionable()).isTrue();
        }

        @Test
        @DisplayName("Summary derivation from tracks satisfies reconciliation invariant")
        void summaryDerivedFromTracks() {
            var cand = createCandidate("yt-1", "T", "A");

            var tracks = List.of(
                    MigrationPlanTrack.autoMatch(0, "s-0", "T0", "A", cand, 0.9, 0.9, List.of()),
                    MigrationPlanTrack.autoMatch(1, "s-1", "T1", "A", cand, 0.9, 0.9, List.of()),
                    MigrationPlanTrack.reviewRequired(2, "s-2", "T2", "A", cand, 0.7, 0.6, List.of()),
                    MigrationPlanTrack.duplicate(3, "s-3", "T3", "A", cand, 0.9, 0.9, "dup"),
                    MigrationPlanTrack.unmatched(4, "s-4", "T4", "A", List.of()),
                    MigrationPlanTrack.failed(5, "s-5", "T5", "A", "error"),
                    MigrationPlanTrack.excluded(6, "s-6", "T6", "A", "skip")
            );

            var summary = MigrationPlanSummary.fromTracks(tracks);

            assertThat(summary.totalTracks()).isEqualTo(7);
            assertThat(summary.autoMatchCount()).isEqualTo(2);
            assertThat(summary.reviewRequiredCount()).isEqualTo(1);
            assertThat(summary.duplicateCount()).isEqualTo(1);
            assertThat(summary.unmatchedCount()).isEqualTo(1);
            assertThat(summary.failedCount()).isEqualTo(1);
            assertThat(summary.excludedCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("Summary throws IllegalArgumentException when totalTracks does not match category sum")
        void unreconciledSummaryThrows() {
            assertThatThrownBy(() -> new MigrationPlanSummary(10, 5, 2, 1, 1, 0, 0)) // sum is 9 != 10
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Summary counts do not reconcile");
        }

        @Test
        @DisplayName("Summary throws IllegalArgumentException when counts are negative")
        void negativeCountsThrow() {
            assertThatThrownBy(() -> new MigrationPlanSummary(1, -1, 1, 1, 0, 0, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("non-negative");
        }
    }
}
