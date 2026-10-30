package com.spotifyyoutube.migrator.migration.application.execution.validation;

import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.matching.engine.MatchDecision;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationTrackCandidate;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationContext;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedTrack;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ExecutionValidationResult;
import com.spotifyyoutube.migrator.migration.application.execution.contract.MigrationExecutionValidationException;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ValidationFailureCode;
import com.spotifyyoutube.migrator.migration.application.plan.MigrationPlan;
import com.spotifyyoutube.migrator.migration.application.plan.MigrationPlanTrack;
import com.spotifyyoutube.migrator.migration.application.plan.PlanTrackClassification;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewDecisionSource;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewProgressSummary;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSession;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewTrack;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewTrackStatus;
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

@DisplayName("Phase 11B: Approved Plan Validator Test")
class ApprovedPlanValidatorTest {

    private DefaultApprovedPlanValidator validator;
    private UUID userId;
    private User testUser;
    private UUID planId;

    @BeforeEach
    void setUp() {
        validator = new DefaultApprovedPlanValidator();
        userId = UUID.randomUUID();
        testUser = new User("user@example.com");
        testUser.setId(userId);
        planId = UUID.randomUUID();
    }

    private ReviewSession createCompletedSession(String sourcePlaylistId, String title) {
        ReviewSession session = new ReviewSession(testUser, sourcePlaylistId, title, planId);
        session.setId(UUID.randomUUID());
        return session;
    }

    private ReviewTrack createAcceptedTrack(int position, String sourceTrackId, String title, String artist, String ytId) {
        ReviewTrack track = new ReviewTrack();
        track.setPosition(position);
        track.setSourceTrackId(sourceTrackId);
        track.setSourceTitle(title);
        track.setSourceArtist(artist);
        track.setOriginalClassification(PlanTrackClassification.AUTO_MATCH);
        track.setOriginalCandidateId(ytId);
        track.setOriginalCandidateTitle(title);
        track.setOriginalCandidateChannel(artist + " - Topic");
        track.setOriginalCandidateDurationMs(200000L);
        track.setOriginalMatchScore(0.95);
        track.setOriginalCalibratedProbability(0.98);
        track.setOriginalMatchDecision(MatchDecision.MATCH);
        track.setSelectedCandidateId(ytId);
        track.setSelectedCandidateTitle(title);
        track.setSelectedCandidateChannel(artist + " - Topic");
        track.setSelectedCandidateDurationMs(200000L);
        track.setReviewStatus(ReviewTrackStatus.ACCEPTED);
        track.setDecisionSource(ReviewDecisionSource.AUTOMATIC);
        track.setExcluded(false);
        track.setHasConflict(false);
        return track;
    }

    private ReviewTrack createUserSelectedTrack(int position, String sourceTrackId, String title, String artist, String ytId, String ytTitle) {
        ReviewTrack track = new ReviewTrack();
        track.setPosition(position);
        track.setSourceTrackId(sourceTrackId);
        track.setSourceTitle(title);
        track.setSourceArtist(artist);
        track.setOriginalClassification(PlanTrackClassification.REVIEW_REQUIRED);
        track.setOriginalCandidateId("old-cand");
        track.setOriginalCandidateTitle("Old Candidate");
        track.setOriginalMatchScore(0.65);
        track.setSelectedCandidateId(ytId);
        track.setSelectedCandidateTitle(ytTitle);
        track.setSelectedCandidateChannel("Selected Channel");
        track.setSelectedCandidateDurationMs(185000L);
        track.setReviewStatus(ReviewTrackStatus.USER_SELECTED);
        track.setDecisionSource(ReviewDecisionSource.USER_SELECTED);
        track.setExcluded(false);
        track.setHasConflict(false);
        return track;
    }

    private ReviewTrack createExcludedTrack(int position, String sourceTrackId, String title, String artist) {
        ReviewTrack track = new ReviewTrack();
        track.setPosition(position);
        track.setSourceTrackId(sourceTrackId);
        track.setSourceTitle(title);
        track.setSourceArtist(artist);
        track.setOriginalClassification(PlanTrackClassification.UNMATCHED);
        track.setReviewStatus(ReviewTrackStatus.EXCLUDED);
        track.setDecisionSource(ReviewDecisionSource.USER_SELECTED);
        track.setExcluded(true);
        track.setHasConflict(false);
        return track;
    }

