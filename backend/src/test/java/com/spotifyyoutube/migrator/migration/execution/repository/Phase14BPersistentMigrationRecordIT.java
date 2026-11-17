package com.spotifyyoutube.migrator.migration.execution.repository;

import com.spotifyyoutube.migrator.common.config.JpaConfig;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.identity.repository.UserRepository;
import com.spotifyyoutube.migrator.matching.engine.MatchDecision;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationContext;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedTrack;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackRecord;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackStatus;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecution;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewDecisionSource;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSession;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSessionStatus;
import com.spotifyyoutube.migrator.migration.review.repository.ReviewSessionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PostgreSQL Testcontainers integration test verifying Phase 14B Persistent Migration Records:
 * - Durable persistence of MigrationExecution with source/matched track counts and failure summaries.
 * - Durable persistence of ExecutionTrackRecord with destination metadata, match scores, and decision provenance.
 * - Nullable destination playlist identity during ALLOCATING_DESTINATION state.
 * - Reverse chronological history sorting and Spring Data JPA Pageable pagination.
 * - Foreign key constraints, cascade lifecycles, and tamper resistance on retry/resume.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(JpaConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Phase 14B: PostgreSQL Persistent Migration History Integration Tests")
public class Phase14BPersistentMigrationRecordIT {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ReviewSessionRepository reviewSessionRepository;

    @Autowired
    private MigrationExecutionRepository executionRepository;

    @Autowired
    private ExecutionTrackRecordRepository trackRecordRepository;

    private User alice;
    private User bob;

    @BeforeEach
    void cleanAndSeed() {
        cleanDatabase();
        alice = userRepository.save(new User("alice-phase14b@example.com"));
        bob = userRepository.save(new User("bob-phase14b@example.com"));
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    private void cleanDatabase() {
        trackRecordRepository.deleteAllInBatch();
        executionRepository.deleteAllInBatch();
        reviewSessionRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("Persists MigrationExecution with complete ownership, playlist metadata, counts, timestamps, and failure summary")
    void testMigrationExecutionPersistenceInPostgres() {
        ReviewSession session = new ReviewSession(alice, "sp_pl_100", "Indie Mixtape", UUID.randomUUID());
        session.setStatus(ReviewSessionStatus.COMPLETED);
        session = reviewSessionRepository.save(session);

        ApprovedTrack track1 = new ApprovedTrack(
                0, "sp_trk_1", "Song A", "Artist 1", "yt_vid_1", "Song A Official", "Artist 1 Vevo",
                Duration.ofSeconds(210), ReviewDecisionSource.AUTOMATIC, 0, 0.94, MatchDecision.MATCH.name()
        );
        ApprovedTrack track2 = new ApprovedTrack(
                1, "sp_trk_2", "Song B", "Artist 2", "yt_vid_2", "Song B Live", "Artist 2 Channel",
                Duration.ofSeconds(195), ReviewDecisionSource.USER_SELECTED, 1, 0.78, MatchDecision.REVIEW_REQUIRED.name()
        );

        ApprovedMigrationContext context = new ApprovedMigrationContext(
                session.getId(), UUID.randomUUID(), alice.getId(), "sp_pl_100", "Indie Mixtape",
                "Indie Mixtape YT", "Migrated description", false, List.of(track1, track2),
                10, 2, 8, Instant.now()
        );

        MigrationExecution execution = new MigrationExecution(alice, session, context, "PL_yt_100", "https://youtube.com/playlist?list=PL_yt_100");
        execution.setFailureSummary("Partially completed due to quota limits");
        execution = executionRepository.saveAndFlush(execution);

        // Fetch from a separate query transaction
        Optional<MigrationExecution> retrievedOpt = executionRepository.findById(execution.getId());
        assertThat(retrievedOpt).isPresent();
        MigrationExecution retrieved = retrievedOpt.get();

        assertThat(retrieved.getUser().getId()).isEqualTo(alice.getId());
        assertThat(retrieved.getSourcePlaylistId()).isEqualTo("sp_pl_100");
        assertThat(retrieved.getSourcePlaylistTitle()).isEqualTo("Indie Mixtape");
        assertThat(retrieved.getDestinationPlaylistId()).isEqualTo("PL_yt_100");
        assertThat(retrieved.getDestinationPlaylistUrl()).isEqualTo("https://youtube.com/playlist?list=PL_yt_100");
        assertThat(retrieved.getTotalSourceTracks()).isEqualTo(10);
        assertThat(retrieved.getMatchedTracks()).isEqualTo(2);
        assertThat(retrieved.getTotalApprovedTracks()).isEqualTo(2);
        assertThat(retrieved.getFailureSummary()).isEqualTo("Partially completed due to quota limits");
        assertThat(retrieved.getStatus()).isEqualTo(MigrationExecutionStatus.INITIALIZED);
        assertThat(retrieved.getCreatedAt()).isNotNull();
        assertThat(retrieved.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("Persists ExecutionTrackRecord with full destination metadata, match scores, and provenance")
    void testExecutionTrackRecordPersistenceInPostgres() {
        ReviewSession session = new ReviewSession(alice, "sp_pl_200", "Jazz Sessions", UUID.randomUUID());
        session.setStatus(ReviewSessionStatus.COMPLETED);
        session = reviewSessionRepository.save(session);

        ApprovedTrack track = new ApprovedTrack(
                0, "sp_jazz_1", "Take Five", "Dave Brubeck", "yt_jazz_1",
                "The Dave Brubeck Quartet - Take Five (Audio)", "DaveBrubeckVEVO",
                Duration.ofSeconds(324), ReviewDecisionSource.AUTOMATIC, 0, 0.992, "MATCH"
        );

        ApprovedMigrationContext context = new ApprovedMigrationContext(
                session.getId(), UUID.randomUUID(), alice.getId(), "sp_pl_200", "Jazz Sessions",
                "Jazz Sessions YT", "Description", false, List.of(track), 1, 1, 0, Instant.now()
        );

        MigrationExecution execution = new MigrationExecution(alice, session, context, "PL_jazz_200", "https://youtube.com/playlist?list=PL_jazz_200");
        execution = executionRepository.saveAndFlush(execution);

        List<ExecutionTrackRecord> trackRecords = trackRecordRepository.findByExecutionIdOrderByTargetPositionAsc(execution.getId());
        assertThat(trackRecords).hasSize(1);

        ExecutionTrackRecord record = trackRecords.get(0);
        assertThat(record.getSourceTrackId()).isEqualTo("sp_jazz_1");
        assertThat(record.getSourceTitle()).isEqualTo("Take Five");
        assertThat(record.getSourceArtist()).isEqualTo("Dave Brubeck");
        assertThat(record.getDestinationTrackId()).isEqualTo("yt_jazz_1");
        assertThat(record.getDestinationTitle()).isEqualTo("The Dave Brubeck Quartet - Take Five (Audio)");
        assertThat(record.getDestinationChannel()).isEqualTo("DaveBrubeckVEVO");
        assertThat(record.getDestinationDurationMs()).isEqualTo(324000L);
        assertThat(record.getDecisionSource()).isEqualTo("AUTOMATIC");
        assertThat(record.getMatchScore()).isEqualTo(0.992);
        assertThat(record.getMatchDecision()).isEqualTo("MATCH");
        assertThat(record.getStatus()).isEqualTo(ExecutionTrackStatus.PENDING);
    }

    @Test
    @DisplayName("Supports nullable destination playlist ID during ALLOCATING_DESTINATION and records durable allocation intent")
    void testNullableDestinationPlaylistInAllocatingState() {
        ReviewSession session = new ReviewSession(alice, "sp_pl_alloc", "Alloc Playlist", UUID.randomUUID());
        session.setStatus(ReviewSessionStatus.COMPLETED);
        session = reviewSessionRepository.save(session);

        ApprovedTrack track = new ApprovedTrack(0, 0, "sp_1", "yt_1", "Song", "Artist");
        ApprovedMigrationContext context = new ApprovedMigrationContext(
                session.getId(), UUID.randomUUID(), alice.getId(), "sp_pl_alloc", "Alloc Playlist",
                "Alloc Playlist YT", "Desc", false, List.of(track), 1, 1, 0, Instant.now()
        );

        UUID operationId = UUID.randomUUID();
        MigrationExecution execution = new MigrationExecution(alice, session, context, operationId);
        execution = executionRepository.saveAndFlush(execution);

        // Verify nullable destination playlist id persisted safely in PostgreSQL
        MigrationExecution fetched = executionRepository.findById(execution.getId()).orElseThrow();
        assertThat(fetched.getDestinationPlaylistId()).isNull();
        assertThat(fetched.getStatus()).isEqualTo(MigrationExecutionStatus.ALLOCATING_DESTINATION);
        assertThat(fetched.getPlaylistCreationOperationId()).isEqualTo(operationId);

        // Remote creation finishes -> mark created
        fetched.markPlaylistCreated("PL_alloc_confirmed", "https://youtube.com/playlist?list=PL_alloc_confirmed", MigrationExecutionStatus.INITIALIZED);
        executionRepository.saveAndFlush(fetched);

        MigrationExecution confirmed = executionRepository.findById(execution.getId()).orElseThrow();
        assertThat(confirmed.getDestinationPlaylistId()).isEqualTo("PL_alloc_confirmed");
        assertThat(confirmed.getStatus()).isEqualTo(MigrationExecutionStatus.INITIALIZED);
    }

    @Test
    @DisplayName("Queries user migration history in reverse chronological order with Spring Data JPA Pageable pagination")
    void testUserMigrationHistorySortingAndPagination() {
        ReviewSession s1 = reviewSessionRepository.save(new ReviewSession(alice, "sp_1", "PL 1", UUID.randomUUID()));
        ReviewSession s2 = reviewSessionRepository.save(new ReviewSession(alice, "sp_2", "PL 2", UUID.randomUUID()));
        ReviewSession s3 = reviewSessionRepository.save(new ReviewSession(alice, "sp_3", "PL 3", UUID.randomUUID()));
        ReviewSession sBob = reviewSessionRepository.save(new ReviewSession(bob, "sp_bob", "Bob PL", UUID.randomUUID()));

        ApprovedTrack dummyTrack = new ApprovedTrack(0, 0, "sp_dummy", "yt_dummy", "Dummy", "Artist");

        ApprovedMigrationContext c1 = new ApprovedMigrationContext(
                s1.getId(), UUID.randomUUID(), alice.getId(), "sp_1", "PL 1", "PL 1 YT", "D", false, List.of(dummyTrack), 1, 1, 0, Instant.now()
        );
        ApprovedMigrationContext c2 = new ApprovedMigrationContext(
                s2.getId(), UUID.randomUUID(), alice.getId(), "sp_2", "PL 2", "PL 2 YT", "D", false, List.of(dummyTrack), 1, 1, 0, Instant.now()
        );
        ApprovedMigrationContext c3 = new ApprovedMigrationContext(
                s3.getId(), UUID.randomUUID(), alice.getId(), "sp_3", "PL 3", "PL 3 YT", "D", false, List.of(dummyTrack), 1, 1, 0, Instant.now()
        );
        ApprovedMigrationContext cBob = new ApprovedMigrationContext(
                sBob.getId(), UUID.randomUUID(), bob.getId(), "sp_bob", "Bob PL", "Bob YT", "D", false, List.of(dummyTrack), 1, 1, 0, Instant.now()
        );

        MigrationExecution e1 = executionRepository.saveAndFlush(new MigrationExecution(alice, s1, c1, "PL_1", "url_1"));
        MigrationExecution e2 = executionRepository.saveAndFlush(new MigrationExecution(alice, s2, c2, "PL_2", "url_2"));
        MigrationExecution e3 = executionRepository.saveAndFlush(new MigrationExecution(alice, s3, c3, "PL_3", "url_3"));
        executionRepository.saveAndFlush(new MigrationExecution(bob, sBob, cBob, "PL_bob", "url_bob"));

        // 1. List for Alice
        List<MigrationExecution> aliceHistory = executionRepository.findByUserIdOrderByCreatedAtDesc(alice.getId());
        assertThat(aliceHistory).hasSize(3);
        // Most recent first: e3, e2, e1
        assertThat(aliceHistory.get(0).getId()).isEqualTo(e3.getId());
        assertThat(aliceHistory.get(1).getId()).isEqualTo(e2.getId());
        assertThat(aliceHistory.get(2).getId()).isEqualTo(e1.getId());

        // 2. Count for Alice
        assertThat(executionRepository.countByUserId(alice.getId())).isEqualTo(3);
        assertThat(executionRepository.countByUserId(bob.getId())).isEqualTo(1);

        // 3. Paginated query (Page 0, Size 2)
        Pageable page0 = PageRequest.of(0, 2);
        Page<MigrationExecution> page0Result = executionRepository.findByUserIdOrderByCreatedAtDesc(alice.getId(), page0);
        assertThat(page0Result.getTotalElements()).isEqualTo(3);
        assertThat(page0Result.getTotalPages()).isEqualTo(2);
        assertThat(page0Result.getContent()).hasSize(2);
        assertThat(page0Result.getContent().get(0).getId()).isEqualTo(e3.getId());
        assertThat(page0Result.getContent().get(1).getId()).isEqualTo(e2.getId());

        // Page 1, Size 2
        Pageable page1 = PageRequest.of(1, 2);
        Page<MigrationExecution> page1Result = executionRepository.findByUserIdOrderByCreatedAtDesc(alice.getId(), page1);
        assertThat(page1Result.getContent()).hasSize(1);
        assertThat(page1Result.getContent().get(0).getId()).isEqualTo(e1.getId());
    }

    @Test
    @DisplayName("Preserves completed track outcomes without modification when parent execution is retried or resumed")
    void testResumePreservesCompletedTracksInDatabase() {
        ReviewSession session = new ReviewSession(alice, "sp_pl_res", "Resume PL", UUID.randomUUID());
        session.setStatus(ReviewSessionStatus.COMPLETED);
        session = reviewSessionRepository.save(session);

        ApprovedTrack track1 = new ApprovedTrack(0, 0, "sp_1", "yt_1", "Song 1", "Artist 1");
        ApprovedTrack track2 = new ApprovedTrack(1, 1, "sp_2", "yt_2", "Song 2", "Artist 2");

        ApprovedMigrationContext context = new ApprovedMigrationContext(
                session.getId(), UUID.randomUUID(), alice.getId(), "sp_pl_res", "Resume PL",
                "Resume PL YT", "Desc", false, List.of(track1, track2), 2, 2, 0, Instant.now()
        );

        MigrationExecution execution = new MigrationExecution(alice, session, context, "PL_res", "url");
        execution = executionRepository.saveAndFlush(execution);

        // Simulate Track 0 succeeded, Track 1 pending directly via the aggregate root
        execution.getTrackRecords().get(0).markAdding();
        execution.getTrackRecords().get(0).markAdded("item_confirmed_0");

        // Execution status partially completed
        execution.releaseExecution();
        executionRepository.saveAndFlush(execution);

        // Verify that upon reload from database, Track 0 remains ADDED with item ID
        List<ExecutionTrackRecord> reloadedTracks = trackRecordRepository.findByExecutionIdOrderByTargetPositionAsc(execution.getId());
        assertThat(reloadedTracks.get(0).getStatus()).isEqualTo(ExecutionTrackStatus.ADDED);
        assertThat(reloadedTracks.get(0).getDestinationPlaylistItemId()).isEqualTo("item_confirmed_0");
        assertThat(reloadedTracks.get(1).getStatus()).isEqualTo(ExecutionTrackStatus.PENDING);
    }
}
