package com.spotifyyoutube.migrator.migration.progress.domain;

import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link MigrationProgressSnapshot} mathematical invariants and calculations (Phase 12D).
 */
class MigrationProgressSnapshotTest {

    private final UUID executionId = UUID.randomUUID();

    @Nested
    @DisplayName("Progress Calculation Scenarios")
    class CalculationScenarios {

        @Test
        @DisplayName("1. All tracks PENDING -> 0 processed, 0% progress")
        void allTracksPending() {
            MigrationProgressSnapshot snapshot = MigrationProgressSnapshot.calculate(
                    executionId,
                    MigrationExecutionStatus.QUEUED,
                    100, // total
                    100, // pending
                    0,   // adding
                    0,   // added
                    0,   // failed
                    0,   // uncertain
                    0,   // skipped
                    0,   // rollingBack
                    0    // rolledBack
            );

            assertThat(snapshot.processedTracks()).isEqualTo(0);
            assertThat(snapshot.remainingTracks()).isEqualTo(100);
            assertThat(snapshot.progressPercentage()).isEqualTo(0.0);
            assertThat(snapshot.executionStatus()).isEqualTo(MigrationExecutionStatus.QUEUED);
        }

        @Test
        @DisplayName("2. All tracks ADDED -> 100% progress, 0 remaining")
        void allTracksAdded() {
            MigrationProgressSnapshot snapshot = MigrationProgressSnapshot.calculate(
                    executionId,
                    MigrationExecutionStatus.COMPLETED,
                    50,
                    0,
                    0,
                    50, // added
                    0,
                    0,
                    0,
                    0,
                    0
            );

            assertThat(snapshot.processedTracks()).isEqualTo(50);
            assertThat(snapshot.remainingTracks()).isEqualTo(0);
            assertThat(snapshot.progressPercentage()).isEqualTo(100.0);
            assertThat(snapshot.executionStatus()).isEqualTo(MigrationExecutionStatus.COMPLETED);
        }

        @Test
        @DisplayName("3. Mixed ADDED and FAILED -> Both count as processed")
        void mixedAddedAndFailed() {
            MigrationProgressSnapshot snapshot = MigrationProgressSnapshot.calculate(
                    executionId,
                    MigrationExecutionStatus.PARTIALLY_COMPLETED,
                    10,
                    3, // pending
                    0, // adding
                    5, // added
                    2, // failed
                    0,
                    0,
                    0,
                    0
            );

            assertThat(snapshot.processedTracks()).isEqualTo(7); // 5 + 2
            assertThat(snapshot.remainingTracks()).isEqualTo(3);  // 10 - 7
            assertThat(snapshot.progressPercentage()).isEqualTo(70.0);
        }

        @Test
        @DisplayName("4. UNCERTAIN outcomes -> Count as processed outcomes")
        void uncertainOutcomes() {
            MigrationProgressSnapshot snapshot = MigrationProgressSnapshot.calculate(
                    executionId,
                    MigrationExecutionStatus.RECOVERY_REQUIRED,
                    20,
                    15,
                    0,
                    3,
                    0,
                    2, // uncertain
                    0,
                    0,
                    0
            );

            assertThat(snapshot.processedTracks()).isEqualTo(5); // 3 + 2
            assertThat(snapshot.remainingTracks()).isEqualTo(15);
            assertThat(snapshot.progressPercentage()).isEqualTo(25.0);
        }

        @Test
        @DisplayName("5. SKIPPED tracks -> Count as processed outcomes")
        void skippedTracks() {
            MigrationProgressSnapshot snapshot = MigrationProgressSnapshot.calculate(
                    executionId,
                    MigrationExecutionStatus.PARTIALLY_COMPLETED,
                    100,
                    0,
                    0,
                    40, // added
                    1,  // failed
                    0,  // uncertain
                    59, // skipped
                    0,
                    0
            );

            assertThat(snapshot.processedTracks()).isEqualTo(100); // 40 + 1 + 59
            assertThat(snapshot.remainingTracks()).isEqualTo(0);
            assertThat(snapshot.progressPercentage()).isEqualTo(100.0);
        }

        @Test
        @DisplayName("6. ADDING tracks -> In-flight tracks do NOT count as processed")
        void addingTracks() {
            MigrationProgressSnapshot snapshot = MigrationProgressSnapshot.calculate(
                    executionId,
                    MigrationExecutionStatus.RUNNING,
                    10,
                    4, // pending
                    1, // adding (in-flight)
                    5, // added
                    0,
                    0,
                    0,
                    0,
                    0
            );

            assertThat(snapshot.addingTracks()).isEqualTo(1);
            assertThat(snapshot.processedTracks()).isEqualTo(5); // only added
            assertThat(snapshot.remainingTracks()).isEqualTo(5);  // 10 - 5
            assertThat(snapshot.progressPercentage()).isEqualTo(50.0);
        }

