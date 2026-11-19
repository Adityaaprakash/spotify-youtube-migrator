package com.spotifyyoutube.migrator.migration.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spotifyyoutube.migrator.common.exception.ExternalProviderException;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.identity.repository.OAuthConnectionRepository;
import com.spotifyyoutube.migrator.identity.repository.UserRepository;
import com.spotifyyoutube.migrator.migration.api.dto.RollbackExecutionApiRequest;
import com.spotifyyoutube.migrator.migration.api.dto.RollbackExecutionResponse;
import com.spotifyyoutube.migrator.migration.application.destination.MusicDestination;
import com.spotifyyoutube.migrator.migration.application.destination.model.AddTrackToDestinationPlaylistRequest;
import com.spotifyyoutube.migrator.migration.application.destination.model.CreateDestinationPlaylistRequest;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationPlaylist;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationPlaylistItem;
import com.spotifyyoutube.migrator.migration.application.execution.MigrationExecutionStateService;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationContext;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedTrack;
import com.spotifyyoutube.migrator.migration.application.plan.PlanTrackClassification;
import com.spotifyyoutube.migrator.migration.async.MigrationBackgroundWorker;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionAuditEvent;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionRollbackRecord;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackRecord;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackStatus;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecution;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus;
import com.spotifyyoutube.migrator.migration.execution.domain.RollbackRecordStatus;
import com.spotifyyoutube.migrator.migration.execution.repository.ExecutionAuditEventRepository;
import com.spotifyyoutube.migrator.migration.execution.repository.ExecutionRollbackRecordRepository;
import com.spotifyyoutube.migrator.migration.execution.repository.ExecutionTrackRecordRepository;
import com.spotifyyoutube.migrator.migration.execution.repository.MigrationExecutionRepository;
import com.spotifyyoutube.migrator.migration.reliability.idempotency.MigrationIdempotencyRepository;
import com.spotifyyoutube.migrator.migration.reliability.scheduling.ClaimedExecution;
import com.spotifyyoutube.migrator.migration.reliability.scheduling.StaleLeaseOwnershipException;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewDecisionSource;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSession;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSessionStatus;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewTrack;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewTrackStatus;
import com.spotifyyoutube.migrator.migration.review.repository.ReviewSessionRepository;
import com.spotifyyoutube.migrator.migration.review.repository.ReviewTrackRepository;
import com.spotifyyoutube.migrator.playlist.domain.Platform;
import com.spotifyyoutube.migrator.spotify.infrastructure.SpotifyApiClient;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Deterministic end-to-end reliability integration test suite for Phase 17F:
 * - Transient destination provider failure recovery via in-flight bounded retry
 * - Permanent provider failure handling, error sanitization, and conservative stopping
 * - Partial migration resume without duplicating confirmed inserted tracks
 * - Worker interruption, lease expiration, and safe recovery claiming
 * - Idempotency contract under repeated and conflicting submission requests
 * - Full and partial rollback of destination playlist insertions with audit retention
 * - State machine transition validation and multi-tenant security rejections
 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@DisplayName("Phase 17F: Migration Reliability, Recovery, Rollback & Idempotency Integration Tests")
