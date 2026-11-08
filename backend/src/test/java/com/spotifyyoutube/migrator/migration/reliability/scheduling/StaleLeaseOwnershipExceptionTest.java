package com.spotifyyoutube.migrator.migration.reliability.scheduling;

import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Phase 13D-A: StaleLeaseOwnershipException Invariant Tests")
class StaleLeaseOwnershipExceptionTest {

    @Test
    @DisplayName("Structured constructor retains executionId, workerId, generations, and message")
    void structuredConstructor() {
        UUID execId = UUID.randomUUID();
        StaleLeaseOwnershipException ex = new StaleLeaseOwnershipException(
                execId,
                "worker-alpha",
                5L,
                6L,
                "Fencing generation 5 is stale; current is 6"
        );

        assertThat(ex).isInstanceOf(InvalidStateException.class);
        assertThat(ex.getExecutionId()).isEqualTo(execId);
        assertThat(ex.getWorkerId()).isEqualTo("worker-alpha");
        assertThat(ex.getAttemptedGeneration()).isEqualTo(5L);
        assertThat(ex.getCurrentGeneration()).isEqualTo(6L);
        assertThat(ex.getMessage()).isEqualTo("Fencing generation 5 is stale; current is 6");
    }

    @Test
    @DisplayName("Simple constructor provides safe null and zero defaults")
    void simpleConstructor() {
        StaleLeaseOwnershipException ex = new StaleLeaseOwnershipException("Generic stale lease");

        assertThat(ex.getExecutionId()).isNull();
        assertThat(ex.getWorkerId()).isNull();
        assertThat(ex.getAttemptedGeneration()).isEqualTo(0L);
        assertThat(ex.getCurrentGeneration()).isNull();
        assertThat(ex.getMessage()).isEqualTo("Generic stale lease");
    }
}