        @Test
        @DisplayName("7. ROLLING_BACK tracks -> In-flight deletions do NOT count as processed")
        void rollingBackTracks() {
            MigrationProgressSnapshot snapshot = MigrationProgressSnapshot.calculate(
                    executionId,
                    MigrationExecutionStatus.RUNNING,
                    10,
                    0,
                    0,
                    5, // added
                    0,
                    0,
                    0,
                    2, // rollingBack (in-flight)
                    3  // rolledBack
            );

            assertThat(snapshot.rollingBackTracks()).isEqualTo(2);
            assertThat(snapshot.processedTracks()).isEqualTo(8); // 5 added + 3 rolledBack
            assertThat(snapshot.remainingTracks()).isEqualTo(2);
            assertThat(snapshot.progressPercentage()).isEqualTo(80.0);
        }

        @Test
        @DisplayName("8. ROLLED_BACK tracks -> Count as processed outcomes")
        void rolledBackTracks() {
            MigrationProgressSnapshot snapshot = MigrationProgressSnapshot.calculate(
                    executionId,
                    MigrationExecutionStatus.ROLLED_BACK,
                    5,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    5 // rolledBack
            );

            assertThat(snapshot.rolledBackTracks()).isEqualTo(5);
            assertThat(snapshot.processedTracks()).isEqualTo(5);
            assertThat(snapshot.remainingTracks()).isEqualTo(0);
            assertThat(snapshot.progressPercentage()).isEqualTo(100.0);
        }

