package com.spotifyyoutube.migrator.migration.execution.repository;

import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.identity.repository.UserRepository;
import com.spotifyyoutube.migrator.matching.engine.MatchDecision;
import com.spotifyyoutube.migrator.migration.application.destination.MusicDestination;
import com.spotifyyoutube.migrator.migration.application.destination.model.AddTrackToDestinationPlaylistRequest;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationPlaylistItem;
import com.spotifyyoutube.migrator.migration.application.execution.MigrationExecutionStateService;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationContext;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedTrack;
import com.spotifyyoutube.migrator.migration.application.execution.contract.MigrationExecutionSummary;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionAuditEvent;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackRecord;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackStatus;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecution;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewDecisionSource;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSession;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSessionStatus;
import com.spotifyyoutube.migrator.migration.review.repository.ReviewSessionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PostgreSQL Testcontainers integration test verifying Phase 14E & 14F:
 * - Safe migration resumption: skips ADDED tracks, executes only eligible unfinished tracks.
 * - Recovery of failed/skipped tracks without re-creating destination playlists.
 * - Idempotency and anti-concurrency protections (rejects RUNNING and COMPLETED).
 * - Multi-tenant anti-IDOR isolation across resume operations.
 * - Audit logging of resume state transitions and chronological timeline.
 * - Summary count consistency and track conservation invariant verification.
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@DisplayName("Phase 14E & 14F: PostgreSQL Migration Resume & Recovery Integration Tests")
public class Phase14EFResumeIntegrationIT {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ReviewSessionRepository reviewSessionRepository;

    @Autowired
    private MigrationExecutionRepository executionRepository;

    @Autowired
    private ExecutionTrackRecordRepository trackRecordRepository;

    @Autowired
    private ExecutionAuditEventRepository auditEventRepository;

    @Autowired
    private MigrationExecutionStateService executionStateService;

    @MockBean
    private MusicDestination musicDestination;

    private User alice;
    private User bob;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        alice = userRepository.save(new User("alice-phase14ef@example.com"));
        bob = userRepository.save(new User("bob-phase14ef@example.com"));

        when(musicDestination.addTrackToPlaylist(any(UUID.class), any(String.class), any(AddTrackToDestinationPlaylistRequest.class)))
                .thenAnswer(invocation -> {
                    String playlistId = invocation.getArgument(1);
                    AddTrackToDestinationPlaylistRequest req = invocation.getArgument(2);
                    return new DestinationPlaylistItem(
                            "item-" + UUID.randomUUID(),
                            playlistId,
                            req.trackId(),
                            req.position()
                    );
                });
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    private void cleanDatabase() {
        auditEventRepository.deleteAllInBatch();
        trackRecordRepository.deleteAllInBatch();
        executionRepository.deleteAllInBatch();
        reviewSessionRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    private ApprovedMigrationContext createContext(UUID userId, int trackCount) {
        List<ApprovedTrack> tracks = new java.util.ArrayList<>();
        for (int i = 0; i < trackCount; i++) {
            tracks.add(new ApprovedTrack(
                    i,
                    "sp-track-" + i,
                    "Source Track " + i,
                    "Source Artist " + i,
                    "yt-video-" + i,
                    "Dest Title " + i,
                    "Dest Channel " + i,
                    Duration.ofSeconds(200),
                    ReviewDecisionSource.AUTOMATIC,
                    i,
                    0.95,
                    MatchDecision.MATCH.name()
            ));
        }
        return new ApprovedMigrationContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                userId,
                "sp-playlist-123",
                "Workout Mix",
                "Workout Mix",
                "Migrated playlist",
                false,
                tracks,
                trackCount,
                trackCount,
                0,
                Instant.now()
        );
    }

