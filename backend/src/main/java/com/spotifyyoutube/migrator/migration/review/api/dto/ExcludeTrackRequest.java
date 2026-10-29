package com.spotifyyoutube.migrator.migration.review.api.dto;

/**
 * Request payload for explicitly excluding a track from migration.
 *
 * @param reason optional reason for excluding the track
 */
public record ExcludeTrackRequest(
        String reason
) {

    public ExcludeTrackRequest() {
        this(null);
    }
}