    @Nested
    @DisplayName("Validation Success Scenarios")
    class ValidationSuccessTests {

        @Test
        @DisplayName("All eligible tracks accepted with valid destination identities succeeds")
        void allAcceptedTracksSucceeds() {
            ReviewSession session = createCompletedSession("spotify-100", "Top Hits");
            session.addTrack(createAcceptedTrack(0, "sp-0", "Song 0", "Artist 0", "yt-0"));
            session.addTrack(createAcceptedTrack(1, "sp-1", "Song 1", "Artist 1", "yt-1"));
            session.complete(session.deriveSummary());

            ExecutionValidationResult result = validator.validate(session, userId);

            assertThat(result.isValid()).isTrue();
            assertThat(result.hasErrors()).isFalse();
            assertThat(result.getApprovedContext()).isPresent();

            ApprovedMigrationContext context = result.approvedContext();
            assertThat(context.reviewSessionId()).isEqualTo(session.getId());
            assertThat(context.userId()).isEqualTo(userId);
            assertThat(context.sourcePlaylistId()).isEqualTo("spotify-100");
            assertThat(context.approvedTrackCount()).isEqualTo(2);
            assertThat(context.excludedTrackCount()).isEqualTo(0);
            assertThat(context.totalSourceTracks()).isEqualTo(2);

            List<ApprovedTrack> approved = context.approvedTracks();
            assertThat(approved).hasSize(2);

            assertThat(approved.get(0).sourcePosition()).isEqualTo(0);
            assertThat(approved.get(0).targetPosition()).isEqualTo(0);
            assertThat(approved.get(0).destinationTrackId()).isEqualTo("yt-0");
            assertThat(approved.get(0).isAutomatic()).isTrue();

            assertThat(approved.get(1).sourcePosition()).isEqualTo(1);
            assertThat(approved.get(1).targetPosition()).isEqualTo(1);
            assertThat(approved.get(1).destinationTrackId()).isEqualTo("yt-1");
            assertThat(approved.get(1).isAutomatic()).isTrue();
        }

        @Test
        @DisplayName("Valid manual user-selected candidate is respected with correct provenance")
        void userSelectedCandidateRespected() {
            ReviewSession session = createCompletedSession("spotify-200", "Indie Vibes");
            session.addTrack(createAcceptedTrack(0, "sp-0", "Auto Song", "Band", "yt-auto"));
            session.addTrack(createUserSelectedTrack(1, "sp-1", "Manual Song", "Band", "yt-manual", "Manual Video Title"));
            session.complete(session.deriveSummary());

            ApprovedMigrationContext context = validator.validateAndApprove(session, userId);

            assertThat(context.approvedTrackCount()).isEqualTo(2);
            ApprovedTrack manualTrack = context.approvedTracks().get(1);
            assertThat(manualTrack.sourcePosition()).isEqualTo(1);
            assertThat(manualTrack.targetPosition()).isEqualTo(1);
            assertThat(manualTrack.destinationTrackId()).isEqualTo("yt-manual");
            assertThat(manualTrack.getDestinationTitle()).contains("Manual Video Title");
            assertThat(manualTrack.isUserSelected()).isTrue();
            assertThat(manualTrack.isAutomatic()).isFalse();
        }

