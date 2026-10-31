package com.spotifyyoutube.migrator.migration.application.execution;

import com.spotifyyoutube.migrator.matching.discovery.CandidateDiscoveryService;
import com.spotifyyoutube.migrator.matching.engine.TrackMatchingEngine;
import com.spotifyyoutube.migrator.matching.normalization.NormalizationOrchestrator;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackRecord;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecution;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Phase 11E: Migration Execution Safety & Architectural Guardrails Test")
class MigrationExecutionSafetyTest {

    @Test
    @DisplayName("Reflection Safety: DefaultMigrationExecutionStateService holds zero matching or discovery pipeline dependencies")
    void executionStateServiceHoldsZeroPipelineDependencies() {
        Field[] fields = DefaultMigrationExecutionStateService.class.getDeclaredFields();
        for (Field field : fields) {
            assertThat(field.getType())
                    .as("Execution state service field '%s' must not be TrackMatchingEngine, NormalizationOrchestrator, or CandidateDiscoveryService", field.getName())
                    .isNotEqualTo(TrackMatchingEngine.class)
                    .isNotEqualTo(NormalizationOrchestrator.class)
                    .isNotEqualTo(CandidateDiscoveryService.class);
        }
    }

    @Test
    @DisplayName("Phase 11E Boundary Safety: Service methods must be strictly synchronous (no @Async or CompletableFuture return types)")
    void executionStateServiceMustBeStrictlySynchronous() {
        Method[] methods = DefaultMigrationExecutionStateService.class.getDeclaredMethods();
        for (Method method : methods) {
            assertThat(method.isAnnotationPresent(Async.class))
                    .as("Method '%s' must not be annotated with @Async in Phase 11E", method.getName())
                    .isFalse();

            assertThat(method.isAnnotationPresent(Scheduled.class))
                    .as("Method '%s' must not be annotated with @Scheduled in Phase 11E", method.getName())
                    .isFalse();

            assertThat(CompletableFuture.class.isAssignableFrom(method.getReturnType()))
                    .as("Method '%s' must not return CompletableFuture in Phase 11E", method.getName())
                    .isFalse();

            assertThat(Future.class.isAssignableFrom(method.getReturnType()))
                    .as("Method '%s' must not return Future in Phase 11E", method.getName())
                    .isFalse();
        }
    }

    @Test
    @DisplayName("Entity Safety: MigrationExecution and ExecutionTrackRecord are JPA Entities with @Version for optimistic locking")
    void entitiesHaveOptimisticLocking() throws Exception {
        assertThat(MigrationExecution.class.isAnnotationPresent(Entity.class)).isTrue();
        assertThat(MigrationExecution.class.getAnnotation(Table.class).name()).isEqualTo("migration_executions");

        Field execVersion = MigrationExecution.class.getDeclaredField("version");
        assertThat(execVersion.isAnnotationPresent(Version.class))
                .as("MigrationExecution must declare @Version for optimistic locking")
                .isTrue();

        assertThat(ExecutionTrackRecord.class.isAnnotationPresent(Entity.class)).isTrue();
        assertThat(ExecutionTrackRecord.class.getAnnotation(Table.class).name()).isEqualTo("execution_track_records");

        Field trackVersion = ExecutionTrackRecord.class.getDeclaredField("version");
        assertThat(trackVersion.isAnnotationPresent(Version.class))
                .as("ExecutionTrackRecord must declare @Version for optimistic locking")
                .isTrue();
    }

    @Test
    @DisplayName("ADR-022 Transient Plan Safety: MigrationExecution must only reference planId, never store MigrationPlan entity")
    void doesNotPersistMigrationPlanEntity() {
        Field[] fields = MigrationExecution.class.getDeclaredFields();
        for (Field field : fields) {
            assertThat(field.getType().getSimpleName())
                    .as("MigrationExecution field '%s' must not reference MigrationPlan entity directly", field.getName())
                    .isNotEqualTo("MigrationPlan");
        }
    }
}
