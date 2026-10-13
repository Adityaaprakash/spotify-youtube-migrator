package com.spotifyyoutube.migrator.matching.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MatchConfidence Contract Tests")
class MatchConfidenceTest {

    @Test
    @DisplayName("should contain expected confidence classifications")
    void shouldContainExpectedClassifications() {
        assertThat(MatchConfidence.values())
                .containsExactly(
                        MatchConfidence.EXCELLENT,
                        MatchConfidence.GOOD,
                        MatchConfidence.POOR
                );
    }

    @Test
    @DisplayName("should support valueOf conversion")
    void shouldSupportValueOf() {
        assertThat(MatchConfidence.valueOf("EXCELLENT")).isEqualTo(MatchConfidence.EXCELLENT);
        assertThat(MatchConfidence.valueOf("GOOD")).isEqualTo(MatchConfidence.GOOD);
        assertThat(MatchConfidence.valueOf("POOR")).isEqualTo(MatchConfidence.POOR);
    }
}