        @Test
        @DisplayName("Intentionally excluded tracks do not appear in approved selection")
        void excludedTracksFilteredOut() {
            ReviewSession session = createCompletedSession("spotify-300", "Mixed Playlist");
            session.addTrack(createAcceptedTrack(0, "sp-0", "Keep 1", "Artist", "yt-0"));
            session.addTrack(createExcludedTrack(1, "sp-1", "Drop 1", "Artist"));
            session.addTrack(createAcceptedTrack(2, "sp-2", "Keep 2", "Artist", "yt-2"));
            session.addTrack(createExcludedTrack(3, "sp-3", "Drop 2", "Artist"));
            session.complete(session.deriveSummary());

            ExecutionValidationResult result = validator.validate(session, userId);

            assertThat(result.isValid()).isTrue();
            ApprovedMigrationContext context = result.approvedContext();
            assertThat(context.totalSourceTracks()).isEqualTo(4);
            assertThat(context.approvedTrackCount()).isEqualTo(2);
            assertThat(context.excludedTrackCount()).isEqualTo(2);

            List<ApprovedTrack> approved = context.approvedTracks();
            assertThat(approved).hasSize(2);

            // First approved track: original source pos 0 -> target pos 0
            assertThat(approved.get(0).sourcePosition()).isEqualTo(0);
            assertThat(approved.get(0).targetPosition()).isEqualTo(0);
            assertThat(approved.get(0).destinationTrackId()).isEqualTo("yt-0");

            // Second approved track: original source pos 2 -> target pos 1 (gapless!)
            assertThat(approved.get(1).sourcePosition()).isEqualTo(2);
            assertThat(approved.get(1).targetPosition()).isEqualTo(1);
            assertThat(approved.get(1).destinationTrackId()).isEqualTo("yt-2");
        }

        @Test
        @DisplayName("Empty source playlist produces valid context with 0 approved tracks")
        void emptyPlaylistSucceeds() {
            ReviewSession session = createCompletedSession("spotify-empty", "Empty Playlist");
            session.complete(session.deriveSummary());

            ExecutionValidationResult result = validator.validate(session, userId);

            assertThat(result.isValid()).isTrue();
            ApprovedMigrationContext context = result.approvedContext();
            assertThat(context.totalSourceTracks()).isEqualTo(0);
            assertThat(context.approvedTrackCount()).isEqualTo(0);
            assertThat(context.excludedTrackCount()).isEqualTo(0);
            assertThat(context.approvedTracks()).isEmpty();
            assertThat(context.hasApprovedTracks()).isFalse();
        }

        @Test
        @DisplayName("Custom request overrides target playlist metadata safely")
        void customRequestOverrides() {
            ReviewSession session = createCompletedSession("spotify-custom", "Source Title");
            session.addTrack(createAcceptedTrack(0, "sp-0", "Song", "Artist", "yt-0"));
            session.complete(session.deriveSummary());

            ApprovedMigrationExecutionRequest req = new ApprovedMigrationExecutionRequest(
                    session.getId(),
                    userId,
                    "spotify-custom",
                    planId,
                    "My YouTube Playlist",
                    "Custom Description",
                    false,
                    null
            );

            ApprovedMigrationContext context = validator.validateAndApprove(session, req, userId);
            assertThat(context.targetPlaylistTitle()).isEqualTo("My YouTube Playlist");
            assertThat(context.getTargetPlaylistDescription()).contains("Custom Description");
            assertThat(context.isPrivate()).isFalse();
        }

        @Test
        @DisplayName("Validation against transient MigrationPlan snapshot succeeds when consistent")
        void consistentMigrationPlanCrossValidation() {
            ReviewSession session = createCompletedSession("spotify-plan", "Plan Source");
            session.addTrack(createAcceptedTrack(0, "sp-0", "Song 0", "Artist 0", "yt-0"));
            session.complete(session.deriveSummary());

            DestinationTrackCandidate cand = new DestinationTrackCandidate("yt-0", "Song 0", "Artist 0", null, null, null, Duration.ofSeconds(200));
            MigrationPlan plan = new MigrationPlan(
                    planId,
                    "spotify-plan",
                    "Plan Source",
                    Instant.now(),
                    List.of(MigrationPlanTrack.autoMatch(0, "sp-0", "Song 0", "Artist 0", cand, 0.95, 0.98, List.of("Auto match")))
            );

            ExecutionValidationResult result = validator.validate(session, plan, userId);
            assertThat(result.isValid()).isTrue();
        }
    }

    @Nested
    @DisplayName("Validation Rejection Scenarios")
    class ValidationRejectionTests {

