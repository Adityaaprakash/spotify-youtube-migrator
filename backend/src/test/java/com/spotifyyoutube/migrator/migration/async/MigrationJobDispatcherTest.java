package com.spotifyyoutube.migrator.migration.async;

import com.spotifyyoutube.migrator.migration.application.execution.ApprovedMigrationOrchestrator;
import com.spotifyyoutube.migrator.migration.application.execution.MigrationExecutionStateService;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.contract.MigrationExecutionSummary;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Phase 12C: MigrationJobDispatcher Unit Tests")
class MigrationJobDispatcherTest {

    @Mock
    private ApprovedMigrationOrchestrator orchestrator;

    @Mock
    private MigrationExecutionStateService executionStateService;

    @Mock
    private MigrationBackgroundWorker worker;

    @Mock
    private TaskExecutor taskExecutor;

    private MigrationJobDispatcher dispatcher;

    private UUID userId;
    private UUID sessionId;
    private UUID executionId;
    private ApprovedMigrationExecutionRequest request;

    @BeforeEach
    void setUp() {
        dispatcher = new MigrationJobDispatcher(
                orchestrator,
                executionStateService,
                worker,
                taskExecutor
        );

        userId = UUID.randomUUID();
        sessionId = UUID.randomUUID();
        executionId = UUID.randomUUID();
        request = ApprovedMigrationExecutionRequest.of(sessionId, userId);
    }

    @Test
    @DisplayName("dispatchMigration successfully prepares QUEUED execution and submits worker task to executor")
    void dispatchMigrationSuccess() {
        MigrationExecutionSummary queuedSummary = new MigrationExecutionSummary(
                executionId,
                sessionId,
                MigrationExecutionStatus.QUEUED,
                "PL_dest_123",
                "https://music.youtube.com/playlist?list=PL_dest_123",
                10, 0, 0, 0, 0, 0,
                null, null, 0L
        );
        when(orchestrator.prepareQueuedMigration(eq(request), eq(userId))).thenReturn(queuedSummary);

        // Capture submitted task and execute it to verify delegation to worker
        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);

        MigrationExecutionSummary result = dispatcher.dispatchMigration(request, userId);

        assertThat(result).isNotNull();
        assertThat(result.executionId()).isEqualTo(executionId);
        assertThat(result.status()).isEqualTo(MigrationExecutionStatus.QUEUED);

        verify(orchestrator).prepareQueuedMigration(request, userId);
        verify(taskExecutor).execute(runnableCaptor.capture());

        // Execute captured runnable to assert background worker interaction
        runnableCaptor.getValue().run();
        verify(worker).processMigration(executionId, userId);
    }

    @Test
    @DisplayName("dispatchMigration returns existing summary without worker dispatch when already COMPLETED")
    void dispatchMigrationReturnsImmediatelyWhenAlreadyCompleted() {
        MigrationExecutionSummary completedSummary = new MigrationExecutionSummary(
                executionId,
                sessionId,
                MigrationExecutionStatus.COMPLETED,
                "PL_dest_123",
                "https://music.youtube.com/playlist?list=PL_dest_123",
                10, 10, 0, 0, 0, 0,
                Instant.now(), Instant.now(), 1L
        );
        when(orchestrator.prepareQueuedMigration(eq(request), eq(userId))).thenReturn(completedSummary);

        MigrationExecutionSummary result = dispatcher.dispatchMigration(request, userId);

        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo(MigrationExecutionStatus.COMPLETED);

        // Verify taskExecutor was never called for already completed migration
        verify(taskExecutor, never()).execute(any());
        verify(worker, never()).processMigration(any(), any());
    }

    @Test
    @DisplayName("dispatchMigration handles TaskRejectedException by resetting execution to INITIALIZED to prevent orphaning")
    void dispatchMigrationHandlesTaskRejectedException() {
        MigrationExecutionSummary queuedSummary = new MigrationExecutionSummary(
                executionId,
                sessionId,
                MigrationExecutionStatus.QUEUED,
                "PL_dest_123",
                "https://music.youtube.com/playlist?list=PL_dest_123",
                10, 0, 0, 0, 0, 0,
                null, null, 0L
        );
        when(orchestrator.prepareQueuedMigration(eq(request), eq(userId))).thenReturn(queuedSummary);

        doThrow(new TaskRejectedException("Worker queue full"))
                .when(taskExecutor).execute(any(Runnable.class));

        assertThatThrownBy(() -> dispatcher.dispatchMigration(request, userId))
                .isInstanceOf(TaskRejectedException.class)
                .hasMessageContaining("Worker queue full");

        // Verify that handleDispatchRejection was called to reset state and avoid orphaning in QUEUED
        verify(executionStateService).handleDispatchRejection(eq(executionId), eq(userId), eq("Worker queue full"));
    }

    @Test
    @DisplayName("dispatchMigration handles unexpected executor failure by resetting execution to INITIALIZED")
    void dispatchMigrationHandlesUnexpectedExecutorFailure() {
        MigrationExecutionSummary queuedSummary = new MigrationExecutionSummary(
                executionId,
                sessionId,
                MigrationExecutionStatus.QUEUED,
                "PL_dest_123",
                "https://music.youtube.com/playlist?list=PL_dest_123",
                10, 0, 0, 0, 0, 0,
                null, null, 0L
        );
        when(orchestrator.prepareQueuedMigration(eq(request), eq(userId))).thenReturn(queuedSummary);

        doThrow(new RuntimeException("Executor thread pool shutdown in progress"))
                .when(taskExecutor).execute(any(Runnable.class));

        assertThatThrownBy(() -> dispatcher.dispatchMigration(request, userId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Executor thread pool shutdown in progress");

        verify(executionStateService).handleDispatchRejection(eq(executionId), eq(userId), eq("Executor thread pool shutdown in progress"));
    }

    @Test
    @DisplayName("dispatchMigration rejects null arguments with IllegalArgumentException")
    void dispatchMigrationRejectsNullArguments() {
        assertThatThrownBy(() -> dispatcher.dispatchMigration(null, userId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ApprovedMigrationExecutionRequest must not be null");

        assertThatThrownBy(() -> dispatcher.dispatchMigration(request, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Authenticated user ID must not be null");
    }
}
