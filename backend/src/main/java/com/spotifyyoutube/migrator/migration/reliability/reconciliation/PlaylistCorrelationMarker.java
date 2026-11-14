package com.spotifyyoutube.migrator.migration.reliability.reconciliation;

import com.spotifyyoutube.migrator.youtube.application.YouTubePlaylistService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility for formatting, appending, and parsing deterministic correlation markers in destination playlist descriptions (Phase 13F).
 * <p>
 * Correlation marker format:
 * <pre>{@code
 * [ref: migrator-op:<operation-uuid>]
 * }</pre>
 * <p>
 * Guarantees:
 * <ul>
 *   <li>Marker is generated deterministically from the persisted operation UUID.</li>
 *   <li>Original user description content is strictly preserved and not overwritten.</li>
 *   <li>Duplicate markers are rejected or avoided.</li>
 *   <li>Total description length is validated against YouTube provider maximum (5,000 characters).</li>
 *   <li>Strict regex parser validating standard 8-4-4-4-12 hex UUID representation.</li>
 * </ul>
 */
public final class PlaylistCorrelationMarker {

    public static final String MARKER_PREFIX = "[ref: migrator-op:";
    public static final String MARKER_SUFFIX = "]";

    private static final Pattern STRICT_MARKER_PATTERN = Pattern.compile(
            "\\[ref: migrator-op:([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\\]"
    );

    private PlaylistCorrelationMarker() {
        // Utility class
    }

    /**
     * Formats the exact correlation marker string for an operation UUID.
     *
     * @param operationId stable playlist creation operation UUID
     * @return formatted correlation marker string
     */
    public static String formatMarker(UUID operationId) {
        Objects.requireNonNull(operationId, "operationId must not be null");
        return MARKER_PREFIX + operationId + MARKER_SUFFIX;
    }

    /**
     * Appends the deterministic correlation marker to the user playlist description,
     * preserving all existing description content and validating against the provider limit.
     *
     * @param originalDescription original user description (may be null or blank)
     * @param operationId         stable playlist creation operation UUID
     * @return description with appended correlation marker
     * @throws IllegalArgumentException if the description combined with the marker exceeds 5,000 characters
     */
    public static String applyMarker(String originalDescription, UUID operationId) {
        Objects.requireNonNull(operationId, "operationId must not be null");
        String marker = formatMarker(operationId);

        if (originalDescription == null || originalDescription.isBlank()) {
            return marker;
        }

        String trimmed = originalDescription.trim();

        // Avoid adding duplicate marker if already present
        if (trimmed.contains(marker)) {
            return trimmed;
        }

        String combined = trimmed + "\n\n" + marker;
        if (combined.length() > YouTubePlaylistService.MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException(String.format(
                    "Playlist description length with correlation marker (%d) exceeds provider maximum limit (%d)",
                    combined.length(),
                    YouTubePlaylistService.MAX_DESCRIPTION_LENGTH
            ));
        }

        return combined;
    }

    /**
     * Extracts all valid correlation marker UUIDs present in the given description.
     *
     * @param description playlist description text (may be null)
     * @return unmodifiable list of parsed UUIDs in order of appearance
     */
    public static List<UUID> extractMarkers(String description) {
        if (description == null || description.isBlank()) {
            return Collections.emptyList();
        }

        Matcher matcher = STRICT_MARKER_PATTERN.matcher(description);
        List<UUID> markers = new ArrayList<>();
        while (matcher.find()) {
            String uuidStr = matcher.group(1);
            try {
                markers.add(UUID.fromString(uuidStr));
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed UUID strings
            }
        }
        return Collections.unmodifiableList(markers);
    }

    /**
     * Extracts the single correlation marker UUID if exactly one valid marker exists.
     * If zero or multiple markers exist, returns empty to signal absence or integrity ambiguity.
     *
     * @param description playlist description text
     * @return Optional containing the single unique operation UUID, or empty if none or multiple
     */
    public static Optional<UUID> extractSingleMarker(String description) {
        List<UUID> markers = extractMarkers(description);
        if (markers.size() == 1) {
            return Optional.of(markers.get(0));
        }
        return Optional.empty();
    }

    /**
     * Checks if the playlist description contains exactly one matching marker for the expected operation UUID.
     *
     * @param description         playlist description text
     * @param expectedOperationId expected stable operation UUID
     * @return true if exactly one marker is present and equals expectedOperationId
     */
    public static boolean matchesOperation(String description, UUID expectedOperationId) {
        if (description == null || expectedOperationId == null) {
            return false;
        }
        List<UUID> markers = extractMarkers(description);
        return markers.size() == 1 && markers.get(0).equals(expectedOperationId);
    }

    /**
     * Returns true if multiple correlation markers are detected in the description (integrity violation indicator).
     *
     * @param description playlist description text
     * @return true if 2 or more markers are found
     */
    public static boolean hasMultipleMarkers(String description) {
        return extractMarkers(description).size() > 1;
    }
}
