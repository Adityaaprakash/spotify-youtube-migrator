package com.spotifyyoutube.migrator.migration.reliability.scheduling;

import com.spotifyyoutube.migrator.migration.execution.repository.MigrationExecutionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Phase 13D-D: DurableLeaseHeartbeatService Unit Test")
class DurableLeaseHeartbeatServiceTest {

    private MigrationExecutionRepository executionRepository;
    private DurableSchedulerProperties properties;
    private DurableLeaseHeartbeatService heartbeatService;

    @BeforeEach
    void setUp() {
        executionRepository = mock(MigrationExecutionRepository.class);
        properties = new DurableSchedulerProperties();
        properties.setHeartbeatIntervalMs(15000L);
        properties.setLeaseDurationSeconds(60L);

        heartbeatService = new DurableLeaseHeartbeatService(executionRepository, properties);
    }

    @Nested
    @DisplayName("Lease Registration and Lifecycle")
    class RegistrationTests {

        @Test
        @DisplayName("registerLease stores context and tracks active count")
        void registerLeaseStoresContext() {
            UUID executionId = UUID.randomUUID();
            heartbeatService.registerLease(executionId, "worker-1", 1L);

            assertThat(heartbeatService.isRegistered(executionId)).isTrue();
            assertThat(heartbeatService.getActiveLeaseCount()).isEqualTo(1);
            assertThat(heartbeatService.isOwnershipLost(executionId)).isFalse();
        }

        @Test
        @DisplayName("registerLease ignores null or blank inputs")
        void registerLeaseIgnoresInvalidInputs() {
            heartbeatService.registerLease(null, "worker-1", 1L);
            heartbeatService.registerLease(UUID.randomUUID(), null, 1L);
            heartbeatService.registerLease(UUID.randomUUID(), "   ", 1L);

            assertThat(heartbeatService.getActiveLeaseCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("unregisterLease removes active context")
        void unregisterLeaseRemovesContext() {
            UUID executionId = UUID.randomUUID();
            heartbeatService.registerLease(executionId, "worker-1", 1L);
            assertThat(heartbeatService.getActiveLeaseCount()).isEqualTo(1);

            heartbeatService.unregisterLease(executionId);
            assertThat(heartbeatService.isRegistered(executionId)).isFalse();
            assertThat(heartbeatService.getActiveLeaseCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("markOwnershipLost marks context as lost")
        void markOwnershipLostSetsFlag() {
            UUID executionId = UUID.randomUUID();
            heartbeatService.registerLease(executionId, "worker-1", 1L);
            assertThat(heartbeatService.isOwnershipLost(executionId)).isFalse();

            heartbeatService.markOwnershipLost(executionId);
            assertThat(heartbeatService.isOwnershipLost(executionId)).isTrue();
        }
    }

    @Nested
    @DisplayName("Heartbeat Renewal Logic")
    class RenewalTests {

        @Test
        @DisplayName("renewLease returns true on successful database update")
        void renewLeaseSuccess() {
            UUID executionId = UUID.randomUUID();
            heartbeatService.registerLease(executionId, "worker-1", 5L, 120L);

            when(executionRepository.renewLeaseHeartbeat(executionId, "worker-1", 5L, 120L))
                    .thenReturn(1);

            boolean result = heartbeatService.renewLease(executionId);

            assertThat(result).isTrue();
            assertThat(heartbeatService.isOwnershipLost(executionId)).isFalse();
            verify(executionRepository).renewLeaseHeartbeat(executionId, "worker-1", 5L, 120L);
        }

        @Test
        @DisplayName("renewLease detects ownership loss when repository returns 0 rows")
        void renewLeaseDetectsOwnershipLoss() {
            UUID executionId = UUID.randomUUID();
            heartbeatService.registerLease(executionId, "worker-1", 3L, 60L);

            when(executionRepository.renewLeaseHeartbeat(executionId, "worker-1", 3L, 60L))
                    .thenReturn(0);

            boolean result = heartbeatService.renewLease(executionId);

            assertThat(result).isFalse();
            assertThat(heartbeatService.isOwnershipLost(executionId)).isTrue();
        }

        @Test
        @DisplayName("renewLease returns false for unregistered execution")
        void renewLeaseUnregistered() {
            boolean result = heartbeatService.renewLease(UUID.randomUUID());
            assertThat(result).isFalse();
            verify(executionRepository, never()).renewLeaseHeartbeat(any(), anyString(), anyLong(), anyLong());
        }

        @Test
        @DisplayName("renewLease handles database exception gracefully")
        void renewLeaseHandlesException() {
            UUID executionId = UUID.randomUUID();
            heartbeatService.registerLease(executionId, "worker-1", 1L);

            when(executionRepository.renewLeaseHeartbeat(eq(executionId), anyString(), anyLong(), anyLong()))
                    .thenThrow(new RuntimeException("Database timeout"));

            boolean result = heartbeatService.renewLease(executionId);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("renewActiveLeases iterates through active healthy leases")
        void renewActiveLeasesIteratesAll() {
            UUID exec1 = UUID.randomUUID();
            UUID exec2 = UUID.randomUUID();
            heartbeatService.registerLease(exec1, "worker-1", 1L);
            heartbeatService.registerLease(exec2, "worker-1", 2L);

            when(executionRepository.renewLeaseHeartbeat(eq(exec1), anyString(), eq(1L), anyLong()))
                    .thenReturn(1);
            when(executionRepository.renewLeaseHeartbeat(eq(exec2), anyString(), eq(2L), anyLong()))
                    .thenReturn(1);

            int count = heartbeatService.renewActiveLeases();

            assertThat(count).isEqualTo(2);
            verify(executionRepository, times(2)).renewLeaseHeartbeat(any(), anyString(), anyLong(), anyLong());
        }

        @Test
        @DisplayName("renewActiveLeases skips already lost leases")
        void renewActiveLeasesSkipsLostLeases() {
            UUID exec1 = UUID.randomUUID();
            UUID exec2 = UUID.randomUUID();
            heartbeatService.registerLease(exec1, "worker-1", 1L);
            heartbeatService.registerLease(exec2, "worker-1", 2L);
            heartbeatService.markOwnershipLost(exec1);

            when(executionRepository.renewLeaseHeartbeat(eq(exec2), anyString(), eq(2L), anyLong()))
                    .thenReturn(1);

            int count = heartbeatService.renewActiveLeases();

            assertThat(count).isEqualTo(1);
            verify(executionRepository, never()).renewLeaseHeartbeat(eq(exec1), anyString(), anyLong(), anyLong());
            verify(executionRepository).renewLeaseHeartbeat(eq(exec2), anyString(), eq(2L), anyLong());
        }
    }
}
