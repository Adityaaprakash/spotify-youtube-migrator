package com.spotifyyoutube.migrator.migration.execution.domain;

import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.matching.engine.MatchDecision;
import com.spotifyyoutube.migrator.migration.api.dto.ExecutionTrackRecordResponse;
import com.spotifyyoutube.migrator.migration.api.dto.MigrationExecutionResponse;
import com.spotifyyoutube.migrator.migration.api.mapper.MigrationExecutionApiMapper;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationTrackCandidate;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationContext;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedTrack;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ExecutionTrackRecordDto;
import com.spotifyyoutube.migrator.migration.application.execution.contract.MigrationExecutionSummary;
import com.spotifyyoutube.migrator.migration.application.execution.validation.DefaultApprovedPlanValidator;
import com.spotifyyoutube.migrator.migration.application.plan.PlanTrackClassification;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewDecisionSource;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSession;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSessionStatus;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewTrack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Phase 14B: Persistent Migration Record Domain & Mapping Tests")
class Phase14BPersistentMigrationRecordTest {

    private User testUser;
    private ReviewSession testSession;
    private ApprovedMigrationContext approvedContext;

    @BeforeEach
    void setUp() {
        testUser = new User("history-user@example.com");
        testUser.setId(UUID.randomUUID());
        testSession = new ReviewSession(testUser, "sp_pl_hist", "Rock Classics", UUID.randomUUID());
        testSession.setId(UUID.randomUUID());
        testSession.setStatus(ReviewSessionStatus.COMPLETED);

        ApprovedTrack track1 = new ApprovedTrack(
                0,
                "sp_track_1",
                "Bohemian Rhapsody",
                "Queen",
                "yt_vid_1",
                "Queen - Bohemian Rhapsody (Official Video)",
                "Queen Official",
                Duration.ofSeconds(355),
                ReviewDecisionSource.AUTOMATIC,
                0,
                0.985,
                MatchDecision.MATCH.name()
        );

        ApprovedTrack track2 = new ApprovedTrack(
                1,
                "sp_track_2",
                "Stairway to Heaven",
                "Led Zeppelin",
                "yt_vid_2",
                "Led Zeppelin - Stairway to Heaven (Live)",
                "Led Zeppelin",
                Duration.ofSeconds(482),
                ReviewDecisionSource.USER_SELECTED,
                1,
                0.812,
                MatchDecision.REVIEW_REQUIRED.name()
        );

        approvedContext = new ApprovedMigrationContext(
                testSession.getId(),
                UUID.randomUUID(),
                testUser.getId(),
                "sp_pl_hist",
                "Rock Classics",
                "Rock Classics (YouTube)",
                "Migrated from Spotify",
                false,
                List.of(track1, track2),
                5, // 5 source tracks evaluated
                2, // 2 approved tracks
                3, // 3 excluded tracks
                Instant.now()
        );
    }

    @Test
    @DisplayName("MigrationExecution correctly records source track count and matched track count from ApprovedMigrationContext")
    void migrationExecutionInitializesSourceAndMatchedCounts() {
        MigrationExecution execution = new MigrationExecution(testUser, testSession, approvedContext, "yt_pl_123", "https://youtube.com/playlist?list=yt_pl_123");

        assertThat(execution.getTotalSourceTracks()).isEqualTo(5);
        assertThat(execution.getMatchedTracks()).isEqualTo(2);
        assertThat(execution.getTotalApprovedTracks()).isEqualTo(2);
        assertThat(execution.getDestinationPlaylistId()).isEqualTo("yt_pl_123");
        assertThat(execution.getSourcePlaylistId()).isEqualTo("sp_pl_hist");
        assertThat(execution.getSourcePlaylistTitle()).isEqualTo("Rock Classics");
        assertThat(execution.getStatus()).isEqualTo(MigrationExecutionStatus.INITIALIZED);
        assertThat(execution.getFailureSummary()).isNull();
    }

