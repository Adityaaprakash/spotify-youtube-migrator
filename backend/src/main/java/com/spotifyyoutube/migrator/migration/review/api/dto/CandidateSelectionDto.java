package com.spotifyyoutube.migrator.migration.review.api.dto;

import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationTrackCandidate;
import jakarta.validation.constraints.NotBlank;

import java.time.Duration;

/**
 * DTO representing candidate details supplied by a reviewer when manually selecting a match.
 *
 * @param id provider candidate identifier (e.g. YouTube video ID)
 * @param title candidate track/video title
 * @param channelOrArtist candidate channel or artist name
 * @param durationMs candidate duration in milliseconds
 * @param duration candidate parsed duration
 * @param description candidate video description
 * @param thumbnailUrl candidate thumbnail URL
 * @param publishedAt candidate release or upload timestamp string
 */
public record CandidateSelectionDto(
        @NotBlank(message = "Candidate ID cannot be blank")
        String id,

        @NotBlank(message = "Candidate title cannot be blank")
        String title,

        String channelOrArtist,

        Long durationMs,

        Duration duration,

        String description,

        String thumbnailUrl,

        String publishedAt
) {

    public CandidateSelectionDto {
        if (durationMs != null && durationMs < 0) {
            throw new IllegalArgumentException("durationMs cannot be negative: " + durationMs);
        }
        if (duration != null && duration.isNegative()) {
            throw new IllegalArgumentException("duration cannot be negative: " + duration);
        }
    }

    public CandidateSelectionDto(String id, String title, String channelOrArtist, Long durationMs) {
        this(id, title, channelOrArtist, durationMs, durationMs != null ? Duration.ofMillis(durationMs) : null, null, null, null);
    }

    public DestinationTrackCandidate toDomain() {
        Duration resolvedDuration = duration;
        if (resolvedDuration == null && durationMs != null) {
            resolvedDuration = Duration.ofMillis(durationMs);
        }
        return new DestinationTrackCandidate(
                id != null ? id.trim() : null,
                title != null ? title.trim() : null,
                channelOrArtist != null ? channelOrArtist.trim() : null,
                description,
                thumbnailUrl,
                publishedAt,
                resolvedDuration
        );
    }
}