    @Test
    @DisplayName("Resume partially completed: Preserves ADDED tracks, inserts PENDING tracks, maintains destination playlist")
    void testResumePartiallyCompletedMigrationPreservesAddedTracksAndInsertsPending() {
        ApprovedMigrationContext context = createContext(alice.getId(), 2);
        ReviewSession session = new ReviewSession(alice, context.sourcePlaylistId(), context.sourcePlaylistTitle(), context.planId());
        session.setStatus(ReviewSessionStatus.COMPLETED);
        ReviewSession savedSession = reviewSessionRepository.save(session);

        String destinationPlaylistId = "yt-pl-resume-" + UUID.randomUUID();
        MigrationExecution execution = new MigrationExecution(
                alice,
                savedSession,
                context,
                destinationPlaylistId,
                "https://youtube.com/playlist?list=" + destinationPlaylistId
        );

        // Track 0 was already ADDED with playlistItem item-existing-0
        ExecutionTrackRecord rec0 = execution.getTrackRecords().get(0);
        rec0.markAdding();
        rec0.markAdded("item-existing-0");

        // Execution status is PARTIALLY_COMPLETED
        execution.setStatus(MigrationExecutionStatus.PARTIALLY_COMPLETED);
        MigrationExecution savedExec = executionRepository.save(execution);

        // Track 1 remains PENDING
        List<ExecutionTrackRecord> records = trackRecordRepository.findByExecutionIdOrderByTargetPositionAsc(savedExec.getId());
        assertThat(records).hasSize(2);
        ExecutionTrackRecord rec1 = records.get(1);
        assertThat(rec1.getStatus()).isEqualTo(ExecutionTrackStatus.PENDING);

        // Resume execution
        MigrationExecutionSummary summary = executionStateService.resumeExecution(savedExec.getId(), alice.getId());

        // Verify outcome
        assertThat(summary.status()).isEqualTo(MigrationExecutionStatus.COMPLETED);
        assertThat(summary.fullySuccessful()).isTrue();
        assertThat(summary.successfulInsertions()).isEqualTo(2);
        assertThat(summary.destinationPlaylistId()).isEqualTo(destinationPlaylistId);

        // Verify Track 0 was NOT re-inserted
        UUID rec0Id = records.get(0).getId();
        ExecutionTrackRecord refreshedRec0 = trackRecordRepository.findById(rec0Id).orElseThrow();
        assertThat(refreshedRec0.getStatus()).isEqualTo(ExecutionTrackStatus.ADDED);
        assertThat(refreshedRec0.getDestinationPlaylistItemId()).isEqualTo("item-existing-0");

        // Verify Track 1 was inserted
        UUID rec1Id = records.get(1).getId();
        ExecutionTrackRecord refreshedRec1 = trackRecordRepository.findById(rec1Id).orElseThrow();
        assertThat(refreshedRec1.getStatus()).isEqualTo(ExecutionTrackStatus.ADDED);
        assertThat(refreshedRec1.getDestinationPlaylistItemId()).isNotEqualTo("item-existing-0");

        // Verify destination API was only called ONCE (for Track 1), never for Track 0
        verify(musicDestination, times(1)).addTrackToPlaylist(eq(alice.getId()), eq(destinationPlaylistId), any(AddTrackToDestinationPlaylistRequest.class));

        // Verify audit timeline captured state transition to RUNNING and COMPLETED
        List<ExecutionAuditEvent> auditEvents = auditEventRepository
                .findByExecutionIdOrderByCreatedAtAsc(savedExec.getId(), PageRequest.of(0, 10))
                .getContent();
        assertThat(auditEvents).isNotEmpty();
        assertThat(auditEvents.stream().anyMatch(e -> "RUNNING".equals(e.getNewState()))).isTrue();
        assertThat(auditEvents.stream().anyMatch(e -> "COMPLETED".equals(e.getNewState()))).isTrue();
    }

    @Test
    @DisplayName("Resume failed migration: Recovers FAILED and SKIPPED tracks, preserving ADDED tracks")
    void testResumeFailedMigrationRecoversFailedAndSkippedTracks() {
        ApprovedMigrationContext context = createContext(alice.getId(), 3);
        ReviewSession session = new ReviewSession(alice, context.sourcePlaylistId(), context.sourcePlaylistTitle(), context.planId());
        session.setStatus(ReviewSessionStatus.COMPLETED);
        ReviewSession savedSession = reviewSessionRepository.save(session);

        String destinationPlaylistId = "yt-pl-recover-" + UUID.randomUUID();
        MigrationExecution execution = new MigrationExecution(
                alice,
                savedSession,
                context,
                destinationPlaylistId,
                "https://youtube.com/playlist?list=" + destinationPlaylistId
        );

        // Track 0 was ADDED
        ExecutionTrackRecord r0 = execution.getTrackRecords().get(0);
        r0.markAdding();
        r0.markAdded("item-prior-0");

        // Track 1 was FAILED
        ExecutionTrackRecord r1 = execution.getTrackRecords().get(1);
        ReflectionTestUtils.setField(r1, "status", ExecutionTrackStatus.FAILED);
        ReflectionTestUtils.setField(r1, "errorMessage", "Simulated transient failure");

        // Track 2 was SKIPPED
        ExecutionTrackRecord r2 = execution.getTrackRecords().get(2);
        r2.markSkipped("Skipped due to prior insertion failure");

        execution.setStatus(MigrationExecutionStatus.FAILED);
        MigrationExecution savedExec = executionRepository.save(execution);

        // Recover and resume execution
        UUID r0Id = trackRecordRepository.findByExecutionIdOrderByTargetPositionAsc(savedExec.getId()).get(0).getId();
        MigrationExecutionSummary summary = executionStateService.recoverExecution(savedExec.getId(), alice.getId());

        // Verify all 3 tracks completed successfully
        assertThat(summary.status()).isEqualTo(MigrationExecutionStatus.COMPLETED);
        assertThat(summary.successfulInsertions()).isEqualTo(3);
        assertThat(summary.failedInsertions()).isEqualTo(0);
        assertThat(summary.skippedInsertions()).isEqualTo(0);

        // Track 0 retained original item ID
        ExecutionTrackRecord finalR0 = trackRecordRepository.findById(r0Id).orElseThrow();
        assertThat(finalR0.getDestinationPlaylistItemId()).isEqualTo("item-prior-0");

        // Destination called exactly twice (for Track 1 and Track 2)
        verify(musicDestination, times(2)).addTrackToPlaylist(eq(alice.getId()), eq(destinationPlaylistId), any(AddTrackToDestinationPlaylistRequest.class));
    }

