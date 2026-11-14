package com.spotifyyoutube.migrator.migration.reliability.reconciliation;

import com.spotifyyoutube.migrator.common.config.JpaConfig;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.identity.repository.UserRepository;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationPlaylist;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationContext;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedTrack;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecution;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus;
import com.spotifyyoutube.migrator.migration.execution.repository.ExecutionTrackRecordRepository;
import com.spotifyyoutube.migrator.migration.execution.repository.MigrationExecutionRepository;
import com.spotifyyoutube.migrator.migration.reliability.idempotency.ClaimResult;
import com.spotifyyoutube.migrator.migration.reliability.idempotency.DefaultMigrationIdempotencyService;
import com.spotifyyoutube.migrator.migration.reliability.idempotency.IdempotencyClaimHeartbeatService;
import com.spotifyyoutube.migrator.migration.reliability.idempotency.IdempotencyClaimStatus;
import com.spotifyyoutube.migrator.migration.reliability.idempotency.MigrationIdempotencyRecord;
import com.spotifyyoutube.migrator.migration.reliability.idempotency.MigrationIdempotencyRepository;
import com.spotifyyoutube.migrator.migration.reliability.idempotency.MigrationIdempotencyService;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewDecisionSource;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSession;
import com.spotifyyoutube.migrator.migration.review.repository.ReviewSessionRepository;
import com.spotifyyoutube.migrator.playlist.domain.Platform;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * PostgreSQL integration tests verifying Phase 13F external operation safety:
 * pre-allocated execution intent, nullable destination playlist ID in ALLOCATING_DESTINATION status,
 * partial unique constraints on playlist_creation_operation_id, and State B claim recovery with reconciliation.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({JpaConfig.class, DefaultMigrationIdempotencyService.class, IdempotencyClaimHeartbeatService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Phase 13F: External Operation Safety PostgreSQL Integration Tests")
public class ExternalOperationSafetyPostgresIT {

    @Autowired
    private MigrationExecutionRepository executionRepository;

    @Autowired
    private ExecutionTrackRecordRepository trackRecordRepository;

    @Autowired
    private ReviewSessionRepository reviewSessionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MigrationIdempotencyRepository idempotencyRepository;

    @Autowired
    private MigrationIdempotencyService idempotencyService;

    @MockBean
    private DestinationPlaylistReconciliationService reconciliationService;

    @PersistenceContext
    private EntityManager entityManager;

    private User testUser;
    private ReviewSession testSession;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        testUser = userRepository.save(new User("phase13f-user@example.com"));
        testSession = reviewSessionRepository.save(new ReviewSession(
                testUser,
                "sp_pl_" + UUID.randomUUID(),
                "Spotify Playlist Title",
                UUID.randomUUID()
        ));
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
        trackRecordRepository.deleteAllInBatch();
        executionRepository.deleteAllInBatch();
        reviewSessionRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    private ApprovedMigrationContext createContext(ReviewSession session, User user) {
        ApprovedTrack track = new ApprovedTrack(
                0, "sp_t_1", "Song", "Artist",
                "yt_v_1", "Video", "Channel",
                Duration.ofMinutes(3), ReviewDecisionSource.AUTOMATIC, 0
        );

        return new ApprovedMigrationContext(
                session.getId(), UUID.randomUUID(), user.getId(),
                "sp_src_1", "Source Title", "Target Title",
                "Description", false, List.of(track),
                1, 1, 0, Instant.now()
        );
    }

    @Test
    @DisplayName("Pre-allocated execution intent persists with null destination playlist ID and opId")
    void preAllocatedExecutionIntentPersists() {
        UUID opId = UUID.randomUUID();
        ApprovedMigrationContext context = createContext(testSession, testUser);

        MigrationExecution execution = new MigrationExecution(testUser, testSession, context, opId);
        MigrationExecution saved = executionRepository.saveAndFlush(execution);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getStatus()).isEqualTo(MigrationExecutionStatus.ALLOCATING_DESTINATION);
        assertThat(saved.getDestinationPlaylistId()).isNull();
        assertThat(saved.getPlaylistCreationOperationId()).isEqualTo(opId);
        assertThat(saved.getPlaylistCreationAttemptCount()).isEqualTo(1);
        assertThat(saved.getPlaylistCreationMaxAttempts()).isEqualTo(3);

        // Find by operation ID
        Optional<MigrationExecution> byOp = executionRepository.findByPlaylistCreationOperationId(opId);
        assertThat(byOp).isPresent();
        assertThat(byOp.get().getId()).isEqualTo(saved.getId());
    }

    @Test
    @DisplayName("Execution transitions cleanly from ALLOCATING_DESTINATION to INITIALIZED on playlist confirmation")
    void executionTransitionsOnConfirmation() {
        UUID opId = UUID.randomUUID();
        ApprovedMigrationContext context = createContext(testSession, testUser);

        MigrationExecution execution = new MigrationExecution(testUser, testSession, context, opId);
        MigrationExecution saved = executionRepository.saveAndFlush(execution);

        String playlistId = "PL_confirmed_123";
        String playlistUrl = "https://music.youtube.com/playlist?list=PL_confirmed_123";

        saved.markPlaylistCreated(playlistId, playlistUrl, MigrationExecutionStatus.INITIALIZED);
        MigrationExecution confirmed = executionRepository.saveAndFlush(saved);

        assertThat(confirmed.getStatus()).isEqualTo(MigrationExecutionStatus.INITIALIZED);
        assertThat(confirmed.getDestinationPlaylistId()).isEqualTo(playlistId);
        assertThat(confirmed.getDestinationPlaylistUrl()).isEqualTo(playlistUrl);
        assertThat(confirmed.getPlaylistCreationFailureCode()).isNull();
    }

    @Test
    @DisplayName("Partial unique index prevents two active executions with identical operation ID")
    void duplicateActiveOperationIdEnforcesUniqueness() {
        UUID opId = UUID.randomUUID();
        ApprovedMigrationContext context = createContext(testSession, testUser);

        MigrationExecution exec1 = new MigrationExecution(testUser, testSession, context, opId);
        executionRepository.saveAndFlush(exec1);

        ReviewSession session2 = reviewSessionRepository.save(new ReviewSession(
                testUser, "sp_pl_2", "Title 2", UUID.randomUUID()
        ));
        ApprovedMigrationContext context2 = createContext(session2, testUser);
        MigrationExecution exec2 = new MigrationExecution(testUser, session2, context2, opId);

        assertThatThrownBy(() -> executionRepository.saveAndFlush(exec2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("markExternalOperationStartedForSession records operationId and stage on pending claim")
    void markExternalOperationStartedRecordsOpId() {
        String key = "test-key-op-" + UUID.randomUUID();
        String fingerprint = "fp123";

        ClaimResult claim = idempotencyService.acquireClaim(testUser.getId(), key, fingerprint, testSession.getId());
        assertThat(claim.isAcquired()).isTrue();

        UUID opId = UUID.randomUUID();
        int rows = idempotencyService.markExternalOperationStartedForSession(testSession.getId(), opId);
        assertThat(rows).isEqualTo(1);

        Optional<MigrationIdempotencyRecord> recordOpt = idempotencyRepository.findByUserIdAndIdempotencyKey(testUser.getId(), key);
        assertThat(recordOpt).isPresent();
        MigrationIdempotencyRecord record = recordOpt.get();
        assertThat(record.getClaimStage()).isEqualTo(MigrationIdempotencyRecord.STAGE_EXTERNAL_OPERATION_STARTED);
        assertThat(record.getPlaylistCreationOperationId()).isEqualTo(opId);
    }

    @Test
    @DisplayName("State B recovery: discovers remote playlist matching opId and safely takes over claim")
    void stateBRecoveryWithReconciliationSuccess() {
        String key = "test-key-recovery-" + UUID.randomUUID();
        String fingerprint = "fp-recovery";
        UUID opId = UUID.randomUUID();

        // 1. Manually insert an expired claim in State B with opId
        MigrationIdempotencyRecord expiredRecord = MigrationIdempotencyRecord.createPending(
                testUser.getId(), key, fingerprint, testSession.getId(),
                "old-owner", 1L, Instant.now().minusSeconds(10)
        );
        expiredRecord.markExternalOperationStarted(opId);
        idempotencyRepository.saveAndFlush(expiredRecord);

        // 2. Mock reconciliation to return exact match
        DestinationPlaylist matched = new DestinationPlaylist(
                "PL_reconciled_999", "Migrated Playlist",
                "Desc [ref: migrator-op:" + opId + "]",
                "https://music.youtube.com/playlist?list=PL_reconciled_999",
                Platform.YOUTUBE
        );
        when(reconciliationService.reconcilePlaylistCreation(eq(testUser.getId()), eq(opId), any()))
                .thenReturn(PlaylistReconciliationResult.exactMatch(matched, "Found matching marker"));

        // 3. New caller attempts to acquire expired claim -> triggers State B recovery
        ClaimResult result = idempotencyService.acquireClaim(testUser.getId(), key, fingerprint, testSession.getId());

        assertThat(result.isAcquired()).isTrue();
        assertThat(result.claimGeneration()).isEqualTo(2L); // monotonic generation increment

        // Verify destinationPlaylistId is recorded on the recovered claim
        Optional<MigrationIdempotencyRecord> updated = idempotencyRepository.findByUserIdAndIdempotencyKey(testUser.getId(), key);
        assertThat(updated).isPresent();
        assertThat(updated.get().getDestinationPlaylistId()).isEqualTo("PL_reconciled_999");
        assertThat(updated.get().getClaimStage()).isEqualTo(MigrationIdempotencyRecord.STAGE_PLAYLIST_CREATED);
    }

    @Test
    @DisplayName("State B recovery: fails closed into reconciliation required when remote playlist outcome is ambiguous")
    void stateBRecoveryFailsClosedOnAmbiguity() {
        String key = "test-key-ambiguous-" + UUID.randomUUID();
        String fingerprint = "fp-ambiguous";
        UUID opId = UUID.randomUUID();

        MigrationIdempotencyRecord expiredRecord = MigrationIdempotencyRecord.createPending(
                testUser.getId(), key, fingerprint, testSession.getId(),
                "old-owner", 1L, Instant.now().minusSeconds(10)
        );
        expiredRecord.markExternalOperationStarted(opId);
        idempotencyRepository.saveAndFlush(expiredRecord);

        // Mock reconciliation returning provider error
        when(reconciliationService.reconcilePlaylistCreation(eq(testUser.getId()), eq(opId), any()))
                .thenReturn(PlaylistReconciliationResult.providerError("YouTube API 500 error"));

        ClaimResult result = idempotencyService.acquireClaim(testUser.getId(), key, fingerprint, testSession.getId());

        assertThat(result.isReconciliationRequired()).isTrue();
        assertThat(result.errorMessage()).contains("YouTube API 500 error");
    }
}
