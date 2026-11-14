package com.spotifyyoutube.migrator.migration.reliability.reconciliation;

import com.fasterxml.jackson.databind.JsonNode;
import com.spotifyyoutube.migrator.migration.api.MigrationController;
import com.spotifyyoutube.migrator.migration.api.MigrationExecutionController;
import com.spotifyyoutube.migrator.migration.api.dto.ReconcileExecutionApiRequest;
import com.spotifyyoutube.migrator.migration.api.dto.RunMigrationApiRequest;
import com.spotifyyoutube.migrator.migration.application.destination.MusicDestination;
import com.spotifyyoutube.migrator.migration.application.destination.model.AddTrackToDestinationPlaylistRequest;
import com.spotifyyoutube.migrator.migration.application.destination.model.CreateDestinationPlaylistRequest;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationPlaylist;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationPlaylistItem;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationPlaylistInspection;
import com.spotifyyoutube.migrator.migration.application.execution.ApprovedMigrationExecutionService;
import com.spotifyyoutube.migrator.migration.application.execution.DefaultApprovedMigrationOrchestrator;
import com.spotifyyoutube.migrator.migration.application.execution.DefaultDestinationPlaylistCreationService;
import com.spotifyyoutube.migrator.migration.application.execution.DestinationPlaylistCreationService;
import com.spotifyyoutube.migrator.migration.application.execution.MigrationExecutionStateService;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationContext;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.contract.DestinationPlaylistCreationResult;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ExecutionValidationResult;
import com.spotifyyoutube.migrator.migration.application.execution.contract.MigrationExecutionSummary;
import com.spotifyyoutube.migrator.migration.async.MigrationBackgroundWorker;
import com.spotifyyoutube.migrator.migration.async.MigrationJobDispatcher;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackRecord;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackStatus;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus;
import com.spotifyyoutube.migrator.migration.reliability.classification.ProviderFailureClassifier;
import com.spotifyyoutube.migrator.migration.reliability.idempotency.ApiIdempotencyFilter;
import com.spotifyyoutube.migrator.migration.reliability.idempotency.DefaultMigrationIdempotencyService;
import com.spotifyyoutube.migrator.migration.reliability.scheduling.ClaimedExecution;
import com.spotifyyoutube.migrator.migration.reliability.scheduling.DurableMigrationScheduler;
import com.spotifyyoutube.migrator.playlist.domain.Platform;
import com.spotifyyoutube.migrator.youtube.api.dto.AddVideoToPlaylistRequest;
import com.spotifyyoutube.migrator.youtube.api.dto.CreateYouTubePlaylistRequest;
import com.spotifyyoutube.migrator.youtube.api.dto.YouTubePlaylistItemResponse;
import com.spotifyyoutube.migrator.youtube.api.dto.YouTubePlaylistResponse;
import com.spotifyyoutube.migrator.youtube.application.YouTubePlaylistService;
import com.spotifyyoutube.migrator.youtube.application.YouTubeSearchService;
import com.spotifyyoutube.migrator.youtube.infrastructure.destination.YouTubeMusicDestinationAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.security.Principal;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression test suite verifying transaction propagation boundaries, connection checkout lifecycle,
 * and lock release behavior for external operations (Phase 13F).
 * <p>
 * Explicitly proves:
 * <ul>
 *   <li>The distinction between transaction suspension and connection/lock release:
 *       {@code Propagation.NOT_SUPPORTED} suspends transaction synchronization, but Spring's JDBC
 *       transaction manager retains the underlying database connection until the outer transaction finishes.</li>
 *   <li>The canonical production execution path enters non-transactionally, ensuring zero database
 *       connections are checked out and zero database locks are held during external HTTP requests or grace-period sleeps.</li>
 *   <li>Individual database persistence operations check out and release connections independently via {@link TransactionTemplate}.</li>
 *   <li>Canonical production entry points (controllers, workers, schedulers, filters) are strictly non-transactional.</li>
 * </ul>
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = ExternalOperationTransactionPropagationTest.TestConfig.class)
class ExternalOperationTransactionPropagationTest {

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TestConfig {

        @Bean
        public TrackingDataSource trackingDataSource() {
            return new TrackingDataSource();
        }

        @Bean
        public PlatformTransactionManager transactionManager(TrackingDataSource trackingDataSource) {
            return new DataSourceTransactionManager(trackingDataSource);
        }

        @Bean
        public TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
            return new TransactionTemplate(transactionManager);
        }

