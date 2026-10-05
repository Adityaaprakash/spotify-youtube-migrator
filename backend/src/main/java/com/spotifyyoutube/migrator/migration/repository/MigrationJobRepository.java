package com.spotifyyoutube.migrator.migration.repository;

import com.spotifyyoutube.migrator.migration.domain.MigrationJob;
import com.spotifyyoutube.migrator.migration.domain.MigrationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MigrationJobRepository extends JpaRepository<MigrationJob, UUID> {
    List<MigrationJob> findByUserId(UUID userId);
    List<MigrationJob> findByStatus(MigrationStatus status);
}
