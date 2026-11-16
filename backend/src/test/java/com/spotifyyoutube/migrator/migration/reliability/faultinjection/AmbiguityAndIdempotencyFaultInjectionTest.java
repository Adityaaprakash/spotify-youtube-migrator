package com.spotifyyoutube.migrator.migration.reliability.faultinjection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.migration.application.destination.MusicDestination;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationPlaylist;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationPlaylistInspection;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationPlaylistItemDetails;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationContext;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedTrack;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackRecord;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecution;
import com.spotifyyoutube.migrator.migration.execution.repository.MigrationExecutionRepository;
import com.spotifyyoutube.migrator.migration.reliability.idempotency.ClaimResult;
import com.spotifyyoutube.migrator.migration.reliability.idempotency.DefaultMigrationIdempotencyService;
import com.spotifyyoutube.migrator.migration.reliability.idempotency.MigrationIdempotencyRecord;
import com.spotifyyoutube.migrator.migration.reliability.idempotency.MigrationIdempotencyRepository;
import com.spotifyyoutube.migrator.migration.reliability.reconciliation.DestinationPlaylistReconciliationService;
import com.spotifyyoutube.migrator.migration.reliability.reconciliation.PlaylistCorrelationMarker;
import com.spotifyyoutube.migrator.migration.reliability.reconciliation.PlaylistReconciliationOutcome;
import com.spotifyyoutube.migrator.migration.reliability.reconciliation.PlaylistReconciliationResult;
import com.spotifyyoutube.migrator.migration.reliability.reconciliation.TrackInsertionReconciliationOutcome;
import com.spotifyyoutube.migrator.migration.reliability.reconciliation.TrackInsertionReconciliationResult;
import com.spotifyyoutube.migrator.migration.reliability.reconciliation.TrackInsertionReconciliationService;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewDecisionSource;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSession;
import com.spotifyyoutube.migrator.playlist.domain.Platform;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 13H: Fault-Injection test suite verifying deterministic resolution of YouTube playlist creation ambiguity,
 * track insertion ambiguity (two-read inspection, replication lag, intentional duplicates), and API idempotency
 * lifecycle transitions under concurrency and crash recovery.
 */
@ExtendWith(MockitoExtension.class)
public class AmbiguityAndIdempotencyFaultInjectionTest {

    @Mock
    private MusicDestination musicDestination;

    @Mock
    private MigrationIdempotencyRepository idempotencyRepository;

    @Mock
    private MigrationExecutionRepository executionRepository;

    @Mock
    private PlatformTransactionManager transactionManager;

    private DestinationPlaylistReconciliationService playlistReconciliationService;
    private TrackInsertionReconciliationService trackReconciliationService;
    private DefaultMigrationIdempotencyService idempotencyService;

    private UUID userId;
    private UUID executionId;
    private UUID reviewSessionId;
    private User testUser;
    private ReviewSession reviewSession;
    private MigrationExecution execution;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        executionId = UUID.randomUUID();
        reviewSessionId = UUID.randomUUID();

        testUser = new User("ambiguity-tester@example.com");
        testUser.setId(userId);

        reviewSession = new ReviewSession(testUser, "sp_pl_1", "Source Playlist", UUID.randomUUID());
        reviewSession.setId(reviewSessionId);

        ApprovedTrack track1 = new ApprovedTrack(
                0, "sp_t1", "Track 1", "Artist 1", "yt_v1", "Track 1", "Channel 1",
                Duration.ofMinutes(3), ReviewDecisionSource.AUTOMATIC, 0
        );
        ApprovedTrack track2 = new ApprovedTrack(
                1, "sp_t2", "Track 2", "Artist 2", "yt_v2", "Track 2", "Channel 2",
                Duration.ofMinutes(4), ReviewDecisionSource.AUTOMATIC, 1
        );
        ApprovedTrack track3Duplicate = new ApprovedTrack(
                2, "sp_t1", "Track 1", "Artist 1", "yt_v1", "Track 1", "Channel 1",
                Duration.ofMinutes(3), ReviewDecisionSource.AUTOMATIC, 2
        );

