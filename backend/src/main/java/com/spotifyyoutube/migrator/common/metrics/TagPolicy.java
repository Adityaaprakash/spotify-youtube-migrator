package com.spotifyyoutube.migrator.common.metrics;

import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;

import java.util.regex.Pattern;

/**
 * Enforces cardinality boundaries and safety checks on metric tags.
 *
 * <p>Prevents unbounded time-series explosion by disallowing prohibited keys,
 * oversized values, UUIDs, URLs, emails, and malformed tags.
 */
public final class TagPolicy {

    public static final int MAX_VALUE_LENGTH = 64;

    private static final Pattern KEY_PATTERN = Pattern.compile("^[a-z0-9_.-]+$");
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern URL_PATTERN = Pattern.compile("^https?://.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private TagPolicy() {
        // Prevent instantiation of utility class
    }

    /**
     * Validates a tag key and value pair against low-cardinality policies.
     *
     * @param key   the tag key
     * @param value the tag value
     * @throws IllegalArgumentException if the key or value violates cardinality or formatting rules
     */
    public static void validateTag(String key, String value) {
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("Metric tag key must not be null or blank");
        }

        String trimmedKey = key.trim();
        if (TagKeys.isProhibited(trimmedKey)) {
            throw new IllegalArgumentException(
                    "Metric tag key '" + trimmedKey + "' is prohibited due to high cardinality risk");
        }

        if (!KEY_PATTERN.matcher(trimmedKey).matches()) {
            throw new IllegalArgumentException(
                    "Metric tag key '" + trimmedKey + "' contains invalid characters. Must match [a-z0-9_.-]+");
        }

        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "Metric tag value for key '" + trimmedKey + "' must not be null or blank");
        }

        String trimmedValue = value.trim();
        if (trimmedValue.length() > MAX_VALUE_LENGTH) {
            throw new IllegalArgumentException(
                    "Metric tag value for key '" + trimmedKey + "' exceeds maximum allowed length of "
                            + MAX_VALUE_LENGTH + " characters");
        }

        if (UUID_PATTERN.matcher(trimmedValue).matches()) {
            throw new IllegalArgumentException(
                    "Metric tag value for key '" + trimmedKey + "' is a UUID, which causes high cardinality");
        }

        if (URL_PATTERN.matcher(trimmedValue).matches()) {
            throw new IllegalArgumentException(
                    "Metric tag value for key '" + trimmedKey + "' is a URL, which causes high cardinality");
        }

        if (EMAIL_PATTERN.matcher(trimmedValue).matches()) {
            throw new IllegalArgumentException(
                    "Metric tag value for key '" + trimmedKey + "' is an email address, which causes high cardinality");
        }
    }

    /**
     * Validates all tags in a {@link Tags} collection.
     *
     * @param tags the tags to validate
     */
    public static void validateTags(Tags tags) {
        if (tags != null) {
            for (Tag tag : tags) {
                validateTag(tag.getKey(), tag.getValue());
            }
        }
    }

    /**
     * Creates and validates a single {@link Tags} instance.
     *
     * @param key   the tag key
     * @param value the tag value
     * @return validated {@link Tags}
     */
    public static Tags tag(String key, String value) {
        validateTag(key, value);
        return Tags.of(key.trim(), value.trim());
    }

    /**
     * Creates and validates a {@link Tags} instance from key-value pairs.
     *
     * @param keyValues alternating key-value strings
     * @return validated {@link Tags}
     */
    public static Tags tags(String... keyValues) {
        if (keyValues == null || keyValues.length == 0) {
            return Tags.empty();
        }
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("Tag key-value arguments must be supplied in even pairs");
        }
        for (int i = 0; i < keyValues.length; i += 2) {
            validateTag(keyValues[i], keyValues[i + 1]);
        }
        return Tags.of(keyValues);
    }
}
