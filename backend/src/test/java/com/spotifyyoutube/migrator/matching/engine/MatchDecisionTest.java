package com.spotifyyoutube.migrator.matching.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MatchDecision Contract Tests")
class MatchDecisionTest {

    @Test
    @DisplayName("should contain expected decision values with distinct semantics")
    void shouldContainExpectedDecisionValues() {
        assertThat(MatchDecision.values())
                .containsExactly(
                        MatchDecision.MATCH,
                        MatchDecision.REVIEW_REQUIRED,
                        MatchDecision.NO_MATCH
                );
    }

    @Test
    @DisplayName("should support valueOf string conversion")
    void shouldSupportValueOf() {
        assertThat(MatchDecision.valueOf("MATCH")).isEqualTo(MatchDecision.MATCH);
        assertThat(MatchDecision.valueOf("REVIEW_REQUIRED")).isEqualTo(MatchDecision.REVIEW_REQUIRED);
        assertThat(MatchDecision.valueOf("NO_MATCH")).isEqualTo(MatchDecision.NO_MATCH);
    }
}
