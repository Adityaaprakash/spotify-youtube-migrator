package com.spotifyyoutube.migrator.migration.api.dto;

import com.spotifyyoutube.migrator.migration.application.plan.MigrationPlan;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Top-level API response representing an immutable migration plan preview.
 *
 * @param planId unique planning session identifier
 * @param sourcePlaylistId source platform playlist identifier (e.g. Spotify playlist ID)
 * @param sourcePlaylistTitle source playlist title at the time of plan generation
 * @param createdAt UTC timestamp when the plan preview was generated
 * @param totalTracks total count of source tracks evaluated
 * @param tracks planned track entries in strict original source playlist order
 * @param summary reconciled aggregate metrics across all classifications
 */
public record MigrationPlanResponse(
        UUID planId,
        String sourcePlaylistId,
        String sourcePlaylistTitle,
        Instant createdAt,
        int totalTracks,
        List<MigrationPlanTrackResponse> tracks,
        MigrationPlanSummaryResponse summary
) {

    public static MigrationPlanResponse from(MigrationPlan plan) {
        if (plan == null) {
            return null;
        }
        List<MigrationPlanTrackResponse> trackResponses = plan.tracks() != null
                ? plan.tracks().stream().map(MigrationPlanTrackResponse::from).toList()
                : List.of();
        return new MigrationPlanResponse(
                plan.planId(),
                plan.sourcePlaylistId(),
                plan.sourcePlaylistTitle(),
                plan.createdAt(),
                plan.summary().totalTracks(),
                trackResponses,
                MigrationPlanSummaryResponse.from(plan.summary())
        );
    }
}
