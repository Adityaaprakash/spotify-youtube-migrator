package com.spotifyyoutube.migrator.migration.application.execution;

import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionAuditEvent;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionAuditEventType;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionReconciliationRecord;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionRollbackRecord;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecution;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus;
import com.spotifyyoutube.migrator.migration.execution.repository.ExecutionAuditEventRepository;
import com.spotifyyoutube.migrator.migration.execution.repository.MigrationExecutionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests verifying durable execution audit logging, lifecycle event persistence,
 * and anti-IDOR security controls (Phase 14D).
 */
@ExtendWith(MockitoExtension.class)
class ExecutionAuditServiceTest {

    @Mock
    private ExecutionAuditEventRepository auditEventRepository;

    @Mock
    private MigrationExecutionRepository executionRepository;

    private DefaultExecutionAuditService auditService;

    private User owner;
    private User attacker;
    private MigrationExecution execution;
    private UUID executionId;
    private UUID ownerId;
    private UUID attackerId;

    @BeforeEach
    void setUp() {
        auditService = new DefaultExecutionAuditService(auditEventRepository, executionRepository);

        ownerId = UUID.randomUUID();
        owner = new User("owner@example.com");
        ReflectionTestUtils.setField(owner, "id", ownerId);

        attackerId = UUID.randomUUID();
        attacker = new User("attacker@example.com");
        ReflectionTestUtils.setField(attacker, "id", attackerId);

        executionId = UUID.randomUUID();
        execution = new MigrationExecution();
        ReflectionTestUtils.setField(execution, "id", executionId);
        ReflectionTestUtils.setField(execution, "user", owner);
        execution.setStatus(MigrationExecutionStatus.INITIALIZED);
    }

    @Nested
    @DisplayName("Audit Event Recording Tests")
    class RecordingTests {

