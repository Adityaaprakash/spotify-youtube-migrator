package com.spotifyyoutube.migrator.migration.review.api.mapper;

import com.spotifyyoutube.migrator.migration.review.api.dto.ReviewCandidateSearchResultResponse;
import com.spotifyyoutube.migrator.migration.review.api.dto.ReviewProgressSummaryResponse;
import com.spotifyyoutube.migrator.migration.review.api.dto.ReviewSessionResponse;
import com.spotifyyoutube.migrator.migration.review.api.dto.ReviewTrackResponse;
import com.spotifyyoutube.migrator.migration.review.application.ReviewCandidateSearchResult;
import com.spotifyyoutube.migrator.migration.review.application.ReviewSessionService;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewProgressSummary;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSession;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewTrack;

import java.util.List;

/**
 * Utility mapper transforming Review domain and application models into public API response DTOs.
 */
public final class ReviewApiMapper {

    private ReviewApiMapper() {
        // Utility class
    }

    public static ReviewSessionResponse toSessionResponse(ReviewSession session, ReviewProgressSummary summary) {
        return ReviewSessionResponse.from(session, summary);
    }

    public static List<ReviewSessionResponse> toSessionResponseList(
            List<ReviewSession> sessions,
            ReviewSessionService sessionService
    ) {
        if (sessions == null) {
            return List.of();
        }
        return sessions.stream()
                .map(session -> {
                    ReviewProgressSummary summary = sessionService != null
                            ? sessionService.getReviewProgress(session.getId())
                            : session.deriveSummary();
                    return ReviewSessionResponse.from(session, summary);
                })
                .toList();
    }

    public static ReviewTrackResponse toTrackResponse(ReviewTrack track) {
        return ReviewTrackResponse.from(track);
    }

    public static List<ReviewTrackResponse> toTrackResponseList(List<ReviewTrack> tracks) {
        if (tracks == null) {
            return List.of();
        }
        return tracks.stream()
                .map(ReviewTrackResponse::from)
                .toList();
    }

    public static ReviewProgressSummaryResponse toProgressResponse(ReviewProgressSummary summary) {
        return ReviewProgressSummaryResponse.from(summary);
    }

    public static ReviewCandidateSearchResultResponse toSearchResultResponse(ReviewCandidateSearchResult result) {
        return ReviewCandidateSearchResultResponse.from(result);
    }
}
