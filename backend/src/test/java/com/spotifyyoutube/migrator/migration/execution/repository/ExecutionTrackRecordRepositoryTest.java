package com.spotifyyoutube.migrator.migration.execution.repository;

import com.spotifyyoutube.migrator.common.config.JpaConfig;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.identity.repository.UserRepository;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationContext;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedTrack;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackRecord;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackStatus;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecution;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewDecisionSource;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSession;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSessionStatus;
import com.spotifyyoutube.migrator.migration.review.repository.ReviewSessionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Data JPA integration test for {@link ExecutionTrackRecordRepository} aggregation queries (Phase 12D).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(JpaConfig.class)
@DisplayName("Phase 12D: ExecutionTrackRecordRepository Aggregation Test")
public class ExecutionTrackRecordRepositoryTest {

    @Autowired
    private ExecutionTrackRecordRepository trackRecordRepository;

    @Autowired
    private MigrationExecutionRepository executionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ReviewSessionRepository reviewSessionRepository;

    @Test
    @DisplayName("Aggregates track counts by status correctly using database index query")
    void testCountTracksByStatusForExecution() {
        User user = new User("agg-test@example.com");
        user = userRepository.save(user);

        ReviewSession session = new ReviewSession(user, "sp_pl_1", "Source PL", UUID.randomUUID());
        session.setStatus(ReviewSessionStatus.COMPLETED);
        session = reviewSessionRepository.save(session);

        ApprovedTrack t0 = new ApprovedTrack(0, "sp0", "S0", "A", "yt0", "S0", "C", Duration.ofMinutes(3), ReviewDecisionSource.AUTOMATIC, 0);
        ApprovedTrack t1 = new ApprovedTrack(1, "sp1", "S1", "A", "yt1", "S1", "C", Duration.ofMinutes(3), ReviewDecisionSource.AUTOMATIC, 1);
        ApprovedTrack t2 = new ApprovedTrack(2, "sp2", "S2", "A", "yt2", "S2", "C", Duration.ofMinutes(3), ReviewDecisionSource.AUTOMATIC, 2);

        ApprovedMigrationContext context = new ApprovedMigrationContext(
                session.getId(), UUID.randomUUID(), user.getId(), "sp_pl_1", "Source PL",
                "Target PL", "Desc", false, List.of(t0, t1, t2), 3, 3, 0, Instant.now()
        );

        MigrationExecution execution = new MigrationExecution(user, session, context, "PL_agg", "url");
        execution = executionRepository.saveAndFlush(execution);

        // Update track records to have different statuses
        List<ExecutionTrackRecord> tracks = trackRecordRepository.findByExecutionIdOrderByTargetPositionAsc(execution.getId());
        assertThat(tracks).hasSize(3);

        // Track 0 -> ADDING -> ADDED
        tracks.get(0).markAdding();
        tracks.get(0).markAdded("item_0");

        // Track 1 -> ADDING -> FAILED
        tracks.get(1).markAdding();
        tracks.get(1).markFailed("rejected");

        // Track 2 remains PENDING

        trackRecordRepository.saveAllAndFlush(tracks);

        List<TrackStatusCount> counts = trackRecordRepository.countTracksByStatusForExecution(execution.getId());
        Map<ExecutionTrackStatus, Long> map = counts.stream()
                .collect(Collectors.toMap(TrackStatusCount::status, TrackStatusCount::count));

        assertThat(map).containsEntry(ExecutionTrackStatus.ADDED, 1L);
        assertThat(map).containsEntry(ExecutionTrackStatus.FAILED, 1L);
        assertThat(map).containsEntry(ExecutionTrackStatus.PENDING, 1L);
        assertThat(map).doesNotContainKey(ExecutionTrackStatus.UNCERTAIN);
        assertThat(map).doesNotContainKey(ExecutionTrackStatus.SKIPPED);
    }

    @Test
    @DisplayName("Returns empty list when execution has no tracks or ID does not exist")
    void testCountTracksForNonExistentExecution() {
        List<TrackStatusCount> counts = trackRecordRepository.countTracksByStatusForExecution(UUID.randomUUID());
        assertThat(counts).isEmpty();
    }
}
