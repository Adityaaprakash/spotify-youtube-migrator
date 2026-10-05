package com.spotifyyoutube.migrator.migration.api.mapper;

import com.spotifyyoutube.migrator.migration.api.dto.MigrationJobResponse;
import com.spotifyyoutube.migrator.migration.api.dto.MigrationTaskResponse;
import com.spotifyyoutube.migrator.migration.domain.MigrationJob;
import com.spotifyyoutube.migrator.migration.domain.MigrationTask;

import java.time.Instant;
// Removed ZoneId
import java.util.List;
import java.util.stream.Collectors;

public class MigrationMapper {

    private MigrationMapper() {
        // Utility class
    }

    public static MigrationJobResponse toJobResponse(MigrationJob job) {
        if (job == null) {
            return null;
        }
        
        Instant startedAt = job.getStartedAt();
        Instant completedAt = job.getCompletedAt();

        return new MigrationJobResponse(
                job.getId(),
                job.getUser().getId(),
                job.getSourcePlaylistId(),
                job.getSourcePlatform(),
                job.getTargetPlatform(),
                job.getStatus(),
                job.getTotalTracks(),
                job.getProcessedTracks(),
                job.getFailedTracks(),
                startedAt,
                completedAt,
                job.getTargetPlaylistUrl()
        );
    }

    public static List<MigrationJobResponse> toJobResponseList(List<MigrationJob> jobs) {
        if (jobs == null) {
            return List.of();
        }
        return jobs.stream()
                .map(MigrationMapper::toJobResponse)
                .collect(Collectors.toList());
    }

    public static MigrationTaskResponse toTaskResponse(MigrationTask task) {
        if (task == null) {
            return null;
        }
        return new MigrationTaskResponse(
                task.getId(),
                task.getJob().getId(),
                task.getSourceTrackId(),
                task.getTargetTrackId(),
                task.getStatus(),
                task.getErrorMessage()
        );
    }

    public static List<MigrationTaskResponse> toTaskResponseList(List<MigrationTask> tasks) {
        if (tasks == null) {
            return List.of();
        }
        return tasks.stream()
                .map(MigrationMapper::toTaskResponse)
                .collect(Collectors.toList());
    }
}
