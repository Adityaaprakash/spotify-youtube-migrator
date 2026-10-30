package com.spotifyyoutube.migrator.migration.api.dto;

/**
 * API response representing an approved track selected for destination insertion.
 *
 * @param sourcePosition 0-indexed position in the original source playlist
 * @param sourceTrackId source track identifier
 * @param sourceTitle source track title
 * @param sourceArtist source track primary artist
 * @param destinationTrackId destination track/video identifier
 * @param destinationTitle destination track title
 * @param destinationChannel destination channel or uploader
 * @param destinationDurationMs destination track duration in milliseconds
 * @param decisionSource provenance of decision (AUTOMATIC vs USER_SELECTED)
 * @param targetPosition 0-indexed position in the destination playlist
 */
public record ApprovedTrackResponse(
        int sourcePosition,
        String sourceTrackId,
        String sourceTitle,
        String sourceArtist,
        String destinationTrackId,
        String destinationTitle,
        String destinationChannel,
        Long destinationDurationMs,
        String decisionSource,
        int targetPosition
) {
}