        @Test
        @DisplayName("Records state transition audit event with correct fields")
        void recordStateTransition_Success() {
            when(auditEventRepository.save(any(ExecutionAuditEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            ExecutionAuditEvent event = auditService.recordStateTransition(
                    execution,
                    MigrationExecutionStatus.INITIALIZED,
                    MigrationExecutionStatus.QUEUED,
                    "Execution queued for processing",
                    "USER",
                    "corr-123"
            );

            assertThat(event).isNotNull();
            assertThat(event.getExecution()).isEqualTo(execution);
            assertThat(event.getEventType()).isEqualTo(ExecutionAuditEventType.STATE_TRANSITION);
            assertThat(event.getPreviousState()).isEqualTo("INITIALIZED");
            assertThat(event.getNewState()).isEqualTo("QUEUED");
            assertThat(event.getReason()).isEqualTo("Execution queued for processing");
            assertThat(event.getActor()).isEqualTo("USER");
            assertThat(event.getCorrelationId()).isEqualTo("corr-123");

            verify(auditEventRepository).save(any(ExecutionAuditEvent.class));
        }

        @Test
        @DisplayName("Records retry audit event with backoff and attempt details")
        void recordRetryEvent_Success() {
            when(auditEventRepository.save(any(ExecutionAuditEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            ExecutionAuditEvent event = auditService.recordRetryEvent(
                    execution,
                    2,
                    1500L,
                    "RATE_LIMIT",
                    "Rate limit hit; retry scheduled",
                    "WORKER",
                    "retry-op-456"
            );

            assertThat(event).isNotNull();
            assertThat(event.getEventType()).isEqualTo(ExecutionAuditEventType.RETRY_SCHEDULED);
            assertThat(event.getDetails()).contains("attempt=2");
            assertThat(event.getDetails()).contains("backoffMs=1500");
            assertThat(event.getDetails()).contains("failureClass=RATE_LIMIT");
            assertThat(event.getActor()).isEqualTo("WORKER");
        }

        @Test
        @DisplayName("Records worker lease acquisition event")
        void recordLeaseEvent_Success() {
            when(auditEventRepository.save(any(ExecutionAuditEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            ExecutionAuditEvent event = auditService.recordLeaseEvent(
                    execution,
                    ExecutionAuditEventType.LEASE_ACQUIRED,
                    "worker-node-1",
                    42L,
                    "Acquired lease for background processing"
            );

            assertThat(event).isNotNull();
            assertThat(event.getEventType()).isEqualTo(ExecutionAuditEventType.LEASE_ACQUIRED);
            assertThat(event.getDetails()).contains("worker=worker-node-1");
            assertThat(event.getDetails()).contains("generation=42");
        }

        @Test
        @DisplayName("Records compensating rollback event with rollback record link")
        void recordRollbackEvent_Success() {
            com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedTrack approvedTrack =
                    new com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedTrack(
                            0, "sp-track-1", "Song A", "Artist X", "yt-vid-1", "Song A Official", "Artist X Vevo",
                            java.time.Duration.ofSeconds(180),
                            com.spotifyyoutube.migrator.migration.review.domain.ReviewDecisionSource.AUTOMATIC,
                            0, 0.98, "EXACT_ISRC"
                    );
            com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackRecord trackRecord =
                    new com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackRecord(execution, approvedTrack);
            ExecutionRollbackRecord rollbackRecord = ExecutionRollbackRecord.success(execution, trackRecord);
            ReflectionTestUtils.setField(rollbackRecord, "id", UUID.randomUUID());

            when(auditEventRepository.save(any(ExecutionAuditEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            ExecutionAuditEvent event = auditService.recordRollbackEvent(
                    execution,
                    ExecutionAuditEventType.ROLLBACK_COMPLETED,
                    rollbackRecord,
                    "Rollback successful",
                    "SYSTEM"
            );

            assertThat(event).isNotNull();
            assertThat(event.getEventType()).isEqualTo(ExecutionAuditEventType.ROLLBACK_COMPLETED);
            assertThat(event.getRollbackRecord()).isEqualTo(rollbackRecord);
            assertThat(event.getReason()).isEqualTo("Rollback successful");
        }

        @Test
        @DisplayName("Records reconciliation audit event with reconciliation record link")
        void recordReconciliationEvent_Success() {
            ExecutionReconciliationRecord recRecord = new ExecutionReconciliationRecord();
            ReflectionTestUtils.setField(recRecord, "id", UUID.randomUUID());

            when(auditEventRepository.save(any(ExecutionAuditEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            ExecutionAuditEvent event = auditService.recordReconciliationEvent(
                    execution,
                    recRecord,
                    "Reconciliation finished with 0 discrepancies",
                    "SYSTEM"
            );

            assertThat(event).isNotNull();
            assertThat(event.getEventType()).isEqualTo(ExecutionAuditEventType.RECONCILIATION_COMPLETED);
            assertThat(event.getReconciliationRecord()).isEqualTo(recRecord);
        }
    }

    @Nested
    @DisplayName("Audit Timeline Retrieval & Anti-IDOR Security Tests")
    class TimelineSecurityTests {

        @Test
        @DisplayName("Owner can retrieve chronological audit timeline")
        void getAuditTimeline_OwnerSuccess() {
            when(executionRepository.findById(executionId)).thenReturn(Optional.of(execution));

            ExecutionAuditEvent event1 = ExecutionAuditEvent.stateTransition(
                    execution, null, MigrationExecutionStatus.INITIALIZED, "Created", "USER", null);
            ExecutionAuditEvent event2 = ExecutionAuditEvent.stateTransition(
                    execution, MigrationExecutionStatus.INITIALIZED, MigrationExecutionStatus.QUEUED, "Queued", "USER", null);

            Pageable pageable = PageRequest.of(0, 10);
            Page<ExecutionAuditEvent> page = new PageImpl<>(List.of(event1, event2), pageable, 2);

            when(auditEventRepository.findByExecutionIdOrderByCreatedAtAsc(executionId, pageable)).thenReturn(page);

            Page<ExecutionAuditEvent> result = auditService.getAuditTimeline(executionId, ownerId, pageable);

            assertThat(result).isNotNull();
            assertThat(result.getContent()).hasSize(2);
            assertThat(result.getContent().get(0).getNewState()).isEqualTo("INITIALIZED");
            assertThat(result.getContent().get(1).getNewState()).isEqualTo("QUEUED");
        }

        @Test
        @DisplayName("Anti-IDOR: Non-owner access throws 404 ResourceNotFoundException without leaking existence")
        void getAuditTimeline_AttackerBlockedWithNotFound() {
            when(executionRepository.findById(executionId)).thenReturn(Optional.of(execution));

            assertThatThrownBy(() -> auditService.getAuditTimeline(executionId, attackerId, PageRequest.of(0, 10)))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Migration execution not found: " + executionId);
        }

        @Test
        @DisplayName("Nonexistent execution throws 404 ResourceNotFoundException")
        void getAuditTimeline_NonexistentExecution() {
            UUID unknownId = UUID.randomUUID();
            when(executionRepository.findById(unknownId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> auditService.getAuditTimeline(unknownId, ownerId, PageRequest.of(0, 10)))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Migration execution not found: " + unknownId);
        }

        @Test
        @DisplayName("Throws IllegalArgumentException when required arguments are null")
        void getAuditTimeline_NullArgumentsRejected() {
            assertThatThrownBy(() -> auditService.getAuditTimeline(null, ownerId, PageRequest.of(0, 10)))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> auditService.getAuditTimeline(executionId, null, PageRequest.of(0, 10)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
