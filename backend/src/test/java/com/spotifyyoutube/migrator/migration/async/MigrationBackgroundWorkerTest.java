package com.spotifyyoutube.migrator.migration.async;

import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.migration.application.execution.ApprovedMigrationOrchestrator;
import com.spotifyyoutube.migrator.migration.application.execution.contract.MigrationExecutionSummary;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Phase 12C: MigrationBackgroundWorker Unit Tests")
class MigrationBackgroundWorkerTest {

    @Mock
    private ApprovedMigrationOrchestrator orchestrator;

    private MigrationBackgroundWorker worker;

    private UUID executionId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        worker = new MigrationBackgroundWorker(orchestrator);
        executionId = UUID.randomUUID();
        userId = UUID.randomUUID();
    }

    @Test
    @DisplayName("processMigration delegates execution to ApprovedMigrationOrchestrator.executeQueuedMigration")
    void processMigrationDelegatesToOrchestrator() {
        MigrationExecutionSummary summary = new MigrationExecutionSummary(
                executionId,
                UUID.randomUUID(),
                MigrationExecutionStatus.COMPLETED,
                "PL_dest_123",
                "https://music.youtube.com/playlist?list=PL_dest_123",
                10, 10, 0, 0, 0, 0,
                Instant.now(),
                Instant.now(),
                1L
        );
        when(orchestrator.executeQueuedMigration(eq(executionId), eq(userId))).thenReturn(summary);

        assertThatCode(() -> worker.processMigration(executionId, userId))
                .doesNotThrowAnyException();

        verify(orchestrator).executeQueuedMigration(executionId, userId);
    }

    @Test
    @DisplayName("processMigration rejects null arguments with IllegalArgumentException")
    void processMigrationRejectsNullArguments() {
        assertThatThrownBy(() -> worker.processMigration(null, userId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Execution ID must not be null");

        assertThatThrownBy(() -> worker.processMigration(executionId, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Authenticated user ID must not be null");
    }

    @Test
    @DisplayName("processMigration handles InvalidStateException gracefully without bubbling")
    void processMigrationHandlesInvalidStateExceptionGracefully() {
        when(orchestrator.executeQueuedMigration(eq(executionId), eq(userId)))
                .thenThrow(new InvalidStateException("Migration execution could not be claimed: already in progress"));

        assertThatCode(() -> worker.processMigration(executionId, userId))
                .doesNotThrowAnyException();

        verify(orchestrator).executeQueuedMigration(executionId, userId);
    }

    @Test
    @DisplayName("processMigration handles ResourceNotFoundException gracefully without bubbling")
    void processMigrationHandlesResourceNotFoundGracefully() {
        when(orchestrator.executeQueuedMigration(eq(executionId), eq(userId)))
                .thenThrow(new ResourceNotFoundException("Migration execution not found: " + executionId));

        assertThatCode(() -> worker.processMigration(executionId, userId))
                .doesNotThrowAnyException();

        verify(orchestrator).executeQueuedMigration(executionId, userId);
    }

    @Test
    @DisplayName("processMigration handles unexpected RuntimeException gracefully without bubbling")
    void processMigrationHandlesUnexpectedExceptionGracefully() {
        when(orchestrator.executeQueuedMigration(eq(executionId), eq(userId)))
                .thenThrow(new RuntimeException("Database connection timeout during worker processing"));

        assertThatCode(() -> worker.processMigration(executionId, userId))
                .doesNotThrowAnyException();

        verify(orchestrator).executeQueuedMigration(executionId, userId);
    }
}
