package com.spotifyyoutube.migrator.migration.application;

import com.spotifyyoutube.migrator.migration.api.dto.CreateMigrationRequest;
import com.spotifyyoutube.migrator.migration.domain.MigrationJob;
import com.spotifyyoutube.migrator.migration.domain.MigrationTask;

import java.util.List;
import java.util.UUID;

public interface MigrationService {

    MigrationJob createMigrationJob(CreateMigrationRequest request);

    MigrationJob getMigrationJobById(UUID jobId);

    List<MigrationJob> getUserMigrationJobs(UUID userId);

    List<MigrationTask> getMigrationTasks(UUID jobId);
}
