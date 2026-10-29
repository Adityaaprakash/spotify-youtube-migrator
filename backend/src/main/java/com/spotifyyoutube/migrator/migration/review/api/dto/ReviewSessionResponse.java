package com.spotifyyoutube.migrator.migration.review.api.dto;

import com.spotifyyoutube.migrator.migration.review.domain.ReviewProgressSummary;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSession;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSessionStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Top-level API response representing a playlist migration review session.
 *
 * @param id unique review session identifier
 * @param userId ID of the user owning the session
 * @param sourcePlaylistId source platform playlist identifier (e.g. Spotify playlist ID)
 * @param sourcePlaylistTitle source playlist title
 * @param planId transient migration plan identifier from Phase 9
 * @param status current lifecycle status (IN_PROGRESS, COMPLETED, CANCELLED)
 * @param totalTracks total count of tracks under review
 * @param createdAt session creation timestamp
 * @param updatedAt session last updated timestamp
 * @param completedAt timestamp when session was completed, or null if in progress/cancelled
 * @param progress real-time review progress summary
 */
public record ReviewSessionResponse(
        UUID id,
        UUID userId,
        String sourcePlaylistId,
        String sourcePlaylistTitle,
        UUID planId,
        ReviewSessionStatus status,
        int totalTracks,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt,
        ReviewProgressSummaryResponse progress
) {

    public static ReviewSessionResponse from(ReviewSession session, ReviewProgressSummary summary) {
        if (session == null) {
            return null;
        }
        return new ReviewSessionResponse(
                session.getId(),
                session.getUser() != null ? session.getUser().getId() : null,
                session.getSourcePlaylistId(),
                session.getSourcePlaylistTitle(),
                session.getPlanId(),
                session.getStatus(),
                session.getTotalTracks() != null ? session.getTotalTracks() : 0,
                session.getCreatedAt(),
                session.getUpdatedAt(),
                session.getCompletedAt(),
                ReviewProgressSummaryResponse.from(summary)
        );
    }
}
