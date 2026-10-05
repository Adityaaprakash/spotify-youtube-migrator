package com.spotifyyoutube.migrator.migration.application;

import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.identity.repository.UserRepository;
import com.spotifyyoutube.migrator.migration.api.dto.CreateMigrationRequest;
import com.spotifyyoutube.migrator.migration.domain.MigrationJob;
import com.spotifyyoutube.migrator.migration.domain.MigrationStatus;
import com.spotifyyoutube.migrator.migration.domain.MigrationTask;
import com.spotifyyoutube.migrator.migration.repository.MigrationJobRepository;
import com.spotifyyoutube.migrator.migration.repository.MigrationTaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Removed LocalDateTime
import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class MigrationServiceImpl implements MigrationService {

    private final MigrationJobRepository migrationJobRepository;
    private final MigrationTaskRepository migrationTaskRepository;
    private final UserRepository userRepository;

    public MigrationServiceImpl(
            MigrationJobRepository migrationJobRepository,
            MigrationTaskRepository migrationTaskRepository,
            UserRepository userRepository) {
        this.migrationJobRepository = migrationJobRepository;
        this.migrationTaskRepository = migrationTaskRepository;
        this.userRepository = userRepository;
    }

    @Override
    @Transactional
    public MigrationJob createMigrationJob(CreateMigrationRequest request) {
        if (request.sourcePlatform() == request.targetPlatform()) {
            throw new InvalidStateException("Source and target platforms must be logically different");
        }

        User user = userRepository.findById(request.userId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + request.userId()));

        MigrationJob job = new MigrationJob();
        job.setUser(user);
        job.setSourcePlaylistId(request.sourcePlaylistId());
        job.setSourcePlatform(request.sourcePlatform());
        job.setTargetPlatform(request.targetPlatform());
        job.setStatus(MigrationStatus.PENDING);
        job.setStartedAt(java.time.Instant.now()); // Phase 1 defaults to starting synchronously from a domain perspective

        return migrationJobRepository.save(job);
    }

    @Override
    public MigrationJob getMigrationJobById(UUID jobId) {
        return migrationJobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Migration job not found with id: " + jobId));
    }

    @Override
    public List<MigrationJob> getUserMigrationJobs(UUID userId) {
        if (!userRepository.existsById(userId)) {
            throw new ResourceNotFoundException("User not found with id: " + userId);
        }
        return migrationJobRepository.findByUserId(userId);
    }

    @Override
    public List<MigrationTask> getMigrationTasks(UUID jobId) {
        if (!migrationJobRepository.existsById(jobId)) {
            throw new ResourceNotFoundException("Migration job not found with id: " + jobId);
        }
        return migrationTaskRepository.findByJobId(jobId);
    }
}
