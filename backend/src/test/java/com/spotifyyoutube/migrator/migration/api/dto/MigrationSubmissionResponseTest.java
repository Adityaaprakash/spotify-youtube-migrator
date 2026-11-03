package com.spotifyyoutube.migrator.migration.api.dto;

import com.spotifyyoutube.migrator.migration.application.execution.contract.MigrationExecutionSummary;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link MigrationSubmissionResponse} (Phase 12E).
 */
@DisplayName("Phase 12E: MigrationSubmissionResponse Test")
class MigrationSubmissionResponseTest {

    private final UUID executionId = UUID.randomUUID();
    private final UUID reviewSessionId = UUID.randomUUID();

    @Test
    @DisplayName("Creates submission response from QUEUED execution summary")
    void fromQueuedSummary() {
        MigrationExecutionSummary summary = new MigrationExecutionSummary(
                executionId,
                reviewSessionId,
                UUID.randomUUID(),
                "sp_1",
                "Source Title",
                "PL_target",
                "https://music.youtube.com/playlist?list=PL_target",
                MigrationExecutionStatus.QUEUED,
                50,
                0, 0, 0, 0, 0,
                Instant.now(),
                null,
                List.of()
        );

        MigrationSubmissionResponse response = MigrationSubmissionResponse.from(summary);

        assertThat(response.executionId()).isEqualTo(executionId);
        assertThat(response.reviewSessionId()).isEqualTo(reviewSessionId);
        assertThat(response.status()).isEqualTo(MigrationExecutionStatus.QUEUED);
        assertThat(response.totalTracks()).isEqualTo(50);
        assertThat(response.progressUrl()).isEqualTo("/api/migrations/" + executionId + "/progress");
        assertThat(response.message()).contains("accepted and queued");
        assertThat(response.submittedAt()).isNotNull();
    }

    @Test
    @DisplayName("Creates submission response from COMPLETED execution summary")
    void fromCompletedSummary() {
        MigrationExecutionSummary summary = new MigrationExecutionSummary(
                executionId,
                reviewSessionId,
                UUID.randomUUID(),
                "sp_1",
                "Source Title",
                "PL_target",
                "https://music.youtube.com/playlist?list=PL_target",
                MigrationExecutionStatus.COMPLETED,
                25,
                25, 0, 0, 0, 0,
                Instant.now().minusSeconds(60),
                Instant.now(),
                List.of()
        );

        MigrationSubmissionResponse response = MigrationSubmissionResponse.from(summary);

        assertThat(response.executionId()).isEqualTo(executionId);
        assertThat(response.status()).isEqualTo(MigrationExecutionStatus.COMPLETED);
        assertThat(response.message()).contains("already COMPLETED");
    }

    @Test
    @DisplayName("Null summary throws NullPointerException")
    void nullSummaryThrows() {
        assertThatThrownBy(() -> MigrationSubmissionResponse.from(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("MigrationExecutionSummary must not be null");
    }

    @Test
    @DisplayName("Constructor enforces non-null invariants")
    void constructorEnforcesInvariants() {
        assertThatThrownBy(() -> new MigrationSubmissionResponse(
                null,
                reviewSessionId,
                MigrationExecutionStatus.QUEUED,
                "/progress",
                "msg",
                10,
                Instant.now()
        )).isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new MigrationSubmissionResponse(
                executionId,
                reviewSessionId,
                null,
                "/progress",
                "msg",
                10,
                Instant.now()
        )).isInstanceOf(NullPointerException.class);
    }
}
