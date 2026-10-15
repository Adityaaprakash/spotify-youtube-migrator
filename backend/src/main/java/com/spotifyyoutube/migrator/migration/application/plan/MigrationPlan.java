package com.spotifyyoutube.migrator.migration.application.plan;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable domain contract representing the complete proposed migration plan for a source playlist.
 * Captures source playlist identity, ordered track entries, and aggregate summary metrics.
 *
 * @param planId unique planning session identifier for preview traceability
 * @param sourcePlaylistId source platform playlist identifier (e.g. Spotify playlist ID)
 * @param sourcePlaylistTitle source playlist title at the time of planning
 * @param createdAt UTC timestamp when this migration plan was generated (snapshot timestamp)
 * @param tracks unmodifiable list of planned track entries in deterministic source order
 * @param summary aggregate classification summary reconciled with tracks
 */
public record MigrationPlan(
        UUID planId,
        String sourcePlaylistId,
        String sourcePlaylistTitle,
        Instant createdAt,
        List<MigrationPlanTrack> tracks,
        MigrationPlanSummary summary
) {

    public MigrationPlan {
        Objects.requireNonNull(planId, "planId must not be null");
        Objects.requireNonNull(sourcePlaylistId, "sourcePlaylistId must not be null");
        if (sourcePlaylistId.isBlank()) {
            throw new IllegalArgumentException("sourcePlaylistId must not be blank");
        }
        Objects.requireNonNull(sourcePlaylistTitle, "sourcePlaylistTitle must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(summary, "summary must not be null");

        tracks = tracks != null ? List.copyOf(tracks) : List.of();

        if (summary.totalTracks() != tracks.size()) {
            throw new IllegalArgumentException(String.format(
                    "Summary totalTracks (%d) does not match tracks count (%d)",
                    summary.totalTracks(), tracks.size()
            ));
        }

        // Enforce deterministic 0-indexed position ordering invariant
        for (int i = 0; i < tracks.size(); i++) {
            MigrationPlanTrack track = tracks.get(i);
            Objects.requireNonNull(track, "Track at position " + i + " must not be null");
            if (track.position() != i) {
                throw new IllegalArgumentException(String.format(
                        "Track at index %d has out-of-order position %d", i, track.position()
                ));
            }
        }
    }

    /**
     * Convenience constructor deriving summary automatically from tracks.
     */
    public MigrationPlan(
            UUID planId,
            String sourcePlaylistId,
            String sourcePlaylistTitle,
            Instant createdAt,
            List<MigrationPlanTrack> tracks
    ) {
        this(
                planId,
                sourcePlaylistId,
                sourcePlaylistTitle,
                createdAt,
                tracks,
                MigrationPlanSummary.fromTracks(tracks)
        );
    }

    /**
     * Convenience factory method creating an empty plan.
     */
    public static MigrationPlan empty(
            UUID planId,
            String sourcePlaylistId,
            String sourcePlaylistTitle,
            Instant createdAt
    ) {
        return new MigrationPlan(
                planId,
                sourcePlaylistId,
                sourcePlaylistTitle,
                createdAt,
                List.of(),
                MigrationPlanSummary.empty()
        );
    }
}
