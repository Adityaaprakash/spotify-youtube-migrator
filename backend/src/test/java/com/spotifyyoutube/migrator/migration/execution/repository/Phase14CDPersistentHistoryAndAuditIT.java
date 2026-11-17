package com.spotifyyoutube.migrator.migration.execution.repository;

import com.spotifyyoutube.migrator.common.config.JpaConfig;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.identity.repository.UserRepository;
import com.spotifyyoutube.migrator.matching.engine.MatchDecision;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationContext;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedTrack;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionAuditEvent;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionAuditEventType;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionRollbackRecord;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackRecord;
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
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PostgreSQL Testcontainers integration test verifying Phase 14C & 14D:
 * - Application of Flyway V16 schema migration (execution_audit_events table, composite index on user_id, status, created_at DESC).
 * - Durable persistence of ExecutionAuditEvent lifecycle records (state transitions, retries, worker leases, rollbacks).
 * - Chronological audit timeline retrieval ordered by createdAt ASC.
 * - Paginated user-scoped history query with status filtering ordered by createdAt DESC.
 * - Integrity of foreign keys, cascade deletes, and relational constraints.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(JpaConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Phase 14C & 14D: PostgreSQL Persistent History and Audit Integration Tests")
public class Phase14CDPersistentHistoryAndAuditIT {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ReviewSessionRepository reviewSessionRepository;

    @Autowired
    private MigrationExecutionRepository executionRepository;

    @Autowired
    private ExecutionAuditEventRepository auditEventRepository;

    @Autowired
    private ExecutionRollbackRecordRepository rollbackRecordRepository;

    @Autowired
    private ExecutionTrackRecordRepository trackRecordRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User alice;
    private User bob;

    @BeforeEach
    void cleanAndSeed() {
        cleanDatabase();
        alice = userRepository.save(new User("alice-phase14cd@example.com"));
        bob = userRepository.save(new User("bob-phase14cd@example.com"));
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    private void cleanDatabase() {
        auditEventRepository.deleteAllInBatch();
        rollbackRecordRepository.deleteAllInBatch();
        trackRecordRepository.deleteAllInBatch();
        executionRepository.deleteAllInBatch();
        reviewSessionRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    private MigrationExecution createExecution(User user, String playlistTitle, MigrationExecutionStatus status, Instant createdAt) {
        ReviewSession session = new ReviewSession(
                user,
                "sp_pl_" + UUID.randomUUID(),
                playlistTitle,
                UUID.randomUUID()
        );
        session.setStatus(ReviewSessionStatus.COMPLETED);
        ReviewSession savedSession = reviewSessionRepository.save(session);

        ApprovedTrack track = new ApprovedTrack(
                0, "sp-1", "Song A", "Artist B", "yt-1", "Song A YT", "Artist B Channel",
                Duration.ofSeconds(180), ReviewDecisionSource.AUTOMATIC, 0, 0.95, MatchDecision.MATCH.name()
        );
        ApprovedMigrationContext context = new ApprovedMigrationContext(
                savedSession.getId(),
                UUID.randomUUID(),
                user.getId(),
                savedSession.getSourcePlaylistId(),
                playlistTitle,
                playlistTitle + " YT",
                "Description",
                true,
                List.of(track),
                10,
                1,
                9,
                Instant.now()
        );

        MigrationExecution execution = new MigrationExecution(
                user,
                savedSession,
                context,
                "yt-pl-" + UUID.randomUUID(),
                "https://youtube.com/playlist?list=yt-pl-" + UUID.randomUUID()
        );
        execution.setStatus(status);
        execution.setTotalSourceTracks(1);
        execution.setMatchedTracks(1);
        if (status == MigrationExecutionStatus.COMPLETED) {
            ReflectionTestUtils.setField(execution, "successfulInsertions", 1);
        } else if (status == MigrationExecutionStatus.FAILED) {
            ReflectionTestUtils.setField(execution, "failedInsertions", 1);
            execution.setFailureSummary("Quota exceeded during insertion");
        }

        MigrationExecution saved = executionRepository.save(execution);
        if (createdAt != null) {
            jdbcTemplate.update("UPDATE migration_executions SET created_at = ? WHERE id = ?", Timestamp.from(createdAt), saved.getId());
            saved = executionRepository.findById(saved.getId()).orElseThrow();
        }
        return saved;
    }

    @Test
    @DisplayName("Flyway V16 schema: Verifies execution_audit_events table and index definitions exist in PostgreSQL")
    void testFlywayV16SchemaObjects() {
        // 1. Verify execution_audit_events table exists
        List<Map<String, Object>> tableInfo = jdbcTemplate.queryForList(
                "SELECT column_name, data_type, is_nullable FROM information_schema.columns "
                        + "WHERE table_name = 'execution_audit_events' ORDER BY ordinal_position"
        );
        assertThat(tableInfo).isNotEmpty();

        List<String> columnNames = tableInfo.stream()
                .map(m -> ((String) m.get("column_name")).toLowerCase())
                .toList();

        assertThat(columnNames).contains(
                "id", "execution_id", "event_type", "previous_state",
                "new_state", "reason", "actor", "correlation_id",
                "details", "rollback_record_id", "reconciliation_record_id", "created_at"
        );

        // 2. Verify composite index on migration_executions(user_id, status, created_at DESC)
        List<Map<String, Object>> indexes = jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'migration_executions' "
                        + "AND indexname = 'idx_migrationexecutions_user_status_created'"
        );
        assertThat(indexes).hasSize(1);

        // 3. Verify audit events indexes exist
        List<Map<String, Object>> auditIndexes = jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'execution_audit_events'"
        );
        List<String> auditIndexNames = auditIndexes.stream()
                .map(m -> ((String) m.get("indexname")).toLowerCase())
                .toList();

        assertThat(auditIndexNames).contains("idx_audit_events_execution_created", "idx_audit_events_type");
    }

    @Test
    @DisplayName("Durable persistence: Records and retrieves chronological audit events for migration lifecycle")
    void testAuditEventsPersistenceAndOrdering() {
        MigrationExecution execution = createExecution(alice, "Roadtrip Hits", MigrationExecutionStatus.INITIALIZED, Instant.now());

        // Step 1: Initialized event
        ExecutionAuditEvent event1 = ExecutionAuditEvent.stateTransition(
                execution, null, MigrationExecutionStatus.INITIALIZED, "Execution initialized", "USER", "trace-1");
        auditEventRepository.save(event1);

        // Step 2: Queued event
        ExecutionAuditEvent event2 = ExecutionAuditEvent.stateTransition(
                execution, MigrationExecutionStatus.INITIALIZED, MigrationExecutionStatus.QUEUED, "Queued", "USER", "trace-2");
        auditEventRepository.save(event2);

        // Step 3: Lease acquired
        ExecutionAuditEvent event3 = ExecutionAuditEvent.leaseEvent(
                execution, ExecutionAuditEventType.LEASE_ACQUIRED, "worker-node-1", 1L, "Worker lease claimed");
        auditEventRepository.save(event3);

        // Step 4: Retry scheduled
        ExecutionAuditEvent event4 = ExecutionAuditEvent.retryScheduled(
                execution, 1, 2000L, "RATE_LIMIT", "Backing off", "WORKER", "trace-3");
        auditEventRepository.save(event4);

        // Retrieve chronological audit timeline
        Pageable pageable = PageRequest.of(0, 10);
        Page<ExecutionAuditEvent> timeline = auditEventRepository.findByExecutionIdOrderByCreatedAtAsc(execution.getId(), pageable);

        assertThat(timeline.getTotalElements()).isEqualTo(4);
        List<ExecutionAuditEvent> content = timeline.getContent();
        assertThat(content.get(0).getEventType()).isEqualTo(ExecutionAuditEventType.STATE_TRANSITION);
        assertThat(content.get(0).getNewState()).isEqualTo("INITIALIZED");
        assertThat(content.get(1).getNewState()).isEqualTo("QUEUED");
        assertThat(content.get(2).getEventType()).isEqualTo(ExecutionAuditEventType.LEASE_ACQUIRED);
        assertThat(content.get(3).getEventType()).isEqualTo(ExecutionAuditEventType.RETRY_SCHEDULED);
        assertThat(content.get(3).getDetails()).contains("backoffMs=2000");
    }

    @Test
    @DisplayName("Rollback link: Verifies audit event links to ExecutionRollbackRecord")
    void testAuditRollbackLink() {
        MigrationExecution execution = createExecution(alice, "Workout Mix", MigrationExecutionStatus.PARTIALLY_ROLLED_BACK, Instant.now());

        ExecutionTrackRecord trackRecord = trackRecordRepository.findByExecutionIdOrderByTargetPositionAsc(execution.getId()).get(0);
        ExecutionRollbackRecord rollbackRecord = ExecutionRollbackRecord.success(execution, trackRecord);
        ExecutionRollbackRecord savedRollback = rollbackRecordRepository.save(rollbackRecord);

        ExecutionAuditEvent rollbackEvent = ExecutionAuditEvent.rollbackEvent(
                execution,
                ExecutionAuditEventType.ROLLBACK_COMPLETED,
                savedRollback,
                "Rollback completed for track 1",
                "SYSTEM"
        );
        ExecutionAuditEvent savedEvent = auditEventRepository.save(rollbackEvent);

        ExecutionAuditEvent loaded = auditEventRepository.findById(savedEvent.getId()).orElseThrow();
        assertThat(loaded.getRollbackRecord()).isNotNull();
        assertThat(loaded.getRollbackRecord().getId()).isEqualTo(savedRollback.getId());
        assertThat(loaded.getEventType()).isEqualTo(ExecutionAuditEventType.ROLLBACK_COMPLETED);
    }

    @Test
    @DisplayName("User-scoped history: Status filtering and reverse-chronological order powered by composite index")
    void testUserScopedHistoryWithStatusFilter() {
        Instant t1 = Instant.parse("2026-03-01T10:00:00Z");
        Instant t2 = Instant.parse("2026-03-01T11:00:00Z");
        Instant t3 = Instant.parse("2026-03-01T12:00:00Z");
        Instant t4 = Instant.parse("2026-03-01T13:00:00Z");

        // Alice's migrations
        createExecution(alice, "Alice Completed 1", MigrationExecutionStatus.COMPLETED, t1);
        createExecution(alice, "Alice Failed", MigrationExecutionStatus.FAILED, t2);
        createExecution(alice, "Alice Completed 2", MigrationExecutionStatus.COMPLETED, t3);

        // Bob's migration (must never appear in Alice's queries)
        createExecution(bob, "Bob Completed", MigrationExecutionStatus.COMPLETED, t4);

        // Query 1: Alice's all migrations (newest first)
        Page<MigrationExecution> aliceAll = executionRepository.findByUserIdOrderByCreatedAtDesc(
                alice.getId(), PageRequest.of(0, 10));
        assertThat(aliceAll.getTotalElements()).isEqualTo(3);
        assertThat(aliceAll.getContent().get(0).getSourcePlaylistTitle()).isEqualTo("Alice Completed 2");
        assertThat(aliceAll.getContent().get(1).getSourcePlaylistTitle()).isEqualTo("Alice Failed");
        assertThat(aliceAll.getContent().get(2).getSourcePlaylistTitle()).isEqualTo("Alice Completed 1");

        // Query 2: Alice's migrations filtered by status=COMPLETED
        Page<MigrationExecution> aliceCompleted = executionRepository.findByUserIdAndStatusOrderByCreatedAtDesc(
                alice.getId(), MigrationExecutionStatus.COMPLETED, PageRequest.of(0, 10));
        assertThat(aliceCompleted.getTotalElements()).isEqualTo(2);
        assertThat(aliceCompleted.getContent().get(0).getSourcePlaylistTitle()).isEqualTo("Alice Completed 2");
        assertThat(aliceCompleted.getContent().get(1).getSourcePlaylistTitle()).isEqualTo("Alice Completed 1");

        // Query 3: Alice's migrations filtered by status=FAILED
        Page<MigrationExecution> aliceFailed = executionRepository.findByUserIdAndStatusOrderByCreatedAtDesc(
                alice.getId(), MigrationExecutionStatus.FAILED, PageRequest.of(0, 10));
        assertThat(aliceFailed.getTotalElements()).isEqualTo(1);
        assertThat(aliceFailed.getContent().get(0).getSourcePlaylistTitle()).isEqualTo("Alice Failed");
        assertThat(aliceFailed.getContent().get(0).getFailureSummary()).isEqualTo("Quota exceeded during insertion");

        // Query 4: Anti-IDOR repository check - Bob sees only Bob's migrations
        Page<MigrationExecution> bobAll = executionRepository.findByUserIdOrderByCreatedAtDesc(
                bob.getId(), PageRequest.of(0, 10));
        assertThat(bobAll.getTotalElements()).isEqualTo(1);
        assertThat(bobAll.getContent().get(0).getSourcePlaylistTitle()).isEqualTo("Bob Completed");
    }

    @Test
    @DisplayName("Cascade lifecycle: Deleting MigrationExecution cascades and cleans up audit events")
    void testCascadeDeleteOnExecution() {
        MigrationExecution execution = createExecution(alice, "Temporary Playlist", MigrationExecutionStatus.INITIALIZED, Instant.now());

        ExecutionAuditEvent event = ExecutionAuditEvent.stateTransition(
                execution, null, MigrationExecutionStatus.INITIALIZED, "Init", "USER", null);
        auditEventRepository.save(event);

        assertThat(auditEventRepository.count()).isEqualTo(1);

        executionRepository.delete(execution);

        assertThat(auditEventRepository.count()).isEqualTo(0);
    }
}
