package com.spotifyyoutube.migrator.migration.review.domain;

import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.matching.engine.MatchDecision;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationTrackCandidate;
import com.spotifyyoutube.migrator.migration.application.plan.PlanTrackClassification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Phase 10B: Review Domain Contracts and State Transitions Test")
class ReviewSessionDomainTest {

    private User testUser;
    private ReviewSession session;

    @BeforeEach
    void setUp() {
        testUser = new User("reviewer@example.com");
        testUser.setId(UUID.randomUUID());
        testUser.setDisplayName("Reviewer");

        session = new ReviewSession(testUser, "spotify-playlist-101", "Chill Vibes", UUID.randomUUID());
        session.setId(UUID.randomUUID());
    }

    private ReviewTrack createSampleTrack(
            int position,
            String sourceTrackId,
            PlanTrackClassification classification,
            String originalCandidateId,
            Double originalScore,
            Double originalCalibratedProb
    ) {
        ReviewTrack track = new ReviewTrack();
        track.setId(UUID.randomUUID());
        track.setPosition(position);
        track.setSourceTrackId(sourceTrackId);
        track.setSourceTitle("Track Title " + position);
        track.setSourceArtist("Track Artist " + position);
        track.setOriginalClassification(classification);
        track.setOriginalCandidateId(originalCandidateId);
        track.setOriginalCandidateTitle("Target Title " + position);
        track.setOriginalCandidateChannel("Official Channel");
        track.setOriginalCandidateDurationMs(210000L);
        track.setOriginalMatchScore(originalScore);
        track.setOriginalCalibratedProbability(originalCalibratedProb);
        track.setOriginalMatchDecision(originalScore != null && originalScore >= 0.75 ? MatchDecision.MATCH : MatchDecision.REVIEW_REQUIRED);
        track.setOriginalMatchReasons("Similarity score: " + originalScore);

        session.addTrack(track);
        return track;
    }

    @Nested
    @DisplayName("Review Track Decision Transitions & Provenance")
    class TrackDecisionTests {

        @Test
        @DisplayName("Manual candidate selection preserves original matching evidence untouched and records USER_SELECTED")
        void manualCandidateSelectionPreservesOriginalEvidence() {
            ReviewTrack track = createSampleTrack(0, "spotify-0", PlanTrackClassification.REVIEW_REQUIRED, "yt-auto-0", 0.72, 0.65);
            track.setReviewStatus(ReviewTrackStatus.AWAITING_REVIEW);

            DestinationTrackCandidate manualCandidate = new DestinationTrackCandidate(
                    "yt-manual-99",
                    "Better Official Video",
                    "Official Artist VEVO",
                    "Official Video Description",
                    "http://thumb.jpg",
                    "2023-01-01",
                    Duration.ofSeconds(212)
            );

            track.selectCandidate(manualCandidate, "Selected higher quality official audio");

            // Verify current decision state
            assertThat(track.getReviewStatus()).isEqualTo(ReviewTrackStatus.USER_SELECTED);
            assertThat(track.getDecisionSource()).isEqualTo(ReviewDecisionSource.USER_SELECTED);
            assertThat(track.getSelectedCandidateId()).isEqualTo("yt-manual-99");
            assertThat(track.getSelectedCandidateTitle()).isEqualTo("Better Official Video");
            assertThat(track.getSelectedCandidateChannel()).isEqualTo("Official Artist VEVO");
            assertThat(track.getSelectedCandidateDurationMs()).isEqualTo(212000L);
            assertThat(track.isExcluded()).isFalse();
            assertThat(track.isApprovedForMigration()).isTrue();
            assertThat(track.getDecisionNotes()).isEqualTo("Selected higher quality official audio");

            // CRITICAL: Original automatic matching evidence MUST remain completely untouched!
            assertThat(track.getOriginalClassification()).isEqualTo(PlanTrackClassification.REVIEW_REQUIRED);
            assertThat(track.getOriginalCandidateId()).isEqualTo("yt-auto-0");
            assertThat(track.getOriginalCandidateTitle()).isEqualTo("Target Title 0");
            assertThat(track.getOriginalMatchScore()).isEqualTo(0.72);
            assertThat(track.getOriginalCalibratedProbability()).isEqualTo(0.65);
            assertThat(track.getOriginalMatchDecision()).isEqualTo(MatchDecision.REVIEW_REQUIRED);
            assertThat(track.getOriginalMatchReasons()).isEqualTo("Similarity score: 0.72");
        }