        @Test
        @DisplayName("9. Zero-track execution -> 0.0% progress, safe denominator")
        void zeroTrackExecution() {
            MigrationProgressSnapshot snapshot = MigrationProgressSnapshot.calculate(
                    executionId,
                    MigrationExecutionStatus.INITIALIZED,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0
            );

            assertThat(snapshot.totalTracks()).isEqualTo(0);
            assertThat(snapshot.processedTracks()).isEqualTo(0);
            assertThat(snapshot.remainingTracks()).isEqualTo(0);
            assertThat(snapshot.progressPercentage()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("10. Percentage rounding -> Rounded to 1 decimal place with HALF_UP")
        void percentageRounding() {
            // 63 processed out of 87 tracks -> 72.41379... -> 72.4 (ADR-022 benchmark)
            MigrationProgressSnapshot snapshot1 = MigrationProgressSnapshot.calculate(
                    executionId,
                    MigrationExecutionStatus.RUNNING,
                    87,
                    24,
                    0,
                    60,
                    3,
                    0,
                    0,
                    0,
                    0
            );
            assertThat(snapshot1.progressPercentage()).isEqualTo(72.4);

            // 1 out of 3 -> 33.333... -> 33.3
            MigrationProgressSnapshot snapshot2 = MigrationProgressSnapshot.calculate(
                    executionId,
                    MigrationExecutionStatus.RUNNING,
                    3,
                    2,
                    0,
                    1,
                    0,
                    0,
                    0,
                    0,
                    0
            );
            assertThat(snapshot2.progressPercentage()).isEqualTo(33.3);

            // 2 out of 3 -> 66.666... -> 66.7
            MigrationProgressSnapshot snapshot3 = MigrationProgressSnapshot.calculate(
                    executionId,
                    MigrationExecutionStatus.RUNNING,
                    3,
                    1,
                    0,
                    2,
                    0,
                    0,
                    0,
                    0,
                    0
            );
            assertThat(snapshot3.progressPercentage()).isEqualTo(66.7);
        }

        @Test
        @DisplayName("11. No negative counts allowed")
        void noNegativeCounts() {
            assertThatThrownBy(() -> MigrationProgressSnapshot.calculate(
                    executionId,
                    MigrationExecutionStatus.RUNNING,
                    10,
                    -1, // negative
                    0, 0, 0, 0, 0, 0, 0
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not be negative");

            assertThatThrownBy(() -> MigrationProgressSnapshot.calculate(
                    executionId,
                    MigrationExecutionStatus.RUNNING,
                    -5, // negative total
                    0, 0, 0, 0, 0, 0, 0, 0
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("totalTracks must not be negative");
        }

        @Test
        @DisplayName("12. Count conservation violation -> Throws ProgressConsistencyException without fabricating counts")
        void countConservationViolationThrows() {
            // totalTracks is 10, but track status counts sum to 12 -> must throw ProgressConsistencyException
            assertThatThrownBy(() -> MigrationProgressSnapshot.calculate(
                    executionId,
                    MigrationExecutionStatus.COMPLETED,
                    10,
                    0,
                    0,
                    12, // 12 > 10
                    0,
                    0,
                    0,
                    0,
                    0
            )).isInstanceOf(ProgressConsistencyException.class)
                    .hasMessageContaining("Progress count conservation violation");
        }

        @Test
        @DisplayName("13. Remaining tracks calculation is non-negative and exact")
        void remainingTracksCalculation() {
            MigrationProgressSnapshot snapshot = MigrationProgressSnapshot.calculate(
                    executionId,
                    MigrationExecutionStatus.RUNNING,
                    100,
                    45,
                    0,
                    55,
                    0,
                    0,
                    0,
                    0,
                    0
            );

            assertThat(snapshot.remainingTracks()).isEqualTo(45);
        }

        @Test
        @DisplayName("14. All status counts reconcile with total tracks")
        void statusCountsReconcile() {
            int pending = 10;
            int adding = 2;
            int added = 50;
            int failed = 5;
            int uncertain = 3;
            int skipped = 15;
            int rollingBack = 1;
            int rolledBack = 4;
            int total = pending + adding + added + failed + uncertain + skipped + rollingBack + rolledBack; // 90

            MigrationProgressSnapshot snapshot = MigrationProgressSnapshot.calculate(
                    executionId,
                    MigrationExecutionStatus.RUNNING,
                    total,
                    pending,
                    adding,
                    added,
                    failed,
                    uncertain,
                    skipped,
                    rollingBack,
                    rolledBack
            );

            int sum = snapshot.pendingTracks()
                    + snapshot.addingTracks()
                    + snapshot.addedTracks()
                    + snapshot.failedTracks()
                    + snapshot.uncertainTracks()
                    + snapshot.skippedTracks()
                    + snapshot.rollingBackTracks()
                    + snapshot.rolledBackTracks();

            assertThat(sum).isEqualTo(snapshot.totalTracks());
            assertThat(snapshot.processedTracks()).isEqualTo(added + failed + uncertain + skipped + rolledBack); // 77
            assertThat(snapshot.remainingTracks()).isEqualTo(13); // 90 - 77 = 13 (pending 10 + adding 2 + rollingBack 1)
        }
    }

    @Nested
    @DisplayName("Validation and Immutability Invariants")
    class ValidationInvariants {

        @Test
        @DisplayName("Null executionId throws NullPointerException")
        void nullExecutionId() {
            assertThatThrownBy(() -> MigrationProgressSnapshot.calculate(
                    null,
                    MigrationExecutionStatus.RUNNING,
                    10, 0, 0, 0, 0, 0, 0, 0, 0
            )).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Null executionStatus throws NullPointerException")
        void nullExecutionStatus() {
            assertThatThrownBy(() -> MigrationProgressSnapshot.calculate(
                    executionId,
                    null,
                    10, 0, 0, 0, 0, 0, 0, 0, 0
            )).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Direct constructor rejects NaN or Infinite progress percentage")
        void rejectsNanAndInfinite() {
            assertThatThrownBy(() -> new MigrationProgressSnapshot(
                    executionId,
                    MigrationExecutionStatus.RUNNING,
                    10, 5, 0, 5, 0, 0, 0, 0, 0, 5, 5,
                    Double.NaN
            )).isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> new MigrationProgressSnapshot(
                    executionId,
                    MigrationExecutionStatus.RUNNING,
                    10, 5, 0, 5, 0, 0, 0, 0, 0, 5, 5,
                    Double.POSITIVE_INFINITY
            )).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Direct constructor rejects count conservation violation (total != sum)")
        void constructorRejectsCountConservationViolation() {
            assertThatThrownBy(() -> new MigrationProgressSnapshot(
                    executionId,
                    MigrationExecutionStatus.RUNNING,
                    10, // total = 10
                    4, 0, 4, 0, 0, 0, 0, 0, // sum of states = 8
                    4, 6, 40.0
            )).isInstanceOf(ProgressConsistencyException.class)
                    .hasMessageContaining("Progress count conservation violation");
        }

        @Test
        @DisplayName("Direct constructor rejects processed tracks mismatch")
        void constructorRejectsProcessedTracksMismatch() {
            assertThatThrownBy(() -> new MigrationProgressSnapshot(
                    executionId,
                    MigrationExecutionStatus.RUNNING,
                    10,
                    5, 0, 5, 0, 0, 0, 0, 0, // sum = 10, expected processed = 5
                    6, // fabricated processedTracks = 6
                    4, 50.0
            )).isInstanceOf(ProgressConsistencyException.class)
                    .hasMessageContaining("Processed tracks mismatch");
        }

        @Test
        @DisplayName("Direct constructor rejects remaining tracks mismatch")
        void constructorRejectsRemainingTracksMismatch() {
            assertThatThrownBy(() -> new MigrationProgressSnapshot(
                    executionId,
                    MigrationExecutionStatus.RUNNING,
                    10,
                    5, 0, 5, 0, 0, 0, 0, 0, // sum = 10, processed = 5, expected remaining = 5
                    5,
                    3, // fabricated remainingTracks = 3
                    50.0
            )).isInstanceOf(ProgressConsistencyException.class)
                    .hasMessageContaining("Remaining tracks mismatch");
        }
    }
}
