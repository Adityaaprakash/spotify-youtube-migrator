package com.spotifyyoutube.migrator.migration.reliability.policy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Phase 13C: RetryBudget Unit Tests")
class RetryBudgetTest {

    @Test
    @DisplayName("Initializes correctly on attempt 1 with remaining attempts")
    void initialAttemptAccounting() {
        RetryBudget budget = RetryBudget.initial(3);

        assertThat(budget.currentAttempt()).isEqualTo(1);
        assertThat(budget.totalAttempts()).isEqualTo(3);
        assertThat(budget.isInitialAttempt()).isTrue();
        assertThat(budget.hasRemainingAttempts()).isTrue();
        assertThat(budget.isExhausted()).isFalse();
        assertThat(budget.remainingAttempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("Handles single-attempt budget (maxAttempts = 1) without retry allowance")
    void singleAttemptBudget() {
        RetryBudget budget = RetryBudget.initial(1);

        assertThat(budget.currentAttempt()).isEqualTo(1);
        assertThat(budget.totalAttempts()).isEqualTo(1);
        assertThat(budget.isInitialAttempt()).isTrue();
        assertThat(budget.hasRemainingAttempts()).isFalse();
        assertThat(budget.isExhausted()).isTrue();
        assertThat(budget.remainingAttempts()).isEqualTo(0);
    }

    @Test
    @DisplayName("Tracks attempt lifecycle for standard maxAttempts = 3")
    void standardThreeAttemptBudgetLifecycle() {
        // Initial attempt (Attempt 1 of 3)
        RetryBudget attempt1 = RetryBudget.initial(3);
        assertThat(attempt1.isInitialAttempt()).isTrue();
        assertThat(attempt1.hasRemainingAttempts()).isTrue();
        assertThat(attempt1.remainingAttempts()).isEqualTo(2);
        assertThat(attempt1.isExhausted()).isFalse();

        // 1st retry (Attempt 2 of 3)
        RetryBudget attempt2 = attempt1.nextAttempt();
        assertThat(attempt2.currentAttempt()).isEqualTo(2);
        assertThat(attempt2.isInitialAttempt()).isFalse();
        assertThat(attempt2.hasRemainingAttempts()).isTrue();
        assertThat(attempt2.remainingAttempts()).isEqualTo(1);
        assertThat(attempt2.isExhausted()).isFalse();

        // 2nd and final retry (Attempt 3 of 3)
        RetryBudget attempt3 = attempt2.nextAttempt();
        assertThat(attempt3.currentAttempt()).isEqualTo(3);
        assertThat(attempt3.isInitialAttempt()).isFalse();
        assertThat(attempt3.hasRemainingAttempts()).isFalse();
        assertThat(attempt3.remainingAttempts()).isEqualTo(0);
        assertThat(attempt3.isExhausted()).isTrue();

        // Further attempt beyond total budget
        RetryBudget attempt4 = attempt3.nextAttempt();
        assertThat(attempt4.currentAttempt()).isEqualTo(4);
        assertThat(attempt4.hasRemainingAttempts()).isFalse();
        assertThat(attempt4.remainingAttempts()).isEqualTo(0);
        assertThat(attempt4.isExhausted()).isTrue();
    }

    @Test
    @DisplayName("Default factory creates budget with 3 total attempts on attempt 1")
    void defaultBudgetFactory() {
        RetryBudget budget = RetryBudget.defaultBudget();

        assertThat(budget.currentAttempt()).isEqualTo(1);
        assertThat(budget.totalAttempts()).isEqualTo(3);
        assertThat(budget.isInitialAttempt()).isTrue();
        assertThat(budget.remainingAttempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("Rejects invalid configuration: currentAttempt < 1 or totalAttempts < 1")
    void rejectsInvalidConfiguration() {
        assertThatThrownBy(() -> RetryBudget.of(0, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currentAttempt must be at least 1");

        assertThatThrownBy(() -> RetryBudget.of(-1, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currentAttempt must be at least 1");

        assertThatThrownBy(() -> RetryBudget.of(1, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("totalAttempts must be at least 1");

        assertThatThrownBy(() -> RetryBudget.of(1, -2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("totalAttempts must be at least 1");

        assertThatThrownBy(() -> RetryBudget.initial(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("totalAttempts must be at least 1");
    }

    @Test
    @DisplayName("Equality and immutability contracts hold")
    void equalityAndImmutability() {
        RetryBudget b1 = RetryBudget.of(2, 5);
        RetryBudget b2 = RetryBudget.of(2, 5);
        RetryBudget b3 = RetryBudget.of(3, 5);

        assertThat(b1).isEqualTo(b2);
        assertThat(b1.hashCode()).isEqualTo(b2.hashCode());
        assertThat(b1).isNotEqualTo(b3);
    }
}