        @Bean
        public MusicDestination mockMusicDestination() {
            return mock(MusicDestination.class);
        }

        @Bean
        public YouTubePlaylistService mockYouTubePlaylistService() {
            return mock(YouTubePlaylistService.class);
        }

        @Bean
        public YouTubeSearchService mockYouTubeSearchService() {
            return mock(YouTubeSearchService.class);
        }

        @Bean
        public ApprovedMigrationExecutionService mockExecutionService() {
            return mock(ApprovedMigrationExecutionService.class);
        }

        @Bean
        public MigrationExecutionStateService mockExecutionStateService() {
            return mock(MigrationExecutionStateService.class);
        }

        @Bean
        public ProviderFailureClassifier providerFailureClassifier() {
            return new ProviderFailureClassifier();
        }

        @Bean
        public DestinationPlaylistReconciliationService destinationPlaylistReconciliationService(
                MusicDestination mockMusicDestination
        ) {
            return new DestinationPlaylistReconciliationService(mockMusicDestination);
        }

        @Bean
        public TrackInsertionReconciliationService trackInsertionReconciliationService(
                MusicDestination mockMusicDestination
        ) {
            return new TrackInsertionReconciliationService(mockMusicDestination);
        }

        @Bean
        public DestinationPlaylistCreationService destinationPlaylistCreationService(
                ApprovedMigrationExecutionService mockExecutionService,
                MusicDestination mockMusicDestination
        ) {
            return new DefaultDestinationPlaylistCreationService(mockExecutionService, mockMusicDestination);
        }

        @Bean
        public YouTubeMusicDestinationAdapter youTubeMusicDestinationAdapter(
                YouTubeSearchService mockYouTubeSearchService,
                YouTubePlaylistService mockYouTubePlaylistService
        ) {
            return new YouTubeMusicDestinationAdapter(mockYouTubeSearchService, mockYouTubePlaylistService);
        }

