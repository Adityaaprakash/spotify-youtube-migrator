package com.spotifyyoutube.migrator.migration.application.execution.contract;

import com.spotifyyoutube.migrator.migration.execution.domain.RollbackRecordStatus;

import java.util.Objects;

/**
 * Immutable outcome for an individual track's rollback attempt (Phase 11F).
 *
 * @param targetPosition 0-indexed destination position
 * @param destinationTrackId destination video identifier
 * @param destinationPlaylistItemId destination playlist item identifier that was targeted
 * @param status confirmed rollback status (ROLLED_BACK, ALREADY_ROLLED_BACK, FAILED)
 * @param message diagnostic message or error description
 */
public record RollbackTrackOutcomeDto(
        int targetPosition,
        String destinationTrackId,
        String destinationPlaylistItemId,
        RollbackRecordStatus status,
        String message
) {

    public RollbackTrackOutcomeDto {
        Objects.requireNonNull(destinationTrackId, "destinationTrackId must not be null");
        Objects.requireNonNull(status, "status must not be null");
    }

    public static RollbackTrackOutcomeDto rolledBack(
            int targetPosition,
            String destinationTrackId,
            String destinationPlaylistItemId
    ) {
        return new RollbackTrackOutcomeDto(
                targetPosition,
                destinationTrackId,
                destinationPlaylistItemId,
                RollbackRecordStatus.ROLLED_BACK,
                "Successfully removed from destination playlist"
        );
    }

    public static RollbackTrackOutcomeDto alreadyRolledBack(
            int targetPosition,
            String destinationTrackId,
            String destinationPlaylistItemId
    ) {
        return new RollbackTrackOutcomeDto(
                targetPosition,
                destinationTrackId,
                destinationPlaylistItemId,
                RollbackRecordStatus.ALREADY_ROLLED_BACK,
                "Track was already rolled back"
        );
    }

    public static RollbackTrackOutcomeDto failed(
            int targetPosition,
            String destinationTrackId,
            String destinationPlaylistItemId,
            String errorMessage
    ) {
        return new RollbackTrackOutcomeDto(
                targetPosition,
                destinationTrackId,
                destinationPlaylistItemId,
                RollbackRecordStatus.FAILED,
                errorMessage
        );
    }
}
