package com.spotifyyoutube.migrator.matching.benchmark.runner;

import com.spotifyyoutube.migrator.matching.benchmark.BenchmarkDatasetSplit;
import com.spotifyyoutube.migrator.matching.benchmark.BenchmarkExample;
import com.spotifyyoutube.migrator.matching.benchmark.BenchmarkTrackCategory;
import com.spotifyyoutube.migrator.matching.benchmark.BenchmarkVerificationStatus;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable evaluation policy determining which benchmark examples are eligible for formal metric scoring.
 * <p>
 * By default, formal scoring excludes {@code UNVERIFIED} records (e.g. {@code BM-0029}) and {@code DISPUTED}
 * records, while supporting filtered runs on designated splits (e.g. held-out {@code TEST} partition)
 * and diagnostic runs inspecting all records.
 */
public record BenchmarkEligibilityPolicy(
        boolean includeUnverified,
        boolean includeDisputed,
        Set<BenchmarkDatasetSplit> allowedSplits,
        Set<BenchmarkTrackCategory> allowedCategories
) {

    public BenchmarkEligibilityPolicy {
        allowedSplits = allowedSplits != null
                ? Collections.unmodifiableSet(EnumSet.copyOf(allowedSplits))
                : Collections.unmodifiableSet(EnumSet.allOf(BenchmarkDatasetSplit.class));
        allowedCategories = allowedCategories != null
                ? Collections.unmodifiableSet(EnumSet.copyOf(allowedCategories))
                : Collections.unmodifiableSet(EnumSet.allOf(BenchmarkTrackCategory.class));
    }

    /**
     * Standard formal evaluation policy: only VERIFIED examples, across all splits and categories.
     * Excludes UNVERIFIED and DISPUTED examples.
     */
    public static BenchmarkEligibilityPolicy defaultPolicy() {
        return new BenchmarkEligibilityPolicy(
                false,
                false,
                EnumSet.allOf(BenchmarkDatasetSplit.class),
                EnumSet.allOf(BenchmarkTrackCategory.class)
        );
    }

    /**
     * Diagnostic policy including UNVERIFIED and DISPUTED examples for exploratory inspection.
     */
    public static BenchmarkEligibilityPolicy diagnosticPolicy() {
        return new BenchmarkEligibilityPolicy(
                true,
                true,
                EnumSet.allOf(BenchmarkDatasetSplit.class),
                EnumSet.allOf(BenchmarkTrackCategory.class)
        );
    }

    /**
     * Policy targeting a single dataset split (e.g. held-out TEST split).
     */
    public static BenchmarkEligibilityPolicy forSplit(BenchmarkDatasetSplit split) {
        Objects.requireNonNull(split, "split must not be null");
        return new BenchmarkEligibilityPolicy(
                false,
                false,
                EnumSet.of(split),
                EnumSet.allOf(BenchmarkTrackCategory.class)
        );
    }

    /**
     * Policy targeting designated splits.
     */
    public static BenchmarkEligibilityPolicy forSplits(Set<BenchmarkDatasetSplit> splits) {
        Objects.requireNonNull(splits, "splits must not be null");
        return new BenchmarkEligibilityPolicy(
                false,
                false,
                splits,
                EnumSet.allOf(BenchmarkTrackCategory.class)
        );
    }

    /**
     * Evaluates whether an example is eligible for formal metric evaluation under this policy.
     *
     * @param example benchmark example
     * @return structured eligibility check decision
     */
    public EligibilityDecision evaluateEligibility(BenchmarkExample example) {
        if (example == null) {
            return new EligibilityDecision(false, EvaluationExclusionReason.INVALID_SOURCE_METADATA);
        }

        if (example.verificationStatus() == BenchmarkVerificationStatus.UNVERIFIED && !includeUnverified) {
            return new EligibilityDecision(false, EvaluationExclusionReason.UNVERIFIED_STATUS);
        }

        if (example.verificationStatus() == BenchmarkVerificationStatus.DISPUTED && !includeDisputed) {
            return new EligibilityDecision(false, EvaluationExclusionReason.DISPUTED_STATUS);
        }

        if (!allowedSplits.contains(example.split())) {
            return new EligibilityDecision(false, EvaluationExclusionReason.SPLIT_EXCLUDED);
        }

        if (!allowedCategories.contains(example.category())) {
            return new EligibilityDecision(false, EvaluationExclusionReason.CATEGORY_EXCLUDED);
        }

        return new EligibilityDecision(true, EvaluationExclusionReason.NONE);
    }

    /**
     * Result of an eligibility check.
     *
     * @param eligible whether the example enters formal scored metrics
     * @param reason reason for exclusion, or {@link EvaluationExclusionReason#NONE} if eligible
     */
    public record EligibilityDecision(boolean eligible, EvaluationExclusionReason reason) {}
}
