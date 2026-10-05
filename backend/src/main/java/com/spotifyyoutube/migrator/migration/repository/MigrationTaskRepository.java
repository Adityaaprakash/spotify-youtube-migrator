package com.spotifyyoutube.migrator.migration.repository;

import com.spotifyyoutube.migrator.migration.domain.MigrationStatus;
import com.spotifyyoutube.migrator.migration.domain.MigrationTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MigrationTaskRepository extends JpaRepository<MigrationTask, UUID> {
    List<MigrationTask> findByJobId(UUID jobId);
    List<MigrationTask> findByJobIdAndStatus(UUID jobId, MigrationStatus status);
}