        @Test
        @DisplayName("Rejects missing execution context (null session)")
        void rejectsNullSession() {
            ExecutionValidationResult result = validator.validate(null, userId);
            assertThat(result.isValid()).isFalse();
            assertThat(result.errors().getFirst().code()).isEqualTo(ValidationFailureCode.MISSING_CONTEXT);
        }

        @Test
        @DisplayName("Rejects null authenticated user")
        void rejectsNullUser() {
            ReviewSession session = createCompletedSession("sp-1", "Title");
            ExecutionValidationResult result = validator.validate(session, null);
            assertThat(result.isValid()).isFalse();
            assertThat(result.errors().getFirst().code()).isEqualTo(ValidationFailureCode.UNAUTHORIZED);
        }

        @Test
        @DisplayName("Rejects review session belonging to another user (anti-IDOR)")
        void rejectsDifferentUser() {
            ReviewSession session = createCompletedSession("sp-1", "Title");
            UUID attackerId = UUID.randomUUID();

            ExecutionValidationResult result = validator.validate(session, attackerId);
            assertThat(result.isValid()).isFalse();
            assertThat(result.errors().getFirst().code()).isEqualTo(ValidationFailureCode.UNAUTHORIZED);
            assertThat(result.firstErrorMessage()).contains("not authorized to execute review session");
        }

        @Test
        @DisplayName("Rejects anti-impersonation when request userId differs from authenticated principal")
        void rejectsImpersonation() {
            ReviewSession session = createCompletedSession("sp-1", "Title");
            session.complete(session.deriveSummary());
            UUID victimId = UUID.randomUUID();

            ApprovedMigrationExecutionRequest req = new ApprovedMigrationExecutionRequest(
                    session.getId(), victimId, null, null, null, null, true, null
            );

            ExecutionValidationResult result = validator.validate(session, req, userId);
            assertThat(result.isValid()).isFalse();
            assertThat(result.errors().getFirst().code()).isEqualTo(ValidationFailureCode.UNAUTHORIZED);
            assertThat(result.firstErrorMessage()).contains("does not match authenticated user");
        }

        @Test
        @DisplayName("Rejects in-progress review session (unfinished review)")
        void rejectsInProgressSession() {
            ReviewSession session = new ReviewSession(testUser, "sp-1", "Title", planId);
            session.setId(UUID.randomUUID());
            session.addTrack(createAcceptedTrack(0, "sp-0", "T", "A", "yt-0"));
            // Session is still IN_PROGRESS!

            ExecutionValidationResult result = validator.validate(session, userId);
            assertThat(result.isValid()).isFalse();
            assertThat(result.errors().getFirst().code()).isEqualTo(ValidationFailureCode.INCOMPLETE_REVIEW);
            assertThat(result.firstErrorMessage()).contains("still in progress");
        }

        @Test
        @DisplayName("Rejects cancelled review session")
        void rejectsCancelledSession() {
            ReviewSession session = new ReviewSession(testUser, "sp-1", "Title", planId);
            session.setId(UUID.randomUUID());
            session.addTrack(createAcceptedTrack(0, "sp-0", "T", "A", "yt-0"));
            session.cancel();

            ExecutionValidationResult result = validator.validate(session, userId);
            assertThat(result.isValid()).isFalse();
            assertThat(result.errors().getFirst().code()).isEqualTo(ValidationFailureCode.INVALID_STATE);
            assertThat(result.firstErrorMessage()).contains("cancelled");
        }

        @Test
        @DisplayName("Rejects track still awaiting review")
        void rejectsAwaitingReviewTrack() {
            ReviewSession session = createCompletedSession("sp-1", "Title");
            session.addTrack(createAcceptedTrack(0, "sp-0", "Good", "Artist", "yt-0"));

            ReviewTrack awaiting = new ReviewTrack();
            awaiting.setPosition(1);
            awaiting.setSourceTrackId("sp-1");
            awaiting.setSourceTitle("Unresolved Song");
            awaiting.setSourceArtist("Artist");
            awaiting.setOriginalClassification(PlanTrackClassification.REVIEW_REQUIRED);
            awaiting.setReviewStatus(ReviewTrackStatus.AWAITING_REVIEW);
            session.addTrack(awaiting);

            // Force session completion state for validator test
            forceCompleteSession(session);

            ExecutionValidationResult result = validator.validate(session, userId);
            assertThat(result.isValid()).isFalse();
            assertThat(result.errors().stream().anyMatch(e -> e.code() == ValidationFailureCode.UNRESOLVED_TRACK)).isTrue();
            assertThat(result.firstErrorMessage()).contains("unresolved or awaiting review");
        }

