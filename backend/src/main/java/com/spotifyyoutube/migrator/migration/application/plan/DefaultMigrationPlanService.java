package com.spotifyyoutube.migrator.migration.application.plan;

import com.spotifyyoutube.migrator.migration.api.dto.MigrationPlanPreviewRequest;
import com.spotifyyoutube.migrator.migration.api.dto.MigrationPlanResponse;
import com.spotifyyoutube.migrator.migration.api.mapper.MigrationPlanMapper;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;

/**
 * Default implementation of {@link MigrationPlanService}.
 * <p>
 * Delegates plan generation to {@link PlaylistMigrationPlanGenerator} and maps
 * the result to {@link MigrationPlanResponse}. Strictly read-only with respect to
 * music destination services.
 */
@Service
public class DefaultMigrationPlanService implements MigrationPlanService {

    private final PlaylistMigrationPlanGenerator planGenerator;

    public DefaultMigrationPlanService(PlaylistMigrationPlanGenerator planGenerator) {
        this.planGenerator = Objects.requireNonNull(planGenerator, "PlaylistMigrationPlanGenerator must not be null");
    }

    @Override
    public MigrationPlanResponse generatePreview(UUID userId, MigrationPlanPreviewRequest request) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(request, "request must not be null");

        String playlistId = request.spotifyPlaylistId();
        if (playlistId == null || playlistId.isBlank()) {
            throw new IllegalArgumentException("Spotify playlist ID cannot be blank");
        }

        boolean deduplicate = request.isDeduplicate();
        MigrationPlan plan = planGenerator.generatePlan(userId, playlistId.trim(), deduplicate);
        return MigrationPlanMapper.toResponse(plan);
    }

    @Override
    public MigrationPlan generatePlan(UUID userId, String sourcePlaylistId, boolean deduplicate) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(sourcePlaylistId, "sourcePlaylistId must not be null");
        if (sourcePlaylistId.isBlank()) {
            throw new IllegalArgumentException("sourcePlaylistId must not be blank");
        }
        return planGenerator.generatePlan(userId, sourcePlaylistId.trim(), deduplicate);
    }
}
