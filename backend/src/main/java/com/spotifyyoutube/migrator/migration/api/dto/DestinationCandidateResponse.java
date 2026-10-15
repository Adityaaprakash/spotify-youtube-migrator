package com.spotifyyoutube.migrator.migration.api.dto;

import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationTrackCandidate;

import java.time.Duration;

/**
 * API response representing a destination candidate track/video.
 *
 * @param id unique provider video identifier (e.g. YouTube video ID)
 * @param title candidate video title
 * @param channelOrArtist candidate uploader channel or artist name
 * @param description candidate video description
 * @param thumbnailUrl candidate thumbnail URL
 * @param publishedAt candidate release or upload timestamp string
 * @param duration candidate duration
 */
public record DestinationCandidateResponse(
        String id,
        String title,
        String channelOrArtist,
        String description,
        String thumbnailUrl,
        String publishedAt,
        Duration duration
) {

    public static DestinationCandidateResponse from(DestinationTrackCandidate candidate) {
        if (candidate == null) {
            return null;
        }
        return new DestinationCandidateResponse(
                candidate.id(),
                candidate.title(),
                candidate.channelOrArtist(),
                candidate.description(),
                candidate.thumbnailUrl(),
                candidate.publishedAt(),
                candidate.duration()
        );
    }
}
