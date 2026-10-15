package com.spotifyyoutube.migrator.migration.api.mapper;

import com.spotifyyoutube.migrator.migration.api.dto.MigrationPlanResponse;
import com.spotifyyoutube.migrator.migration.application.plan.MigrationPlan;

/**
 * Mapper utility for transforming migration planning domain contracts into API response DTOs.
 */
public final class MigrationPlanMapper {

    private MigrationPlanMapper() {
        // Utility class
    }

    public static MigrationPlanResponse toResponse(MigrationPlan plan) {
        return MigrationPlanResponse.from(plan);
    }
}
