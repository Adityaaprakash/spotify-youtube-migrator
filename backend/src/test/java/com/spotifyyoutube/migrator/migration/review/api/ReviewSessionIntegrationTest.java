package com.spotifyyoutube.migrator.migration.review.api;

import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.identity.repository.UserRepository;
import com.spotifyyoutube.migrator.matching.engine.MatchDecision;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationTrackCandidate;
import com.spotifyyoutube.migrator.migration.application.plan.MigrationPlan;
import com.spotifyyoutube.migrator.migration.application.plan.MigrationPlanSummary;
import com.spotifyyoutube.migrator.migration.application.plan.MigrationPlanTrack;
import com.spotifyyoutube.migrator.migration.application.plan.PlanTrackClassification;
import com.spotifyyoutube.migrator.migration.review.application.DefaultReviewSessionService;
import com.spotifyyoutube.migrator.migration.review.application.ReviewCandidateSearchRequest;
import com.spotifyyoutube.migrator.migration.review.application.ReviewCandidateSearchResult;
import com.spotifyyoutube.migrator.migration.review.application.ReviewCandidateSearchService;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewDecisionSource;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewProgressSummary;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSession;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSessionStatus;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewTrack;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewTrackStatus;
import com.spotifyyoutube.migrator.migration.review.repository.ReviewSessionRepository;
import com.spotifyyoutube.migrator.migration.review.repository.ReviewTrackRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Phase 10D: Review Application Integration Tests")
public class ReviewSessionIntegrationTest {

    @Mock
    private ReviewSessionRepository reviewSessionRepository;

    @Mock
    private ReviewTrackRepository reviewTrackRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ReviewCandidateSearchService reviewCandidateSearchService;

    private DefaultReviewSessionService reviewSessionService;

    private User testUser;
    private UUID userId;

    @BeforeEach
    void setUp() {
        reviewSessionService = new DefaultReviewSessionService(
                reviewSessionRepository,
                reviewTrackRepository,
                userRepository,
                reviewCandidateSearchService
        );

        userId = UUID.randomUUID();
        testUser = new User();
        testUser.setId(userId);
        testUser.setEmail("tester@example.com");
    }

    private MigrationPlan createSamplePlan(UUID planId, String playlistId) {
        DestinationTrackCandidate cand0 = new DestinationTrackCandidate(
                "yt-0", "Bohemian Rhapsody", "Queen", (Duration) null
        );
        DestinationTrackCandidate cand1 = new DestinationTrackCandidate(
                "yt-1", "Don't Stop Me Now", "Queen", (Duration) null
        );

        MigrationPlanTrack track0 = new MigrationPlanTrack(
                0, "sp-0", "Bohemian Rhapsody", "Queen",
                PlanTrackClassification.AUTO_MATCH, cand0,
                MatchDecision.MATCH, 0.98, 0.99, List.of("High match"), null
        );
        MigrationPlanTrack track1 = new MigrationPlanTrack(
                1, "sp-1", "Don't Stop Me Now", "Queen",
                PlanTrackClassification.REVIEW_REQUIRED, cand1,
                MatchDecision.REVIEW_REQUIRED, 0.75, 0.70, List.of("Duration difference"), null
        );

        MigrationPlanSummary summary = new MigrationPlanSummary(
                2, 1, 1, 0, 0, 0, 0, 0.5, 0.5, 0.0, 0.0, 0.0, 0.0
        );

        return new MigrationPlan(planId, playlistId, "Queen Hits", Instant.now(), List.of(track0, track1), summary);
    }

