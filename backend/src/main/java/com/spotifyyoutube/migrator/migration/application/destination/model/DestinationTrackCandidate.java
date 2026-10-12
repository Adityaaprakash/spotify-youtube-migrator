package com.spotifyyoutube.migrator.migration.application.destination.model;

import java.time.Duration;
import java.time.format.DateTimeParseException;

/**
 * Provider-neutral representation of a candidate track/video discovered from a music destination.
 * Holds original metadata and optional duration for subsequent candidate discovery, filtering, and matching.
 *
 * @param id provider-specific unique track or video identifier (e.g. YouTube video ID)
 * @param title original title as returned by the destination provider
 * @param channelOrArtist name of the channel, uploader, or primary artist
 * @param description video or track description, if available
 * @param thumbnailUrl URL pointing to candidate cover art or video thumbnail
 * @param publishedAt ISO-8601 publication or release timestamp string
 * @param duration parsed duration as {@link Duration}, or null if absent/unavailable from search
 */
public record DestinationTrackCandidate(
        String id,
        String title,
        String channelOrArtist,
        String description,
        String thumbnailUrl,
        String publishedAt,
        Duration duration
) {
    /**
     * Backward-compatible 6-argument constructor for search candidate instances where duration is unavailable.
     */
    public DestinationTrackCandidate(
            String id,
            String title,
            String channelOrArtist,
            String description,
            String thumbnailUrl,
            String publishedAt
    ) {
        this(id, title, channelOrArtist, description, thumbnailUrl, publishedAt, (Duration) null);
    }

    /**
     * Convenience constructor parsing an ISO-8601 duration string into {@link Duration}.
     * If the ISO-8601 duration string is null, blank, or malformed, duration is preserved as null rather than failing or defaulting to zero.
     */
    public DestinationTrackCandidate(
            String id,
            String title,
            String channelOrArtist,
            String description,
            String thumbnailUrl,
            String publishedAt,
            String isoDuration
    ) {
        this(id, title, channelOrArtist, description, thumbnailUrl, publishedAt, parseIsoDuration(isoDuration));
    }

    /**
     * Compact constructor validating duration semantics:
     * - Negative durations are rejected with IllegalArgumentException if passed directly as Duration
     * - Missing duration remains null (does not become Duration.ZERO)
     */
    public DestinationTrackCandidate {
        if (duration != null && duration.isNegative()) {
            throw new IllegalArgumentException("Duration cannot be negative: " + duration);
        }
    }

    private static Duration parseIsoDuration(String isoDuration) {
        if (isoDuration == null || isoDuration.isBlank()) {
            return null;
        }
        try {
            Duration parsed = Duration.parse(isoDuration.trim());
            return parsed.isNegative() ? null : parsed;
        } catch (DateTimeParseException | ArithmeticException ex) {
            return null;
        }
    }

    /**
     * Creates a {@link DestinationTrackCandidate} from {@link DestinationTrackDetails},
     * parsing the ISO-8601 duration string into {@link Duration}.
     */
    public static DestinationTrackCandidate fromDetails(DestinationTrackDetails details) {
        if (details == null) {
            return null;
        }
        return new DestinationTrackCandidate(
                details.id(),
                details.title(),
                details.channelOrArtist(),
                details.description(),
                details.thumbnailUrl(),
                details.publishedAt(),
                details.duration()
        );
    }
}