        @Test
        @DisplayName("Candidate validation rejects null, blank, or negative duration candidates")
        void candidateValidationRejectsInvalidCandidates() {
            ReviewTrack track = createSampleTrack(0, "spotify-0", PlanTrackClassification.REVIEW_REQUIRED, "yt-0", 0.70, 0.60);

            assertThatThrownBy(() -> track.selectCandidate(null, "Notes"))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Candidate must not be null");

            DestinationTrackCandidate blankId = new DestinationTrackCandidate("   ", "Valid Title", "Artist", null, null, null, Duration.ofSeconds(180));
            assertThatThrownBy(() -> track.selectCandidate(blankId, "Notes"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Candidate ID must not be null or blank");

            DestinationTrackCandidate blankTitle = new DestinationTrackCandidate("valid-id", "   ", "Artist", null, null, null, Duration.ofSeconds(180));
            assertThatThrownBy(() -> track.selectCandidate(blankTitle, "Notes"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Candidate title must not be null or blank");

            assertThatThrownBy(() -> new DestinationTrackCandidate("valid-id", "Title", "Artist", null, null, null, Duration.ofSeconds(-10)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Duration cannot be negative");
        }

        @Test
        @DisplayName("Accepting original match succeeds when candidate exists and is idempotent")
        void acceptOriginalMatchSucceedsAndIsIdempotent() {
            ReviewTrack track = createSampleTrack(0, "spotify-0", PlanTrackClassification.REVIEW_REQUIRED, "yt-auto-0", 0.74, 0.70);
            track.setReviewStatus(ReviewTrackStatus.AWAITING_REVIEW);

            track.acceptOriginalMatch();

            assertThat(track.getReviewStatus()).isEqualTo(ReviewTrackStatus.ACCEPTED);
            assertThat(track.getSelectedCandidateId()).isEqualTo("yt-auto-0");
            assertThat(track.getSelectedCandidateTitle()).isEqualTo("Target Title 0");
            assertThat(track.isApprovedForMigration()).isTrue();
            assertThat(track.isExcluded()).isFalse();

            // Repeating the same decision is idempotent
            track.acceptOriginalMatch();
            assertThat(track.getReviewStatus()).isEqualTo(ReviewTrackStatus.ACCEPTED);
            assertThat(track.getSelectedCandidateId()).isEqualTo("yt-auto-0");
        }

        @Test
        @DisplayName("Accepting original match fails when track has no original candidate")
        void acceptOriginalMatchFailsWithoutCandidate() {
            ReviewTrack track = createSampleTrack(0, "spotify-0", PlanTrackClassification.UNMATCHED, null, null, null);
            track.setReviewStatus(ReviewTrackStatus.AWAITING_REVIEW);

            assertThatThrownBy(track::acceptOriginalMatch)
                    .isInstanceOf(InvalidStateException.class)
                    .hasMessageContaining("no automatic candidate exists");
        }

        @Test
        @DisplayName("Explicit track exclusion marks excluded and prevents approval for migration (Invariant 3)")
        void explicitTrackExclusionEnforcesInvariant3() {
            ReviewTrack track = createSampleTrack(0, "spotify-0", PlanTrackClassification.AUTO_MATCH, "yt-auto-0", 0.95, 0.98);
            track.setReviewStatus(ReviewTrackStatus.ACCEPTED);
            track.setSelectedCandidateId("yt-auto-0");

            track.exclude("User does not want this track in destination playlist");

            assertThat(track.getReviewStatus()).isEqualTo(ReviewTrackStatus.EXCLUDED);
            assertThat(track.isExcluded()).isTrue();
            assertThat(track.getDecisionSource()).isEqualTo(ReviewDecisionSource.USER_SELECTED);
            assertThat(track.getSelectedCandidateId()).isNull();
            assertThat(track.getDecisionNotes()).isEqualTo("User does not want this track in destination playlist");

            // INVARIANT 3: Excluded track must NEVER be treated as approved for migration
            assertThat(track.isApprovedForMigration()).isFalse();

            // Repeating exclusion is idempotent
            track.exclude("Repeated call");
            assertThat(track.isExcluded()).isTrue();
            assertThat(track.getReviewStatus()).isEqualTo(ReviewTrackStatus.EXCLUDED);
        }

        @Test
        @DisplayName("Modifying track in completed or cancelled session fails fast (Invariant 7)")
        void modifyingTrackInClosedSessionFails() {
            ReviewTrack track = createSampleTrack(0, "spotify-0", PlanTrackClassification.AUTO_MATCH, "yt-0", 0.95, 0.98);
            track.acceptOriginalMatch();

            ReviewProgressSummary summary = session.deriveSummary();
            session.complete(summary);

            DestinationTrackCandidate newCand = new DestinationTrackCandidate("yt-new", "New Title", "Artist", null, null, null, Duration.ofSeconds(120));

            assertThatThrownBy(() -> track.selectCandidate(newCand, "Late edit"))
                    .isInstanceOf(InvalidStateException.class)
                    .hasMessageContaining("Cannot modify review track because session is in status: COMPLETED");

            assertThatThrownBy(track::acceptOriginalMatch)
                    .isInstanceOf(InvalidStateException.class)
                    .hasMessageContaining("Cannot modify review track because session is in status: COMPLETED");

            assertThatThrownBy(() -> track.exclude("Late exclusion"))
                    .isInstanceOf(InvalidStateException.class)
                    .hasMessageContaining("Cannot modify review track because session is in status: COMPLETED");
        }
    }

    @Nested
    @DisplayName("Review Session Lifecycle, Invariants and Completion Eligibility")
    class SessionLifecycleTests {

        @Test
        @DisplayName("Session completion succeeds when all required decisions are resolved")
        void sessionCompletionSucceedsWhenAllDecisionsResolved() {
            ReviewTrack track0 = createSampleTrack(0, "spotify-0", PlanTrackClassification.AUTO_MATCH, "yt-0", 0.95, 0.98);
            track0.setReviewStatus(ReviewTrackStatus.ACCEPTED);
            track0.setSelectedCandidateId("yt-0");

            ReviewTrack track1 = createSampleTrack(1, "spotify-1", PlanTrackClassification.REVIEW_REQUIRED, "yt-1", 0.70, 0.60);
            track1.selectCandidate(new DestinationTrackCandidate("yt-custom-1", "Title 1", "Artist 1", null, null, null, Duration.ofSeconds(200)), "Manual choice");

            ReviewTrack track2 = createSampleTrack(2, "spotify-2", PlanTrackClassification.UNMATCHED, null, null, null);
            track2.exclude("Not in catalog");

            ReviewProgressSummary summary = session.deriveSummary();
            assertThat(summary.totalTracks()).isEqualTo(3);
            assertThat(summary.awaitingReviewCount()).isEqualTo(0);
            assertThat(summary.acceptedCount()).isEqualTo(1);
            assertThat(summary.manuallyCorrectedCount()).isEqualTo(1);
            assertThat(summary.excludedCount()).isEqualTo(1);
            assertThat(summary.unresolvedConflictCount()).isEqualTo(0);
            assertThat(summary.remainingRequiredDecisions()).isEqualTo(0);
            assertThat(summary.eligibleForCompletion()).isTrue();

            session.complete(summary);

            assertThat(session.getStatus()).isEqualTo(ReviewSessionStatus.COMPLETED);
            assertThat(session.isCompleted()).isTrue();
            assertThat(session.getCompletedAt()).isNotNull();
        }

        @Test
        @DisplayName("Session completion fails when required decisions remain unresolved (Invariant 5)")
        void sessionCompletionFailsWithUnresolvedDecisions() {
            ReviewTrack track0 = createSampleTrack(0, "spotify-0", PlanTrackClassification.AUTO_MATCH, "yt-0", 0.95, 0.98);
            track0.setReviewStatus(ReviewTrackStatus.ACCEPTED);
            track0.setSelectedCandidateId("yt-0");

            ReviewTrack track1 = createSampleTrack(1, "spotify-1", PlanTrackClassification.REVIEW_REQUIRED, "yt-1", 0.70, 0.60);
            track1.setReviewStatus(ReviewTrackStatus.AWAITING_REVIEW);

            ReviewProgressSummary summary = session.deriveSummary();
            assertThat(summary.remainingRequiredDecisions()).isEqualTo(1);
            assertThat(summary.eligibleForCompletion()).isFalse();

            assertThatThrownBy(() -> session.complete(summary))
                    .isInstanceOf(InvalidStateException.class)
                    .hasMessageContaining("required decisions remain unresolved");

            assertThat(session.getStatus()).isEqualTo(ReviewSessionStatus.IN_PROGRESS);
        }

        @Test
        @DisplayName("Session completion fails when active duplicate conflict exists (Invariant 4)")
        void sessionCompletionFailsWithDuplicateConflict() {
            ReviewTrack track0 = createSampleTrack(0, "spotify-0", PlanTrackClassification.AUTO_MATCH, "yt-shared", 0.95, 0.98);
            track0.setReviewStatus(ReviewTrackStatus.ACCEPTED);
            track0.setSelectedCandidateId("yt-shared");

            ReviewTrack track1 = createSampleTrack(1, "spotify-1", PlanTrackClassification.AUTO_MATCH, "yt-shared", 0.90, 0.92);
            track1.setReviewStatus(ReviewTrackStatus.ACCEPTED);
            track1.setSelectedCandidateId("yt-shared");
            track1.setConflict("Duplicate destination candidate: 'yt-shared' already claimed by track at position 0");

            ReviewProgressSummary summary = session.deriveSummary();
            assertThat(summary.unresolvedConflictCount()).isEqualTo(1);
            assertThat(summary.remainingRequiredDecisions()).isEqualTo(1);
            assertThat(summary.eligibleForCompletion()).isFalse();

            assertThatThrownBy(() -> session.complete(summary))
                    .isInstanceOf(InvalidStateException.class)
                    .hasMessageContaining("required decisions remain unresolved");
        }

        @Test
        @DisplayName("Cancelling session transitions to CANCELLED and blocks further changes")
        void cancellingSessionTransitionsToCancelled() {
            createSampleTrack(0, "spotify-0", PlanTrackClassification.AUTO_MATCH, "yt-0", 0.95, 0.98);

            session.cancel();

            assertThat(session.getStatus()).isEqualTo(ReviewSessionStatus.CANCELLED);
            assertThat(session.isCancelled()).isTrue();

            assertThatThrownBy(session::cancel)
                    .isInstanceOf(InvalidStateException.class)
                    .hasMessageContaining("Cannot cancel review session in status: CANCELLED");
        }
    }

    @Nested
    @DisplayName("Review Progress Summary Mathematical Reconciliation")
    class SummaryReconciliationTests {

        @Test
        @DisplayName("ReviewProgressSummary strictly validates mathematical reconciliation invariant")
        void summaryStrictlyReconciles() {
            ReviewProgressSummary summary = new ReviewProgressSummary(
                    5, 2, 1, 1, 1, 0, 2, false
            );

            assertThat(summary.totalTracks()).isEqualTo(5);
            assertThat(summary.remainingRequiredDecisions()).isEqualTo(2);
            assertThat(summary.eligibleForCompletion()).isFalse();

            // Mismatched total vs status categories throws IllegalArgumentException
            assertThatThrownBy(() -> new ReviewProgressSummary(5, 2, 1, 1, 0, 0, 2, false))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("counts do not reconcile with totalTracks");

            // Claiming eligible when remaining decisions > 0 throws IllegalArgumentException
            assertThatThrownBy(() -> new ReviewProgressSummary(4, 1, 1, 1, 1, 0, 1, true))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot be eligible for completion when 1 required decisions remain");
        }

        @Test
        @DisplayName("Summary derived from empty track list is complete and empty")
        void emptyTrackListSummary() {
            ReviewProgressSummary summary = ReviewProgressSummary.fromTracks(List.of());
            assertThat(summary.totalTracks()).isEqualTo(0);
            assertThat(summary.remainingRequiredDecisions()).isEqualTo(0);
            assertThat(summary.eligibleForCompletion()).isTrue();
        }
    }
}