        ApprovedMigrationContext approvedContext = new ApprovedMigrationContext(
                reviewSessionId, UUID.randomUUID(), userId, "sp_pl_1", "Source Playlist",
                "Target Playlist", "Description", false, List.of(track1, track2, track3Duplicate),
                3, 3, 0, Instant.now()
        );

        execution = new MigrationExecution(testUser, reviewSession, approvedContext, "PL_target", "https://yt/PL_target");
        execution.setId(executionId);
        execution.getTrackRecords().forEach(tr -> tr.setId(UUID.randomUUID()));

        playlistReconciliationService = new DestinationPlaylistReconciliationService(musicDestination);
        playlistReconciliationService.setVerificationGracePeriod(Duration.ZERO);

        trackReconciliationService = new TrackInsertionReconciliationService(musicDestination);
        trackReconciliationService.setGracePeriod(Duration.ZERO);

        Clock clock = Clock.fixed(Instant.parse("2026-11-17T00:00:00Z"), ZoneOffset.UTC);
        idempotencyService = new DefaultMigrationIdempotencyService(
                idempotencyRepository,
                executionRepository,
                transactionManager,
                clock,
                new ObjectMapper()
        );

        lenient().when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
    }

    @Nested
    @DisplayName("YouTube Playlist Creation Ambiguity & Marker Reconciliation")
    class PlaylistCreationAmbiguityTests {

        @Test
        @DisplayName("Timeout after creation request reconciles exact match via correlation marker")
        void timeoutAfterCreationReconcilesExactMatchViaMarker() {
            UUID opId = UUID.randomUUID();
            String markedDescription = PlaylistCorrelationMarker.applyMarker("My Cool Playlist", opId);

            DestinationPlaylist existingPlaylist = new DestinationPlaylist(
                    "PL_created_remotely",
                    "My Cool Playlist",
                    markedDescription,
                    "PRIVATE",
                    "https://yt/PL_created_remotely",
                    Platform.YOUTUBE
            );

            when(musicDestination.getUserPlaylists(userId)).thenReturn(List.of(existingPlaylist));

            PlaylistReconciliationResult result = playlistReconciliationService.reconcilePlaylistCreation(
                    userId, opId, "My Cool Playlist"
            );

            assertThat(result.isExactMatch()).isTrue();
            assertThat(result.outcome()).isEqualTo(PlaylistReconciliationOutcome.EXACT_MATCH_FOUND);
            assertThat(result.matchedPlaylist().id()).isEqualTo("PL_created_remotely");
        }

        @Test
        @DisplayName("HTTP 500 error where playlist was not created yields CONCLUSIVELY_ABSENT for safe retry")
        void serverErrorYieldsConclusivelyAbsentForSafeRetry() {
            UUID opId = UUID.randomUUID();

            when(musicDestination.getUserPlaylists(userId)).thenReturn(List.of());

            PlaylistReconciliationResult result = playlistReconciliationService.reconcilePlaylistCreation(
                    userId, opId, "Target Playlist"
            );

            assertThat(result.outcome()).isEqualTo(PlaylistReconciliationOutcome.CONCLUSIVELY_ABSENT);
            assertThat(result.isExactMatch()).isFalse();
        }

        @Test
        @DisplayName("Same title playlist without marker yields TITLE_COLLISION to prevent hijacking")
        void sameTitleWithoutMarkerYieldsTitleCollision() {
            UUID opId = UUID.randomUUID();

            DestinationPlaylist foreignPlaylist = new DestinationPlaylist(
                    "PL_foreign_user_playlist",
                    "Target Playlist",
                    "A playlist created manually with no marker",
                    "PUBLIC",
                    "https://yt/PL_foreign_user_playlist",
                    Platform.YOUTUBE
            );

            when(musicDestination.getUserPlaylists(userId)).thenReturn(List.of(foreignPlaylist));

            PlaylistReconciliationResult result = playlistReconciliationService.reconcilePlaylistCreation(
                    userId, opId, "Target Playlist"
            );

            assertThat(result.outcome()).isEqualTo(PlaylistReconciliationOutcome.TITLE_COLLISION);
            assertThat(result.isExactMatch()).isFalse();
        }

        @Test
        @DisplayName("Multiple playlists with same marker yields MULTIPLE_MATCHING_MARKERS for operator resolution")
        void multiplePlaylistsWithSameMarkerYieldsAmbiguousConflict() {
            UUID opId = UUID.randomUUID();
            String markedDesc = PlaylistCorrelationMarker.applyMarker("Test Playlist", opId);

            DestinationPlaylist pl1 = new DestinationPlaylist("PL_1", "Test Playlist", markedDesc, "https://yt/PL_1", Platform.YOUTUBE);
            DestinationPlaylist pl2 = new DestinationPlaylist("PL_2", "Test Playlist", markedDesc, "https://yt/PL_2", Platform.YOUTUBE);

            when(musicDestination.getUserPlaylists(userId)).thenReturn(List.of(pl1, pl2));

            PlaylistReconciliationResult result = playlistReconciliationService.reconcilePlaylistCreation(
                    userId, opId, "Test Playlist"
            );

            assertThat(result.outcome()).isEqualTo(PlaylistReconciliationOutcome.MULTIPLE_MATCHING_MARKERS);
            assertThat(result.isExactMatch()).isFalse();
        }

        @Test
        @DisplayName("Provider error during inspection returns PROVIDER_ERROR without marking absent")
        void providerErrorReturnsProviderErrorSafely() {
            UUID opId = UUID.randomUUID();
            when(musicDestination.getUserPlaylists(userId)).thenThrow(new RuntimeException("Remote YouTube outage"));

            PlaylistReconciliationResult result = playlistReconciliationService.reconcilePlaylistCreation(
                    userId, opId, "Target Playlist"
            );

            assertThat(result.outcome()).isEqualTo(PlaylistReconciliationOutcome.PROVIDER_ERROR);
            assertThat(result.diagnosticMessage()).contains("Remote YouTube outage");
        }
    }

    @Nested
    @DisplayName("Track Insertion Ambiguity, 2-Read Verification & Intentional Duplicates")
    class TrackInsertionAmbiguityTests {

        @Test
        @DisplayName("Ambiguous write confirmed present on second read after replication lag grace period")
        void ambiguousWriteConfirmedOnSecondRead() {
            UUID ambiguousTrackId = execution.getTrackRecords().get(0).getId();
            execution.getTrackRecords().get(0).markAdding();

            // Read 1 (immediate): Remote replication lag makes track temporarily invisible
            DestinationPlaylistInspection emptyInspection = DestinationPlaylistInspection.complete(
                    "PL_target", List.of(), 0
            );

            // Read 2 (after grace period): Remote cluster replicated the item
            DestinationPlaylistItemDetails item = new DestinationPlaylistItemDetails("yt_item_1", "yt_v1", 0);
            DestinationPlaylistInspection populatedInspection = DestinationPlaylistInspection.complete(
                    "PL_target", List.of(item), 1
            );

            when(musicDestination.inspectPlaylist(userId, "PL_target"))
                    .thenReturn(emptyInspection)
                    .thenReturn(populatedInspection);

            TrackInsertionReconciliationResult result = trackReconciliationService.reconcileTrackInsertion(
                    userId, "PL_target", ambiguousTrackId, execution.getTrackRecords(), () -> false
            );

            assertThat(result.isVerifiedAdded()).isTrue();
            assertThat(result.outcome()).isEqualTo(TrackInsertionReconciliationOutcome.VERIFIED_ADDED);
            assertThat(result.verifiedPlaylistItemId()).isEqualTo("yt_item_1");
        }

        @Test
        @DisplayName("Intentional duplicate tracks in source playlist matched 1-to-1 without skipping or duplicates")
        void intentionalDuplicateTracksMatchedOneToOne() {
            // Track 0 and Track 2 both have destination video ID "yt_v1"
            ExecutionTrackRecord tr0 = execution.getTrackRecords().get(0);
            ExecutionTrackRecord tr1 = execution.getTrackRecords().get(1);
            ExecutionTrackRecord tr2 = execution.getTrackRecords().get(2);

            // Track 0 was already successfully added as item "yt_item_0"
            tr0.markAdding();
            tr0.markAdded("yt_item_0");

            // Track 1 was already successfully added as item "yt_item_1"
            tr1.markAdding();
            tr1.markAdded("yt_item_1");

            // Track 2 is ambiguous after write timeout
            tr2.markAdding();

            // Remote playlist has 3 items:
            // position 0: yt_v1 (item 0)
            // position 1: yt_v2 (item 1)
            // position 2: yt_v1 (item 2 - duplicate!)
            DestinationPlaylistItemDetails it0 = new DestinationPlaylistItemDetails("yt_item_0", "yt_v1", 0);
            DestinationPlaylistItemDetails it1 = new DestinationPlaylistItemDetails("yt_item_1", "yt_v2", 1);
            DestinationPlaylistItemDetails it2 = new DestinationPlaylistItemDetails("yt_item_2", "yt_v1", 2);

            DestinationPlaylistInspection inspection = DestinationPlaylistInspection.complete(
                    "PL_target", List.of(it0, it1, it2), 3
            );

            when(musicDestination.inspectPlaylist(userId, "PL_target")).thenReturn(inspection);

            TrackInsertionReconciliationResult result = trackReconciliationService.reconcileTrackInsertion(
                    userId, "PL_target", tr2.getId(), execution.getTrackRecords(), () -> false
            );

            // Crucial: Track 2 matches occurrence 2 ("yt_item_2") rather than falsely claiming occurrence 1 ("yt_item_0")
            assertThat(result.isVerifiedAdded()).isTrue();
            assertThat(result.outcome()).isEqualTo(TrackInsertionReconciliationOutcome.VERIFIED_ADDED);
            assertThat(result.verifiedPlaylistItemId()).isEqualTo("yt_item_2");
        }

        @Test
        @DisplayName("Track absent on both inspection reads yields CONFIRMED_ABSENT for safe retry")
        void trackAbsentOnBothReadsYieldsConfirmedAbsent() {
            UUID ambiguousTrackId = execution.getTrackRecords().get(0).getId();
            execution.getTrackRecords().get(0).markAdding();

            DestinationPlaylistInspection emptyInspection = DestinationPlaylistInspection.complete(
                    "PL_target", List.of(), 0
            );

            when(musicDestination.inspectPlaylist(userId, "PL_target"))
                    .thenReturn(emptyInspection);

            TrackInsertionReconciliationResult result = trackReconciliationService.reconcileTrackInsertion(
                    userId, "PL_target", ambiguousTrackId, execution.getTrackRecords(), () -> false
            );

            assertThat(result.isConfirmedAbsent()).isTrue();
            assertThat(result.outcome()).isEqualTo(TrackInsertionReconciliationOutcome.CONFIRMED_ABSENT);
        }

        @Test
        @DisplayName("Incomplete remote inspection does not make destructive decisions")
        void incompleteRemoteInspectionFailsSafely() {
            UUID ambiguousTrackId = execution.getTrackRecords().get(0).getId();

            DestinationPlaylistInspection failedInspection = DestinationPlaylistInspection.failed(
                    "PL_target", "API Quota Exceeded during pagination", Instant.now()
            );

            when(musicDestination.inspectPlaylist(userId, "PL_target")).thenReturn(failedInspection);

            TrackInsertionReconciliationResult result = trackReconciliationService.reconcileTrackInsertion(
                    userId, "PL_target", ambiguousTrackId, execution.getTrackRecords(), () -> false
            );

            assertThat(result.isInspectionFailed()).isTrue();
            assertThat(result.outcome()).isEqualTo(TrackInsertionReconciliationOutcome.INSPECTION_FAILED);
        }
    }

    @Nested
    @DisplayName("API Idempotency Concurrency, Fingerprint Validation & Crash Recovery")
    class IdempotencyFaultInjectionTests {

        @Test
        @DisplayName("State A: Concurrent duplicate request with same key and fingerprint receives PENDING state")
        void concurrentDuplicateRequestReceivesPending() {
            String key = "idem-key-123";
            String fingerprint = "sha256-payload-abc";

            MigrationIdempotencyRecord activeRecord = MigrationIdempotencyRecord.createPending(
                    userId, key, fingerprint, reviewSessionId,
                    "leader-worker", 1L, Instant.now().plusSeconds(60)
            );

            when(idempotencyRepository.findByUserIdAndIdempotencyKey(userId, key))
                    .thenReturn(Optional.of(activeRecord));
            when(idempotencyRepository.isClaimActiveDatabaseTime(activeRecord.getId()))
                    .thenReturn(true);

            ClaimResult result = idempotencyService.acquireClaim(userId, key, fingerprint, reviewSessionId);

            assertThat(result.isPending()).isTrue();
            assertThat(result.isMismatch()).isFalse();
            assertThat(result.isReplay()).isFalse();
        }

        @Test
        @DisplayName("Fingerprint mismatch on reused key immediately returns MISMATCH conflict")
        void fingerprintMismatchReturnsConflict() {
            String key = "idem-key-123";
            String originalFingerprint = "sha256-original";
            String mutatedFingerprint = "sha256-altered-body";

            MigrationIdempotencyRecord existingRecord = MigrationIdempotencyRecord.createPending(
                    userId, key, originalFingerprint, reviewSessionId,
                    "leader-worker", 1L, Instant.now().plusSeconds(60)
            );

            when(idempotencyRepository.findByUserIdAndIdempotencyKey(userId, key))
                    .thenReturn(Optional.of(existingRecord));

            ClaimResult result = idempotencyService.acquireClaim(userId, key, mutatedFingerprint, reviewSessionId);

            assertThat(result.isMismatch()).isTrue();
            assertThat(result.message()).contains("previously used with a different request payload");
        }

        @Test
        @DisplayName("State C: Completed migration execution returns REPLAY without re-executing")
        void completedMigrationReturnsReplayWithoutExecuting() {
            String key = "idem-key-456";
            String fingerprint = "sha256-same-body";

            MigrationIdempotencyRecord completedRecord = new MigrationIdempotencyRecord(
                    userId, key, fingerprint, executionId,
                    200, "{\"status\":\"COMPLETED\"}",
                    Instant.now().minusSeconds(100), Instant.now().plusSeconds(3600)
            );
            completedRecord.setDestinationPlaylistId("PL_target");

            when(idempotencyRepository.findByUserIdAndIdempotencyKey(userId, key))
                    .thenReturn(Optional.of(completedRecord));

            ClaimResult result = idempotencyService.acquireClaim(userId, key, fingerprint, reviewSessionId);

            assertThat(result.isReplay()).isTrue();
            assertThat(result.getRecord()).isPresent();
            assertThat(result.getRecord().get().getDestinationPlaylistId()).isEqualTo("PL_target");
            assertThat(result.getRecord().get().getExecutionId()).isEqualTo(executionId);
        }

        @Test
        @DisplayName("State B: Expired claim from crashed leader is safely stolen by successor with incremented generation")
        void expiredClaimFromCrashedLeaderStolenBySuccessor() {
            String key = "idem-key-789";
            String fingerprint = "sha256-crash-recovery";

            MigrationIdempotencyRecord expiredRecord = MigrationIdempotencyRecord.createPending(
                    userId, key, fingerprint, reviewSessionId,
                    "crashed-worker", 1L, Instant.now().minusSeconds(30) // Expired!
            );

            when(idempotencyRepository.findByUserIdAndIdempotencyKey(userId, key))
                    .thenReturn(Optional.of(expiredRecord));
            when(idempotencyRepository.isClaimActiveDatabaseTime(expiredRecord.getId()))
                    .thenReturn(false);

            when(idempotencyRepository.takeOverClaimWithFencingDatabaseTime(
                    eq(expiredRecord.getId()), anyString(), eq(1L), anyLong()
            )).thenReturn(1);

            ClaimResult result = idempotencyService.acquireClaim(userId, key, fingerprint, reviewSessionId);

            assertThat(result.isAcquired()).isTrue();
            verify(idempotencyRepository).takeOverClaimWithFencingDatabaseTime(
                    eq(expiredRecord.getId()), anyString(), eq(1L), anyLong()
            );
        }
    }
}
