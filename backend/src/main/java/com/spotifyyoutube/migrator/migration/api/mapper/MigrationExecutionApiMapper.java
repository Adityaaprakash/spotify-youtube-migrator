package com.spotifyyoutube.migrator.migration.api.mapper;

import com.spotifyyoutube.migrator.migration.api.dto.ApprovedMigrationContextResponse;
import com.spotifyyoutube.migrator.migration.api.dto.ApprovedTrackResponse;
import com.spotifyyoutube.migrator.migration.api.dto.CreateDestinationPlaylistRequest;
import com.spotifyyoutube.migrator.migration.api.dto.DestinationPlaylistResponse;
import com.spotifyyoutube.migrator.migration.api.dto.ExecutionValidationErrorResponse;
import com.spotifyyoutube.migrator.migration.api.dto.ExecutionValidationResponse;
import com.spotifyyoutube.migrator.migration.api.dto.ValidateExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationContext;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedTrack;
import com.spotifyyoutube.migrator.migration.application.execution.contract.DestinationPlaylistCreationResult;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ExecutionValidationError;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ExecutionValidationResult;

import java.util.List;
import java.util.UUID;

/**
 * Mapper converting execution domain contracts and validation results to API presentation DTOs.
 */
public final class MigrationExecutionApiMapper {

    private MigrationExecutionApiMapper() {
    }

    public static ApprovedMigrationExecutionRequest toDomainRequest(ValidateExecutionRequest dto, UUID authenticatedUserId) {
        if (dto == null) {
            return null;
        }
        return new ApprovedMigrationExecutionRequest(
                dto.reviewSessionId(),
                dto.userId() != null ? dto.userId() : authenticatedUserId,
                dto.sourcePlaylistId(),
                null,
                dto.targetPlaylistTitle(),
                dto.targetPlaylistDescription(),
                dto.isPrivate(),
                null
        );
    }

    public static ApprovedMigrationExecutionRequest toDomainRequest(CreateDestinationPlaylistRequest dto, UUID authenticatedUserId) {
        if (dto == null) {
            return null;
        }
        return new ApprovedMigrationExecutionRequest(
                dto.reviewSessionId(),
                dto.userId() != null ? dto.userId() : authenticatedUserId,
                dto.sourcePlaylistId(),
                null,
                dto.targetPlaylistTitle(),
                dto.targetPlaylistDescription(),
                dto.isPrivate(),
                null
        );
    }

    public static ExecutionValidationResponse toResponse(UUID reviewSessionId, ExecutionValidationResult result) {
        if (result == null) {
            return new ExecutionValidationResponse(false, reviewSessionId, null, List.of());
        }

        ApprovedMigrationContextResponse contextResponse = result.getApprovedContext()
                .map(MigrationExecutionApiMapper::toContextResponse)
                .orElse(null);

        List<ExecutionValidationErrorResponse> errorResponses = result.errors().stream()
                .map(MigrationExecutionApiMapper::toErrorResponse)
                .toList();

        return new ExecutionValidationResponse(
                result.isValid(),
                reviewSessionId,
                contextResponse,
                errorResponses
        );
    }

    public static ApprovedMigrationContextResponse toContextResponse(ApprovedMigrationContext context) {
        if (context == null) {
            return null;
        }

        List<ApprovedTrackResponse> trackResponses = context.approvedTracks().stream()
                .map(MigrationExecutionApiMapper::toTrackResponse)
                .toList();

        return new ApprovedMigrationContextResponse(
                context.reviewSessionId(),
                context.planId(),
                context.sourcePlaylistId(),
                context.sourcePlaylistTitle(),
                context.targetPlaylistTitle(),
                context.targetPlaylistDescription(),
                context.isPrivate(),
                context.totalSourceTracks(),
                context.approvedTrackCount(),
                context.excludedTrackCount(),
                context.validatedAt(),
                trackResponses
        );
    }

    public static ApprovedTrackResponse toTrackResponse(ApprovedTrack track) {
        if (track == null) {
            return null;
        }
        Long durationMs = track.destinationDuration() != null ? track.destinationDuration().toMillis() : null;
        return new ApprovedTrackResponse(
                track.sourcePosition(),
                track.sourceTrackId(),
                track.sourceTitle(),
                track.sourceArtist(),
                track.destinationTrackId(),
                track.destinationTitle(),
                track.destinationChannel(),
                durationMs,
                track.decisionSource().name(),
                track.targetPosition()
        );
    }

    public static ExecutionValidationErrorResponse toErrorResponse(ExecutionValidationError error) {
        if (error == null) {
            return null;
        }
        return new ExecutionValidationErrorResponse(
                error.code().name(),
                error.position(),
                error.sourceTrackId(),
                error.message()
        );
    }

    public static DestinationPlaylistResponse toPlaylistResponse(DestinationPlaylistCreationResult result) {
        if (result == null) {
            return null;
        }
        return new DestinationPlaylistResponse(
                result.reviewSessionId(),
                result.planId(),
                result.destinationPlatform(),
                result.destinationPlaylistId(),
                result.destinationPlaylistUrl(),
                result.playlistTitle(),
                result.playlistDescription(),
                result.isPrivate(),
                result.approvedTrackCount(),
                "CREATED",
                result.createdAt()
        );
    }
}