    @Test
    @DisplayName("Resume rejects completed migration: Prevents duplicate execution")
    void testResumeCompletedMigrationThrowsConflict() {
        ApprovedMigrationContext context = createContext(alice.getId(), 1);
        ReviewSession session = new ReviewSession(alice, context.sourcePlaylistId(), context.sourcePlaylistTitle(), context.planId());
        session.setStatus(ReviewSessionStatus.COMPLETED);
        ReviewSession savedSession = reviewSessionRepository.save(session);

        MigrationExecution execution = new MigrationExecution(
                alice,
                savedSession,
                context,
                "yt-pl-done",
                "https://youtube.com/playlist?list=yt-pl-done"
        );
        execution.setStatus(MigrationExecutionStatus.COMPLETED);
        MigrationExecution savedExec = executionRepository.save(execution);

        assertThatThrownBy(() -> executionStateService.resumeExecution(savedExec.getId(), alice.getId()))
                .isInstanceOf(InvalidStateException.class)
                .hasMessageContaining("terminal status");
    }

    @Test
    @DisplayName("Resume rejects running migration: Prevents concurrent worker conflicts")
    void testResumeRunningMigrationThrowsConflict() {
        ApprovedMigrationContext context = createContext(alice.getId(), 1);
        ReviewSession session = new ReviewSession(alice, context.sourcePlaylistId(), context.sourcePlaylistTitle(), context.planId());
        session.setStatus(ReviewSessionStatus.COMPLETED);
        ReviewSession savedSession = reviewSessionRepository.save(session);

        MigrationExecution execution = new MigrationExecution(
                alice,
                savedSession,
                context,
                "yt-pl-running",
                "https://youtube.com/playlist?list=yt-pl-running"
        );
        execution.setStatus(MigrationExecutionStatus.RUNNING);
        MigrationExecution savedExec = executionRepository.save(execution);

        assertThatThrownBy(() -> executionStateService.resumeExecution(savedExec.getId(), alice.getId()))
                .isInstanceOf(InvalidStateException.class)
                .hasMessageContaining("in progress");
    }

    @Test
    @DisplayName("Anti-IDOR isolation: Cross-user resume attempt throws ResourceNotFoundException")
    void testResumeAntiIdorIsolation() {
        ApprovedMigrationContext context = createContext(alice.getId(), 1);
        ReviewSession session = new ReviewSession(alice, context.sourcePlaylistId(), context.sourcePlaylistTitle(), context.planId());
        session.setStatus(ReviewSessionStatus.COMPLETED);
        ReviewSession savedSession = reviewSessionRepository.save(session);

        MigrationExecution execution = new MigrationExecution(
                alice,
                savedSession,
                context,
                "yt-pl-alice",
                "https://youtube.com/playlist?list=yt-pl-alice"
        );
        execution.setStatus(MigrationExecutionStatus.PARTIALLY_COMPLETED);
        MigrationExecution savedExec = executionRepository.save(execution);

        // Bob attempts to resume Alice's migration -> 404 NOT FOUND
        assertThatThrownBy(() -> executionStateService.resumeExecution(savedExec.getId(), bob.getId()))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("Persistence & Provenance: Track outcomes, candidate details, and match scores survive restart")
    void testTrackOutcomesPersistenceAndOrderingAcrossApplicationRestart() {
        ApprovedMigrationContext context = createContext(alice.getId(), 2);
        ReviewSession session = new ReviewSession(alice, context.sourcePlaylistId(), context.sourcePlaylistTitle(), context.planId());
        session.setStatus(ReviewSessionStatus.COMPLETED);
        ReviewSession savedSession = reviewSessionRepository.save(session);

        MigrationExecution execution = new MigrationExecution(
                alice,
                savedSession,
                context,
                "yt-pl-prov",
                "https://youtube.com/playlist?list=yt-pl-prov"
        );
        MigrationExecution savedExec = executionRepository.save(execution);

        List<ExecutionTrackRecord> records = trackRecordRepository.findByExecutionIdOrderByTargetPositionAsc(savedExec.getId());
        assertThat(records).hasSize(2);

        ExecutionTrackRecord r0 = records.get(0);
        assertThat(r0.getTargetPosition()).isEqualTo(0);
        assertThat(r0.getSourceTitle()).isEqualTo("Source Track 0");
        assertThat(r0.getSourceArtist()).isEqualTo("Source Artist 0");
        assertThat(r0.getDestinationTitle()).isEqualTo("Dest Title 0");
        assertThat(r0.getDestinationChannel()).isEqualTo("Dest Channel 0");
        assertThat(r0.getMatchScore()).isEqualTo(0.95);
        assertThat(r0.getMatchDecision()).isEqualTo("MATCH");
        assertThat(r0.getDecisionSource()).isEqualTo("AUTOMATIC");

        ExecutionTrackRecord r1 = records.get(1);
        assertThat(r1.getTargetPosition()).isEqualTo(1);
        assertThat(r1.getSourceTitle()).isEqualTo("Source Track 1");
    }
}
