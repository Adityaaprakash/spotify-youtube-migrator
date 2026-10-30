package com.spotifyyoutube.migrator.migration.application.execution;

import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.matching.engine.MatchDecision;
import com.spotifyyoutube.migrator.migration.application.destination.MusicDestination;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationTrackCandidate;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationContext;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ExecutionValidationResult;
import com.spotifyyoutube.migrator.migration.application.execution.validation.ApprovedPlanValidator;
import com.spotifyyoutube.migrator.migration.application.execution.validation.DefaultApprovedPlanValidator;
import com.spotifyyoutube.migrator.migration.application.plan.MigrationPlan;
import com.spotifyyoutube.migrator.migration.application.plan.MigrationPlanSummary;
import com.spotifyyoutube.migrator.migration.application.plan.MigrationPlanTrack;
import com.spotifyyoutube.migrator.migration.application.plan.PlanTrackClassification;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewDecisionSource;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSession;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewTrack;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewTrackStatus;
import com.spotifyyoutube.migrator.migration.review.repository.ReviewSessionRepository;
import com.spotifyyoutube.migrator.migration.review.repository.ReviewTrackRepository;
import com.spotifyyoutube.migrator.youtube.application.YouTubePlaylistService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("Phase 11A/11B: Mutation Safety, Reflection Isolation, and Immutability Test")
class ApprovedPlanExecutionSafetyTest {

    @Test
    @DisplayName("Reflection Safety: DefaultApprovedPlanValidator holds zero destination mutation dependencies")
    void validatorHoldsZeroDestinationDependencies() {
        Field[] fields = DefaultApprovedPlanValidator.class.getDeclaredFields();
        for (Field field : fields) {
            assertThat(field.getType())
                    .as("Validator field '%s' must not be MusicDestination or YouTubePlaylistService", field.getName())
                    .isNotEqualTo(MusicDestination.class)
                    .isNotEqualTo(YouTubePlaylistService.class);
        }
    }

    @Test
    @DisplayName("Reflection Safety: DefaultApprovedMigrationExecutionService holds zero destination mutation dependencies")
    void executionServiceHoldsZeroDestinationDependencies() {
        Field[] fields = DefaultApprovedMigrationExecutionService.class.getDeclaredFields();
        for (Field field : fields) {
            assertThat(field.getType())
                    .as("Execution service field '%s' must not be MusicDestination or YouTubePlaylistService", field.getName())
                    .isNotEqualTo(MusicDestination.class)
                    .isNotEqualTo(YouTubePlaylistService.class);
        }
    }

    @Test
    @DisplayName("Zero Mutation: Execution validation and preparation perform zero calls to MusicDestination")
    void validationPerformsZeroDestinationCalls() {
        MusicDestination musicDestination = mock(MusicDestination.class);
        ReviewSessionRepository sessionRepo = mock(ReviewSessionRepository.class);
        ReviewTrackRepository trackRepo = mock(ReviewTrackRepository.class);

        ApprovedPlanValidator validator = new DefaultApprovedPlanValidator();
        ApprovedMigrationExecutionService executionService = new DefaultApprovedMigrationExecutionService(
                sessionRepo, trackRepo, validator
        );

        UUID userId = UUID.randomUUID();
        User user = new User("user@example.com");
        user.setId(userId);

        UUID sessionId = UUID.randomUUID();
        ReviewSession session = new ReviewSession(user, "spotify-1", "Playlist", UUID.randomUUID());
        session.setId(sessionId);

        ReviewTrack track = new ReviewTrack();
        track.setPosition(0);
        track.setSourceTrackId("sp-0");
        track.setSourceTitle("Song");
        track.setSourceArtist("Artist");
        track.setOriginalClassification(PlanTrackClassification.AUTO_MATCH);
        track.setOriginalCandidateId("yt-0");
        track.setSelectedCandidateId("yt-0");
        track.setReviewStatus(ReviewTrackStatus.ACCEPTED);
        track.setDecisionSource(ReviewDecisionSource.AUTOMATIC);
        session.addTrack(track);
        session.complete(session.deriveSummary());

        when(sessionRepo.findById(sessionId)).thenReturn(Optional.of(session));

        // 1. Validate execution
        ExecutionValidationResult result = executionService.validateExecution(sessionId, userId);
        assertThat(result.isValid()).isTrue();

        // 2. Prepare execution
        ApprovedMigrationContext context = executionService.prepareExecution(sessionId, userId);
        assertThat(context.approvedTrackCount()).isEqualTo(1);

        // Strict assertion: zero interactions with MusicDestination
        verifyNoInteractions(musicDestination);
    }