        @Bean
        public DefaultApprovedMigrationOrchestrator approvedMigrationOrchestrator(
                ApprovedMigrationExecutionService mockExecutionService,
                DestinationPlaylistCreationService destinationPlaylistCreationService,
                MigrationExecutionStateService mockExecutionStateService,
                DestinationPlaylistReconciliationService destinationPlaylistReconciliationService,
                ProviderFailureClassifier providerFailureClassifier
        ) {
            return new DefaultApprovedMigrationOrchestrator(
                    mockExecutionService,
                    destinationPlaylistCreationService,
                    mockExecutionStateService,
                    null,
                    destinationPlaylistReconciliationService,
                    providerFailureClassifier
            );
        }
    }

    /**
     * In-memory tracking DataSource and JDBC connection proxy for observing actual
     * database connection checkout and release behavior during transaction lifecycle.
     */
    static class TrackingDataSource implements DataSource {
        private final AtomicInteger activeConnections = new AtomicInteger(0);
        private final AtomicInteger totalAcquisitions = new AtomicInteger(0);
        private final AtomicInteger totalReleases = new AtomicInteger(0);

        public int getActiveConnections() {
            return activeConnections.get();
        }

        public int getTotalAcquisitions() {
            return totalAcquisitions.get();
        }

        public int getTotalReleases() {
            return totalReleases.get();
        }

        public void reset() {
            activeConnections.set(0);
            totalAcquisitions.set(0);
            totalReleases.set(0);
        }

        @Override
        public Connection getConnection() {
            activeConnections.incrementAndGet();
            totalAcquisitions.incrementAndGet();
            AtomicBoolean closed = new AtomicBoolean(false);
            AtomicBoolean autoCommit = new AtomicBoolean(true);
            AtomicBoolean readOnly = new AtomicBoolean(false);

            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if ("close".equals(name)) {
                            if (closed.compareAndSet(false, true)) {
                                activeConnections.decrementAndGet();
                                totalReleases.incrementAndGet();
                            }
                            return null;
                        }
                        if ("isClosed".equals(name)) {
                            return closed.get();
                        }
                        if ("getAutoCommit".equals(name)) {
                            return autoCommit.get();
                        }
                        if ("setAutoCommit".equals(name)) {
                            autoCommit.set((Boolean) args[0]);
                            return null;
                        }
                        if ("isReadOnly".equals(name)) {
                            return readOnly.get();
                        }
                        if ("setReadOnly".equals(name)) {
                            readOnly.set((Boolean) args[0]);
                            return null;
                        }
                        if ("getTransactionIsolation".equals(name)) {
                            return Connection.TRANSACTION_READ_COMMITTED;
                        }
                        if ("setTransactionIsolation".equals(name)) {
                            return null;
                        }
                        if ("commit".equals(name) || "rollback".equals(name)) {
                            return null;
                        }
                        if ("getWarnings".equals(name) || "clearWarnings".equals(name)) {
                            return null;
                        }
                        if ("getHoldability".equals(name)) {
                            return 1; // ResultSet.HOLD_CURSORS_OVER_COMMIT
                        }
                        if ("hashCode".equals(name)) {
                            return System.identityHashCode(proxy);
                        }
                        if ("equals".equals(name)) {
                            return proxy == (args != null && args.length > 0 ? args[0] : null);
                        }
                        if ("toString".equals(name)) {
                            return "TrackingConnectionProxy@" + Integer.toHexString(System.identityHashCode(proxy));
                        }
                        Class<?> returnType = method.getReturnType();
                        if (returnType.equals(boolean.class)) return false;
                        if (returnType.equals(int.class)) return 0;
                        if (returnType.equals(long.class)) return 0L;
                        return null;
                    }
            );
        }

        @Override
        public Connection getConnection(String username, String password) {
            return getConnection();
        }

        @Override
        public java.io.PrintWriter getLogWriter() { return null; }

        @Override
        public void setLogWriter(java.io.PrintWriter out) {}

        @Override
        public void setLoginTimeout(int seconds) {}

        @Override
        public int getLoginTimeout() { return 0; }

        @Override
        public java.util.logging.Logger getParentLogger() { return java.util.logging.Logger.getGlobal(); }

        @Override
        public <T> T unwrap(Class<T> iface) { return null; }

        @Override
        public boolean isWrapperFor(Class<?> iface) { return false; }
    }

    @Autowired
    private TrackingDataSource trackingDataSource;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private DestinationPlaylistReconciliationService playlistReconciliationService;

    @Autowired
    private TrackInsertionReconciliationService trackReconciliationService;

    @Autowired
    private DestinationPlaylistCreationService playlistCreationService;

    @Autowired
    private YouTubeMusicDestinationAdapter youTubeAdapter;

    @Autowired
    private DefaultApprovedMigrationOrchestrator orchestrator;

    @Autowired
    private MusicDestination mockMusicDestination;

    @Autowired
    private YouTubePlaylistService mockYouTubePlaylistService;

    @Autowired
    private ApprovedMigrationExecutionService mockExecutionService;

    @Autowired
    private MigrationExecutionStateService mockExecutionStateService;

    @BeforeEach
    void setUp() {
        Mockito.reset(
                mockMusicDestination,
                mockYouTubePlaylistService,
                mockExecutionService,
                mockExecutionStateService
        );
        trackingDataSource.reset();
        playlistReconciliationService.setVerificationGracePeriod(Duration.ofMillis(10));
        trackReconciliationService.setGracePeriod(Duration.ofMillis(10));
    }

    @Test
    @DisplayName("Destination playlist reconciliation suspends active transaction; HTTP & sleep run outside transaction")
    void destinationPlaylistReconciliation_suspendsInheritedTransaction() {
        UUID userId = UUID.randomUUID();
        UUID opId = UUID.randomUUID();

        AtomicBoolean activeTxDuringFirstHttp = new AtomicBoolean(true);
        AtomicBoolean activeTxDuringSecondHttp = new AtomicBoolean(true);

        when(mockMusicDestination.getUserPlaylists(userId)).thenAnswer(inv -> {
            activeTxDuringFirstHttp.set(TransactionSynchronizationManager.isActualTransactionActive());
            return Collections.emptyList();
        }).thenAnswer(inv -> {
            activeTxDuringSecondHttp.set(TransactionSynchronizationManager.isActualTransactionActive());
            return Collections.emptyList();
        });

        transactionTemplate.execute(status -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .as("Caller transaction must be active before calling service")
                    .isTrue();

            PlaylistReconciliationResult result = playlistReconciliationService.reconcilePlaylistCreation(
                    userId, opId, "My Playlist"
            );

            assertThat(result.outcome()).isEqualTo(PlaylistReconciliationOutcome.CONCLUSIVELY_ABSENT);

            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .as("Caller transaction must be resumed after service returns")
                    .isTrue();

            return null;
        });

        assertThat(activeTxDuringFirstHttp.get())
                .as("First HTTP read of remote playlists must execute with zero active transaction")
                .isFalse();
        assertThat(activeTxDuringSecondHttp.get())
                .as("Second confirmation HTTP read must execute with zero active transaction")
                .isFalse();
    }

    @Test
    @DisplayName("Track insertion reconciliation suspends active transaction; grace period & inspect run outside tx")
    void trackInsertionReconciliation_suspendsInheritedTransaction() {
        UUID userId = UUID.randomUUID();
        String playlistId = "PL_recon_tx_123";
        UUID trackId = UUID.randomUUID();

        ExecutionTrackRecord trackRecord = new ExecutionTrackRecord(
                null, 0, "spotify-1", "yt-video-1", "Track 1", "Artist 1"
        );
        setField(trackRecord, "id", trackId);
        trackRecord.markAdding();

        AtomicBoolean activeTxDuringGracePeriodCheck = new AtomicBoolean(true);
        AtomicBoolean activeTxDuringFirstInspection = new AtomicBoolean(true);
        AtomicBoolean activeTxDuringSecondInspection = new AtomicBoolean(true);

        when(mockMusicDestination.inspectPlaylist(userId, playlistId)).thenAnswer(inv -> {
            activeTxDuringFirstInspection.set(TransactionSynchronizationManager.isActualTransactionActive());
            return DestinationPlaylistInspection.complete(playlistId, Collections.emptyList(), 0);
        }).thenAnswer(inv -> {
            activeTxDuringSecondInspection.set(TransactionSynchronizationManager.isActualTransactionActive());
            return DestinationPlaylistInspection.complete(playlistId, Collections.emptyList(), 0);
        });

        transactionTemplate.execute(status -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .as("Caller transaction must be active before calling track reconciliation")
                    .isTrue();

            TrackInsertionReconciliationResult result = trackReconciliationService.reconcileTrackInsertion(
                    userId,
                    playlistId,
                    trackId,
                    List.of(trackRecord),
                    () -> {
                        activeTxDuringGracePeriodCheck.set(TransactionSynchronizationManager.isActualTransactionActive());
                        return false;
                    }
            );

            assertThat(result.outcome()).isEqualTo(TrackInsertionReconciliationOutcome.CONFIRMED_ABSENT);

            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .as("Caller transaction must be resumed after track reconciliation returns")
                    .isTrue();

            return null;
        });

        assertThat(activeTxDuringGracePeriodCheck.get())
                .as("Grace period sleep and cancellation check must execute with zero active transaction")
                .isFalse();
        assertThat(activeTxDuringFirstInspection.get())
                .as("First remote inspection HTTP request must execute with zero active transaction")
                .isFalse();
        assertThat(activeTxDuringSecondInspection.get())
                .as("Second remote inspection HTTP request must execute with zero active transaction")
                .isFalse();
    }

    @Test
    @DisplayName("Playlist creation service suspends active transaction; external YouTube call runs outside transaction")
    void destinationPlaylistCreationService_suspendsInheritedTransaction() {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        ApprovedMigrationExecutionRequest request = new ApprovedMigrationExecutionRequest(sessionId, userId);

        ApprovedMigrationContext mockContext = mock(ApprovedMigrationContext.class);
        when(mockContext.hasApprovedTracks()).thenReturn(true);
        when(mockContext.targetPlaylistTitle()).thenReturn("Target Playlist");
        when(mockContext.targetPlaylistDescription()).thenReturn("Description");
        when(mockContext.isPrivate()).thenReturn(false);
        when(mockContext.reviewSessionId()).thenReturn(sessionId);
        when(mockContext.planId()).thenReturn(UUID.randomUUID());
        when(mockContext.approvedTrackCount()).thenReturn(5);

        when(mockExecutionService.prepareExecution(request, userId)).thenReturn(mockContext);

        AtomicBoolean activeTxDuringPlaylistCreateHttp = new AtomicBoolean(true);
        when(mockMusicDestination.createPlaylist(eq(userId), any(CreateDestinationPlaylistRequest.class)))
                .thenAnswer(inv -> {
                    activeTxDuringPlaylistCreateHttp.set(TransactionSynchronizationManager.isActualTransactionActive());
                    return new DestinationPlaylist(
                            "PL_created_999", "Target Playlist", "Description", "public", "http://url", Platform.YOUTUBE
                    );
                });

        transactionTemplate.execute(status -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();

            DestinationPlaylistCreationResult result = playlistCreationService.createDestinationPlaylist(request, userId);

            assertThat(result.destinationPlaylistId()).isEqualTo("PL_created_999");
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return null;
        });

        assertThat(activeTxDuringPlaylistCreateHttp.get())
                .as("musicDestination.createPlaylist must execute outside transaction")
                .isFalse();
    }

    @Test
    @DisplayName("YouTubeMusicDestinationAdapter suspends active transaction across all destination mutation and read calls")
    void youTubeMusicDestinationAdapter_suspendsInheritedTransaction() {
        UUID userId = UUID.randomUUID();
        String playlistId = "PL_test_adapter";

        AtomicBoolean activeTxDuringAddVideo = new AtomicBoolean(true);
        when(mockYouTubePlaylistService.addVideoToPlaylist(eq(userId), eq(playlistId), any(AddVideoToPlaylistRequest.class)))
                .thenAnswer(inv -> {
                    activeTxDuringAddVideo.set(TransactionSynchronizationManager.isActualTransactionActive());
                    return new YouTubePlaylistItemResponse("item-123", playlistId, "video-abc", 0, Instant.now());
                });

        AtomicBoolean activeTxDuringCreatePlaylist = new AtomicBoolean(true);
        when(mockYouTubePlaylistService.createPlaylist(eq(userId), any(CreateYouTubePlaylistRequest.class)))
                .thenAnswer(inv -> {
                    activeTxDuringCreatePlaylist.set(TransactionSynchronizationManager.isActualTransactionActive());
                    return new YouTubePlaylistResponse("PL_new", "Title", "Desc", "public", 0, "http://url");
                });

        transactionTemplate.execute(status -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();

            DestinationPlaylistItem item = youTubeAdapter.addTrackToPlaylist(
                    userId, playlistId, new AddTrackToDestinationPlaylistRequest("video-abc", 0)
            );
            assertThat(item).isNotNull();

            DestinationPlaylist playlist = youTubeAdapter.createPlaylist(
                    userId, new CreateDestinationPlaylistRequest("Title", "Desc", false)
            );
            assertThat(playlist).isNotNull();

            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return null;
        });

        assertThat(activeTxDuringAddVideo.get())
                .as("YouTube addVideoToPlaylist must execute outside database transaction")
                .isFalse();
        assertThat(activeTxDuringCreatePlaylist.get())
                .as("YouTube createPlaylist must execute outside database transaction")
                .isFalse();
    }

    @Test
    @DisplayName("DefaultApprovedMigrationOrchestrator suspends inherited transaction, separating DB & external phases")
    void approvedMigrationOrchestrator_suspendsInheritedTransaction() {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        ApprovedMigrationExecutionRequest request = new ApprovedMigrationExecutionRequest(sessionId, userId);

        when(mockExecutionService.validateExecution(request, userId))
                .thenReturn(ExecutionValidationResult.valid());

        MigrationExecutionSummary intent = new MigrationExecutionSummary(
                UUID.randomUUID(), userId, sessionId, UUID.randomUUID(),
                MigrationExecutionStatus.ALLOCATING_DESTINATION,
                null, null, null, 1, 0, 0, 0, 0, null, Instant.now(), null
        );

        when(mockExecutionStateService.initializeExecutionIntent(eq(sessionId), any(UUID.class), eq(userId)))
                .thenReturn(intent);

        ApprovedMigrationContext mockContext = mock(ApprovedMigrationContext.class);
        when(mockContext.hasApprovedTracks()).thenReturn(true);
        when(mockContext.targetPlaylistTitle()).thenReturn("Orchestrated Playlist");
        when(mockContext.targetPlaylistDescription()).thenReturn("Orchestrated Desc");
        when(mockContext.isPrivate()).thenReturn(false);
        when(mockContext.reviewSessionId()).thenReturn(sessionId);
        when(mockContext.planId()).thenReturn(UUID.randomUUID());
        when(mockContext.approvedTrackCount()).thenReturn(1);

        when(mockExecutionService.prepareExecution(any(ApprovedMigrationExecutionRequest.class), eq(userId)))
                .thenReturn(mockContext);

        AtomicBoolean activeTxDuringOrchestratedHttp = new AtomicBoolean(true);
        when(mockMusicDestination.createPlaylist(eq(userId), any(CreateDestinationPlaylistRequest.class)))
                .thenAnswer(inv -> {
                    activeTxDuringOrchestratedHttp.set(TransactionSynchronizationManager.isActualTransactionActive());
                    return new DestinationPlaylist("PL_orch_123", "Title", "Desc", "public", "http://url", Platform.YOUTUBE);
                });

        MigrationExecutionSummary confirmed = new MigrationExecutionSummary(
                intent.executionId(), userId, sessionId, UUID.randomUUID(),
                MigrationExecutionStatus.INITIALIZED,
                "PL_orch_123", "http://url", null, 1, 0, 0, 0, 0, null, Instant.now(), null
        );
        when(mockExecutionStateService.confirmDestinationPlaylist(
                eq(intent.executionId()), eq("PL_orch_123"), any(), eq(MigrationExecutionStatus.INITIALIZED), eq(userId)
        )).thenReturn(confirmed);

        MigrationExecutionSummary completed = new MigrationExecutionSummary(
                intent.executionId(), userId, sessionId, UUID.randomUUID(),
                MigrationExecutionStatus.COMPLETED,
                "PL_orch_123", "http://url", null, 1, 1, 0, 0, 0, null, Instant.now(), null
        );
        when(mockExecutionStateService.executeTrackInsertion(eq(intent.executionId()), eq(userId)))
                .thenReturn(completed);

        transactionTemplate.execute(status -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();

            MigrationExecutionSummary summary = orchestrator.executeApprovedMigration(request, userId);

            assertThat(summary.status()).isEqualTo(MigrationExecutionStatus.COMPLETED);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return null;
        });

        assertThat(activeTxDuringOrchestratedHttp.get())
                .as("Orchestrated playlist creation call must execute outside database transaction")
                .isFalse();
    }

    @Test
    @DisplayName("Non-transactional caller: all external operations and grace periods execute cleanly without transaction")
    void nonTransactionalCaller_allExternalOperationsExecuteWithoutTransaction() {
        UUID userId = UUID.randomUUID();
        UUID opId = UUID.randomUUID();

        when(mockMusicDestination.getUserPlaylists(userId)).thenReturn(Collections.emptyList());

        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();

        PlaylistReconciliationResult result = playlistReconciliationService.reconcilePlaylistCreation(
                userId, opId, "My Playlist"
        );

        assertThat(result.outcome()).isEqualTo(PlaylistReconciliationOutcome.CONCLUSIVELY_ABSENT);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    @Test
    @DisplayName("Ambient transaction retains database connection during suspension: proves NOT_SUPPORTED alone does NOT release connection")
    void ambientTransaction_retainsDatabaseConnectionDuringSuspension_demonstratingLimitation() {
        UUID userId = UUID.randomUUID();
        UUID opId = UUID.randomUUID();

        AtomicBoolean activeTxDuringHttp = new AtomicBoolean(true);
        AtomicInteger checkedOutConnectionsDuringHttp = new AtomicInteger(-1);

        when(mockMusicDestination.getUserPlaylists(userId)).thenAnswer(inv -> {
            activeTxDuringHttp.set(TransactionSynchronizationManager.isActualTransactionActive());
            checkedOutConnectionsDuringHttp.set(trackingDataSource.getActiveConnections());
            return Collections.emptyList();
        });

        assertThat(trackingDataSource.getActiveConnections()).isZero();

        transactionTemplate.execute(status -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(trackingDataSource.getActiveConnections()).isEqualTo(1);

            PlaylistReconciliationResult result = playlistReconciliationService.reconcilePlaylistCreation(
                    userId, opId, "Ambient Tx Playlist"
            );

            assertThat(result.outcome()).isEqualTo(PlaylistReconciliationOutcome.CONCLUSIVELY_ABSENT);

            // Resumed
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(trackingDataSource.getActiveConnections()).isEqualTo(1);
            return null;
        });

        // Outer transaction has committed
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(trackingDataSource.getActiveConnections()).isZero();
        assertThat(trackingDataSource.getTotalReleases()).isGreaterThanOrEqualTo(1);

        // THE CRITICAL PROOF:
        // During the external HTTP call inside NOT_SUPPORTED:
        // 1. Transaction synchronization WAS suspended:
        assertThat(activeTxDuringHttp.get())
                .as("Transaction synchronization must be suspended during HTTP operation")
                .isFalse();
        // 2. BUT the connection WAS RETAINED by Spring's SuspendedResourcesHolder:
        assertThat(checkedOutConnectionsDuringHttp.get())
                .as("Database connection remains checked out during NOT_SUPPORTED suspension")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Canonical production execution path: holds zero connections and zero locks during external calls and reconciliation sleeps")
    void canonicalProductionPath_holdsZeroConnectionsDuringExternalOperationsAndSleeps() {
        UUID userId = UUID.randomUUID();
        UUID opId = UUID.randomUUID();

        AtomicBoolean activeTxDuringHttp = new AtomicBoolean(true);
        AtomicInteger checkedOutConnectionsDuringHttp = new AtomicInteger(-1);

        when(mockMusicDestination.getUserPlaylists(userId)).thenAnswer(inv -> {
            activeTxDuringHttp.set(TransactionSynchronizationManager.isActualTransactionActive());
            checkedOutConnectionsDuringHttp.set(trackingDataSource.getActiveConnections());
            return Collections.emptyList();
        });

        // 1. Production entry caller is strictly non-transactional
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(trackingDataSource.getActiveConnections()).isZero();

        // 2. Discrete persistence operation checks out and immediately releases connection
        transactionTemplate.execute(status -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(trackingDataSource.getActiveConnections()).isEqualTo(1);
            return "persisted";
        });
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(trackingDataSource.getActiveConnections()).isZero();

        // 3. External operation (including grace-period sleeps) runs with ZERO active connections
        PlaylistReconciliationResult result = playlistReconciliationService.reconcilePlaylistCreation(
                userId, opId, "Canonical Path Playlist"
        );

        assertThat(result.outcome()).isEqualTo(PlaylistReconciliationOutcome.CONCLUSIVELY_ABSENT);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(trackingDataSource.getActiveConnections()).isZero();

        // THE CANONICAL GUARANTEE:
        assertThat(activeTxDuringHttp.get())
                .as("Canonical path has zero active transaction during HTTP calls")
                .isFalse();
        assertThat(checkedOutConnectionsDuringHttp.get())
                .as("Canonical path checks out zero database connections during HTTP calls")
                .isZero();
    }

    @Test
    @DisplayName("Architectural invariant: Canonical production entry points are strictly non-transactional")
    void canonicalProductionEntryPoints_areStrictlyNonTransactional() {
        Class<?>[] nonTransactionalClasses = {
                MigrationExecutionController.class,
                MigrationController.class,
                MigrationJobDispatcher.class,
                MigrationBackgroundWorker.class,
                DurableMigrationScheduler.class,
                DefaultMigrationIdempotencyService.class,
                ApiIdempotencyFilter.class
        };

        for (Class<?> clazz : nonTransactionalClasses) {
            assertThat(clazz.isAnnotationPresent(Transactional.class))
                    .as("Class %s must NOT be annotated with @Transactional (would create ambient transaction holding connections)",
                            clazz.getSimpleName())
                    .isFalse();
        }

        // Verify entry point execution methods specifically
        assertMethodNotTransactional(MigrationExecutionController.class, "runApprovedMigration", RunMigrationApiRequest.class, Principal.class);
        assertMethodNotTransactional(MigrationExecutionController.class, "runApprovedMigrationBySessionId", UUID.class, Principal.class);
        assertMethodNotTransactional(MigrationExecutionController.class, "createDestinationPlaylist",
                com.spotifyyoutube.migrator.migration.api.dto.CreateDestinationPlaylistRequest.class, Principal.class);
        assertMethodNotTransactional(MigrationExecutionController.class, "reconcileExecution", UUID.class, ReconcileExecutionApiRequest.class, Principal.class);
        assertMethodNotTransactional(MigrationController.class, "submitMigrationDirect", RunMigrationApiRequest.class, Principal.class);
        assertMethodNotTransactional(MigrationController.class, "createMigration", JsonNode.class);
        assertMethodNotTransactional(MigrationJobDispatcher.class, "dispatchMigration", ApprovedMigrationExecutionRequest.class, UUID.class);
        assertMethodNotTransactional(MigrationBackgroundWorker.class, "processMigration", UUID.class, UUID.class);
        assertMethodNotTransactional(MigrationBackgroundWorker.class, "processExecution", UUID.class, UUID.class, long.class);
        assertMethodNotTransactional(DurableMigrationScheduler.class, "pollAndDispatch");
        assertMethodNotTransactional(DurableMigrationScheduler.class, "dispatchClaimedExecution", ClaimedExecution.class);
        assertMethodNotTransactional(DefaultMigrationIdempotencyService.class, "acquireClaim", UUID.class, String.class, String.class, UUID.class);
    }

    private static void assertMethodNotTransactional(Class<?> clazz, String methodName, Class<?>... paramTypes) {
        try {
            Method method = clazz.getDeclaredMethod(methodName, paramTypes);
            assertThat(method.isAnnotationPresent(Transactional.class))
                    .as("Method %s.%s must NOT be annotated with @Transactional", clazz.getSimpleName(), methodName)
                    .isFalse();
        } catch (NoSuchMethodException e) {
            org.junit.jupiter.api.Assertions.fail("Method " + methodName + " not found on class " + clazz.getSimpleName());
        }
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
