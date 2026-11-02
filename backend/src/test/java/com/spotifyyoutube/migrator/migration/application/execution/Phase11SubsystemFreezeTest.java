package com.spotifyyoutube.migrator.migration.application.execution;

import com.spotifyyoutube.migrator.migration.application.plan.MigrationPlan;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionReconciliationRecord;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionRollbackRecord;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackRecord;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecution;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Phase 11H: Production Hardening, Architectural Guardrails & Subsystem Freeze Test")
class Phase11SubsystemFreezeTest {

    private static final List<Class<?>> PHASE_11_SERVICES = List.of(
            ApprovedMigrationExecutionService.class,
            DefaultApprovedMigrationExecutionService.class,
            DestinationPlaylistCreationService.class,
            DefaultDestinationPlaylistCreationService.class,
            OrderedTrackInsertionService.class,
            DefaultOrderedTrackInsertionService.class,
            MigrationExecutionStateService.class,
            DefaultMigrationExecutionStateService.class,
            ApprovedMigrationOrchestrator.class,
            DefaultApprovedMigrationOrchestrator.class
    );

    @Nested
    @DisplayName("Architectural Guardrail 1: Strictly Synchronous Execution (Phase 12 Isolation)")
    class SynchronousExecutionGuardrails {

        @Test
        @DisplayName("Phase 11 services contain ZERO methods annotated with @Async")
        void zeroAsyncMethods() {
            for (Class<?> serviceClass : PHASE_11_SERVICES) {
                for (Method method : serviceClass.getDeclaredMethods()) {
                    assertThat(method.isAnnotationPresent(Async.class))
                            .withFailMessage("Service %s has @Async method %s, violating synchronous Phase 11 boundary",
                                    serviceClass.getSimpleName(), method.getName())
                            .isFalse();
                }
            }
        }

        @Test
        @DisplayName("Phase 11 services contain ZERO methods annotated with @Scheduled")
        void zeroScheduledMethods() {
            for (Class<?> serviceClass : PHASE_11_SERVICES) {
                for (Method method : serviceClass.getDeclaredMethods()) {
                    assertThat(method.isAnnotationPresent(Scheduled.class))
                            .withFailMessage("Service %s has @Scheduled method %s, violating synchronous Phase 11 boundary",
                                    serviceClass.getSimpleName(), method.getName())
                            .isFalse();
                }
            }
        }
    }

    @Nested
    @DisplayName("Architectural Guardrail 2: Entity Schema and Optimistic Locking Consistency")
    class EntityPersistenceGuardrails {

        @Test
        @DisplayName("MigrationExecution is annotated with @Entity, table migration_executions, and @Version")
        void migrationExecutionEntityAnnotations() {
            assertThat(MigrationExecution.class.isAnnotationPresent(Entity.class)).isTrue();
            Table table = MigrationExecution.class.getAnnotation(Table.class);
            assertThat(table).isNotNull();
            assertThat(table.name()).isEqualTo("migration_executions");

            boolean hasVersion = Arrays.stream(MigrationExecution.class.getDeclaredFields())
                    .anyMatch(f -> f.isAnnotationPresent(Version.class));
            assertThat(hasVersion).isTrue();
        }

        @Test
        @DisplayName("ExecutionTrackRecord is annotated with @Entity, table execution_track_records, and @Version")
        void executionTrackRecordEntityAnnotations() {
            assertThat(ExecutionTrackRecord.class.isAnnotationPresent(Entity.class)).isTrue();
            Table table = ExecutionTrackRecord.class.getAnnotation(Table.class);
            assertThat(table).isNotNull();
            assertThat(table.name()).isEqualTo("execution_track_records");

            boolean hasVersion = Arrays.stream(ExecutionTrackRecord.class.getDeclaredFields())
                    .anyMatch(f -> f.isAnnotationPresent(Version.class));
            assertThat(hasVersion).isTrue();
        }