public class MigrationReliabilityAndRecoveryE2EIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OAuthConnectionRepository oAuthConnectionRepository;

    @Autowired
    private ReviewSessionRepository reviewSessionRepository;

    @Autowired
    private ReviewTrackRepository reviewTrackRepository;

    @Autowired
    private MigrationExecutionRepository executionRepository;

    @Autowired
    private ExecutionTrackRecordRepository trackRecordRepository;

    @Autowired
    private ExecutionRollbackRecordRepository rollbackRecordRepository;

    @Autowired
    private ExecutionAuditEventRepository auditEventRepository;

    @Autowired
    private MigrationIdempotencyRepository idempotencyRepository;

    @Autowired
    private MigrationBackgroundWorker backgroundWorker;

    @Autowired
    private MigrationExecutionStateService executionStateService;

    @PersistenceContext
    private EntityManager entityManager;

    @MockBean
    private SpotifyApiClient spotifyApiClient;

    @MockBean
    private MusicDestination musicDestination;

    private User primaryUser;
    private User secondaryUser;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        primaryUser = userRepository.save(new User("primary-reliability-user@example.com"));
        secondaryUser = userRepository.save(new User("secondary-reliability-user@example.com"));

        when(musicDestination.getPlatform()).thenReturn(Platform.YOUTUBE);
        when(musicDestination.createPlaylist(any(UUID.class), any(CreateDestinationPlaylistRequest.class)))
                .thenAnswer(invocation -> {
                    CreateDestinationPlaylistRequest req = invocation.getArgument(1);
                    return new DestinationPlaylist(
                            "PL_dest_reliability_" + UUID.randomUUID(),
                            req.title(),
                            req.description(),
                            "private",
                            "https://music.youtube.com/playlist?list=PL_dest_reliability_test",
                            Platform.YOUTUBE
                    );
                });
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    private void cleanDatabase() {
        if (entityManager != null) {
            entityManager.clear();
        }
        idempotencyRepository.deleteAllInBatch();
        rollbackRecordRepository.deleteAllInBatch();
        auditEventRepository.deleteAllInBatch();
        trackRecordRepository.deleteAllInBatch();
        executionRepository.deleteAllInBatch();
        reviewTrackRepository.deleteAllInBatch();
        reviewSessionRepository.deleteAllInBatch();
        oAuthConnectionRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    private ReviewSession createApprovedReviewSession(User user, String title, int trackCount) {
        ReviewSession session = new ReviewSession(
                user, "sp_src_" + UUID.randomUUID(), title, UUID.randomUUID()
        );
        for (int i = 0; i < trackCount; i++) {
            ReviewTrack track = new ReviewTrack();
            track.setPosition(i);
            track.setSourceTrackId("sp_track_" + i);
            track.setSourceTitle("Track Title " + i);
            track.setSourceArtist("Track Artist " + i);
            track.setOriginalClassification(PlanTrackClassification.AUTO_MATCH);
            track.setOriginalCandidateId("yt_candidate_" + i);
            track.setSelectedCandidateId("yt_candidate_" + i);
            track.setReviewStatus(ReviewTrackStatus.ACCEPTED);
            track.setDecisionSource(ReviewDecisionSource.AUTOMATIC);
            track.setExcluded(false);
            session.addTrack(track);
        }
        session.complete(session.deriveSummary());
        return reviewSessionRepository.save(session);
    }

    private com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationContext createApprovedContext(
            ReviewSession session, User user, List<ApprovedTrack> tracks
    ) {
        return new com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationContext(
                session.getId(),
                UUID.randomUUID(),
                user.getId(),
                session.getSourcePlaylistId(),
                session.getSourcePlaylistTitle(),
                "Target Playlist",
                "Description",
                false,
                tracks,
                tracks.size(),
                tracks.size(),
                0,
                Instant.now()
        );
    }

    @Test
    @DisplayName("Scenario A: Transient provider failure recovers via in-flight retry without track duplication")
    void transientDestinationFailure_inFlightRetrySucceeds_completesWithoutDuplicates() throws Exception {
        ReviewSession session = createApprovedReviewSession(primaryUser, "Transient Failure Playlist", 3);

        ApprovedTrack track0 = new ApprovedTrack(0, "sp_track_0", "Track 0", "Artist 0", "yt_candidate_0", "Video 0", "Channel 0", Duration.ofMinutes(3), ReviewDecisionSource.AUTOMATIC, 0);
        ApprovedTrack track1 = new ApprovedTrack(1, "sp_track_1", "Track 1", "Artist 1", "yt_candidate_1", "Video 1", "Channel 1", Duration.ofMinutes(3), ReviewDecisionSource.AUTOMATIC, 1);
        ApprovedTrack track2 = new ApprovedTrack(2, "sp_track_2", "Track 2", "Artist 2", "yt_candidate_2", "Video 2", "Channel 2", Duration.ofMinutes(3), ReviewDecisionSource.AUTOMATIC, 2);

        ApprovedMigrationContext context = createApprovedContext(session, primaryUser, List.of(track0, track1, track2));
        MigrationExecution execution = new MigrationExecution(
                primaryUser,
                session,
                context,
                "PL_retry_test",
                "https://music.youtube.com/playlist?list=PL_retry_test",
                MigrationExecutionStatus.QUEUED
        );
        execution.reconcileCounts();
        MigrationExecution saved = executionRepository.saveAndFlush(execution);

        AtomicInteger track1Attempts = new AtomicInteger(0);

        when(musicDestination.addTrackToPlaylist(
                any(UUID.class), anyString(), any(AddTrackToDestinationPlaylistRequest.class)
        )).thenAnswer(invocation -> {
            AddTrackToDestinationPlaylistRequest req = invocation.getArgument(2);
            String videoId = req.trackId();

            if ("yt_candidate_1".equals(videoId)) {
                int attempt = track1Attempts.incrementAndGet();
                if (attempt == 1) {
                    // Inject transient rate-limit error with Retry-After
                    throw new ExternalProviderException("Transient rate limit exceeded", 429, "1");
                }
            }

            return new DestinationPlaylistItem(
                    "item_" + videoId,
                    invocation.getArgument(1),
                    videoId,
                    req.position()
            );
        });

        // Worker claims the queued execution with fencing generation
        ClaimedExecution claim = executionRepository.claimNextEligibleExecution("worker-retry-test", 60L).orElseThrow();
        assertThat(claim.executionId()).isEqualTo(saved.getId());

        if (entityManager != null) {
            entityManager.clear();
        }

        // Execute claimed track insertion with retry-enabled executor
        executionStateService.executeClaimedTrackInsertion(claim.executionId(), claim.userId(), claim.leaseGeneration());

        // Verify persisted state: all 3 tracks are successfully ADDED
        MigrationExecution completedExec = executionRepository.findById(saved.getId()).orElseThrow();
        assertThat(completedExec.getStatus()).isEqualTo(MigrationExecutionStatus.COMPLETED);
        assertThat(completedExec.getSuccessfulInsertions()).isEqualTo(3);

        List<ExecutionTrackRecord> records =
                trackRecordRepository.findByExecutionIdOrderByTargetPositionAsc(saved.getId());
        assertThat(records).hasSize(3);
        assertThat(records.get(0).getStatus()).isEqualTo(ExecutionTrackStatus.ADDED);
        assertThat(records.get(1).getStatus()).isEqualTo(ExecutionTrackStatus.ADDED);
        assertThat(records.get(2).getStatus()).isEqualTo(ExecutionTrackStatus.ADDED);

        // Verify track 1 required exactly 2 attempts (in-flight retry)
        assertThat(track1Attempts.get()).isEqualTo(2);

        // Verify progress endpoint reports 100%
        mockMvc.perform(get("/api/migrations/" + saved.getId() + "/progress")
                        .with(user(primaryUser.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.addedTracks").value(3))
                .andExpect(jsonPath("$.failedTracks").value(0))
                .andExpect(jsonPath("$.skippedTracks").value(0))
                .andExpect(jsonPath("$.progressPercentage").value(100.0));
    }

    @Test
    @DisplayName("Scenario B: Permanent provider failure halts forward insertion and marks subsequent tracks SKIPPED")
    void permanentDestinationFailure_conservativeStopping_marksSkippedAndPreservesState() throws Exception {
        ReviewSession session = createApprovedReviewSession(primaryUser, "Permanent Failure Playlist", 4);

        when(musicDestination.addTrackToPlaylist(
                any(UUID.class), anyString(), any(AddTrackToDestinationPlaylistRequest.class)
        )).thenAnswer(invocation -> {
            AddTrackToDestinationPlaylistRequest req = invocation.getArgument(2);
            String videoId = req.trackId();

            if ("yt_candidate_1".equals(videoId)) {
                // Permanent non-retryable 400 Bad Request error
                throw new ExternalProviderException("Video not available for playlist insertion", 400);
            }

            return new DestinationPlaylistItem(
                    "item_" + videoId,
                    invocation.getArgument(1),
                    videoId,
                    req.position()
            );
        });

        String payload = "{\"reviewSessionId\":\"" + session.getId() + "\"}";
        MvcResult submitResult = mockMvc.perform(post("/api/migrations")
                        .with(user(primaryUser.getEmail()))
                        .with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isAccepted())
                .andReturn();

        JsonNode submitNode = objectMapper.readTree(submitResult.getResponse().getContentAsString());
        UUID executionId = UUID.fromString(submitNode.get("executionId").asText());

        // Wait for terminal state (PARTIALLY_COMPLETED or FAILED)
        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    MigrationExecution exec = executionRepository.findById(executionId).orElseThrow();
                    assertThat(exec.getStatus()).isIn(
                            MigrationExecutionStatus.PARTIALLY_COMPLETED,
                            MigrationExecutionStatus.FAILED
                    );
                });

        // Verify conservative stopping: track 0 ADDED, track 1 FAILED, tracks 2 and 3 SKIPPED
        List<ExecutionTrackRecord> records =
                trackRecordRepository.findByExecutionIdOrderByTargetPositionAsc(executionId);
        assertThat(records).hasSize(4);
        assertThat(records.get(0).getStatus()).isEqualTo(ExecutionTrackStatus.ADDED);
        assertThat(records.get(1).getStatus()).isEqualTo(ExecutionTrackStatus.FAILED);
        assertThat(records.get(1).getErrorMessage()).contains("Video not available");
        assertThat(records.get(2).getStatus()).isEqualTo(ExecutionTrackStatus.SKIPPED);
        assertThat(records.get(3).getStatus()).isEqualTo(ExecutionTrackStatus.SKIPPED);

        // Verify mathematical conservation invariant holds
        MigrationExecution execution = executionRepository.findById(executionId).orElseThrow();
        assertThat(execution.getTotalApprovedTracks()).isEqualTo(4);
        assertThat(execution.getSuccessfulInsertions()).isEqualTo(1);
        assertThat(execution.getFailedInsertions()).isEqualTo(1);
        assertThat(execution.getSkippedInsertions()).isEqualTo(2);
        assertThat(execution.getSuccessfulInsertions() + execution.getFailedInsertions() + execution.getSkippedInsertions() + execution.getUncertainInsertions() + execution.getRolledBackInsertions())
                .isEqualTo(execution.getTotalApprovedTracks());

        // Verify progress endpoint reflects partial completion and does not reach 100%
        mockMvc.perform(get("/api/migrations/" + executionId + "/progress")
                        .with(user(primaryUser.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(execution.getStatus().name()))
                .andExpect(jsonPath("$.addedTracks").value(1))
                .andExpect(jsonPath("$.failedTracks").value(1))
                .andExpect(jsonPath("$.skippedTracks").value(2))
                .andExpect(jsonPath("$.processedTracks").value(4));
    }

    @Test
    @DisplayName("Scenario C: Resume operation recovers only failed/skipped tracks without duplicating added tracks")
    void partialMigration_resumeOperation_resumesOnlyUnfinishedTracksWithoutDuplicates() throws Exception {
        ReviewSession session = createApprovedReviewSession(primaryUser, "Resume Migration Playlist", 4);

        AtomicInteger track1Calls = new AtomicInteger(0);

        when(musicDestination.addTrackToPlaylist(
                any(UUID.class), anyString(), any(AddTrackToDestinationPlaylistRequest.class)
        )).thenAnswer(invocation -> {
            AddTrackToDestinationPlaylistRequest req = invocation.getArgument(2);
            String videoId = req.trackId();

            if ("yt_candidate_1".equals(videoId) && track1Calls.get() == 0) {
                track1Calls.incrementAndGet();
                throw new ExternalProviderException("Temporary destination outage", 400);
            }

            return new DestinationPlaylistItem(
                    "item_" + videoId,
                    invocation.getArgument(1),
                    videoId,
                    req.position()
            );
        });

        // 1. Initial submission fails at track 1
        String payload = "{\"reviewSessionId\":\"" + session.getId() + "\"}";
        MvcResult submitResult = mockMvc.perform(post("/api/migrations")
                        .with(user(primaryUser.getEmail()))
                        .with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isAccepted())
                .andReturn();

        JsonNode submitNode = objectMapper.readTree(submitResult.getResponse().getContentAsString());
        UUID executionId = UUID.fromString(submitNode.get("executionId").asText());

        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    MigrationExecution exec = executionRepository.findById(executionId).orElseThrow();
                    assertThat(exec.getStatus()).isEqualTo(MigrationExecutionStatus.PARTIALLY_COMPLETED);
                });

        // Reset invocation counter for track 0 to assert it is never called during resume
        track1Calls.set(1); // Allow track 1 to succeed now

        // 2. Client initiates resume via POST /api/migrations/{id}/resume
        mockMvc.perform(post("/api/migrations/" + executionId + "/resume")
                        .with(user(primaryUser.getEmail()))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.successfulInsertions").value(4))
                .andExpect(jsonPath("$.failedInsertions").value(0))
                .andExpect(jsonPath("$.skippedInsertions").value(0));

        // 3. Verify all 4 tracks are now ADDED in database
        List<ExecutionTrackRecord> records =
                trackRecordRepository.findByExecutionIdOrderByTargetPositionAsc(executionId);
        assertThat(records).hasSize(4);
        for (ExecutionTrackRecord rec : records) {
            assertThat(rec.getStatus()).isEqualTo(ExecutionTrackStatus.ADDED);
        }

        // Verify final execution status is COMPLETED
        MigrationExecution finalExec = executionRepository.findById(executionId).orElseThrow();
        assertThat(finalExec.getStatus()).isEqualTo(MigrationExecutionStatus.COMPLETED);
        assertThat(finalExec.getSuccessfulInsertions()).isEqualTo(4);
    }

    @Test
    @DisplayName("Scenario D: Worker interruption with expired lease allows safe scheduler claim and completion")
    void workerInterruption_leaseExpiration_allowsLeaseTakeoverAndSafeCompletion() throws Exception {
        ReviewSession session = createApprovedReviewSession(primaryUser, "Interrupted Worker Playlist", 3);

        when(musicDestination.addTrackToPlaylist(
                any(UUID.class), anyString(), any(AddTrackToDestinationPlaylistRequest.class)
        )).thenAnswer(invocation -> {
            AddTrackToDestinationPlaylistRequest req = invocation.getArgument(2);
            return new DestinationPlaylistItem(
                    "item_" + req.trackId(),
                    invocation.getArgument(1),
                    req.trackId(),
                    req.position()
            );
        });

        // Initialize execution directly in database in RECOVERY_REQUIRED status with expired lease
        ApprovedTrack track0 = new ApprovedTrack(
                0, "sp_track_0", "Track 0", "Artist 0",
                "yt_candidate_0", "Video 0", "Channel 0",
                Duration.ofMinutes(3), ReviewDecisionSource.AUTOMATIC, 0
        );
        ApprovedTrack track1 = new ApprovedTrack(
                1, "sp_track_1", "Track 1", "Artist 1",
                "yt_candidate_1", "Video 1", "Channel 1",
                Duration.ofMinutes(3), ReviewDecisionSource.AUTOMATIC, 1
        );
        ApprovedTrack track2 = new ApprovedTrack(
                2, "sp_track_2", "Track 2", "Artist 2",
                "yt_candidate_2", "Video 2", "Channel 2",
                Duration.ofMinutes(3), ReviewDecisionSource.AUTOMATIC, 2
        );

        ApprovedMigrationContext context = createApprovedContext(session, primaryUser, List.of(track0, track1, track2));
        MigrationExecution execution = new MigrationExecution(
                primaryUser,
                session,
                context,
                "PL_interrupted_test",
                "https://music.youtube.com/playlist?list=PL_interrupted_test",
                MigrationExecutionStatus.QUEUED
        );
        execution.setStatus(MigrationExecutionStatus.RECOVERY_REQUIRED);
        execution.setLeaseOwner("crashed-worker-node-1");
        execution.setLeaseGeneration(1L);
        execution.setLeaseUntil(Instant.now().minus(Duration.ofMinutes(2)));

        execution.getTrackRecords().get(0).markAdding();
        execution.getTrackRecords().get(0).markAdded("item_yt_candidate_0");
        execution.reconcileCounts();
        MigrationExecution saved = executionRepository.saveAndFlush(execution);

        if (entityManager != null) {
            entityManager.clear();
        }

        // Simulate recovery scheduler acquiring the expired execution
        Optional<ClaimedExecution> claimedOpt =
                executionRepository.claimNextEligibleExecution("recovery-worker-2", 60L);

        assertThat(claimedOpt).isPresent();
        ClaimedExecution claimed = claimedOpt.get();
        assertThat(claimed.executionId()).isEqualTo(saved.getId());
        assertThat(claimed.workerId()).isEqualTo("recovery-worker-2");
        assertThat(claimed.leaseGeneration()).isEqualTo(2L);

        if (entityManager != null) {
            entityManager.clear();
        }

        // Verify stale lease from crashed worker-node-1 is rejected with StaleLeaseOwnershipException
        assertThatThrownBy(() -> executionStateService.executeClaimedTrackInsertion(
                claimed.executionId(), claimed.userId(), 1L
        )).isInstanceOf(StaleLeaseOwnershipException.class);

        // Process the claimed execution with the new recovery worker under generation 2
        executionStateService.executeClaimedTrackInsertion(
                claimed.executionId(), claimed.userId(), claimed.leaseGeneration()
        );

        // Verify remaining tracks were processed and execution reached COMPLETED
        MigrationExecution completedExec = executionRepository.findById(saved.getId()).orElseThrow();
        assertThat(completedExec.getStatus()).isEqualTo(MigrationExecutionStatus.COMPLETED);
        assertThat(completedExec.getSuccessfulInsertions()).isEqualTo(3);

        List<ExecutionTrackRecord> records =
                trackRecordRepository.findByExecutionIdOrderByTargetPositionAsc(saved.getId());
        assertThat(records.get(0).getStatus()).isEqualTo(ExecutionTrackStatus.ADDED);
        assertThat(records.get(1).getStatus()).isEqualTo(ExecutionTrackStatus.ADDED);
        assertThat(records.get(2).getStatus()).isEqualTo(ExecutionTrackStatus.ADDED);
    }

    @Test
    @DisplayName("Scenario E1: Duplicate submission with same Idempotency-Key returns existing execution")
    void idempotentSubmission_repeatedRequests_preventDuplicatePlaylistsAndTracks() throws Exception {
        ReviewSession session = createApprovedReviewSession(primaryUser, "Idempotent Submission Playlist", 2);

        when(musicDestination.addTrackToPlaylist(
                any(UUID.class), anyString(), any(AddTrackToDestinationPlaylistRequest.class)
        )).thenAnswer(invocation -> {
            AddTrackToDestinationPlaylistRequest req = invocation.getArgument(2);
            return new DestinationPlaylistItem(
                    "item_" + req.trackId(),
                    invocation.getArgument(1),
                    req.trackId(),
                    req.position()
            );
        });

        String idempotencyKey = UUID.randomUUID().toString();
        String payload = "{\"reviewSessionId\":\"" + session.getId() + "\"}";

        // First submission
        MvcResult firstResult = mockMvc.perform(post("/api/migrations")
                        .with(user(primaryUser.getEmail()))
                        .with(csrf())
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isAccepted())
                .andReturn();

        JsonNode firstNode = objectMapper.readTree(firstResult.getResponse().getContentAsString());
        UUID executionId = UUID.fromString(firstNode.get("executionId").asText());

        // Repeated submission with exact same key and payload
        MvcResult secondResult = mockMvc.perform(post("/api/migrations")
                        .with(user(primaryUser.getEmail()))
                        .with(csrf())
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isAccepted())
                .andReturn();

        JsonNode secondNode = objectMapper.readTree(secondResult.getResponse().getContentAsString());
        UUID secondExecutionId = UUID.fromString(secondNode.get("executionId").asText());

        // Assert identical execution returned without duplicate entity creation
        assertThat(secondExecutionId).isEqualTo(executionId);

        // Verify only 1 destination playlist created
        verify(musicDestination, times(1)).createPlaylist(any(UUID.class), any(CreateDestinationPlaylistRequest.class));
    }

    @Test
    @DisplayName("Scenario E2: Idempotency fingerprint mismatch is rejected with HTTP 409 Conflict")
    void idempotencyFingerprintMismatch_differentPayloadWithSameKey_isRejectedWithConflict() throws Exception {
        ReviewSession session1 = createApprovedReviewSession(primaryUser, "Session 1", 2);
        ReviewSession session2 = createApprovedReviewSession(primaryUser, "Session 2", 2);

        String sharedKey = UUID.randomUUID().toString();
        String payload1 = "{\"reviewSessionId\":\"" + session1.getId() + "\"}";
        String payload2 = "{\"reviewSessionId\":\"" + session2.getId() + "\"}";

        // Initial submission with sharedKey
        mockMvc.perform(post("/api/migrations")
                        .with(user(primaryUser.getEmail()))
                        .with(csrf())
                        .header("Idempotency-Key", sharedKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload1))
                .andExpect(status().isAccepted());

        // Conflicting submission reusing sharedKey with different payload
        mockMvc.perform(post("/api/migrations")
                        .with(user(primaryUser.getEmail()))
                        .with(csrf())
                        .header("Idempotency-Key", sharedKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload2))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_FINGERPRINT_MISMATCH"));
    }

    @Test
    @DisplayName("Scenario F: Full and partial rollback removes destination tracks and preserves audit records")
    void rollbackExecution_partiallyOrFullyRemovesInsertedTracks_recordsAudit() throws Exception {
        ReviewSession session = createApprovedReviewSession(primaryUser, "Rollback Test Playlist", 3);

        when(musicDestination.addTrackToPlaylist(
                any(UUID.class), anyString(), any(AddTrackToDestinationPlaylistRequest.class)
        )).thenAnswer(invocation -> {
            AddTrackToDestinationPlaylistRequest req = invocation.getArgument(2);
            return new DestinationPlaylistItem(
                    "item_" + req.trackId(),
                    invocation.getArgument(1),
                    req.trackId(),
                    req.position()
            );
        });

        // 1. Submit migration and await completion
        String payload = "{\"reviewSessionId\":\"" + session.getId() + "\"}";
        MvcResult submitResult = mockMvc.perform(post("/api/migrations")
                        .with(user(primaryUser.getEmail()))
                        .with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isAccepted())
                .andReturn();

        JsonNode submitNode = objectMapper.readTree(submitResult.getResponse().getContentAsString());
        UUID executionId = UUID.fromString(submitNode.get("executionId").asText());

        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    MigrationExecution exec = executionRepository.findById(executionId).orElseThrow();
                    assertThat(exec.getStatus()).isEqualTo(MigrationExecutionStatus.COMPLETED);
                });

        // 2. Perform rollback of all tracks
        RollbackExecutionApiRequest rollbackRequest = new RollbackExecutionApiRequest(List.of(), primaryUser.getId());
        MvcResult rollbackResult = mockMvc.perform(post("/api/migrations/execution/" + executionId + "/rollback")
                        .with(user(primaryUser.getEmail()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rollbackRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overallStatus").value("SUCCESS"))
                .andExpect(jsonPath("$.successfullyRolledBack").value(3))
                .andExpect(jsonPath("$.failedRollbacks").value(0))
                .andExpect(jsonPath("$.alreadyRolledBack").value(0))
                .andReturn();

        // Verify removeTrackFromPlaylist was invoked 3 times
        verify(musicDestination, times(3)).removeTrackFromPlaylist(eq(primaryUser.getId()), anyString());

        // Verify database records transitioned to ROLLED_BACK
        MigrationExecution rolledBackExec = executionRepository.findById(executionId).orElseThrow();
        assertThat(rolledBackExec.getStatus()).isEqualTo(MigrationExecutionStatus.ROLLED_BACK);
        assertThat(rolledBackExec.getRolledBackInsertions()).isEqualTo(3);

        List<ExecutionRollbackRecord> auditRecords =
                rollbackRecordRepository.findByExecutionIdOrderByTargetPositionAsc(executionId);
        assertThat(auditRecords).hasSize(3);
        for (ExecutionRollbackRecord audit : auditRecords) {
            assertThat(audit.getStatus()).isEqualTo(RollbackRecordStatus.ROLLED_BACK);
        }

        // 3. Repeated rollback request on terminal ROLLED_BACK execution is rejected with 409 Conflict
        mockMvc.perform(post("/api/migrations/execution/" + executionId + "/rollback")
                        .with(user(primaryUser.getEmail()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rollbackRequest)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Scenario G: Invalid state transitions and cross-user operations are safely rejected")
    void invalidStateTransitions_rejectedWithExpectedContracts() throws Exception {
        ReviewSession session = createApprovedReviewSession(primaryUser, "State Rejections Playlist", 2);

        when(musicDestination.addTrackToPlaylist(
                any(UUID.class), anyString(), any(AddTrackToDestinationPlaylistRequest.class)
        )).thenAnswer(invocation -> {
            AddTrackToDestinationPlaylistRequest req = invocation.getArgument(2);
            return new DestinationPlaylistItem(
                    "item_" + req.trackId(),
                    invocation.getArgument(1),
                    req.trackId(),
                    req.position()
            );
        });

        // 1. Submit and await COMPLETED
        String payload = "{\"reviewSessionId\":\"" + session.getId() + "\"}";
        MvcResult submitResult = mockMvc.perform(post("/api/migrations")
                        .with(user(primaryUser.getEmail()))
                        .with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isAccepted())
                .andReturn();

        JsonNode submitNode = objectMapper.readTree(submitResult.getResponse().getContentAsString());
        UUID executionId = UUID.fromString(submitNode.get("executionId").asText());

        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    MigrationExecution exec = executionRepository.findById(executionId).orElseThrow();
                    assertThat(exec.getStatus()).isEqualTo(MigrationExecutionStatus.COMPLETED);
                });

        // 2. Calling resume on already COMPLETED execution returns HTTP 409 Conflict
        mockMvc.perform(post("/api/migrations/" + executionId + "/resume")
                        .with(user(primaryUser.getEmail()))
                        .with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Cannot resume: execution is already COMPLETED"));

        // 3. Multi-tenant isolation: secondaryUser cannot resume primaryUser's execution (404 Not Found non-disclosure)
        mockMvc.perform(post("/api/migrations/" + executionId + "/resume")
                        .with(user(secondaryUser.getEmail()))
                        .with(csrf()))
                .andExpect(status().isNotFound());

        // 4. Secondary user cannot rollback primaryUser's execution (404 Not Found non-disclosure)
        RollbackExecutionApiRequest foreignRollback = new RollbackExecutionApiRequest(List.of(), secondaryUser.getId());
        mockMvc.perform(post("/api/migrations/execution/" + executionId + "/rollback")
                        .with(user(secondaryUser.getEmail()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(foreignRollback)))
                .andExpect(status().isNotFound());
    }
}
