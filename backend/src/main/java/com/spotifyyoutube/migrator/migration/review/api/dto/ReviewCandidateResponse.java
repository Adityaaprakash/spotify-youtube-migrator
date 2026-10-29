package com.spotifyyoutube.migrator.migration.review.api.dto;

import java.time.Duration;

/**
 * Provider-neutral API representation of a destination candidate track/video.
 *
 * @param id provider video or track identifier (e.g. YouTube video ID)
 * @param title candidate title
 * @param channelOrArtist candidate uploader channel or primary artist
 * @param durationMs candidate duration in milliseconds, or null if unknown
 * @param duration parsed duration as {@link Duration}, or null if unknown
 */
public record ReviewCandidateResponse(
        String id,
        String title,
        String channelOrArtist,
        Long durationMs,
        Duration duration
) {

    public ReviewCandidateResponse(String id, String title, String channelOrArtist, Long durationMs) {
        this(id, title, channelOrArtist, durationMs, durationMs != null ? Duration.ofMillis(durationMs) : null);
    }
}