    @Test
    @DisplayName("ExecutionTrackRecord preserves destination details, match score, match decision, and decision provenance")
    void executionTrackRecordPreservesMatchingEvidenceAndProvenance() {
        MigrationExecution execution = new MigrationExecution(testUser, testSession, approvedContext, "yt_pl_123", "https://youtube.com/playlist?list=yt_pl_123");
        List<ExecutionTrackRecord> records = execution.getTrackRecords();

        assertThat(records).hasSize(2);

        // Track 1: Automatic match
        ExecutionTrackRecord rec1 = records.get(0);
        assertThat(rec1.getSourceTrackId()).isEqualTo("sp_track_1");
        assertThat(rec1.getSourceTitle()).isEqualTo("Bohemian Rhapsody");
        assertThat(rec1.getSourceArtist()).isEqualTo("Queen");
        assertThat(rec1.getDestinationTrackId()).isEqualTo("yt_vid_1");
        assertThat(rec1.getDestinationTitle()).isEqualTo("Queen - Bohemian Rhapsody (Official Video)");
        assertThat(rec1.getDestinationChannel()).isEqualTo("Queen Official");
        assertThat(rec1.getDestinationDurationMs()).isEqualTo(355000L);
        assertThat(rec1.getDecisionSource()).isEqualTo("AUTOMATIC");
        assertThat(rec1.getMatchScore()).isEqualTo(0.985);
        assertThat(rec1.getMatchDecision()).isEqualTo("MATCH");
        assertThat(rec1.getStatus()).isEqualTo(ExecutionTrackStatus.PENDING);

        // Track 2: User-selected match
        ExecutionTrackRecord rec2 = records.get(1);
        assertThat(rec2.getSourceTrackId()).isEqualTo("sp_track_2");
        assertThat(rec2.getDestinationTrackId()).isEqualTo("yt_vid_2");
        assertThat(rec2.getDestinationTitle()).isEqualTo("Led Zeppelin - Stairway to Heaven (Live)");
        assertThat(rec2.getDestinationChannel()).isEqualTo("Led Zeppelin");
        assertThat(rec2.getDestinationDurationMs()).isEqualTo(482000L);
        assertThat(rec2.getDecisionSource()).isEqualTo("USER_SELECTED");
        assertThat(rec2.getMatchScore()).isEqualTo(0.812);
        assertThat(rec2.getMatchDecision()).isEqualTo("REVIEW_REQUIRED");
    }

    @Test
    @DisplayName("DefaultApprovedPlanValidator captures match score, match decision, and provenance from ReviewTrack")
    void approvedPlanValidatorCapturesEvidenceFromReviewTrack() {
        ReviewSession session = new ReviewSession(testUser, "sp_val_1", "Validation Playlist", UUID.randomUUID());
        session.setId(UUID.randomUUID());

        ReviewTrack rt1 = new ReviewTrack();
        rt1.setPosition(0);
        rt1.setSourceTrackId("sp_101");
        rt1.setSourceTitle("Hotel California");
        rt1.setSourceArtist("Eagles");
        rt1.setOriginalClassification(PlanTrackClassification.AUTO_MATCH);
        rt1.setOriginalCandidateId("yt_101");
        rt1.setOriginalCandidateTitle("Eagles - Hotel California");
        rt1.setOriginalCandidateChannel("Eagles Official");
        rt1.setOriginalCandidateDurationMs(390000L);
        rt1.setOriginalMatchScore(0.97);
        rt1.setOriginalCalibratedProbability(0.99);
        rt1.setOriginalMatchDecision(MatchDecision.MATCH);
        rt1.setOriginalMatchReasons("High title and artist correlation");
        session.addTrack(rt1);
        rt1.acceptOriginalMatch();

        ReviewTrack rt2 = new ReviewTrack();
        rt2.setPosition(1);
        rt2.setSourceTrackId("sp_102");
        rt2.setSourceTitle("Desperado");
        rt2.setSourceArtist("Eagles");
        rt2.setOriginalClassification(PlanTrackClassification.REVIEW_REQUIRED);
        rt2.setOriginalCandidateId("yt_cand_orig");
        rt2.setOriginalCandidateTitle("Desperado Cover");
        rt2.setOriginalCandidateChannel("Random Channel");
        rt2.setOriginalCandidateDurationMs(200000L);
        rt2.setOriginalMatchScore(0.62);
        rt2.setOriginalCalibratedProbability(0.55);
        rt2.setOriginalMatchDecision(MatchDecision.REVIEW_REQUIRED);
        rt2.setOriginalMatchReasons("Ambiguous cover version");
        session.addTrack(rt2);
        rt2.selectCandidate(
                new DestinationTrackCandidate(
                        "yt_102",
                        "Eagles - Desperado (Remastered)",
                        "Eagles Official",
                        null,
                        null,
                        null,
                        Duration.ofMillis(215000L)
                ),
                "User selected canonical studio version"
        );
        session.setStatus(ReviewSessionStatus.COMPLETED);

        DefaultApprovedPlanValidator validator = new DefaultApprovedPlanValidator();
        ApprovedMigrationContext validatedContext = validator.validateAndApprove(session, testUser.getId());

        assertThat(validatedContext.approvedTrackCount()).isEqualTo(2);

        ApprovedTrack at1 = validatedContext.approvedTracks().get(0);
        assertThat(at1.destinationTrackId()).isEqualTo("yt_101");
        assertThat(at1.decisionSource()).isEqualTo(ReviewDecisionSource.AUTOMATIC);
        assertThat(at1.matchScore()).isEqualTo(0.97);
        assertThat(at1.matchDecision()).isEqualTo("MATCH");

        ApprovedTrack at2 = validatedContext.approvedTracks().get(1);
        assertThat(at2.destinationTrackId()).isEqualTo("yt_102");
        assertThat(at2.decisionSource()).isEqualTo(ReviewDecisionSource.USER_SELECTED);
        assertThat(at2.matchScore()).isEqualTo(0.62);
        assertThat(at2.matchDecision()).isEqualTo("REVIEW_REQUIRED");
    }

