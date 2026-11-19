package com.spotifyyoutube.migrator.migration.metrics;

import com.spotifyyoutube.migrator.common.metrics.MetricNames;
import com.spotifyyoutube.migrator.common.metrics.MetricsRegistrationHelper;
import com.spotifyyoutube.migrator.common.metrics.TagKeys;
import com.spotifyyoutube.migrator.common.metrics.TagPolicy;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Application metrics service for recording business events in playlist migration and track matching workflows.
 *
 * <p>Enforces low-cardinality tagging policy via {@link TagPolicy} and {@link TagKeys}, and provides
 * in-memory idempotency protections to prevent duplicate metric increments during retries, repeated callbacks,
 * or progress polling.
 */
@Component
public class MigrationMetrics {

    private static final Logger log = LoggerFactory.getLogger(MigrationMetrics.class);

    private final MeterRegistry registry;

    // In-memory sets to guarantee exactly-once recording within application lifecycle
    private final Set<UUID> startedExecutions = ConcurrentHashMap.newKeySet();
    private final Set<UUID> completedExecutions = ConcurrentHashMap.newKeySet();
    private final Set<UUID> failedExecutions = ConcurrentHashMap.newKeySet();
    private final Set<UUID> durationRecordedExecutions = ConcurrentHashMap.newKeySet();

