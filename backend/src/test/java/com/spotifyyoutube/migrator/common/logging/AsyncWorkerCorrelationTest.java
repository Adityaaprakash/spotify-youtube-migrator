package com.spotifyyoutube.migrator.common.logging;

import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
import com.spotifyyoutube.migrator.migration.application.execution.ApprovedMigrationOrchestrator;
import com.spotifyyoutube.migrator.migration.application.execution.contract.MigrationExecutionSummary;
import com.spotifyyoutube.migrator.migration.async.MigrationBackgroundWorker;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.MDC;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@DisplayName("Async & Scheduled Worker Correlation Tests (Phase 18F)")
class AsyncWorkerCorrelationTest {

    private ApprovedMigrationOrchestrator orchestrator;
    private MigrationBackgroundWorker worker;

    @BeforeEach
    void setUp() {
        MDC.clear();
        orchestrator = Mockito.mock(ApprovedMigrationOrchestrator.class);
        worker = new MigrationBackgroundWorker(orchestrator);
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Nested
    @DisplayName("LoggingContextHolder Scoping Tests")
    class ContextHolderTests {

        @Test
        @DisplayName("forExecution preserves caller correlation ID if already present")
        void preservesCallerCorrelationIdInManualExecution() {
            MDC.put(LoggingConstants.MDC_CORRELATION_ID, "inbound-corr-123");
            UUID executionId = UUID.randomUUID();
            UUID userId = UUID.randomUUID();

            try (var ignored = LoggingContextHolder.forExecution(executionId, userId, "migration_execution", "manual")) {
                assertThat(MDC.get(LoggingConstants.MDC_CORRELATION_ID)).isEqualTo("inbound-corr-123");
                assertThat(MDC.get(LoggingConstants.MDC_MIGRATION_EXECUTION_ID)).isEqualTo(executionId.toString());
                assertThat(MDC.get(LoggingConstants.MDC_USER_ID)).isEqualTo(userId.toString());
                assertThat(MDC.get(LoggingConstants.MDC_OPERATION)).isEqualTo("migration_execution");
                assertThat(MDC.get(LoggingConstants.MDC_MODE)).isEqualTo("manual");
            }

            // Outside scope, previous context is restored
            assertThat(MDC.get(LoggingConstants.MDC_CORRELATION_ID)).isEqualTo("inbound-corr-123");
            assertThat(MDC.get(LoggingConstants.MDC_MIGRATION_EXECUTION_ID)).isNull();
        }

        @Test
        @DisplayName("forExecution generates execution correlation ID when caller has none")
        void generatesExecutionCorrelationIdWhenMissing() {
            UUID executionId = UUID.randomUUID();
            UUID userId = UUID.randomUUID();

            try (var ignored = LoggingContextHolder.forExecution(executionId, userId, "migration_execution", "manual")) {
                String corrId = MDC.get(LoggingConstants.MDC_CORRELATION_ID);
                assertThat(corrId).isNotNull();
                assertThat(corrId).startsWith("exec-");
            }

            assertThat(MDC.get(LoggingConstants.MDC_CORRELATION_ID)).isNull();
        }

        @Test
        @DisplayName("forScheduledExecution establishes scheduled correlation ID and strips stale requestId")
        void establishesScheduledCorrelationIdAndStripsRequestId() {
            MDC.put(LoggingConstants.MDC_REQUEST_ID, "stale-request-id-should-be-removed");
            UUID executionId = UUID.randomUUID();
            UUID userId = UUID.randomUUID();
            long leaseGen = 42L;

            try (var ignored = LoggingContextHolder.forScheduledExecution(executionId, userId, leaseGen)) {
                assertThat(MDC.get(LoggingConstants.MDC_REQUEST_ID)).isNull();
                assertThat(MDC.get(LoggingConstants.MDC_CORRELATION_ID)).isEqualTo("sched-42-" + executionId);
                assertThat(MDC.get(LoggingConstants.MDC_MIGRATION_EXECUTION_ID)).isEqualTo(executionId.toString());
                assertThat(MDC.get(LoggingConstants.MDC_USER_ID)).isEqualTo(userId.toString());
                assertThat(MDC.get(LoggingConstants.MDC_LEASE_GENERATION)).isEqualTo("42");
                assertThat(MDC.get(LoggingConstants.MDC_OPERATION)).isEqualTo("scheduled_migration_execution");
                assertThat(MDC.get(LoggingConstants.MDC_MODE)).isEqualTo("automated");
            }

            // Restored after closure
            assertThat(MDC.get(LoggingConstants.MDC_REQUEST_ID)).isEqualTo("stale-request-id-should-be-removed");
            assertThat(MDC.get(LoggingConstants.MDC_CORRELATION_ID)).isNull();
        }

        @Test
        @DisplayName("withProperty sets and restores a single property safely")
        void withPropertySetsAndRestores() {
            MDC.put("testProp", "original");

            try (var ignored = LoggingContextHolder.withProperty("testProp", "temporary")) {
                assertThat(MDC.get("testProp")).isEqualTo("temporary");
            }

            assertThat(MDC.get("testProp")).isEqualTo("original");
        }
    }

    @Nested
    @DisplayName("MigrationBackgroundWorker Execution Context Tests")
    class BackgroundWorkerContextTests {

        @Test
        @DisplayName("processMigration populates context during execution and cleans up on success")
        void processMigrationPopulatesContextAndCleansUp() {
            UUID executionId = UUID.randomUUID();
            UUID userId = UUID.randomUUID();

            AtomicReference<String> observedExecutionId = new AtomicReference<>();
            AtomicReference<String> observedUserId = new AtomicReference<>();
            AtomicReference<String> observedMode = new AtomicReference<>();

            when(orchestrator.executeQueuedMigration(eq(executionId), eq(userId))).thenAnswer(invocation -> {
                observedExecutionId.set(MDC.get(LoggingConstants.MDC_MIGRATION_EXECUTION_ID));
                observedUserId.set(MDC.get(LoggingConstants.MDC_USER_ID));
                observedMode.set(MDC.get(LoggingConstants.MDC_MODE));
                MigrationExecutionSummary mockSummary = Mockito.mock(MigrationExecutionSummary.class);
                when(mockSummary.status()).thenReturn(MigrationExecutionStatus.COMPLETED);
                return mockSummary;
            });

            worker.processMigration(executionId, userId);

            assertThat(observedExecutionId.get()).isEqualTo(executionId.toString());
            assertThat(observedUserId.get()).isEqualTo(userId.toString());
            assertThat(observedMode.get()).isEqualTo("manual");

            // Guaranteed clean thread after worker finishes
            assertThat(MDC.get(LoggingConstants.MDC_MIGRATION_EXECUTION_ID)).isNull();
            assertThat(MDC.get(LoggingConstants.MDC_USER_ID)).isNull();
            assertThat(MDC.get(LoggingConstants.MDC_MODE)).isNull();
        }

        @Test
        @DisplayName("processMigration cleans up thread context even when orchestrator throws an exception")
        void processMigrationCleansUpOnException() {
            UUID executionId = UUID.randomUUID();
            UUID userId = UUID.randomUUID();

            when(orchestrator.executeQueuedMigration(any(), any()))
                    .thenThrow(new InvalidStateException("Simulated state rejection"));

            worker.processMigration(executionId, userId);

            assertThat(MDC.get(LoggingConstants.MDC_MIGRATION_EXECUTION_ID)).isNull();
            assertThat(MDC.get(LoggingConstants.MDC_USER_ID)).isNull();
        }

        @Test
        @DisplayName("processExecution populates scheduled context and cleans up on completion")
        void processExecutionPopulatesScheduledContextAndCleansUp() {
            UUID executionId = UUID.randomUUID();
            UUID userId = UUID.randomUUID();
            long leaseGen = 7L;

            AtomicReference<String> observedGen = new AtomicReference<>();
            AtomicReference<String> observedMode = new AtomicReference<>();
            AtomicReference<String> observedCorr = new AtomicReference<>();

            when(orchestrator.executeClaimedMigration(eq(executionId), eq(userId), eq(leaseGen))).thenAnswer(invocation -> {
                observedGen.set(MDC.get(LoggingConstants.MDC_LEASE_GENERATION));
                observedMode.set(MDC.get(LoggingConstants.MDC_MODE));
                observedCorr.set(MDC.get(LoggingConstants.MDC_CORRELATION_ID));
                MigrationExecutionSummary mockSummary = Mockito.mock(MigrationExecutionSummary.class);
                when(mockSummary.status()).thenReturn(MigrationExecutionStatus.COMPLETED);
                return mockSummary;
            });

            worker.processExecution(executionId, userId, leaseGen);

            assertThat(observedGen.get()).isEqualTo("7");
            assertThat(observedMode.get()).isEqualTo("automated");
            assertThat(observedCorr.get()).isEqualTo("sched-7-" + executionId);

            assertThat(MDC.get(LoggingConstants.MDC_LEASE_GENERATION)).isNull();
            assertThat(MDC.get(LoggingConstants.MDC_MODE)).isNull();
            assertThat(MDC.get(LoggingConstants.MDC_CORRELATION_ID)).isNull();
        }
    }
}