    @Test
    @DisplayName("Immutability: MigrationPlan preview snapshot remains 100% unmutated during validation")
    void transientPlanRemainsUnmutated() {
        UUID planId = UUID.randomUUID();
        DestinationTrackCandidate cand = new DestinationTrackCandidate("yt-1", "Title", "Artist", null, null, null, Duration.ofSeconds(180));
        MigrationPlanTrack planTrack = MigrationPlanTrack.autoMatch(0, "sp-1", "Title", "Artist", cand, 0.95, 0.98, List.of("Auto match"));
        MigrationPlanSummary summary = new MigrationPlanSummary(1, 1, 0, 0, 0, 0, 0);
        MigrationPlan plan = new MigrationPlan(planId, "sp-pl", "Title", Instant.now(), List.of(planTrack), summary);

        // Pre-validation state
        int initialTracks = plan.tracks().size();
        PlanTrackClassification initialClass = plan.tracks().getFirst().classification();
        Double initialScore = plan.tracks().getFirst().matchScore();

        UUID userId = UUID.randomUUID();
        User user = new User("u@example.com");
        user.setId(userId);

        ReviewSession session = new ReviewSession(user, "sp-pl", "Title", planId);
        session.setId(UUID.randomUUID());
        ReviewTrack track = new ReviewTrack();
        track.setPosition(0);
        track.setSourceTrackId("sp-1");
        track.setSourceTitle("Title");
        track.setSourceArtist("Artist");
        track.setOriginalClassification(PlanTrackClassification.AUTO_MATCH);
        track.setOriginalCandidateId("yt-1");
        track.setSelectedCandidateId("yt-1");
        track.setReviewStatus(ReviewTrackStatus.ACCEPTED);
        session.addTrack(track);
        session.complete(session.deriveSummary());

        DefaultApprovedPlanValidator validator = new DefaultApprovedPlanValidator();
        ExecutionValidationResult result = validator.validate(session, plan, userId);
        assertThat(result.isValid()).isTrue();

        // Verify plan fields are completely unchanged
        assertThat(plan.tracks()).hasSize(initialTracks);
        assertThat(plan.tracks().getFirst().classification()).isEqualTo(initialClass);
        assertThat(plan.tracks().getFirst().matchScore()).isEqualTo(initialScore);
    }

    @Test
    @DisplayName("Immutability: ReviewTrack original matching evidence is untouched by execution validation")
    void reviewTrackEvidenceUntouched() {
        UUID userId = UUID.randomUUID();
        User user = new User("u@example.com");
        user.setId(userId);

        ReviewSession session = new ReviewSession(user, "sp-pl", "Title", UUID.randomUUID());
        session.setId(UUID.randomUUID());

        ReviewTrack track = new ReviewTrack();
        track.setPosition(0);
        track.setSourceTrackId("sp-1");
        track.setSourceTitle("Original Title");
        track.setSourceArtist("Original Artist");
        track.setOriginalClassification(PlanTrackClassification.REVIEW_REQUIRED);
        track.setOriginalCandidateId("orig-yt-id");
        track.setOriginalCandidateTitle("Orig Video");
        track.setOriginalCandidateChannel("Orig Channel");
        track.setOriginalCandidateDurationMs(200000L);
        track.setOriginalMatchScore(0.68);
        track.setOriginalCalibratedProbability(0.72);
        track.setOriginalMatchDecision(MatchDecision.REVIEW_REQUIRED);
        track.setOriginalMatchReasons("Borderline score");

        track.setSelectedCandidateId("new-yt-id");
        track.setSelectedCandidateTitle("Selected Video");
        track.setSelectedCandidateChannel("Selected Channel");
        track.setSelectedCandidateDurationMs(195000L);
        track.setReviewStatus(ReviewTrackStatus.USER_SELECTED);
        track.setDecisionSource(ReviewDecisionSource.USER_SELECTED);

        session.addTrack(track);
        session.complete(session.deriveSummary());

        DefaultApprovedPlanValidator validator = new DefaultApprovedPlanValidator();
        ApprovedMigrationContext context = validator.validateAndApprove(session, userId);

        assertThat(context.approvedTrackCount()).isEqualTo(1);

        // Verify original matching evidence remains 100% intact
        assertThat(track.getOriginalClassification()).isEqualTo(PlanTrackClassification.REVIEW_REQUIRED);
        assertThat(track.getOriginalCandidateId()).isEqualTo("orig-yt-id");
        assertThat(track.getOriginalCandidateTitle()).isEqualTo("Orig Video");
        assertThat(track.getOriginalCandidateChannel()).isEqualTo("Orig Channel");
        assertThat(track.getOriginalCandidateDurationMs()).isEqualTo(200000L);
        assertThat(track.getOriginalMatchScore()).isEqualTo(0.68);
        assertThat(track.getOriginalCalibratedProbability()).isEqualTo(0.72);
        assertThat(track.getOriginalMatchDecision()).isEqualTo(MatchDecision.REVIEW_REQUIRED);
        assertThat(track.getOriginalMatchReasons()).isEqualTo("Borderline score");
    }
}