    @Autowired
    public MigrationMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "MeterRegistry must not be null");
    }

    /**
     * Creates a standalone MigrationMetrics instance backed by a new {@link SimpleMeterRegistry}.
     * Useful for isolated unit testing.
     *
     * @return a new MigrationMetrics instance
     */
    public static MigrationMetrics noop() {
        return new MigrationMetrics(new SimpleMeterRegistry());
    }

    /**
     * Records the genuine initiation of a migration execution.
     *
     * @param executionId durable execution ID (or null for untracked/anonymous runs)
     * @param mode        execution trigger mode (e.g. "manual", "automated")
     */
    public void recordMigrationStarted(UUID executionId, String mode) {
        if (executionId != null && !startedExecutions.add(executionId)) {
            log.debug("Migration execution {} already recorded as started; skipping duplicate metric increment", executionId);
            return;
        }

        String safeMode = normalizeMode(mode);
        Tags tags = TagPolicy.tags(TagKeys.TRIGGER_MODE, safeMode);

        Counter counter = MetricsRegistrationHelper.counter(
                registry,
                MetricNames.MIGRATION_STARTED,
                "Total number of migration jobs initiated",
                tags
        );
        counter.increment();
    }

    /**
     * Convenience overload for recording migration start without an execution ID.
     */
    public void recordMigrationStarted(String mode) {
        recordMigrationStarted(null, mode);
    }

    /**
     * Records the successful terminal completion of a migration execution.
     *
     * @param executionId durable execution ID (or null for untracked/anonymous runs)
     * @param outcome     terminal outcome (e.g. "completed", "partially_completed")
     * @param mode        execution trigger mode (e.g. "manual", "automated")
     * @param duration    elapsed migration execution duration (optional)
     */
    public void recordMigrationCompleted(UUID executionId, String outcome, String mode, Duration duration) {
        if (executionId != null) {
            if (failedExecutions.contains(executionId) || !completedExecutions.add(executionId)) {
                log.debug("Migration execution {} already recorded as terminal; skipping duplicate metric increment", executionId);
                return;
            }
        }

        String safeOutcome = normalizeOutcome(outcome, "completed");
        String safeMode = normalizeMode(mode);
        Tags tags = TagPolicy.tags(TagKeys.OUTCOME, safeOutcome, TagKeys.TRIGGER_MODE, safeMode);

        Counter counter = MetricsRegistrationHelper.counter(
                registry,
                MetricNames.MIGRATION_COMPLETED,
                "Total number of migration jobs that completed successfully",
                tags
        );
        counter.increment();

        if (duration != null) {
            recordMigrationDuration(executionId, duration, safeOutcome, safeMode);
        }
    }

    /**
     * Convenience overload for recording migration completion without an execution ID.
     */
    public void recordMigrationCompleted(String outcome, String mode, Duration duration) {
        recordMigrationCompleted(null, outcome, mode, duration);
    }

    /**
     * Records the terminal failure of a migration execution.
     *
     * @param executionId   durable execution ID (or null for untracked/anonymous runs)
     * @param errorCategory categorized failure reason (e.g. "server_error", "rate_limit")
     * @param outcome       terminal outcome (e.g. "failed")
     * @param duration      elapsed migration duration prior to failure (optional)
     */
    public void recordMigrationFailed(UUID executionId, String errorCategory, String outcome, Duration duration) {
        if (executionId != null) {
            if (completedExecutions.contains(executionId) || !failedExecutions.add(executionId)) {
                log.debug("Migration execution {} already recorded as terminal; skipping duplicate metric increment", executionId);
                return;
            }
        }

        String safeErrorCategory = normalizeErrorCategory(errorCategory);
        String safeOutcome = normalizeOutcome(outcome, "failed");
        Tags tags = TagPolicy.tags(TagKeys.ERROR_CATEGORY, safeErrorCategory, TagKeys.OUTCOME, safeOutcome);

        Counter counter = MetricsRegistrationHelper.counter(
                registry,
                MetricNames.MIGRATION_FAILED,
                "Total number of migration jobs that failed",
                tags
        );
        counter.increment();

        if (duration != null) {
            recordMigrationDuration(executionId, duration, safeOutcome, "manual");
        }
    }

    /**
     * Convenience overload for recording migration failure without an execution ID.
     */
    public void recordMigrationFailed(String errorCategory, String outcome, Duration duration) {
        recordMigrationFailed(null, errorCategory, outcome, duration);
    }

    /**
     * Records the execution duration of a migration upon reaching terminal outcome.
     *
     * @param executionId durable execution ID (or null for untracked/anonymous runs)
     * @param duration    measured execution duration
     * @param outcome     outcome of the migration (e.g. "completed", "failed", "partially_completed")
     * @param mode        execution trigger mode (e.g. "manual", "automated")
     */
    public void recordMigrationDuration(UUID executionId, Duration duration, String outcome, String mode) {
        if (duration == null) {
            return;
        }
        if (executionId != null && !durationRecordedExecutions.add(executionId)) {
            log.debug("Duration for migration execution {} already recorded; skipping duplicate timer record", executionId);
            return;
        }

        Duration safeDuration = duration.isNegative() ? Duration.ZERO : duration;
        String safeOutcome = normalizeOutcome(outcome, "completed");
        String safeMode = normalizeMode(mode);
        Tags tags = TagPolicy.tags(TagKeys.OUTCOME, safeOutcome, TagKeys.TRIGGER_MODE, safeMode);

        Timer timer = MetricsRegistrationHelper.timer(
                registry,
                MetricNames.MIGRATION_DURATION,
                "Total duration of migration executions from initiation to completion",
                tags
        );
        timer.record(safeDuration);
    }

    /**
     * Convenience overload for recording migration duration without an execution ID.
     */
    public void recordMigrationDuration(Duration duration, String outcome, String mode) {
        recordMigrationDuration(null, duration, outcome, mode);
    }

    /**
     * Records a successful track match decision.
     *
     * @param confidence qualitative confidence level ("excellent", "good", "poor")
     */
    public void recordTrackMatched(String confidence) {
        String safeConfidence = normalizeConfidence(confidence);
        Tags tags = TagPolicy.tags(
                TagKeys.DECISION, "matched",
                TagKeys.CONFIDENCE, safeConfidence
        );

        Counter counter = MetricsRegistrationHelper.counter(
                registry,
                MetricNames.TRACKS_MATCHED,
                "Total number of tracks successfully matched",
                tags
        );
        counter.increment();
    }

    /**
     * Records an unmatched track decision where no acceptable destination candidate was found.
     */
    public void recordTrackUnmatched() {
        Tags tags = TagPolicy.tags(TagKeys.DECISION, "unmatched");

        Counter counter = MetricsRegistrationHelper.counter(
                registry,
                MetricNames.TRACKS_UNMATCHED,
                "Total number of tracks that could not be matched",
                tags
        );
        counter.increment();
    }

    /**
     * Records a valid final composite matching score in the distribution summary.
     *
     * @param score      numerical composite score (must be in [0.0, 1.0])
     * @param decision   matching decision (e.g. "matched", "review_required", "no_match")
     * @param confidence qualitative confidence level ("excellent", "good", "poor")
     */
    public void recordMatchingScore(double score, String decision, String confidence) {
        if (Double.isNaN(score) || Double.isInfinite(score) || score < 0.0 || score > 1.0) {
            log.debug("Skipping invalid matching score {}: must be finite and within [0.0, 1.0]", score);
            return;
        }

        String safeDecision = normalizeDecision(decision);
        String safeConfidence = normalizeConfidence(confidence);
        Tags tags = TagPolicy.tags(
                TagKeys.DECISION, safeDecision,
                TagKeys.CONFIDENCE, safeConfidence
        );

        DistributionSummary summary = MetricsRegistrationHelper.distributionSummary(
                registry,
                MetricNames.MATCHING_SCORE,
                "Statistical distribution of track match confidence scores",
                tags,
                0.01,
                1.0
        );
        summary.record(score);
    }

    /**
     * Classifies a free-form failure summary or exception message into a safe, bounded low-cardinality category.
     *
     * @param failureSummary optional failure message
     * @return bounded error category string
     */
    public static String classifyErrorCategory(String failureSummary) {
        if (failureSummary == null || failureSummary.isBlank()) {
            return "unknown_error";
        }
        String lower = failureSummary.toLowerCase(Locale.ROOT);
        if (lower.contains("rate limit") || lower.contains("429")) {
            return "rate_limit";
        }
        if (lower.contains("quota")) {
            return "quota_exceeded";
        }
        if (lower.contains("timeout") || lower.contains("connection") || lower.contains("network")) {
            return "network_error";
        }
        if (lower.contains("unauthorized") || lower.contains("401") || lower.contains("403") || lower.contains("auth")) {
            return "auth_error";
        }
        if (lower.contains("validation") || lower.contains("invalid")) {
            return "validation_error";
        }
        return "server_error";
    }

    /**
     * Resets in-memory deduplication state. Useful in test fixtures.
     */
    public void clearDeduplicationState() {
        startedExecutions.clear();
        completedExecutions.clear();
        failedExecutions.clear();
        durationRecordedExecutions.clear();
    }

    // --- Normalization Helpers ---

    private String normalizeMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return "manual";
        }
        String trimmed = mode.trim().toLowerCase(Locale.ROOT);
        if ("automated".equals(trimmed) || "manual".equals(trimmed) || "initial".equals(trimmed) || "resumed".equals(trimmed)) {
            return trimmed;
        }
        return "manual";
    }

    private String normalizeOutcome(String outcome, String fallback) {
        if (outcome == null || outcome.isBlank()) {
            return fallback;
        }
        String trimmed = outcome.trim().toLowerCase(Locale.ROOT);
        if ("completed".equals(trimmed) || "partially_completed".equals(trimmed) || "failed".equals(trimmed)) {
            return trimmed;
        }
        return fallback;
    }

    private String normalizeErrorCategory(String category) {
        if (category == null || category.isBlank()) {
            return "unknown_error";
        }
        String trimmed = category.trim().toLowerCase(Locale.ROOT);
        return classifyErrorCategory(trimmed);
    }

    private String normalizeConfidence(String confidence) {
        if (confidence == null || confidence.isBlank()) {
            return "good";
        }
        String trimmed = confidence.trim().toLowerCase(Locale.ROOT);
        if ("excellent".equals(trimmed) || "good".equals(trimmed) || "poor".equals(trimmed)
                || "high".equals(trimmed) || "medium".equals(trimmed) || "low".equals(trimmed)) {
            return trimmed;
        }
        return "good";
    }

    private String normalizeDecision(String decision) {
        if (decision == null || decision.isBlank()) {
            return "matched";
        }
        String trimmed = decision.trim().toLowerCase(Locale.ROOT);
        if ("matched".equals(trimmed) || "unmatched".equals(trimmed) || "review_required".equals(trimmed) || "no_match".equals(trimmed)) {
            return trimmed;
        }
        return "matched";
    }
}
