package com.spotifyyoutube.migrator.migration.application.execution;

import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.migration.application.destination.MusicDestination;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationPlaylistInspection;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationPlaylistItemDetails;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationContext;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedTrack;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ExecutionReconciliationStatusDto;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ExecutionReconciliationSummary;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ReconcileExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ReconciliationRecordDto;
import com.spotifyyoutube.migrator.migration.application.execution.contract.TrackReconciliationOutcomeDto;
import com.spotifyyoutube.migrator.migration.application.execution.failure.ExecutionFailureClassifier;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionReconciliationRecord;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackRecord;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackStatus;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecution;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus;
import com.spotifyyoutube.migrator.migration.execution.domain.ReconciliationOutcome;
import com.spotifyyoutube.migrator.migration.execution.domain.ReconciliationType;
import com.spotifyyoutube.migrator.migration.execution.repository.ExecutionReconciliationRecordRepository;
import com.spotifyyoutube.migrator.migration.execution.repository.ExecutionTrackRecordRepository;
import com.spotifyyoutube.migrator.migration.execution.repository.MigrationExecutionRepository;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewDecisionSource;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSession;
import com.spotifyyoutube.migrator.migration.review.repository.ReviewSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Phase 11G: DefaultMigrationExecutionReconciliationTest")
class DefaultMigrationExecutionReconciliationTest {

    private MigrationExecutionRepository executionRepository;
    private ExecutionTrackRecordRepository trackRecordRepository;
    private ExecutionReconciliationRecordRepository reconciliationRecordRepository;
    private ReviewSessionRepository reviewSessionRepository;
    private ApprovedMigrationExecutionService executionService;
    private MusicDestination musicDestination;
    private TransactionTemplate transactionTemplate;
    private ExecutionFailureClassifier failureClassifier;

    private DefaultMigrationExecutionStateService service;

    private User user;
    private ReviewSession session;
    private MigrationExecution execution;
    private UUID executionId;
    private UUID userId;

