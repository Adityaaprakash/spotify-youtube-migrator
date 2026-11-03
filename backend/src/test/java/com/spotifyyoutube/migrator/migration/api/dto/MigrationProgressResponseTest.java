package com.spotifyyoutube.migrator.migration.api.dto;

import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus;
import com.spotifyyoutube.migrator.migration.progress.domain.MigrationProgressSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link MigrationProgressResponse} (Phase 12E).
 */
@DisplayName("Phase 12E: MigrationProgressResponse Test")
class MigrationProgressResponseTest {

    private final UUID executionId = UUID.randomUUID();

    @Test
    @DisplayName("Maps 1-to-1 from MigrationProgressSnapshot without recalculation")
    void mapsFromSnapshot() {
        MigrationProgressSnapshot snapshot = MigrationProgressSnapshot.calculate(
                executionId,
                MigrationExecutionStatus.RUNNING,
                87,
                24,
                1,
                55,
                4,
                2,
                1,
                0,
                0
        );

        MigrationProgressResponse response = MigrationProgressResponse.from(snapshot);

        assertThat(response.executionId()).isEqualTo(executionId);
        assertThat(response.status()).isEqualTo(MigrationExecutionStatus.RUNNING);
        assertThat(response.totalTracks()).isEqualTo(87);
        assertThat(response.pendingTracks()).isEqualTo(24);
        assertThat(response.addingTracks()).isEqualTo(1);
        assertThat(response.addedTracks()).isEqualTo(55);
        assertThat(response.failedTracks()).isEqualTo(4);
        assertThat(response.uncertainTracks()).isEqualTo(2);
        assertThat(response.skippedTracks()).isEqualTo(1);
        assertThat(response.rollingBackTracks()).isEqualTo(0);
        assertThat(response.rolledBackTracks()).isEqualTo(0);
        assertThat(response.processedTracks()).isEqualTo(snapshot.processedTracks());
        assertThat(response.remainingTracks()).isEqualTo(snapshot.remainingTracks());
        assertThat(response.progressPercentage()).isEqualTo(snapshot.progressPercentage());
    }

    @Test
    @DisplayName("Null snapshot throws NullPointerException")
    void nullSnapshotThrows() {
        assertThatThrownBy(() -> MigrationProgressResponse.from(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("MigrationProgressSnapshot must not be null");
    }

    @Test
    @DisplayName("Constructor enforces non-null invariants")
    void constructorEnforcesInvariants() {
        assertThatThrownBy(() -> new MigrationProgressResponse(
                null,
                MigrationExecutionStatus.RUNNING,
                10, 0, 0, 10, 0, 0, 0, 0, 0, 10, 0, 100.0
        )).isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new MigrationProgressResponse(
                executionId,
                null,
                10, 0, 0, 10, 0, 0, 0, 0, 0, 10, 0, 100.0
        )).isInstanceOf(NullPointerException.class);
    }
}