    @Test
    @DisplayName("Creates review session from Phase 9 plan mapping initial track states and preserving transient plan immutability")
    void createSessionFromPlanIntegration() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));
        when(reviewSessionRepository.save(any(ReviewSession.class))).thenAnswer(i -> {
            ReviewSession s = i.getArgument(0);
            s.setId(UUID.randomUUID());
            return s;
        });

        UUID planId = UUID.randomUUID();
        MigrationPlan plan = createSamplePlan(planId, "sp-queen");

        ReviewSession session = reviewSessionService.createSessionFromPlan(userId, plan);

        assertThat(session.getStatus()).isEqualTo(ReviewSessionStatus.IN_PROGRESS);
        assertThat(session.getSourcePlaylistId()).isEqualTo("sp-queen");
        assertThat(session.getSourcePlaylistTitle()).isEqualTo("Queen Hits");
        assertThat(session.getPlanId()).isEqualTo(planId);
        assertThat(session.getTotalTracks()).isEqualTo(2);

        List<ReviewTrack> tracks = session.getTracks();
        assertThat(tracks).hasSize(2);

        // Track 0 (AUTO_MATCH) -> ACCEPTED
        ReviewTrack t0 = tracks.get(0);
        assertThat(t0.getPosition()).isEqualTo(0);
        assertThat(t0.getReviewStatus()).isEqualTo(ReviewTrackStatus.ACCEPTED);
        assertThat(t0.getDecisionSource()).isEqualTo(ReviewDecisionSource.AUTOMATIC);
        assertThat(t0.getSelectedCandidateId()).isEqualTo("yt-0");
        assertThat(t0.isApprovedForMigration()).isTrue();

        // Track 1 (REVIEW_REQUIRED) -> AWAITING_REVIEW
        ReviewTrack t1 = tracks.get(1);
        assertThat(t1.getPosition()).isEqualTo(1);
        assertThat(t1.getReviewStatus()).isEqualTo(ReviewTrackStatus.AWAITING_REVIEW);
        assertThat(t1.getSelectedCandidateId()).isNull();
        assertThat(t1.isApprovedForMigration()).isFalse();

        // Progress summary derivation
        ReviewProgressSummary progress = session.deriveSummary();
        assertThat(progress.totalTracks()).isEqualTo(2);
        assertThat(progress.acceptedCount()).isEqualTo(1);
        assertThat(progress.awaitingReviewCount()).isEqualTo(1);
        assertThat(progress.remainingRequiredDecisions()).isEqualTo(1);
        assertThat(progress.eligibleForCompletion()).isFalse();

        // Transient plan remains unmutated
        assertThat(plan.tracks()).hasSize(2);
        assertThat(plan.tracks().get(0).classification()).isEqualTo(PlanTrackClassification.AUTO_MATCH);
    }

    @Test
    @DisplayName("Decision workflow: accept automatic candidate resolves awaiting decision and enables completion")
    void decisionWorkflowAcceptEnablesCompletion() {
        UUID sessionId = UUID.randomUUID();
        ReviewSession session = new ReviewSession(testUser, "sp-queen", "Queen Hits", UUID.randomUUID());
        session.setId(sessionId);

        ReviewTrack t0 = new ReviewTrack();
        t0.setSession(session);
        t0.setPosition(0);
        t0.setSourceTrackId("sp-0");
        t0.setSourceTitle("Track 0");
        t0.setSourceArtist("Queen");
        t0.setOriginalClassification(PlanTrackClassification.AUTO_MATCH);
        t0.setOriginalCandidateId("yt-0");
        t0.setOriginalCandidateTitle("Track 0");
        t0.setReviewStatus(ReviewTrackStatus.ACCEPTED);
        t0.setSelectedCandidateId("yt-0");
        session.addTrack(t0);

        ReviewTrack t1 = new ReviewTrack();
        t1.setSession(session);
        t1.setPosition(1);
        t1.setSourceTrackId("sp-1");
        t1.setSourceTitle("Track 1");
        t1.setSourceArtist("Queen");
        t1.setOriginalClassification(PlanTrackClassification.REVIEW_REQUIRED);
        t1.setOriginalCandidateId("yt-1");
        t1.setOriginalCandidateTitle("Track 1");
        t1.setReviewStatus(ReviewTrackStatus.AWAITING_REVIEW);
        session.addTrack(t1);

        when(reviewSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

        // Prior to decision, completion must fail
        assertThatThrownBy(() -> reviewSessionService.completeSession(sessionId, userId))
                .isInstanceOf(InvalidStateException.class)
                .hasMessageContaining("1 required decisions remain unresolved");

        // User accepts automatic candidate for track 1
        ReviewTrack updatedT1 = reviewSessionService.acceptAutomaticMatch(sessionId, 1, userId);
        assertThat(updatedT1.getReviewStatus()).isEqualTo(ReviewTrackStatus.ACCEPTED);
        assertThat(updatedT1.getSelectedCandidateId()).isEqualTo("yt-1");
        assertThat(updatedT1.isApprovedForMigration()).isTrue();

        // Now session is eligible for completion
        when(reviewSessionRepository.save(any(ReviewSession.class))).thenAnswer(i -> i.getArgument(0));
        ReviewSession completed = reviewSessionService.completeSession(sessionId, userId);
        assertThat(completed.getStatus()).isEqualTo(ReviewSessionStatus.COMPLETED);
        assertThat(completed.getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("Candidate search delegation preserves search limits and result format")
    void candidateSearchDelegationIntegration() {
        UUID sessionId = UUID.randomUUID();
        ReviewCandidateSearchRequest searchReq = ReviewCandidateSearchRequest.of("Radio Ga Ga", 5);
        ReviewCandidateSearchResult expectedResult = new ReviewCandidateSearchResult(
                sessionId, 0, "sp-0", "Radio Ga Ga", "Queen",
                "Radio Ga Ga", List.of(), true, 0
        );

        when(reviewCandidateSearchService.searchCandidates(sessionId, 0, searchReq, userId))
                .thenReturn(expectedResult);

        ReviewCandidateSearchResult actual = reviewSessionService.searchCandidates(sessionId, 0, searchReq, userId);
        assertThat(actual.queryUsed()).isEqualTo("Radio Ga Ga");
        assertThat(actual.isCustomQuery()).isTrue();
        assertThat(actual.totalResults()).isEqualTo(0);
    }

    @Test
    @DisplayName("Manual candidate replacement records USER_SELECTED provenance and preserves original evidence")
    void manualCandidateSelectionPreservesProvenance() {
        UUID sessionId = UUID.randomUUID();
        ReviewSession session = new ReviewSession(testUser, "sp-queen", "Queen Hits", UUID.randomUUID());
        session.setId(sessionId);

        ReviewTrack t0 = new ReviewTrack();
        t0.setSession(session);
        t0.setPosition(0);
        t0.setSourceTrackId("sp-0");
        t0.setSourceTitle("Track 0");
        t0.setSourceArtist("Queen");
        t0.setOriginalClassification(PlanTrackClassification.REVIEW_REQUIRED);
        t0.setOriginalCandidateId("yt-auto-0");
        t0.setOriginalCandidateTitle("Auto Title");
        t0.setOriginalMatchScore(0.72);
        t0.setOriginalCalibratedProbability(0.68);
        t0.setOriginalMatchDecision(MatchDecision.REVIEW_REQUIRED);
        t0.setReviewStatus(ReviewTrackStatus.AWAITING_REVIEW);
        session.addTrack(t0);

        when(reviewSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

        DestinationTrackCandidate manualCand = new DestinationTrackCandidate(
                "yt-manual-replacement", "Manual Choice Title", "Queen Channel", (Duration) null
        );

        ReviewTrack selectedTrack = reviewSessionService.selectCandidate(sessionId, 0, manualCand, "Better audio", userId);

        assertThat(selectedTrack.getReviewStatus()).isEqualTo(ReviewTrackStatus.USER_SELECTED);
        assertThat(selectedTrack.getDecisionSource()).isEqualTo(ReviewDecisionSource.USER_SELECTED);
        assertThat(selectedTrack.getSelectedCandidateId()).isEqualTo("yt-manual-replacement");
        assertThat(selectedTrack.getSelectedCandidateTitle()).isEqualTo("Manual Choice Title");

        // Immutable original matching evidence preserved untouched
        assertThat(selectedTrack.getOriginalCandidateId()).isEqualTo("yt-auto-0");
        assertThat(selectedTrack.getOriginalCandidateTitle()).isEqualTo("Auto Title");
        assertThat(selectedTrack.getOriginalMatchScore()).isEqualTo(0.72);
        assertThat(selectedTrack.getOriginalCalibratedProbability()).isEqualTo(0.68);
        assertThat(selectedTrack.getOriginalMatchDecision()).isEqualTo(MatchDecision.REVIEW_REQUIRED);
    }
}