    private static final String DEST_PLAYLIST_ID = "PL_reconciliation_playlist_123";

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        executionRepository = mock(MigrationExecutionRepository.class);
        trackRecordRepository = mock(ExecutionTrackRecordRepository.class);
        reconciliationRecordRepository = mock(ExecutionReconciliationRecordRepository.class);
        reviewSessionRepository = mock(ReviewSessionRepository.class);
        executionService = mock(ApprovedMigrationExecutionService.class);
        musicDestination = mock(MusicDestination.class);
        transactionTemplate = mock(TransactionTemplate.class);
        failureClassifier = new ExecutionFailureClassifier();

        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });

        service = new DefaultMigrationExecutionStateService(
                executionRepository,
                trackRecordRepository,
                null,
                reconciliationRecordRepository,
                reviewSessionRepository,
                executionService,
                musicDestination,
                failureClassifier,
                transactionTemplate
        );

        userId = UUID.randomUUID();
        user = new User("user@example.com", "Test User");
        try {
            var idField = com.spotifyyoutube.migrator.common.domain.BaseEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(user, userId);
        } catch (Exception ignored) {}

        session = new ReviewSession(user, "sp_pl_1", "Source Playlist");
        ApprovedTrack track1 = new ApprovedTrack(0, "sp_1", "Track One", "Artist", "yt_vid_1", "Title 1", "Channel", Duration.ofSeconds(200), ReviewDecisionSource.AUTOMATIC, 0);
        ApprovedTrack track2 = new ApprovedTrack(1, "sp_2", "Track Two", "Artist", "yt_vid_2", "Title 2", "Channel", Duration.ofSeconds(180), ReviewDecisionSource.AUTOMATIC, 1);

        ApprovedMigrationContext context = new ApprovedMigrationContext(
                session.getId(),
                UUID.randomUUID(),
                "sp_pl_1",
                "Source Playlist",
                "Target Playlist",
                "Desc",
                false,
                2,
                2,
                0,
                Instant.now(),
                List.of(track1, track2)
        );

        execution = new MigrationExecution(user, session, context, DEST_PLAYLIST_ID, "https://youtube.com/playlist?list=" + DEST_PLAYLIST_ID);
        executionId = execution.getId();

        when(executionRepository.findById(executionId)).thenReturn(Optional.of(execution));
        when(executionRepository.findByIdAndUserId(executionId, userId)).thenReturn(Optional.of(execution));
        when(executionRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(trackRecordRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(trackRecordRepository.findByExecutionIdOrderByTargetPositionAsc(executionId))
                .thenReturn(execution.getTrackRecords());

        when(executionRepository.claimExecutionRunningWithVersion(eq(executionId), anyCollection(), anyLong(), any()))
                .thenReturn(1);
    }

    @Nested
    @DisplayName("Insertion Reconciliation")
    class InsertionReconciliation {

        @Test
        @DisplayName("CONFIRMED_INSERTED: Video is present with matching publishedAt during attempt -> becomes ADDED")
        void confirmsInsertionWhenMatchingItemFoundWithValidTimestamp() {
            ExecutionTrackRecord track0 = execution.getTrackRecords().get(0);
            track0.markAdding();
            track0.markUncertain("Timeout");
            execution.setStatus(MigrationExecutionStatus.RECOVERY_REQUIRED);
            execution.reconcileCounts();

            Instant attemptStarted = track0.getStartedAt();
            DestinationPlaylistItemDetails item = new DestinationPlaylistItemDetails(
                    "item_yt_100",
                    DEST_PLAYLIST_ID,
                    "yt_vid_1",
                    0,
                    attemptStarted.plusSeconds(2),
                    "Title 1"
            );
            DestinationPlaylistInspection inspection = DestinationPlaylistInspection.complete(
                    DEST_PLAYLIST_ID,
                    List.of(item),
                    Instant.now()
            );

            when(musicDestination.inspectPlaylist(userId, DEST_PLAYLIST_ID)).thenReturn(inspection);

            ExecutionReconciliationSummary summary = service.reconcileExecution(executionId, userId);

            assertThat(summary.fullyResolved()).isTrue();
            assertThat(summary.confirmedInsertedCount()).isEqualTo(1);
            assertThat(summary.resolvedExecutionStatus()).isEqualTo(MigrationExecutionStatus.PARTIALLY_COMPLETED);

            assertThat(track0.getStatus()).isEqualTo(ExecutionTrackStatus.ADDED);
            assertThat(track0.getDestinationPlaylistItemId()).isEqualTo("item_yt_100");

            verify(reconciliationRecordRepository).save(any(ExecutionReconciliationRecord.class));
        }

        @Test
        @DisplayName("CONFIRMED_NOT_INSERTED: Video is absent from complete playlist -> becomes FAILED for recovery")
        void confirmsNotInsertedWhenVideoAbsentFromCompleteInspection() {
            ExecutionTrackRecord track0 = execution.getTrackRecords().get(0);
            track0.markAdding();
            track0.markUncertain("Timeout");
            execution.setStatus(MigrationExecutionStatus.RECOVERY_REQUIRED);
            execution.reconcileCounts();

            DestinationPlaylistInspection inspection = DestinationPlaylistInspection.complete(
                    DEST_PLAYLIST_ID,
                    Collections.emptyList(),
                    Instant.now()
            );

            when(musicDestination.inspectPlaylist(userId, DEST_PLAYLIST_ID)).thenReturn(inspection);

            ExecutionReconciliationSummary summary = service.reconcileExecution(executionId, userId);

            assertThat(summary.confirmedNotInsertedCount()).isEqualTo(1);
            assertThat(track0.getStatus()).isEqualTo(ExecutionTrackStatus.FAILED);
            assertThat(summary.resolvedExecutionStatus()).isEqualTo(MigrationExecutionStatus.FAILED);
        }

        @Test
        @DisplayName("AMBIGUOUS: Matching video exists but publishedAt pre-dates attempt -> preserves UNCERTAIN")
        void preservesUncertainWhenMatchingItemPredatesAttempt() {
            ExecutionTrackRecord track0 = execution.getTrackRecords().get(0);
            track0.markAdding();
            track0.markUncertain("Timeout");
            execution.setStatus(MigrationExecutionStatus.RECOVERY_REQUIRED);
            execution.reconcileCounts();

            Instant attemptStarted = track0.getStartedAt();
            // Video existed 1 hour before attempt started
            DestinationPlaylistItemDetails item = new DestinationPlaylistItemDetails(
                    "item_old_1",
                    DEST_PLAYLIST_ID,
                    "yt_vid_1",
                    0,
                    attemptStarted.minusSeconds(3600),
                    "Pre-existing Video"
            );
            DestinationPlaylistInspection inspection = DestinationPlaylistInspection.complete(
                    DEST_PLAYLIST_ID,
                    List.of(item),
                    Instant.now()
            );

            when(musicDestination.inspectPlaylist(userId, DEST_PLAYLIST_ID)).thenReturn(inspection);

            ExecutionReconciliationSummary summary = service.reconcileExecution(executionId, userId);

            assertThat(summary.ambiguousCount()).isEqualTo(1);
            assertThat(summary.fullyResolved()).isFalse();
            assertThat(track0.getStatus()).isEqualTo(ExecutionTrackStatus.UNCERTAIN);
            assertThat(summary.resolvedExecutionStatus()).isEqualTo(MigrationExecutionStatus.RECOVERY_REQUIRED);
        }

        @Test
        @DisplayName("AMBIGUOUS: Multiple unassigned items with same video ID -> preserves UNCERTAIN")
        void preservesUncertainWhenMultipleUnassignedItemsFound() {
            ExecutionTrackRecord track0 = execution.getTrackRecords().get(0);
            track0.markAdding();
            track0.markUncertain("Timeout");
            execution.setStatus(MigrationExecutionStatus.RECOVERY_REQUIRED);
            execution.reconcileCounts();

            Instant now = Instant.now();
            DestinationPlaylistItemDetails item1 = new DestinationPlaylistItemDetails("item_1", DEST_PLAYLIST_ID, "yt_vid_1", 0, now, "Dup 1");
            DestinationPlaylistItemDetails item2 = new DestinationPlaylistItemDetails("item_2", DEST_PLAYLIST_ID, "yt_vid_1", 1, now, "Dup 2");

            DestinationPlaylistInspection inspection = DestinationPlaylistInspection.complete(
                    DEST_PLAYLIST_ID,
                    List.of(item1, item2),
                    Instant.now()
            );

            when(musicDestination.inspectPlaylist(userId, DEST_PLAYLIST_ID)).thenReturn(inspection);

            ExecutionReconciliationSummary summary = service.reconcileExecution(executionId, userId);

            assertThat(summary.ambiguousCount()).isEqualTo(1);
            assertThat(track0.getStatus()).isEqualTo(ExecutionTrackStatus.UNCERTAIN);
        }

        @Test
        @DisplayName("INSPECTION_FAILED: Provider error on inspection keeps track UNCERTAIN")
        void keepsTrackUncertainWhenProviderInspectionFails() {
            ExecutionTrackRecord track0 = execution.getTrackRecords().get(0);
            track0.markAdding();
            track0.markUncertain("Timeout");
            execution.setStatus(MigrationExecutionStatus.RECOVERY_REQUIRED);
            execution.reconcileCounts();

            DestinationPlaylistInspection inspection = DestinationPlaylistInspection.failed(
                    DEST_PLAYLIST_ID,
                    "Provider rate limit exceeded HTTP 429",
                    Instant.now()
            );

            when(musicDestination.inspectPlaylist(userId, DEST_PLAYLIST_ID)).thenReturn(inspection);

            ExecutionReconciliationSummary summary = service.reconcileExecution(executionId, userId);

            assertThat(summary.inspectionFailedCount()).isEqualTo(1);
            assertThat(track0.getStatus()).isEqualTo(ExecutionTrackStatus.UNCERTAIN);
            assertThat(track0.getErrorMessage()).contains("429");
        }
    }

    @Nested
    @DisplayName("Deletion Reconciliation")
    class DeletionReconciliation {

        @Test
        @DisplayName("CONFIRMED_DELETED: Exact playlistItemId absent from complete inspection -> becomes ROLLED_BACK")
        void confirmsDeletedWhenPlaylistItemAbsent() {
            ExecutionTrackRecord track0 = execution.getTrackRecords().get(0);
            track0.markAdding();
            track0.markAdded("item_del_999");
            track0.markRollingBack();
            track0.markUncertain("Delete timed out");
            execution.setStatus(MigrationExecutionStatus.RECOVERY_REQUIRED);
            execution.reconcileCounts();

            DestinationPlaylistInspection inspection = DestinationPlaylistInspection.complete(
                    DEST_PLAYLIST_ID,
                    Collections.emptyList(),
                    Instant.now()
            );

            when(musicDestination.inspectPlaylist(userId, DEST_PLAYLIST_ID)).thenReturn(inspection);

            ExecutionReconciliationSummary summary = service.reconcileExecution(executionId, userId);

            assertThat(summary.confirmedDeletedCount()).isEqualTo(1);
            assertThat(track0.getStatus()).isEqualTo(ExecutionTrackStatus.ROLLED_BACK);
            assertThat(summary.resolvedExecutionStatus()).isEqualTo(MigrationExecutionStatus.ROLLED_BACK);
        }

        @Test
        @DisplayName("CONFIRMED_PRESENT: Exact playlistItemId still present in playlist -> restores ADDED")
        void confirmsPresentWhenPlaylistItemStillInPlaylist() {
            ExecutionTrackRecord track0 = execution.getTrackRecords().get(0);
            track0.markAdding();
            track0.markAdded("item_still_alive");
            track0.markRollingBack();
            track0.markUncertain("Delete timed out");
            execution.setStatus(MigrationExecutionStatus.RECOVERY_REQUIRED);
            execution.reconcileCounts();

            DestinationPlaylistItemDetails item = new DestinationPlaylistItemDetails(
                    "item_still_alive",
                    DEST_PLAYLIST_ID,
                    "yt_vid_1",
                    0,
                    Instant.now(),
                    "Song"
            );

            DestinationPlaylistInspection inspection = DestinationPlaylistInspection.complete(
                    DEST_PLAYLIST_ID,
                    List.of(item),
                    Instant.now()
            );

            when(musicDestination.inspectPlaylist(userId, DEST_PLAYLIST_ID)).thenReturn(inspection);

            ExecutionReconciliationSummary summary = service.reconcileExecution(executionId, userId);

            assertThat(summary.confirmedPresentCount()).isEqualTo(1);
            assertThat(track0.getStatus()).isEqualTo(ExecutionTrackStatus.ADDED);
            assertThat(track0.getDestinationPlaylistItemId()).isEqualTo("item_still_alive");
        }
    }

    @Nested
    @DisplayName("Stale In-Flight Operations")
    class StaleInFlightOperations {

        @Test
        @DisplayName("Fresh ADDING state (<5 min) is active and rejected from reconciliation")
        void rejectsFreshAddingState() {
            ExecutionTrackRecord track0 = execution.getTrackRecords().get(0);
            track0.markAdding(); // startedAt = now() -> fresh
            execution.setStatus(MigrationExecutionStatus.RECOVERY_REQUIRED);

            assertThatThrownBy(() -> service.reconcileExecution(executionId, userId))
                    .isInstanceOf(InvalidStateException.class)
                    .hasMessageContaining("No uncertain or stale in-flight tracks available");

            verify(musicDestination, never()).inspectPlaylist(any(), any());
        }

        @Test
        @DisplayName("Stale ADDING state (>5 min) is inspected and reconciled")
        void reconcilesStaleAddingState() {
            ExecutionTrackRecord track0 = execution.getTrackRecords().get(0);
            track0.markAdding();
            track0.setStartedAt(Instant.now().minus(Duration.ofMinutes(10))); // 10 min ago
            execution.setStatus(MigrationExecutionStatus.RECOVERY_REQUIRED);

            DestinationPlaylistInspection inspection = DestinationPlaylistInspection.complete(
                    DEST_PLAYLIST_ID,
                    Collections.emptyList(),
                    Instant.now()
            );

            when(musicDestination.inspectPlaylist(userId, DEST_PLAYLIST_ID)).thenReturn(inspection);

            ExecutionReconciliationSummary summary = service.reconcileExecution(executionId, userId);

            assertThat(summary.reconciledTracksCount()).isEqualTo(1);
            assertThat(track0.getStatus()).isEqualTo(ExecutionTrackStatus.FAILED);
        }
    }

    @Nested
    @DisplayName("Concurrency, Locking & Security")
    class ConcurrencyAndSecurity {

        @Test
        @DisplayName("Concurrent claim failure throws InvalidStateException (HTTP 409 Conflict)")
        void throwsConflictWhenExecutionClaimFails() {
            ExecutionTrackRecord track0 = execution.getTrackRecords().get(0);
            track0.markAdding();
            track0.markUncertain("Timeout");
            execution.setStatus(MigrationExecutionStatus.RECOVERY_REQUIRED);

            when(executionRepository.claimExecutionRunningWithVersion(eq(executionId), anyCollection(), anyLong(), any()))
                    .thenReturn(0);

            assertThatThrownBy(() -> service.reconcileExecution(executionId, userId))
                    .isInstanceOf(InvalidStateException.class)
                    .hasMessageContaining("could not be claimed for reconciliation");
        }

        @Test
        @DisplayName("User ID impersonation attempt throws IllegalArgumentException")
        void rejectsUserImpersonation() {
            UUID foreignUser = UUID.randomUUID();
            ReconcileExecutionRequest request = new ReconcileExecutionRequest(foreignUser, null, "test");

            assertThatThrownBy(() -> service.reconcileExecution(executionId, request, userId))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does not match the requested user ID");
        }

        @Test
        @DisplayName("Foreign execution ID throws ResourceNotFoundException (Anti-IDOR)")
        void rejectsForeignExecution() {
            UUID foreignUser = UUID.randomUUID();
            when(executionRepository.findByIdAndUserId(executionId, foreignUser)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.reconcileExecution(executionId, foreignUser))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("Audit History and Reconciliation Status")
    class AuditAndStatus {

        @Test
        @DisplayName("getReconciliationHistory retrieves audit list ordered by createdAt")
        void retrievesReconciliationHistory() {
            ExecutionTrackRecord track0 = execution.getTrackRecords().get(0);
            ExecutionReconciliationRecord record = new ExecutionReconciliationRecord(
                    execution,
                    track0,
                    ReconciliationType.INSERTION,
                    ExecutionTrackStatus.UNCERTAIN,
                    ExecutionTrackStatus.ADDED,
                    ReconciliationOutcome.CONFIRMED_INSERTED,
                    DEST_PLAYLIST_ID,
                    "item_123",
                    "yt_vid_1",
                    "Evidence",
                    Instant.now(),
                    Instant.now(),
                    Instant.now(),
                    "CONFIRMED_INSERTED",
                    null
            );

            when(reconciliationRecordRepository.findByExecutionIdOrderByCreatedAtAsc(executionId))
                    .thenReturn(List.of(record));

            List<ReconciliationRecordDto> history = service.getReconciliationHistory(executionId, userId);

            assertThat(history).hasSize(1);
            assertThat(history.get(0).reconciliationOutcome()).isEqualTo(ReconciliationOutcome.CONFIRMED_INSERTED);
            assertThat(history.get(0).destinationPlaylistItemId()).isEqualTo("item_123");
        }

        @Test
        @DisplayName("getReconciliationStatus correctly flags uncertain tracks requiring reconciliation")
        void retrievesReconciliationStatus() {
            ExecutionTrackRecord track0 = execution.getTrackRecords().get(0);
            track0.markAdding();
            track0.markUncertain("Timeout");
            execution.setStatus(MigrationExecutionStatus.RECOVERY_REQUIRED);
            execution.reconcileCounts();

            ExecutionReconciliationStatusDto status = service.getReconciliationStatus(executionId, userId);

            assertThat(status.reconciliationRequired()).isTrue();
            assertThat(status.uncertainInsertions()).isEqualTo(1);
            assertThat(status.eligibleTracks()).hasSize(1);
            assertThat(status.eligibleTracks().get(0).targetPosition()).isEqualTo(0);
        }
    }
}