    @Test
    @DisplayName("releaseExecution sets failureSummary on failed or partially completed executions and clears on success")
    void releaseExecutionSetsAndClearsFailureSummary() {
        // 1. Both tracks failed -> FAILED
        MigrationExecution execFailed = new MigrationExecution(testUser, testSession, approvedContext, "yt_pl_123", "https://youtube.com/playlist?list=yt_pl_123");
        execFailed.getTrackRecords().get(0).markAdding();
        execFailed.getTrackRecords().get(0).markFailed("Quota exceeded");
        execFailed.getTrackRecords().get(1).markAdding();
        execFailed.getTrackRecords().get(1).markFailed("Rate limited");

        execFailed.releaseExecution();
        assertThat(execFailed.getStatus()).isEqualTo(MigrationExecutionStatus.FAILED);
        assertThat(execFailed.getFailureSummary()).contains("Execution failed: 2 failed, 0 skipped out of 2 approved tracks");
        assertThat(execFailed.getCompletedAt()).isNotNull();

        // 2. Track 0 succeeds, Track 1 fails -> PARTIALLY_COMPLETED
        MigrationExecution execPartial = new MigrationExecution(testUser, testSession, approvedContext, "yt_pl_123", "https://youtube.com/playlist?list=yt_pl_123");
        execPartial.getTrackRecords().get(0).markAdding();
        execPartial.getTrackRecords().get(0).markAdded("item_1");
        execPartial.getTrackRecords().get(1).markAdding();
        execPartial.getTrackRecords().get(1).markFailed("Network failure");

        execPartial.releaseExecution();
        assertThat(execPartial.getStatus()).isEqualTo(MigrationExecutionStatus.PARTIALLY_COMPLETED);
        assertThat(execPartial.getFailureSummary()).contains("Partially completed: 1 added, 1 failed, 0 skipped out of 2 approved tracks");
        assertThat(execPartial.getCompletedAt()).isNull(); // Non-terminal; resumable

        // 3. Both succeed -> COMPLETED, failureSummary is null
        MigrationExecution execCompleted = new MigrationExecution(testUser, testSession, approvedContext, "yt_pl_123", "https://youtube.com/playlist?list=yt_pl_123");
        execCompleted.getTrackRecords().get(0).markAdding();
        execCompleted.getTrackRecords().get(0).markAdded("item_1");
        execCompleted.getTrackRecords().get(1).markAdding();
        execCompleted.getTrackRecords().get(1).markAdded("item_2");

        execCompleted.releaseExecution();
        assertThat(execCompleted.getStatus()).isEqualTo(MigrationExecutionStatus.COMPLETED);
        assertThat(execCompleted.getFailureSummary()).isNull();
        assertThat(execCompleted.getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("recordPlaylistCreationFailure records failureSummary and markPlaylistCreated clears it")
    void playlistCreationFailureLifecycleManagement() {
        MigrationExecution execution = new MigrationExecution(testUser, testSession, approvedContext, UUID.randomUUID());
        assertThat(execution.getStatus()).isEqualTo(MigrationExecutionStatus.ALLOCATING_DESTINATION);
        assertThat(execution.getDestinationPlaylistId()).isNull();

        execution.recordPlaylistCreationFailure("QUOTA_EXCEEDED", Instant.now().plusSeconds(60));
        assertThat(execution.getPlaylistCreationFailureCode()).isEqualTo("QUOTA_EXCEEDED");
        assertThat(execution.getFailureSummary()).isEqualTo("Destination playlist creation failed: QUOTA_EXCEEDED");

        // Reconciled playlist created clears the creation failure summary
        execution.markPlaylistCreated("PL_created_123", "https://youtube.com/playlist?list=PL_created_123", MigrationExecutionStatus.INITIALIZED);
        assertThat(execution.getDestinationPlaylistId()).isEqualTo("PL_created_123");
        assertThat(execution.getStatus()).isEqualTo(MigrationExecutionStatus.INITIALIZED);
        assertThat(execution.getPlaylistCreationFailureCode()).isNull();
        assertThat(execution.getFailureSummary()).isNull();
    }

    @Test
    @DisplayName("MigrationExecutionSummary and MigrationExecutionApiMapper map all Phase 14B fields correctly")
    void summaryAndApiMapperFullFieldMapping() {
        MigrationExecution execution = new MigrationExecution(testUser, testSession, approvedContext, "yt_pl_123", "https://youtube.com/playlist?list=yt_pl_123");
        execution.setId(UUID.randomUUID());
        for (ExecutionTrackRecord rec : execution.getTrackRecords()) {
            rec.setId(UUID.randomUUID());
        }
        execution.setFailureSummary("Custom test failure explanation");

        MigrationExecutionSummary summary = MigrationExecutionSummary.from(execution);
        assertThat(summary.totalSourceTracks()).isEqualTo(5);
        assertThat(summary.matchedTracks()).isEqualTo(2);
        assertThat(summary.failureSummary()).isEqualTo("Custom test failure explanation");
        assertThat(summary.trackRecords()).hasSize(2);

        ExecutionTrackRecordDto trackDto1 = summary.trackRecords().get(0);
        assertThat(trackDto1.destinationTitle()).isEqualTo("Queen - Bohemian Rhapsody (Official Video)");
        assertThat(trackDto1.decisionSource()).isEqualTo("AUTOMATIC");
        assertThat(trackDto1.matchScore()).isEqualTo(0.985);
        assertThat(trackDto1.matchDecision()).isEqualTo("MATCH");

        MigrationExecutionResponse response = MigrationExecutionApiMapper.toExecutionResponse(summary);
        assertThat(response.totalSourceTracks()).isEqualTo(5);
        assertThat(response.matchedTracks()).isEqualTo(2);
        assertThat(response.failureSummary()).isEqualTo("Custom test failure explanation");

        ExecutionTrackRecordResponse trackResp1 = response.trackRecords().get(0);
        assertThat(trackResp1.destinationTitle()).isEqualTo("Queen - Bohemian Rhapsody (Official Video)");
        assertThat(trackResp1.destinationChannel()).isEqualTo("Queen Official");
        assertThat(trackResp1.decisionSource()).isEqualTo("AUTOMATIC");
        assertThat(trackResp1.matchScore()).isEqualTo(0.985);
        assertThat(trackResp1.matchDecision()).isEqualTo("MATCH");
    }
}
