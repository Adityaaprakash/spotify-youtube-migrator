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
import com.spotifyyoutube.migrator.playlist.domain.Platform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class MigrationServiceImplTest {

    @Mock
    private MigrationJobRepository migrationJobRepository;

    @Mock
    private MigrationTaskRepository migrationTaskRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private MigrationServiceImpl migrationService;

    @Test
    public void testCreateMigrationJob_Success() {
        UUID userId = UUID.randomUUID();
        CreateMigrationRequest req = new CreateMigrationRequest(userId, "ext-1", Platform.SPOTIFY, Platform.YOUTUBE);
        User user = new User();
        user.setId(userId);

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(migrationJobRepository.save(any(MigrationJob.class))).thenAnswer(i -> i.getArguments()[0]);

        MigrationJob job = migrationService.createMigrationJob(req);

        assertEquals(Platform.SPOTIFY, job.getSourcePlatform());
        assertEquals(Platform.YOUTUBE, job.getTargetPlatform());
        assertEquals(MigrationStatus.PENDING, job.getStatus());
        assertNotNull(job.getStartedAt());
    }

    @Test
    public void testCreateMigrationJob_SamePlatform() {
        CreateMigrationRequest req = new CreateMigrationRequest(UUID.randomUUID(), "ext-1", Platform.SPOTIFY, Platform.SPOTIFY);
        assertThrows(InvalidStateException.class, () -> migrationService.createMigrationJob(req));
    }

    @Test
    public void testCreateMigrationJob_UserNotFound() {
        UUID userId = UUID.randomUUID();
        CreateMigrationRequest req = new CreateMigrationRequest(userId, "ext-1", Platform.SPOTIFY, Platform.YOUTUBE);
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> migrationService.createMigrationJob(req));
    }

    @Test
    public void testGetMigrationJobById_Success() {
        UUID jobId = UUID.randomUUID();
        MigrationJob job = new MigrationJob();
        when(migrationJobRepository.findById(jobId)).thenReturn(Optional.of(job));

        assertNotNull(migrationService.getMigrationJobById(jobId));
    }

    @Test
    public void testGetMigrationJobById_NotFound() {
        UUID jobId = UUID.randomUUID();
        when(migrationJobRepository.findById(jobId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> migrationService.getMigrationJobById(jobId));
    }

    @Test
    public void testGetUserMigrationJobs_Success() {
        UUID userId = UUID.randomUUID();
        when(userRepository.existsById(userId)).thenReturn(true);
        when(migrationJobRepository.findByUserId(userId)).thenReturn(List.of(new MigrationJob()));

        assertEquals(1, migrationService.getUserMigrationJobs(userId).size());
    }

    @Test
    public void testGetUserMigrationJobs_UserNotFound() {
        UUID userId = UUID.randomUUID();
        when(userRepository.existsById(userId)).thenReturn(false);

        assertThrows(ResourceNotFoundException.class, () -> migrationService.getUserMigrationJobs(userId));
    }

    @Test
    public void testGetMigrationTasks_Success() {
        UUID jobId = UUID.randomUUID();
        when(migrationJobRepository.existsById(jobId)).thenReturn(true);
        when(migrationTaskRepository.findByJobId(jobId)).thenReturn(List.of(new MigrationTask()));

        assertEquals(1, migrationService.getMigrationTasks(jobId).size());
    }
}