        @Test
        @DisplayName("Rejects track with unresolved duplicate conflict")
        void rejectsUnresolvedConflict() {
            ReviewSession session = createCompletedSession("sp-1", "Title");
            ReviewTrack conflicted = createAcceptedTrack(0, "sp-0", "Dup Song", "Artist", "yt-0");
            conflicted.setConflict("Duplicate destination candidate: 'yt-0' already claimed");
            session.addTrack(conflicted);

            forceCompleteSession(session);

            ExecutionValidationResult result = validator.validate(session, userId);
            assertThat(result.isValid()).isFalse();
            assertThat(result.errors().stream().anyMatch(e -> e.code() == ValidationFailureCode.UNRESOLVED_CONFLICT)).isTrue();
            assertThat(result.firstErrorMessage()).contains("unresolved conflict");
        }

        @Test
        @DisplayName("Rejects accepted track with missing destination-track identity")
        void rejectsMissingDestinationId() {
            ReviewSession session = createCompletedSession("sp-1", "Title");
            ReviewTrack track = createAcceptedTrack(0, "sp-0", "Song", "Artist", "yt-0");
            track.setSelectedCandidateId("   "); // Blank!
            session.addTrack(track);

            forceCompleteSession(session);

            ExecutionValidationResult result = validator.validate(session, userId);
            assertThat(result.isValid()).isFalse();
            assertThat(result.errors().stream().anyMatch(e -> e.code() == ValidationFailureCode.INVALID_DESTINATION_ID)).isTrue();
        }

        @Test
        @DisplayName("Rejects user-selected candidate with blank title")
        void rejectsUserSelectedBlankTitle() {
            ReviewSession session = createCompletedSession("sp-1", "Title");
            ReviewTrack track = createUserSelectedTrack(0, "sp-0", "Song", "Artist", "yt-custom", "  ");
            session.addTrack(track);

            forceCompleteSession(session);

            ExecutionValidationResult result = validator.validate(session, userId);
            assertThat(result.isValid()).isFalse();
            assertThat(result.errors().stream().anyMatch(e -> e.code() == ValidationFailureCode.INVALID_CANDIDATE)).isTrue();
            assertThat(result.firstErrorMessage()).contains("blank title");
        }

        @Test
        @DisplayName("Rejects user-selected candidate with negative duration")
        void rejectsUserSelectedNegativeDuration() {
            ReviewSession session = createCompletedSession("sp-1", "Title");
            ReviewTrack track = createUserSelectedTrack(0, "sp-0", "Song", "Artist", "yt-custom", "Valid Title");
            track.setSelectedCandidateDurationMs(-5000L);
            session.addTrack(track);

            forceCompleteSession(session);

            ExecutionValidationResult result = validator.validate(session, userId);
            assertThat(result.isValid()).isFalse();
            assertThat(result.errors().stream().anyMatch(e -> e.code() == ValidationFailureCode.INVALID_CANDIDATE)).isTrue();
            assertThat(result.firstErrorMessage()).contains("negative duration");
        }

        @Test
        @DisplayName("Rejects duplicate destination track across approved tracks")
        void rejectsDuplicateDestinationTrack() {
            ReviewSession session = createCompletedSession("sp-1", "Title");
            session.addTrack(createAcceptedTrack(0, "sp-0", "Song 0", "Artist 0", "yt-SAME"));
            session.addTrack(createAcceptedTrack(1, "sp-1", "Song 1", "Artist 1", "yt-SAME"));
            session.complete(new ReviewProgressSummary(2, 0, 2, 0, 0, 0, true));

            ExecutionValidationResult result = validator.validate(session, userId);
            assertThat(result.isValid()).isFalse();
            assertThat(result.errors().stream().anyMatch(e -> e.code() == ValidationFailureCode.DUPLICATE_DESTINATION_TRACK)).isTrue();
            assertThat(result.firstErrorMessage()).contains("Duplicate destination track ID 'yt-SAME'");
        }

