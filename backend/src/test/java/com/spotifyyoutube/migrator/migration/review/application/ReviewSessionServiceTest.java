package com.spotifyyoutube.migrator.migration.review.application;

import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.identity.repository.UserRepository;
import com.spotifyyoutube.migrator.matching.engine.MatchDecision;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationTrackCandidate;
import com.spotifyyoutube.migrator.migration.application.plan.MigrationPlan;
import com.spotifyyoutube.migrator.migration.application.plan.MigrationPlanSummary;
import com.spotifyyoutube.migrator.migration.application.plan.MigrationPlanTrack;
import com.spotifyyoutube.migrator.migration.application.plan.PlanTrackClassification;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewDecisionSource;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Phase 10B: ReviewSessionService Application Layer Test")
class ReviewSessionServiceTest {

    @Mock
    private ReviewSessionRepository reviewSessionRepository;

    @Mock
    private ReviewTrackRepository reviewTrackRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private DefaultReviewSessionService reviewSessionService;

    private UUID userId;
    private User testUser;
    private UUID sessionId;
    private ReviewSession testSession;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        testUser = new User("reviewer@example.com");
        testUser.setId(userId);
        testUser.setDisplayName("Test Reviewer");

        sessionId = UUID.randomUUID();
        testSession = new ReviewSession(testUser, "spotify-playlist-1", "Indie Rock", UUID.randomUUID());
        testSession.setId(sessionId);
    }

    private DestinationTrackCandidate createCandidate(String id, String title) {
        return new DestinationTrackCandidate(id, title, "Artist", null, null, null, Duration.ofSeconds(210));
    }

    @Test
    @DisplayName("createSessionFromPlan maps all 6 Phase 9 classifications into proper review track states")
    void createSessionFromPlanMapsAllClassifications() {
        DestinationTrackCandidate cand0 = createCandidate("yt-cand-0", "Song 0");
        DestinationTrackCandidate cand1 = createCandidate("yt-cand-1", "Song 1");
        DestinationTrackCandidate cand3 = createCandidate("yt-cand-3", "Song 3");

        List<MigrationPlanTrack> planTracks = List.of(
                MigrationPlanTrack.autoMatch(0, "sp-0", "Title 0", "Artist 0", cand0, 0.95, 0.99, List.of("Exact match")),
                MigrationPlanTrack.reviewRequired(1, "sp-1", "Title 1", "Artist 1", cand1, 0.70, 0.60, List.of("Variant ambiguity")),
                MigrationPlanTrack.unmatched(2, "sp-2", "Title 2", "Artist 2", List.of("No candidate")),
                MigrationPlanTrack.duplicate(3, "sp-3", "Title 3", "Artist 3", cand3, 0.90, 0.92, "Duplicate of earlier track"),
                MigrationPlanTrack.failed(4, "sp-4", "Title 4", "Artist 4", "Network timeout"),
                new MigrationPlanTrack(5, "sp-5", "Title 5", "Artist 5", PlanTrackClassification.EXCLUDED, null, null, null, null, List.of("User excluded"), null)
        );

        MigrationPlanSummary summary = new MigrationPlanSummary(6, 1, 1, 1, 1, 1, 1);
        MigrationPlan plan = new MigrationPlan(UUID.randomUUID(), "spotify-pl-123", "Cool Tunes", Instant.now(), planTracks, summary);

        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));
        when(reviewSessionRepository.save(any(ReviewSession.class))).thenAnswer(invocation -> {
            ReviewSession s = invocation.getArgument(0);
            s.setId(sessionId);
            return s;
        });

        ReviewSession createdSession = reviewSessionService.createSessionFromPlan(userId, plan);

        assertThat(createdSession).isNotNull();
        assertThat(createdSession.getSourcePlaylistId()).isEqualTo("spotify-pl-123");
        assertThat(createdSession.getSourcePlaylistTitle()).isEqualTo("Cool Tunes");
        assertThat(createdSession.getPlanId()).isEqualTo(plan.planId());
        assertThat(createdSession.getStatus()).isEqualTo(ReviewSessionStatus.IN_PROGRESS);
        assertThat(createdSession.getTotalTracks()).isEqualTo(6);

        ArgumentCaptor<ReviewSession> sessionCaptor = ArgumentCaptor.forClass(ReviewSession.class);
        verify(reviewSessionRepository).save(sessionCaptor.capture());
        ReviewSession captured = sessionCaptor.getValue();
        List<ReviewTrack> tracks = captured.getTracks();
        assertThat(tracks).hasSize(6);

        // Track 0: AUTO_MATCH -> ACCEPTED (AUTOMATIC)
        ReviewTrack t0 = tracks.get(0);
        assertThat(t0.getOriginalClassification()).isEqualTo(PlanTrackClassification.AUTO_MATCH);
        assertThat(t0.getReviewStatus()).isEqualTo(ReviewTrackStatus.ACCEPTED);
        assertThat(t0.getDecisionSource()).isEqualTo(ReviewDecisionSource.AUTOMATIC);
        assertThat(t0.getSelectedCandidateId()).isEqualTo("yt-cand-0");
        assertThat(t0.isApprovedForMigration()).isTrue();

        // Track 1: REVIEW_REQUIRED -> AWAITING_REVIEW
        ReviewTrack t1 = tracks.get(1);
        assertThat(t1.getOriginalClassification()).isEqualTo(PlanTrackClassification.REVIEW_REQUIRED);
        assertThat(t1.getReviewStatus()).isEqualTo(ReviewTrackStatus.AWAITING_REVIEW);
        assertThat(t1.getOriginalCandidateId()).isEqualTo("yt-cand-1");
        assertThat(t1.getSelectedCandidateId()).isNull(); // held back from auto-approval
        assertThat(t1.isApprovedForMigration()).isFalse();

        // Track 2: UNMATCHED -> AWAITING_REVIEW (no candidate)
        ReviewTrack t2 = tracks.get(2);
        assertThat(t2.getOriginalClassification()).isEqualTo(PlanTrackClassification.UNMATCHED);
        assertThat(t2.getReviewStatus()).isEqualTo(ReviewTrackStatus.AWAITING_REVIEW);
        assertThat(t2.getOriginalCandidateId()).isNull();

        // Track 3: DUPLICATE -> AWAITING_REVIEW with conflict
        ReviewTrack t3 = tracks.get(3);
        assertThat(t3.getOriginalClassification()).isEqualTo(PlanTrackClassification.DUPLICATE);
        assertThat(t3.getReviewStatus()).isEqualTo(ReviewTrackStatus.AWAITING_REVIEW);
        assertThat(t3.hasConflict()).isTrue();
        assertThat(t3.getConflictReason()).contains("Duplicate destination candidate");
        assertThat(t3.isApprovedForMigration()).isFalse();
        assertThat(t3.getSelectedCandidateId()).isNull();

        // Track 4: FAILED -> AWAITING_REVIEW
        ReviewTrack t4 = tracks.get(4);
        assertThat(t4.getOriginalClassification()).isEqualTo(PlanTrackClassification.FAILED);
        assertThat(t4.getReviewStatus()).isEqualTo(ReviewTrackStatus.AWAITING_REVIEW);

        // Track 5: EXCLUDED -> EXCLUDED (isExcluded = true)
        ReviewTrack t5 = tracks.get(5);
        assertThat(t5.getOriginalClassification()).isEqualTo(PlanTrackClassification.EXCLUDED);
        assertThat(t5.getReviewStatus()).isEqualTo(ReviewTrackStatus.EXCLUDED);
        assertThat(t5.isExcluded()).isTrue();
        assertThat(t5.isApprovedForMigration()).isFalse();
    }

    @Test
    @DisplayName("createSessionFromPlan detects automatic duplicate conflicts when plan has multiple AUTO_MATCH with same candidate")
    void createSessionFromPlanDetectsAutomaticDuplicateConflicts() {
        DestinationTrackCandidate sharedCand = createCandidate("yt-shared-dup", "Shared Song");

        List<MigrationPlanTrack> planTracks = List.of(
                MigrationPlanTrack.autoMatch(0, "sp-0", "Song A", "Artist A", sharedCand, 0.95, 0.99, List.of("Auto")),
                MigrationPlanTrack.autoMatch(1, "sp-1", "Song B", "Artist B", sharedCand, 0.90, 0.92, List.of("Auto"))
        );
        MigrationPlanSummary summary = new MigrationPlanSummary(2, 2, 0, 0, 0, 0, 0);
        MigrationPlan plan = new MigrationPlan(UUID.randomUUID(), "spotify-pl-dup", "Dup Tunes", Instant.now(), planTracks, summary);

        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));
        when(reviewSessionRepository.save(any(ReviewSession.class))).thenAnswer(i -> i.getArgument(0));

        ReviewSession session = reviewSessionService.createSessionFromPlan(userId, plan);

        ReviewTrack t0 = session.getTracks().get(0);
        ReviewTrack t1 = session.getTracks().get(1);

        assertThat(t0.hasConflict()).isFalse();
        assertThat(t1.hasConflict()).isTrue();
        assertThat(t1.getConflictReason()).contains("Duplicate destination candidate: 'yt-shared-dup' already claimed by earlier track at position 0");
    }

    @Test
    @DisplayName("createSessionFromPlan fails if user is not found")
    void createSessionFromPlanFailsWhenUserNotFound() {
        MigrationPlan plan = MigrationPlan.empty(UUID.randomUUID(), "sp-1", "Title", Instant.now());
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reviewSessionService.createSessionFromPlan(userId, plan))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("User not found with id");
    }

    @Test
    @DisplayName("getSession returns session and getSessionForUser validates ownership")
    void getSessionAndSecurityValidation() {
        when(reviewSessionRepository.findById(sessionId)).thenReturn(Optional.of(testSession));

        ReviewSession found = reviewSessionService.getSession(sessionId);
        assertThat(found).isEqualTo(testSession);

        // When requesting user matches
        ReviewSession userSession = reviewSessionService.getSessionForUser(sessionId, userId);
        assertThat(userSession).isEqualTo(testSession);

        // When requesting user is different
        UUID wrongUserId = UUID.randomUUID();
        assertThatThrownBy(() -> reviewSessionService.getSessionForUser(sessionId, wrongUserId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("for user");
    }

    @Test
    @DisplayName("selectCandidate records USER_SELECTED provenance and preserves original evidence")
    void selectCandidateRecordsProvenance() {
        ReviewTrack track = new ReviewTrack();
        track.setPosition(0);
        track.setSourceTrackId("sp-0");
        track.setSourceTitle("Original Title");
        track.setSourceArtist("Original Artist");
        track.setOriginalClassification(PlanTrackClassification.REVIEW_REQUIRED);
        track.setOriginalCandidateId("yt-auto-0");
        track.setOriginalMatchScore(0.68);
        track.setOriginalCalibratedProbability(0.55);
        track.setOriginalMatchDecision(MatchDecision.REVIEW_REQUIRED);
        track.setReviewStatus(ReviewTrackStatus.AWAITING_REVIEW);
        testSession.addTrack(track);

        when(reviewSessionRepository.findById(sessionId)).thenReturn(Optional.of(testSession));
        when(reviewTrackRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));

        DestinationTrackCandidate manualCandidate = createCandidate("yt-manual-42", "Manual Selected Video");
        ReviewTrack updated = reviewSessionService.selectCandidate(sessionId, 0, manualCandidate, "Picked by user");

        assertThat(updated.getReviewStatus()).isEqualTo(ReviewTrackStatus.USER_SELECTED);
        assertThat(updated.getDecisionSource()).isEqualTo(ReviewDecisionSource.USER_SELECTED);
        assertThat(updated.getSelectedCandidateId()).isEqualTo("yt-manual-42");
        assertThat(updated.getSelectedCandidateTitle()).isEqualTo("Manual Selected Video");
        assertThat(updated.isApprovedForMigration()).isTrue();
        assertThat(updated.isExcluded()).isFalse();

        // Original automatic matching evidence preserved
        assertThat(updated.getOriginalCandidateId()).isEqualTo("yt-auto-0");
        assertThat(updated.getOriginalMatchScore()).isEqualTo(0.68);
        assertThat(updated.getOriginalCalibratedProbability()).isEqualTo(0.55);
        assertThat(updated.getOriginalMatchDecision()).isEqualTo(MatchDecision.REVIEW_REQUIRED);

        verify(reviewTrackRepository).saveAll(any());
    }

    @Test
    @DisplayName("acceptAutomaticMatch approves original candidate and clears conflict if resolved")
    void acceptAutomaticMatchApprovesCandidate() {
        ReviewTrack track = new ReviewTrack();
        track.setPosition(0);
        track.setSourceTrackId("sp-0");
        track.setSourceTitle("Original Title");
        track.setSourceArtist("Original Artist");
        track.setOriginalClassification(PlanTrackClassification.REVIEW_REQUIRED);
        track.setOriginalCandidateId("yt-auto-0");
        track.setOriginalCandidateTitle("Auto Title");
        track.setReviewStatus(ReviewTrackStatus.AWAITING_REVIEW);
        testSession.addTrack(track);

        when(reviewSessionRepository.findById(sessionId)).thenReturn(Optional.of(testSession));
        when(reviewTrackRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));

        ReviewTrack accepted = reviewSessionService.acceptAutomaticMatch(sessionId, 0);

        assertThat(accepted.getReviewStatus()).isEqualTo(ReviewTrackStatus.ACCEPTED);
        assertThat(accepted.getSelectedCandidateId()).isEqualTo("yt-auto-0");
        assertThat(accepted.isApprovedForMigration()).isTrue();

        verify(reviewTrackRepository).saveAll(any());
    }

    @Test
    @DisplayName("excludeTrack marks excluded and clears candidate")
    void excludeTrackMarksExcluded() {
        ReviewTrack track = new ReviewTrack();
        track.setPosition(0);
        track.setSourceTrackId("sp-0");
        track.setSourceTitle("Original Title");
        track.setSourceArtist("Original Artist");
        track.setOriginalClassification(PlanTrackClassification.AUTO_MATCH);
        track.setOriginalCandidateId("yt-auto-0");
        track.setSelectedCandidateId("yt-auto-0");
        track.setReviewStatus(ReviewTrackStatus.ACCEPTED);
        testSession.addTrack(track);

        when(reviewSessionRepository.findById(sessionId)).thenReturn(Optional.of(testSession));
        when(reviewTrackRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));

        ReviewTrack excluded = reviewSessionService.excludeTrack(sessionId, 0, "Do not want this song");

        assertThat(excluded.getReviewStatus()).isEqualTo(ReviewTrackStatus.EXCLUDED);
        assertThat(excluded.isExcluded()).isTrue();
        assertThat(excluded.getSelectedCandidateId()).isNull();
        assertThat(excluded.isApprovedForMigration()).isFalse();

        verify(reviewTrackRepository).saveAll(any());
    }

    @Test
    @DisplayName("completeSession succeeds when all decisions are resolved and no conflicts exist")
    void completeSessionSucceedsWhenAllResolved() {
        ReviewTrack track0 = new ReviewTrack();
        track0.setPosition(0);
        track0.setSourceTrackId("sp-0");
        track0.setSourceTitle("Title 0");
        track0.setSourceArtist("Artist 0");
        track0.setOriginalClassification(PlanTrackClassification.AUTO_MATCH);
        track0.setOriginalCandidateId("yt-0");
        track0.setSelectedCandidateId("yt-0");
        track0.setReviewStatus(ReviewTrackStatus.ACCEPTED);
        testSession.addTrack(track0);

        ReviewTrack track1 = new ReviewTrack();
        track1.setPosition(1);
        track1.setSourceTrackId("sp-1");
        track1.setSourceTitle("Title 1");
        track1.setSourceArtist("Artist 1");
        track1.setOriginalClassification(PlanTrackClassification.UNMATCHED);
        track1.setReviewStatus(ReviewTrackStatus.EXCLUDED);
        track1.setExcluded(true);
        testSession.addTrack(track1);

        when(reviewSessionRepository.findById(sessionId)).thenReturn(Optional.of(testSession));
        when(reviewTrackRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));
        when(reviewSessionRepository.save(any(ReviewSession.class))).thenAnswer(i -> i.getArgument(0));

        ReviewSession completed = reviewSessionService.completeSession(sessionId);

        assertThat(completed.getStatus()).isEqualTo(ReviewSessionStatus.COMPLETED);
        assertThat(completed.isCompleted()).isTrue();
        assertThat(completed.getCompletedAt()).isNotNull();

        verify(reviewSessionRepository).save(testSession);
    }

    @Test
    @DisplayName("completeSession fails if any track is still awaiting review (Invariant 5)")
    void completeSessionFailsWithAwaitingTrack() {
        ReviewTrack track0 = new ReviewTrack();
        track0.setPosition(0);
        track0.setSourceTrackId("sp-0");
        track0.setSourceTitle("Title 0");
        track0.setSourceArtist("Artist 0");
        track0.setOriginalClassification(PlanTrackClassification.REVIEW_REQUIRED);
        track0.setReviewStatus(ReviewTrackStatus.AWAITING_REVIEW);
        testSession.addTrack(track0);

        when(reviewSessionRepository.findById(sessionId)).thenReturn(Optional.of(testSession));

        assertThatThrownBy(() -> reviewSessionService.completeSession(sessionId))
                .isInstanceOf(InvalidStateException.class)
                .hasMessageContaining("required decisions remain unresolved");
    }

    @Test
    @DisplayName("Duplicate conflict resolution: selecting identical candidate creates conflict; choosing different candidate resolves it")
    void duplicateConflictDetectionAndResolution() {
        ReviewTrack track0 = new ReviewTrack();
        track0.setPosition(0);
        track0.setSourceTrackId("sp-0");
        track0.setSourceTitle("Title 0");
        track0.setSourceArtist("Artist 0");
        track0.setOriginalClassification(PlanTrackClassification.AUTO_MATCH);
        track0.setOriginalCandidateId("yt-shared");
        track0.setSelectedCandidateId("yt-shared");
        track0.setReviewStatus(ReviewTrackStatus.ACCEPTED);
        testSession.addTrack(track0);

        ReviewTrack track1 = new ReviewTrack();
        track1.setPosition(1);
        track1.setSourceTrackId("sp-1");
        track1.setSourceTitle("Title 1");
        track1.setSourceArtist("Artist 1");
        track1.setOriginalClassification(PlanTrackClassification.REVIEW_REQUIRED);
        track1.setOriginalCandidateId("yt-shared");
        track1.setReviewStatus(ReviewTrackStatus.AWAITING_REVIEW);
        testSession.addTrack(track1);

        when(reviewSessionRepository.findById(sessionId)).thenReturn(Optional.of(testSession));
        when(reviewTrackRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));

        // 1. User accepts auto-match on track 1 (which has identical candidate 'yt-shared' as track 0)
        reviewSessionService.acceptAutomaticMatch(sessionId, 1);

        // Track 1 should now be flagged with duplicate conflict!
        assertThat(track1.hasConflict()).isTrue();
        assertThat(track1.getConflictReason()).contains("Duplicate destination candidate: 'yt-shared'");

        // Completing session now MUST fail!
        assertThatThrownBy(() -> reviewSessionService.completeSession(sessionId))
                .isInstanceOf(InvalidStateException.class)
                .hasMessageContaining("required decisions remain unresolved");

        // 2. User resolves conflict by selecting an alternate unique candidate
        DestinationTrackCandidate alternate = createCandidate("yt-unique-2", "Unique Alternate Video");
        reviewSessionService.selectCandidate(sessionId, 1, alternate, "Resolved duplicate");

        // Conflict on track 1 should now be cleared!
        assertThat(track1.hasConflict()).isFalse();
        assertThat(track1.getConflictReason()).isNull();
        assertThat(track1.getSelectedCandidateId()).isEqualTo("yt-unique-2");

        // Now completing session succeeds!
        when(reviewSessionRepository.save(any(ReviewSession.class))).thenAnswer(i -> i.getArgument(0));
        ReviewSession completed = reviewSessionService.completeSession(sessionId);
        assertThat(completed.isCompleted()).isTrue();
    }

    @Test
    @DisplayName("Duplicate conflict automatically cleared when earlier claiming track is excluded")
    void duplicateConflictClearedWhenEarlierTrackExcluded() {
        ReviewTrack track0 = new ReviewTrack();
        track0.setPosition(0);
        track0.setSourceTrackId("sp-0");
        track0.setSourceTitle("Title 0");
        track0.setSourceArtist("Artist 0");
        track0.setOriginalClassification(PlanTrackClassification.AUTO_MATCH);
        track0.setOriginalCandidateId("yt-shared");
        track0.setSelectedCandidateId("yt-shared");
        track0.setReviewStatus(ReviewTrackStatus.ACCEPTED);
        testSession.addTrack(track0);

        ReviewTrack track1 = new ReviewTrack();
        track1.setPosition(1);
        track1.setSourceTrackId("sp-1");
        track1.setSourceTitle("Title 1");
        track1.setSourceArtist("Artist 1");
        track1.setOriginalClassification(PlanTrackClassification.AUTO_MATCH);
        track1.setOriginalCandidateId("yt-shared");
        track1.setSelectedCandidateId("yt-shared");
        track1.setReviewStatus(ReviewTrackStatus.ACCEPTED);
        track1.setConflict("Duplicate destination candidate: 'yt-shared' already claimed by earlier track at position 0");
        testSession.addTrack(track1);

        when(reviewSessionRepository.findById(sessionId)).thenReturn(Optional.of(testSession));
        when(reviewTrackRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));

        // Prior to exclusion, track 1 has conflict
        assertThat(track1.hasConflict()).isTrue();

        // User excludes track 0
        reviewSessionService.excludeTrack(sessionId, 0, "Not needed");

        // Track 0 is excluded
        assertThat(track0.isExcluded()).isTrue();
        // Track 1's conflict is automatically cleared!
        assertThat(track1.hasConflict()).isFalse();
        assertThat(track1.getConflictReason()).isNull();

        verify(reviewTrackRepository).saveAll(any());
    }

    @Test
    @DisplayName("Duplicate conflict automatically cleared when earlier claiming track changes candidate")
    void duplicateConflictClearedWhenEarlierTrackChangesCandidate() {
        ReviewTrack track0 = new ReviewTrack();
        track0.setPosition(0);
        track0.setSourceTrackId("sp-0");
        track0.setSourceTitle("Title 0");
        track0.setSourceArtist("Artist 0");
        track0.setOriginalClassification(PlanTrackClassification.AUTO_MATCH);
        track0.setOriginalCandidateId("yt-shared");
        track0.setSelectedCandidateId("yt-shared");
        track0.setReviewStatus(ReviewTrackStatus.ACCEPTED);
        testSession.addTrack(track0);

        ReviewTrack track1 = new ReviewTrack();
        track1.setPosition(1);
        track1.setSourceTrackId("sp-1");
        track1.setSourceTitle("Title 1");
        track1.setSourceArtist("Artist 1");
        track1.setOriginalClassification(PlanTrackClassification.AUTO_MATCH);
        track1.setOriginalCandidateId("yt-shared");
        track1.setSelectedCandidateId("yt-shared");
        track1.setReviewStatus(ReviewTrackStatus.ACCEPTED);
        track1.setConflict("Duplicate destination candidate: 'yt-shared' already claimed by earlier track at position 0");
        testSession.addTrack(track1);

        when(reviewSessionRepository.findById(sessionId)).thenReturn(Optional.of(testSession));
        when(reviewTrackRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));

        // Track 0 changes to a new unique candidate
        DestinationTrackCandidate newFor0 = createCandidate("yt-unique-for-0", "Unique for 0");
        reviewSessionService.selectCandidate(sessionId, 0, newFor0, "Changed 0");

        // Track 0 has new candidate
        assertThat(track0.getSelectedCandidateId()).isEqualTo("yt-unique-for-0");
        // Track 1's conflict on 'yt-shared' is cleared!
        assertThat(track1.hasConflict()).isFalse();
        assertThat(track1.getConflictReason()).isNull();

        verify(reviewTrackRepository).saveAll(any());
    }

    @Test
    @DisplayName("cancelSession transitions session to CANCELLED")
    void cancelSessionTransitionsToCancelled() {
        when(reviewSessionRepository.findById(sessionId)).thenReturn(Optional.of(testSession));
        when(reviewSessionRepository.save(any(ReviewSession.class))).thenAnswer(i -> i.getArgument(0));

        ReviewSession cancelled = reviewSessionService.cancelSession(sessionId);
        assertThat(cancelled.getStatus()).isEqualTo(ReviewSessionStatus.CANCELLED);
        assertThat(cancelled.isCancelled()).isTrue();
    }

    @Test
    @DisplayName("Initial DUPLICATE track retains conflict until explicitly resolved by user")
    void initialDuplicateTrackRetainsConflictUntilExplicitlyResolvedByUser() {
        DestinationTrackCandidate cand0 = createCandidate("yt-cand-0", "Song 0");
        DestinationTrackCandidate cand1 = createCandidate("yt-cand-0", "Song 0 Duplicate");

        List<MigrationPlanTrack> planTracks = List.of(
                MigrationPlanTrack.autoMatch(0, "sp-0", "Song 0", "Artist 0", cand0, 0.95, 0.99, List.of("Exact match")),
                MigrationPlanTrack.duplicate(1, "sp-1", "Song 1", "Artist 1", cand1, 0.90, 0.92, "Duplicate candidate yt-cand-0")
        );
        MigrationPlanSummary summary = new MigrationPlanSummary(2, 1, 0, 0, 1, 0, 0);
        MigrationPlan plan = new MigrationPlan(UUID.randomUUID(), "spotify-pl-dup2", "Dup2 Tunes", Instant.now(), planTracks, summary);

        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));
        when(reviewSessionRepository.save(any(ReviewSession.class))).thenAnswer(i -> {
            ReviewSession s = i.getArgument(0);
            s.setId(sessionId);
            return s;
        });
        when(reviewSessionRepository.findById(sessionId)).thenAnswer(i -> Optional.of(testSession));
        when(reviewTrackRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));

        ReviewSession session = reviewSessionService.createSessionFromPlan(userId, plan);
        ReviewTrack t1 = session.getTracks().get(1);

        // 1. Initial state: track 1 has active duplicate conflict and is awaiting review
        assertThat(t1.hasConflict()).isTrue();
        assertThat(t1.getConflictReason()).contains("Duplicate destination candidate");
        assertThat(t1.isApprovedForMigration()).isFalse();

        // 2. Setting testSession tracks to match created session for subsequent service calls
        testSession.setTracks(session.getTracks());

        // 3. Completing session fails because track 1 has unresolved conflict and is awaiting review
        assertThatThrownBy(() -> reviewSessionService.completeSession(sessionId))
                .isInstanceOf(InvalidStateException.class)
                .hasMessageContaining("required decisions remain unresolved");

        // 4. User resolves conflict by selecting an alternate unique candidate
        DestinationTrackCandidate alternate = createCandidate("yt-unique-alt", "Alternate Track");
        reviewSessionService.selectCandidate(sessionId, 1, alternate, "Resolved duplicate");

        assertThat(t1.hasConflict()).isFalse();
        assertThat(t1.getConflictReason()).isNull();
        assertThat(t1.isApprovedForMigration()).isTrue();
        assertThat(t1.getReviewStatus()).isEqualTo(ReviewTrackStatus.USER_SELECTED);

        // 5. Now completing session succeeds
        ReviewSession completed = reviewSessionService.completeSession(sessionId);
        assertThat(completed.isCompleted()).isTrue();
    }

    @Test
    @DisplayName("ensureTracksLoaded loads tracks from repository when session collection is empty or detached")
    void ensureTracksLoadedReloadsWhenCollectionEmptyOrIncomplete() {
        ReviewTrack track0 = new ReviewTrack();
        track0.setPosition(0);
        track0.setSourceTrackId("sp-0");
        track0.setSourceTitle("Title 0");
        track0.setReviewStatus(ReviewTrackStatus.ACCEPTED);
        track0.setSelectedCandidateId("yt-0");

        ReviewTrack track1 = new ReviewTrack();
        track1.setPosition(1);
        track1.setSourceTrackId("sp-1");
        track1.setSourceTitle("Title 1");
        track1.setReviewStatus(ReviewTrackStatus.AWAITING_REVIEW);

        // testSession has totalTracks = 2, but its in-memory collection is empty (simulating lazy/unloaded)
        testSession.setTotalTracks(2);
        testSession.getTracks().clear();

        when(reviewSessionRepository.findById(sessionId)).thenReturn(Optional.of(testSession));
        when(reviewTrackRepository.findBySessionIdOrderByPositionAsc(sessionId)).thenReturn(List.of(track0, track1));

        ReviewProgressSummary progress = reviewSessionService.getReviewProgress(sessionId);

        assertThat(progress.totalTracks()).isEqualTo(2);
        assertThat(progress.acceptedCount()).isEqualTo(1);
        assertThat(progress.awaitingReviewCount()).isEqualTo(1);
        assertThat(testSession.getTracks()).hasSize(2);
        assertThat(track0.getSession()).isEqualTo(testSession);
        assertThat(track1.getSession()).isEqualTo(testSession);

        verify(reviewTrackRepository).findBySessionIdOrderByPositionAsc(sessionId);
    }
}
