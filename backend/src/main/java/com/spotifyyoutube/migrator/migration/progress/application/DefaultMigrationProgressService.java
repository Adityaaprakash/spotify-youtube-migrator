package com.spotifyyoutube.migrator.migration.progress.application;

import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackStatus;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecution;
import com.spotifyyoutube.migrator.migration.execution.repository.ExecutionTrackRecordRepository;
import com.spotifyyoutube.migrator.migration.execution.repository.MigrationExecutionRepository;
import com.spotifyyoutube.migrator.migration.execution.repository.TrackStatusCount;
import com.spotifyyoutube.migrator.migration.progress.domain.MigrationProgressSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Default implementation of {@link MigrationProgressService} (Phase 12D).
 * <p>
 * Provides read-only progress calculation directly from persisted execution track state.
 * Uses database index aggregation to avoid loading track entities into memory.
 */
@Service
@Transactional(readOnly = true)
public class DefaultMigrationProgressService implements MigrationProgressService {

    private static final Logger log = LoggerFactory.getLogger(DefaultMigrationProgressService.class);

    private final MigrationExecutionRepository executionRepository;
    private final ExecutionTrackRecordRepository trackRecordRepository;

    public DefaultMigrationProgressService(
            MigrationExecutionRepository executionRepository,
            ExecutionTrackRecordRepository trackRecordRepository
    ) {
        this.executionRepository = Objects.requireNonNull(executionRepository, "MigrationExecutionRepository must not be null");
        this.trackRecordRepository = Objects.requireNonNull(trackRecordRepository, "ExecutionTrackRecordRepository must not be null");
    }

    @Override
    public MigrationProgressSnapshot getProgress(UUID executionId, UUID authenticatedUserId) {
        Objects.requireNonNull(executionId, "executionId must not be null");
        Objects.requireNonNull(authenticatedUserId, "authenticatedUserId must not be null");

        MigrationExecution execution = executionRepository.findById(executionId)
                .orElseThrow(() -> new ResourceNotFoundException("Migration execution not found: " + executionId));

        validateOwnership(execution, authenticatedUserId, executionId);

        return buildSnapshot(execution);
    }

    @Override
    public MigrationProgressSnapshot getProgressByReviewSession(UUID reviewSessionId, UUID authenticatedUserId) {
        Objects.requireNonNull(reviewSessionId, "reviewSessionId must not be null");
        Objects.requireNonNull(authenticatedUserId, "authenticatedUserId must not be null");

        MigrationExecution execution = executionRepository.findByReviewSessionId(reviewSessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Migration execution not found for review session: " + reviewSessionId));

        validateOwnership(execution, authenticatedUserId, execution.getId());

        return buildSnapshot(execution);
    }

    private MigrationProgressSnapshot buildSnapshot(MigrationExecution execution) {
        UUID executionId = execution.getId();
        List<TrackStatusCount> counts = trackRecordRepository.countTracksByStatusForExecution(executionId);

        Map<ExecutionTrackStatus, Long> countMap = new EnumMap<>(ExecutionTrackStatus.class);
        for (TrackStatusCount sc : counts) {
            countMap.put(sc.status(), sc.count());
        }

        int pending = countMap.getOrDefault(ExecutionTrackStatus.PENDING, 0L).intValue();
        int adding = countMap.getOrDefault(ExecutionTrackStatus.ADDING, 0L).intValue();
        int added = countMap.getOrDefault(ExecutionTrackStatus.ADDED, 0L).intValue();
        int failed = countMap.getOrDefault(ExecutionTrackStatus.FAILED, 0L).intValue();
        int uncertain = countMap.getOrDefault(ExecutionTrackStatus.UNCERTAIN, 0L).intValue();
        int skipped = countMap.getOrDefault(ExecutionTrackStatus.SKIPPED, 0L).intValue();
        int rollingBack = countMap.getOrDefault(ExecutionTrackStatus.ROLLING_BACK, 0L).intValue();
        int rolledBack = countMap.getOrDefault(ExecutionTrackStatus.ROLLED_BACK, 0L).intValue();

        int sumOfRecords = pending + adding + added + failed + uncertain + skipped + rollingBack + rolledBack;
        int totalTracks = Math.max(execution.getTotalApprovedTracks(), sumOfRecords);

        MigrationProgressSnapshot snapshot = MigrationProgressSnapshot.calculate(
                executionId,
                execution.getStatus(),
                totalTracks,
                pending,
                adding,
                added,
                failed,
                uncertain,
                skipped,
                rollingBack,
                rolledBack
        );

        log.debug("Calculated progress for execution {}: status={}, total={}, processed={}, percentage={}%",
                executionId, execution.getStatus(), snapshot.totalTracks(), snapshot.processedTracks(), snapshot.progressPercentage());

        return snapshot;
    }

    private void validateOwnership(MigrationExecution execution, UUID authenticatedUserId, UUID identifier) {
        if (!execution.getUser().getId().equals(authenticatedUserId)) {
            log.warn("Unauthorized access attempt to migration execution {} by user {}", identifier, authenticatedUserId);
            throw new ResourceNotFoundException("Migration execution not found: " + identifier);
        }
    }
}