        @Test
        @DisplayName("Rejects non-contiguous or out-of-sequence source track positions")
        void rejectsOutOfSequencePositions() {
            ReviewSession session = createCompletedSession("sp-1", "Title");
            ReviewTrack track0 = createAcceptedTrack(0, "sp-0", "Song 0", "Artist", "yt-0");
            ReviewTrack trackOutOfOrder = createAcceptedTrack(5, "sp-1", "Song 1", "Artist", "yt-1"); // Position 5 at index 1!
            session.addTrack(track0);
            session.addTrack(trackOutOfOrder);

            forceCompleteSession(session);

            ExecutionValidationResult result = validator.validate(session, userId);
            assertThat(result.isValid()).isFalse();
            assertThat(result.errors().stream().anyMatch(e -> e.code() == ValidationFailureCode.ORDERING_MISMATCH)).isTrue();
            assertThat(result.firstErrorMessage()).contains("non-contiguous position");
        }

        @Test
        @DisplayName("Rejects source playlist ID mismatch in request")
        void rejectsSourcePlaylistMismatch() {
            ReviewSession session = createCompletedSession("spotify-actual", "Title");
            session.addTrack(createAcceptedTrack(0, "sp-0", "Song", "Artist", "yt-0"));
            session.complete(session.deriveSummary());

            ApprovedMigrationExecutionRequest req = new ApprovedMigrationExecutionRequest(
                    session.getId(), userId, "spotify-DIFFERENT", null, null, null, true, null
            );

            ExecutionValidationResult result = validator.validate(session, req, userId);
            assertThat(result.isValid()).isFalse();
            assertThat(result.errors().stream().anyMatch(e -> e.code() == ValidationFailureCode.INCONSISTENT_CONTEXT)).isTrue();
            assertThat(result.firstErrorMessage()).contains("does not match session source playlist ID");
        }

        @Test
        @DisplayName("Rejects candidate associated with a different source track when cross-validating MigrationPlan")
        void rejectsPlanSourceTrackMismatch() {
            ReviewSession session = createCompletedSession("spotify-plan", "Title");
            session.addTrack(createAcceptedTrack(0, "sp-TAMPERED", "Song 0", "Artist 0", "yt-0"));
            session.complete(session.deriveSummary());

            DestinationTrackCandidate cand = new DestinationTrackCandidate("yt-0", "Song 0", "Artist 0", null, null, null, Duration.ofSeconds(200));
            MigrationPlan plan = new MigrationPlan(
                    planId,
                    "spotify-plan",
                    "Title",
                    Instant.now(),
                    List.of(MigrationPlanTrack.autoMatch(0, "sp-ORIGINAL", "Song 0", "Artist 0", cand, 0.95, 0.98, List.of("Auto match")))
            );

            ExecutionValidationResult result = validator.validate(session, plan, userId);
            assertThat(result.isValid()).isFalse();
            assertThat(result.errors().stream().anyMatch(e -> e.code() == ValidationFailureCode.CANDIDATE_ASSOCIATION_MISMATCH)).isTrue();
            assertThat(result.firstErrorMessage()).contains("does not match plan track sourceTrackId");
        }

        @Test
        @DisplayName("validateAndApprove throws MigrationExecutionValidationException on failure")
        void validateAndApproveThrowsOnFailure() {
            ReviewSession session = createCompletedSession("sp-1", "Title");
            // Session has no tracks, and is still in progress!

            assertThatThrownBy(() -> validator.validateAndApprove(session, userId))
                    .isInstanceOf(MigrationExecutionValidationException.class);
        }
    }

    private void forceCompleteSession(ReviewSession session) {
        session.setStatus(com.spotifyyoutube.migrator.migration.review.domain.ReviewSessionStatus.COMPLETED);
    }
}
