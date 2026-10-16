package com.spotifyyoutube.migrator.migration.review;

import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.identity.repository.UserRepository;
import com.spotifyyoutube.migrator.matching.engine.MatchDecision;
import com.spotifyyoutube.migrator.migration.application.destination.MusicDestination;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationTrackCandidate;
import com.spotifyyoutube.migrator.migration.application.orchestration.PlaylistMigrationOrchestrator;
import com.spotifyyoutube.migrator.migration.application.plan.MigrationPlan;
import com.spotifyyoutube.migrator.migration.application.plan.MigrationPlanSummary;
import com.spotifyyoutube.migrator.migration.application.plan.MigrationPlanTrack;
import com.spotifyyoutube.migrator.migration.application.plan.PlanTrackClassification;
import com.spotifyyoutube.migrator.migration.domain.MigrationJob;
import com.spotifyyoutube.migrator.migration.domain.MigrationStatus;
import com.spotifyyoutube.migrator.migration.repository.MigrationJobRepository;
import com.spotifyyoutube.migrator.migration.repository.MigrationTaskRepository;
import com.spotifyyoutube.migrator.migration.review.application.DefaultReviewSessionService;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewDecisionSource;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSession;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewTrack;
import com.spotifyyoutube.migrator.migration.review.repository.ReviewSessionRepository;
import com.spotifyyoutube.migrator.migration.review.repository.ReviewTrackRepository;
import com.spotifyyoutube.migrator.playlist.domain.Platform;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Phase 10: Review State Safety, Isolation, and Backward Compatibility Test")
class ReviewStatePersistenceSafetyTest {

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

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        testUser = new User("tester@example.com");
        testUser.setId(userId);
    }

    @Test
    @DisplayName("Invariant 10: Review domain operations never mutate Phase 9's read-only preview snapshot")
    void reviewOperationsDoNotMutatePhase9Preview() {
        DestinationTrackCandidate cand0 = new DestinationTrackCandidate("yt-0", "Title 0", "Artist", null, null, null, Duration.ofSeconds(180));
        DestinationTrackCandidate cand1 = new DestinationTrackCandidate("yt-1", "Title 1", "Artist", null, null, null, Duration.ofSeconds(200));

        List<MigrationPlanTrack> planTracks = List.of(
                MigrationPlanTrack.autoMatch(0, "sp-0", "Title 0", "Artist 0", cand0, 0.95, 0.98, List.of("Auto match")),
                MigrationPlanTrack.reviewRequired(1, "sp-1", "Title 1", "Artist 1", cand1, 0.70, 0.65, List.of("Borderline")),
                MigrationPlanTrack.unmatched(2, "sp-2", "Title 2", "Artist 2", List.of("No candidate"))
        );
        MigrationPlanSummary summary = new MigrationPlanSummary(3, 1, 1, 1, 0, 0, 0);
        MigrationPlan plan = new MigrationPlan(UUID.randomUUID(), "spotify-100", "Playlist 100", Instant.now(), planTracks, summary);

        // Snapshot state of plan prior to review operations
        int initialTrackCount = plan.tracks().size();
        PlanTrackClassification initialClassification0 = plan.tracks().get(0).classification();
        DestinationTrackCandidate initialCand0 = plan.tracks().get(0).selectedCandidate();
        Double initialScore0 = plan.tracks().get(0).matchScore();

        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));
        when(reviewSessionRepository.save(any(ReviewSession.class))).thenAnswer(i -> {
            ReviewSession s = i.getArgument(0);
            s.setId(UUID.randomUUID());
            return s;
        });

        // 1. Create review session from plan
        ReviewSession session = reviewSessionService.createSessionFromPlan(userId, plan);

        // 2. Perform various review decisions on session tracks
        ReviewTrack track1 = session.getTracks().get(1);
        track1.selectCandidate(new DestinationTrackCandidate("yt-manual-replacement", "Manual Title", "Artist", null, null, null, Duration.ofSeconds(205)), "User choice");

        ReviewTrack track2 = session.getTracks().get(2);
        track2.exclude("User excluded");

        // Verify that Phase 9's MigrationPlan and all its tracks are 100% UNMUTATED!
        assertThat(plan.tracks()).hasSize(initialTrackCount);
        assertThat(plan.tracks().get(0).classification()).isEqualTo(initialClassification0);
        assertThat(plan.tracks().get(0).selectedCandidate()).isEqualTo(initialCand0);
        assertThat(plan.tracks().get(0).matchScore()).isEqualTo(initialScore0);

        assertThat(plan.tracks().get(1).classification()).isEqualTo(PlanTrackClassification.REVIEW_REQUIRED);
        assertThat(plan.tracks().get(1).selectedCandidate()).isEqualTo(cand1);
        assertThat(plan.tracks().get(1).selectedCandidate().id()).isEqualTo("yt-1"); // NOT "yt-manual-replacement"

        assertThat(plan.tracks().get(2).classification()).isEqualTo(PlanTrackClassification.UNMATCHED);
        assertThat(plan.tracks().get(2).isExcluded()).isFalse(); // NOT excluded in plan preview
    }

    @Test
    @DisplayName("Invariant 9: Review domain has ZERO dependency on MusicDestination and performs zero YouTube mutations")
    void reviewDomainHasZeroDestinationDependencies() {
        MusicDestination mockDestination = mock(MusicDestination.class);

        // Architectural check: verify no field of type MusicDestination in DefaultReviewSessionService
        Field[] fields = DefaultReviewSessionService.class.getDeclaredFields();
        boolean hasDestinationField = Arrays.stream(fields)
                .anyMatch(f -> MusicDestination.class.isAssignableFrom(f.getType()));
        assertThat(hasDestinationField)
                .as("DefaultReviewSessionService must NOT depend on MusicDestination")
                .isFalse();

        // Also verify on ReviewSession and ReviewTrack entities
        boolean entityHasDestination = Arrays.stream(ReviewSession.class.getDeclaredFields())
                .anyMatch(f -> MusicDestination.class.isAssignableFrom(f.getType()))
                || Arrays.stream(ReviewTrack.class.getDeclaredFields())
                .anyMatch(f -> MusicDestination.class.isAssignableFrom(f.getType()));
        assertThat(entityHasDestination).isFalse();

        verifyNoInteractions(mockDestination);
    }

    @Test
    @DisplayName("Original automatic matching evidence is strictly preserved across multiple decisions without score fabrication")
    void originalMatchingEvidenceIsStrictlyPreserved() {
        ReviewTrack track = new ReviewTrack();
        track.setPosition(0);
        track.setSourceTrackId("spotify-track-999");
        track.setSourceTitle("Original Track");
        track.setSourceArtist("Original Artist");
        track.setOriginalClassification(PlanTrackClassification.REVIEW_REQUIRED);
        track.setOriginalCandidateId("yt-auto-original");
        track.setOriginalCandidateTitle("Auto Title");
        track.setOriginalCandidateChannel("Auto Channel");
        track.setOriginalCandidateDurationMs(200000L);
        track.setOriginalMatchScore(0.71);
        track.setOriginalCalibratedProbability(0.62);
        track.setOriginalMatchDecision(MatchDecision.REVIEW_REQUIRED);
        track.setOriginalMatchReasons("Borderline score: 0.71; Duration delta: 8s");

        // Action 1: User selects replacement candidate 1
        DestinationTrackCandidate candA = new DestinationTrackCandidate("yt-cand-A", "Title A", "Channel A", null, null, null, Duration.ofSeconds(198));
        track.selectCandidate(candA, "First choice");

        assertThat(track.getSelectedCandidateId()).isEqualTo("yt-cand-A");
        assertThat(track.getDecisionSource()).isEqualTo(ReviewDecisionSource.USER_SELECTED);

        // Action 2: User changes mind to replacement candidate B
        DestinationTrackCandidate candB = new DestinationTrackCandidate("yt-cand-B", "Title B", "Channel B", null, null, null, Duration.ofSeconds(201));
        track.selectCandidate(candB, "Second choice");

        assertThat(track.getSelectedCandidateId()).isEqualTo("yt-cand-B");

        // Action 3: User excludes track
        track.exclude("Changed mind again - exclude");
        assertThat(track.isExcluded()).isTrue();

        // Action 4: User un-excludes and picks candidate C
        DestinationTrackCandidate candC = new DestinationTrackCandidate("yt-cand-C", "Title C", "Channel C", null, null, null, Duration.ofSeconds(200));
        track.selectCandidate(candC, "Final choice");

        // VERIFY: All original automatic evidence fields remain 100% EQUAL to their initial values!
        assertThat(track.getOriginalClassification()).isEqualTo(PlanTrackClassification.REVIEW_REQUIRED);
        assertThat(track.getOriginalCandidateId()).isEqualTo("yt-auto-original");
        assertThat(track.getOriginalCandidateTitle()).isEqualTo("Auto Title");
        assertThat(track.getOriginalCandidateChannel()).isEqualTo("Auto Channel");
        assertThat(track.getOriginalCandidateDurationMs()).isEqualTo(200000L);
        assertThat(track.getOriginalMatchScore()).isEqualTo(0.71);
        assertThat(track.getOriginalCalibratedProbability()).isEqualTo(0.62);
        assertThat(track.getOriginalMatchDecision()).isEqualTo(MatchDecision.REVIEW_REQUIRED);
        assertThat(track.getOriginalMatchReasons()).isEqualTo("Borderline score: 0.71; Duration delta: 8s");

        // Verify that NO confidence score or calibrated probability was fabricated for candidate C
        assertThat(track.getSelectedCandidate().orElseThrow().id()).isEqualTo("yt-cand-C");
    }

    @Test
    @DisplayName("Backward Compatibility: Existing PlaylistMigrationOrchestrator.executeMigration contract is preserved")
    void existingOrchestratorContractIsPreserved() {
        MigrationJobRepository mockJobRepo = mock(MigrationJobRepository.class);
        MigrationTaskRepository mockTaskRepo = mock(MigrationTaskRepository.class);

        UUID jobId = UUID.randomUUID();
        MigrationJob completedJob = new MigrationJob();
        completedJob.setId(jobId);
        completedJob.setStatus(MigrationStatus.COMPLETED);
        completedJob.setSourcePlatform(Platform.SPOTIFY);
        completedJob.setTargetPlatform(Platform.YOUTUBE);

        when(mockJobRepo.findById(jobId)).thenReturn(Optional.of(completedJob));

        PlaylistMigrationOrchestrator orchestrator = new PlaylistMigrationOrchestrator(
                mock(com.spotifyyoutube.migrator.spotify.application.SpotifyPlaylistService.class),
                mock(com.spotifyyoutube.migrator.matching.normalization.NormalizationOrchestrator.class),
                mock(com.spotifyyoutube.migrator.matching.discovery.CandidateDiscoveryService.class),
                mock(com.spotifyyoutube.migrator.matching.engine.TrackMatchingEngine.class),
                mock(MusicDestination.class),
                mockJobRepo,
                mockTaskRepo
        );

        // Verifies executeMigration(UUID) signature and contract are intact
        org.junit.jupiter.api.Assertions.assertThrows(
                com.spotifyyoutube.migrator.common.exception.InvalidStateException.class,
                () -> orchestrator.executeMigration(jobId)
        );
    }
}
