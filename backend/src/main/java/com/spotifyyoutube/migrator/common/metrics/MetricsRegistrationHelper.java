package com.spotifyyoutube.migrator.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

import java.util.Objects;

/**
 * Generic registration utility for constructing and binding Micrometer meters.
 *
 * <p>Enforces tag validation via {@link TagPolicy} prior to meter registration in
 * the dependency-injected {@link MeterRegistry}.
 */
public final class MetricsRegistrationHelper {

    private MetricsRegistrationHelper() {
        // Prevent instantiation of utility class
    }

    /**
     * Registers or retrieves a {@link Counter} with tag validation.
     *
     * @param registry    the injected meter registry
     * @param name        the metric name
     * @param description documentation description
     * @param tags        metric tags
     * @return registered Counter
     */
    public static Counter counter(MeterRegistry registry, String name, String description, Tags tags) {
        Objects.requireNonNull(registry, "MeterRegistry must not be null");
        validateName(name);
        TagPolicy.validateTags(tags);

        Counter.Builder builder = Counter.builder(name.trim());
        if (description != null && !description.isBlank()) {
            builder.description(description.trim());
        }
        if (tags != null) {
            builder.tags(tags);
        }
        return builder.register(registry);
    }

    /**
     * Registers or retrieves a {@link Timer} with tag validation.
     *
     * @param registry    the injected meter registry
     * @param name        the metric name
     * @param description documentation description
     * @param tags        metric tags
     * @return registered Timer
     */
    public static Timer timer(MeterRegistry registry, String name, String description, Tags tags) {
        Objects.requireNonNull(registry, "MeterRegistry must not be null");
        validateName(name);
        TagPolicy.validateTags(tags);

        Timer.Builder builder = Timer.builder(name.trim());
        if (description != null && !description.isBlank()) {
            builder.description(description.trim());
        }
        if (tags != null) {
            builder.tags(tags);
        }
        return builder.register(registry);
    }

    /**
     * Registers or retrieves a {@link DistributionSummary} with expected bounds and tag validation.
     *
     * @param registry             the injected meter registry
     * @param name                 the metric name
     * @param description          documentation description
     * @param tags                 metric tags
     * @param minimumExpectedValue minimum expected value
     * @param maximumExpectedValue maximum expected value
     * @return registered DistributionSummary
     */
    public static DistributionSummary distributionSummary(
            MeterRegistry registry,
            String name,
            String description,
            Tags tags,
            double minimumExpectedValue,
            double maximumExpectedValue) {
        Objects.requireNonNull(registry, "MeterRegistry must not be null");
        validateName(name);
        TagPolicy.validateTags(tags);

        DistributionSummary.Builder builder = DistributionSummary.builder(name.trim());
        if (minimumExpectedValue > 0) {
            builder.minimumExpectedValue(minimumExpectedValue);
        }
        if (maximumExpectedValue > 0) {
            builder.maximumExpectedValue(maximumExpectedValue);
        }

        if (description != null && !description.isBlank()) {
            builder.description(description.trim());
        }
        if (tags != null) {
            builder.tags(tags);
        }
        return builder.register(registry);
    }

    private static void validateName(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Metric name must not be null or blank");
        }
    }
}
