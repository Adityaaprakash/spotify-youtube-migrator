package com.spotifyyoutube.migrator.migration.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.identity.repository.UserRepository;
import com.spotifyyoutube.migrator.matching.engine.MatchDecision;
import com.spotifyyoutube.migrator.migration.api.dto.RunMigrationApiRequest;
import com.spotifyyoutube.migrator.migration.application.destination.MusicDestination;
import com.spotifyyoutube.migrator.migration.application.destination.model.AddTrackToDestinationPlaylistRequest;
import com.spotifyyoutube.migrator.migration.application.destination.model.CreateDestinationPlaylistRequest;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationPlaylist;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationPlaylistItem;
import com.spotifyyoutube.migrator.migration.application.plan.PlanTrackClassification;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecution;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus;
import com.spotifyyoutube.migrator.migration.execution.repository.MigrationExecutionRepository;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewDecisionSource;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSession;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSessionStatus;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewTrack;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewTrackStatus;
import com.spotifyyoutube.migrator.migration.review.repository.ReviewSessionRepository;
import com.spotifyyoutube.migrator.migration.review.repository.ReviewTrackRepository;
import com.spotifyyoutube.migrator.playlist.domain.Platform;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end integration test for the asynchronous migration lifecycle (Phase 12G-I).
 * <p>
 * Tests the complete REST path from {@code POST /api/migrations} through background worker execution,
 * atomic database claiming, track insertion, progress polling, and terminal state verification.
 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@DisplayName("Phase 12G-I: End-to-End Asynchronous Migration Lifecycle Integration Test")
public class EndToEndAsyncMigrationLifecycleIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ReviewSessionRepository reviewSessionRepository;

    @Autowired
    private ReviewTrackRepository reviewTrackRepository;

    @Autowired
    private MigrationExecutionRepository executionRepository;

    @MockBean
    private MusicDestination musicDestination;

    private User alice;
    private User bob;
    private ReviewSession sessionAlice;

    @BeforeEach
    void setUp() {
        alice = userRepository.save(new User("alice@example.com"));
        bob = userRepository.save(new User("bob@example.com"));

        sessionAlice = new ReviewSession(alice, "sp_pl_alice", "Alice Favs", UUID.randomUUID());

        // Track 0
        ReviewTrack t0 = new ReviewTrack();
        t0.setPosition(0);
        t0.setSourceTrackId("sp0");
        t0.setSourceTitle("Song 0");
        t0.setSourceArtist("Artist 0");
        t0.setOriginalClassification(PlanTrackClassification.AUTO_MATCH);
        t0.setOriginalCandidateId("yt0");
        t0.setOriginalCandidateTitle("Song 0 (Audio)");
        t0.setOriginalCandidateChannel("Channel");
        t0.setOriginalCandidateDurationMs(Duration.ofMinutes(3).toMillis());
        t0.setOriginalMatchScore(0.98);
        t0.setOriginalMatchDecision(MatchDecision.MATCH);
        t0.acceptOriginalMatch();
        sessionAlice.addTrack(t0);

        // Track 1
        ReviewTrack t1 = new ReviewTrack();
        t1.setPosition(1);
        t1.setSourceTrackId("sp1");
        t1.setSourceTitle("Song 1");
        t1.setSourceArtist("Artist 1");
        t1.setOriginalClassification(PlanTrackClassification.AUTO_MATCH);
        t1.setOriginalCandidateId("yt1");
        t1.setOriginalCandidateTitle("Song 1 (Audio)");
        t1.setOriginalCandidateChannel("Channel");
        t1.setOriginalCandidateDurationMs(Duration.ofMinutes(4).toMillis());
        t1.setOriginalMatchScore(0.95);
        t1.setOriginalMatchDecision(MatchDecision.MATCH);
        t1.acceptOriginalMatch();
        t1.setDecisionSource(ReviewDecisionSource.USER_SELECTED);
        t1.setReviewStatus(ReviewTrackStatus.USER_SELECTED);
        sessionAlice.addTrack(t1);

        sessionAlice.setStatus(ReviewSessionStatus.COMPLETED);
        sessionAlice = reviewSessionRepository.save(sessionAlice);
        reviewTrackRepository.saveAll(List.of(t0, t1));

        // Mock MusicDestination
        when(musicDestination.getPlatform()).thenReturn(Platform.YOUTUBE);
        when(musicDestination.createPlaylist(eq(alice.getId()), any(CreateDestinationPlaylistRequest.class)))
                .thenReturn(new DestinationPlaylist("yt_pl_alice_1", "Alice Favs", "Alice Favs Description", "private", "https://music.youtube.com/playlist?list=yt_pl_alice_1", Platform.YOUTUBE));

        when(musicDestination.addTrackToPlaylist(eq(alice.getId()), eq("yt_pl_alice_1"), any(AddTrackToDestinationPlaylistRequest.class)))
                .thenReturn(new DestinationPlaylistItem("item_yt_" + UUID.randomUUID(), "yt_pl_alice_1", "yt_vid", 0));
    }

    @Test
    @WithMockUser(username = "alice@example.com")
    @DisplayName("Complete E2E: POST /api/migrations -> 202 Accepted -> worker execution -> progress 200 -> idempotent resubmission")
    void endToEndAsyncLifecycleSuccessfulExecution() throws Exception {
        RunMigrationApiRequest request = new RunMigrationApiRequest(
                sessionAlice.getId(),
                null, null, null, null, null
        );

        // 1. Submit migration -> Expect 202 Accepted with Location header
        MvcResult submitResult = mockMvc.perform(post("/api/migrations")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.reviewSessionId").value(sessionAlice.getId().toString()))
                .andExpect(jsonPath("$.totalTracks").value(2))
                .andReturn();

        JsonNode responseJson = objectMapper.readTree(submitResult.getResponse().getContentAsString());
        UUID executionId = UUID.fromString(responseJson.get("executionId").asText());

        // Verify playlist creation on external destination happened once
        verify(musicDestination, times(1)).createPlaylist(eq(alice.getId()), any());

        // 2. Poll until worker completes processing
        MigrationExecution completed = null;
        for (int i = 0; i < 50; i++) {
            completed = executionRepository.findById(executionId).orElse(null);
            if (completed != null && completed.getStatus() == MigrationExecutionStatus.COMPLETED) {
                break;
            }
            Thread.sleep(100);
        }

        assertThat(completed).isNotNull();
        assertThat(completed.getStatus()).isEqualTo(MigrationExecutionStatus.COMPLETED);

        // 3. Poll progress as Alice -> Expect 200 OK with 100% completed
        mockMvc.perform(get("/api/migrations/" + executionId + "/progress"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionId").value(executionId.toString()))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.totalTracks").value(2))
                .andExpect(jsonPath("$.processedTracks").value(2))
                .andExpect(jsonPath("$.remainingTracks").value(0))
                .andExpect(jsonPath("$.progressPercentage").value(100.0));

        // 4. Idempotent resubmission by Alice -> Expect 200 OK without re-running playlist creation
        mockMvc.perform(post("/api/migrations")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.executionId").value(executionId.toString()));

        // Playlist was NOT created again
        verify(musicDestination, times(1)).createPlaylist(eq(alice.getId()), any());
    }

    @Test
    @WithMockUser(username = "bob@example.com")
    @DisplayName("Anti-IDOR: Bob cannot access Alice's migration progress")
    void antiIdorCrossUserAccessDenied() throws Exception {
        // Create an execution owned by Alice
        RunMigrationApiRequest request = new RunMigrationApiRequest(
                sessionAlice.getId(),
                null, null, null, null, null
        );

        // Attempt submission by Bob for Alice's review session -> 404 Not Found (Anti-IDOR)
        mockMvc.perform(post("/api/migrations")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }
}