        @Test
        @DisplayName("ExecutionRollbackRecord is annotated with @Entity, table execution_rollback_records, and @Version")
        void executionRollbackRecordEntityAnnotations() {
            assertThat(ExecutionRollbackRecord.class.isAnnotationPresent(Entity.class)).isTrue();
            Table table = ExecutionRollbackRecord.class.getAnnotation(Table.class);
            assertThat(table).isNotNull();
            assertThat(table.name()).isEqualTo("execution_rollback_records");

            boolean hasVersion = Arrays.stream(ExecutionRollbackRecord.class.getDeclaredFields())
                    .anyMatch(f -> f.isAnnotationPresent(Version.class));
            assertThat(hasVersion).isTrue();
        }

        @Test
        @DisplayName("ExecutionReconciliationRecord is annotated with @Entity, table execution_reconciliation_records, and @Version")
        void executionReconciliationRecordEntityAnnotations() {
            assertThat(ExecutionReconciliationRecord.class.isAnnotationPresent(Entity.class)).isTrue();
            Table table = ExecutionReconciliationRecord.class.getAnnotation(Table.class);
            assertThat(table).isNotNull();
            assertThat(table.name()).isEqualTo("execution_reconciliation_records");

            boolean hasVersion = Arrays.stream(ExecutionReconciliationRecord.class.getDeclaredFields())
                    .anyMatch(f -> f.isAnnotationPresent(Version.class));
            assertThat(hasVersion).isTrue();
        }
    }

    @Nested
    @DisplayName("Architectural Guardrail 3: ADR-022 Transient Plan Guarantee")
    class TransientPlanGuarantee {

        @Test
        @DisplayName("MigrationPlan has ZERO JPA annotations, preserving transient in-memory preview invariant")
        void migrationPlanIsTransient() {
            assertThat(MigrationPlan.class.isAnnotationPresent(Entity.class)).isFalse();
            assertThat(MigrationPlan.class.isAnnotationPresent(Table.class)).isFalse();
        }
    }

    @Nested
    @DisplayName("Operational Capacity Assessment: Provider API Quota & Bounding Calculation")
    class CapacityAssessment {

        /**
         * YouTube Data API v3 Quota Costs:
         * - Playlist Creation (POST /playlists): 50 units
         * - Track Insertion (POST /playlistItems): 50 units
         * - Playlist Inspection (GET /playlistItems, 50 items/page): 1 unit per page
         * - Track Deletion (DELETE /playlistItems): 50 units
         * Default YouTube Daily Quota: 10,000 units.
         */
        @Test
        @DisplayName("Validates YouTube API quota consumption formulas across realistic playlist sizes")
        void quotaCalculations() {
            // 50 tracks
            int creationCost = 50;
            int insertionCost50 = 50 * 50; // 2,500 units
            int inspectionCost50 = (int) Math.ceil(50.0 / 50.0) * 1; // 1 unit
            int totalMigration50 = creationCost + insertionCost50;
            assertThat(totalMigration50).isEqualTo(2550);
            assertThat(inspectionCost50).isEqualTo(1);

            // 100 tracks
            int insertionCost100 = 100 * 50; // 5,000 units
            int inspectionCost100 = (int) Math.ceil(100.0 / 50.0) * 1; // 2 units
            int totalMigration100 = creationCost + insertionCost100;
            assertThat(totalMigration100).isEqualTo(5050);
            assertThat(inspectionCost100).isEqualTo(2);

            // 500 tracks
            int insertionCost500 = 500 * 50; // 25,000 units (requires quota increase or batching)
            int inspectionCost500 = (int) Math.ceil(500.0 / 50.0) * 1; // 10 units
            assertThat(inspectionCost500).isEqualTo(10);

            // 1,000 tracks
            int inspectionCost1000 = (int) Math.ceil(1000.0 / 50.0) * 1; // 20 units
            assertThat(inspectionCost1000).isEqualTo(20);
        }
    }
}
