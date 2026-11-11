package com.spotifyyoutube.migrator.migration.reliability.idempotency;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spotifyyoutube.migrator.common.exception.ErrorCode;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.identity.repository.UserRepository;
import com.spotifyyoutube.migrator.migration.api.dto.RunMigrationApiRequest;
import com.spotifyyoutube.migrator.migration.application.destination.MusicDestination;
import com.spotifyyoutube.migrator.migration.application.destination.model.CreateDestinationPlaylistRequest;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationPlaylist;
import com.spotifyyoutube.migrator.migration.application.plan.PlanTrackClassification;
import com.spotifyyoutube.migrator.migration.async.MigrationBackgroundWorker;
import com.spotifyyoutube.migrator.migration.execution.repository.ExecutionTrackRecordRepository;
import com.spotifyyoutube.migrator.migration.execution.repository.MigrationExecutionRepository;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewDecisionSource;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSession;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSessionStatus;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewTrack;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewTrackStatus;
import com.spotifyyoutube.migrator.migration.review.repository.ReviewSessionRepository;
import com.spotifyyoutube.migrator.migration.review.repository.ReviewTrackRepository;
import com.spotifyyoutube.migrator.playlist.domain.Platform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Dedicated integration test verifying Phase 13E idempotency filter ordering,
 * authentication resolution, claim acquisition, fingerprint mismatch rejection,
 * and replay caching using the application's normal Spring Boot auto-configured
 * MockMvc filter chain without any manual MockMvcBuilders construction.
 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@DisplayName("Phase 13E: Auto-Configured MockMvc Filter Ordering and Idempotency Audit")
public class MigrationIdempotencyAutoConfiguredMockMvcIT {

    @Autowired
    private MockMvc mockMvc; // Pure Spring Boot auto-configured MockMvc instance

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MigrationIdempotencyRepository idempotencyRepository;

    @Autowired
    private MigrationExecutionRepository executionRepository;

    @Autowired
    private ExecutionTrackRecordRepository trackRecordRepository;

    @Autowired
    private ReviewSessionRepository reviewSessionRepository;

    @Autowired
    private ReviewTrackRepository reviewTrackRepository;

    @Autowired
    private UserRepository userRepository;

    @MockBean
    private MusicDestination musicDestination;

    @MockBean
    private MigrationBackgroundWorker backgroundWorker;

    private User testUser1;
    private ReviewSession testSession1;
    private ReviewSession testSession2;

    @BeforeEach
    void setUp() {
        cleanDatabase();

        testUser1 = userRepository.save(new User("autoconfig-audit-1@test.com"));
        testSession1 = createApprovedSession(testUser1, "Spotify Playlist 1");
        testSession2 = createApprovedSession(testUser1, "Spotify Playlist 2");

        DestinationPlaylist mockPlaylist = new DestinationPlaylist(
                "PL_mock_dest_autoconfig", "Target Playlist", "Description", "private",
                "https://music.youtube.com/playlist?list=PL_mock_dest_autoconfig", Platform.YOUTUBE
        );
        when(musicDestination.createPlaylist(any(UUID.class), any(CreateDestinationPlaylistRequest.class)))
                .thenReturn(mockPlaylist);
        when(musicDestination.getPlaylist(any(UUID.class), any(String.class)))
                .thenReturn(mockPlaylist);
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    private void cleanDatabase() {
        idempotencyRepository.deleteAllInBatch();
        trackRecordRepository.deleteAllInBatch();
        executionRepository.deleteAllInBatch();
        reviewTrackRepository.deleteAllInBatch();
        reviewSessionRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    private ReviewSession createApprovedSession(User user, String title) {
        ReviewSession session = new ReviewSession(
                user, "sp_src_" + UUID.randomUUID(), title, UUID.randomUUID()
        );

        ReviewTrack track = new ReviewTrack();
        track.setPosition(0);
        track.setSourceTrackId("sp_tr_001");
        track.setSourceTitle("Test Song");
        track.setSourceArtist("Test Artist");
        track.setOriginalClassification(PlanTrackClassification.AUTO_MATCH);
        track.setOriginalCandidateId("yt_cand_001");
        track.setSelectedCandidateId("yt_cand_001");
        track.setReviewStatus(ReviewTrackStatus.ACCEPTED);
        track.setDecisionSource(ReviewDecisionSource.AUTOMATIC);
        track.setExcluded(false);

        session.addTrack(track);
        session.setStatus(ReviewSessionStatus.COMPLETED);
        session = reviewSessionRepository.save(session);
        reviewTrackRepository.save(track);

        return session;
    }

    @Test
    @DisplayName("Audit: Normal auto-configured MockMvc executes filter chain, acquires claim, rejects mismatch, and replays cached response")
    void normalAutoConfiguredMockMvc_filterOrderingAndIdempotencyAudit() throws Exception {
        String idempotencyKey = "audit-autoconfig-key-001";
        RunMigrationApiRequest requestDto = new RunMigrationApiRequest(
                testSession1.getId(), testUser1.getId(), null, "Target Playlist", null, true
        );
        String requestJson = objectMapper.writeValueAsString(requestDto);

        // Instrumentation to prove external mutation boundary: claim acquisition precedes playlist creation
        AtomicBoolean claimExistedDuringPlaylistCreation = new AtomicBoolean(false);
        AtomicBoolean noExecutionDuringPlaylistCreation = new AtomicBoolean(false);
        DestinationPlaylist mockPlaylist = new DestinationPlaylist(
                "PL_mock_dest_autoconfig", "Target Playlist", "Description", "private",
                "https://music.youtube.com/playlist?list=PL_mock_dest_autoconfig", Platform.YOUTUBE
        );
        when(musicDestination.createPlaylist(any(UUID.class), any(CreateDestinationPlaylistRequest.class)))
                .thenAnswer(invocation -> {
                    List<MigrationIdempotencyRecord> inFlightClaims = idempotencyRepository.findAll();
                    if (!inFlightClaims.isEmpty() && inFlightClaims.get(0).getStatus() == IdempotencyClaimStatus.PENDING) {
                        claimExistedDuringPlaylistCreation.set(true);
                    }
                    if (executionRepository.count() == 0) {
                        noExecutionDuringPlaylistCreation.set(true);
                    }
                    return mockPlaylist;
                });

        // 1. Initial valid submission via auto-configured MockMvc
        MockHttpServletResponse firstResponse = mockMvc.perform(
                post("/api/migrations/submit")
                        .with(csrf())
                        .with(user(testUser1.getEmail()))
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson)
        ).andReturn().getResponse();

        assertThat(firstResponse.getStatus()).isEqualTo(202);
        JsonNode firstRoot = objectMapper.readTree(firstResponse.getContentAsString());
        assertThat(firstRoot.hasNonNull("executionId")).isTrue();
        UUID firstExecutionId = UUID.fromString(firstRoot.get("executionId").asText());

        // Boundary Verification: Claim acquired before destination playlist creation & execution creation
        assertThat(claimExistedDuringPlaylistCreation.get())
                .as("Claim acquisition must precede destination playlist creation")
                .isTrue();
        assertThat(noExecutionDuringPlaylistCreation.get())
                .as("Destination playlist creation must precede execution creation")
                .isTrue();

        // Verification 1 & 2: ApiIdempotencyFilter executed and created exactly one claim record
        List<MigrationIdempotencyRecord> records = idempotencyRepository.findAll();
        assertThat(records).hasSize(1);
        MigrationIdempotencyRecord record = records.get(0);

        // Verification 3: Authenticated user was resolved correctly to testUser1
        assertThat(record.getUserId()).isEqualTo(testUser1.getId());
        assertThat(record.getIdempotencyKey()).isEqualTo(idempotencyKey);

        // Verification 4: Claim was completed with execution ID before response was written
        assertThat(record.getStatus()).isEqualTo(IdempotencyClaimStatus.COMPLETED);
        assertThat(record.getExecutionId()).isEqualTo(firstExecutionId);
        assertThat(record.getClaimExpiresAt()).isNull();

        // Verification: Exactly one execution aggregate and one worker dispatch
        assertThat(executionRepository.count()).isEqualTo(1);
        verify(musicDestination, times(1)).createPlaylist(any(UUID.class), any(CreateDestinationPlaylistRequest.class));
        verify(backgroundWorker, times(1)).processMigration(eq(firstExecutionId), eq(testUser1.getId()));

        // 2. Conflicting submission with same key but different review session (different fingerprint)
        RunMigrationApiRequest conflictingDto = new RunMigrationApiRequest(
                testSession2.getId(), testUser1.getId(), null, "Target Playlist 2", null, true
        );

        MockHttpServletResponse conflictResponse = mockMvc.perform(
                post("/api/migrations/submit")
                        .with(csrf())
                        .with(user(testUser1.getEmail()))
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(conflictingDto))
        ).andReturn().getResponse();

        // Verification 5: Fingerprint mismatch returns HTTP 409 without invoking controller/dispatcher
        assertThat(conflictResponse.getStatus()).isEqualTo(409);
        assertThat(conflictResponse.getContentAsString()).contains(ErrorCode.IDEMPOTENCY_FINGERPRINT_MISMATCH.name());
        assertThat(executionRepository.count()).isEqualTo(1);
        // Destination playlist creation and worker dispatch still called only 1 time!
        verify(musicDestination, times(1)).createPlaylist(any(UUID.class), any(CreateDestinationPlaylistRequest.class));
        verify(backgroundWorker, times(1)).processMigration(any(UUID.class), any(UUID.class));

        // 3. Replay submission with same key and original request payload
        MockHttpServletResponse replayResponse = mockMvc.perform(
                post("/api/migrations/submit")
                        .with(csrf())
                        .with(user(testUser1.getEmail()))
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson)
        ).andReturn().getResponse();

        // Verification 6: Replay returns stored response without invoking controller or dispatcher
        assertThat(replayResponse.getStatus()).isEqualTo(202);
        assertThat(replayResponse.getContentAsString()).isEqualTo(firstResponse.getContentAsString());
        assertThat(replayResponse.getHeader(HttpHeaders.LOCATION)).isEqualTo("/api/migrations/" + firstExecutionId + "/progress");
        assertThat(executionRepository.count()).isEqualTo(1);
        verify(musicDestination, times(1)).createPlaylist(any(UUID.class), any(CreateDestinationPlaylistRequest.class));
        verify(backgroundWorker, times(1)).processMigration(any(UUID.class), any(UUID.class));
    }

    @Test
    @DisplayName("Audit: Anonymous request to typed endpoint /api/migrations/submit is rejected with 401 without claim acquisition")
    void anonymousSubmission_rejectedWith401_noClaimAcquired() throws Exception {
        String key = "anonymous-key-002";
        RunMigrationApiRequest requestDto = new RunMigrationApiRequest(
                testSession1.getId(), testUser1.getId(), null, "Target Playlist", null, true
        );

        MockHttpServletResponse response = mockMvc.perform(
                post("/api/migrations/submit")
                        .with(csrf())
                        // NO authenticated user post processor
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestDto))
        ).andReturn().getResponse();

        // Must reject with 401 Unauthorized
        assertThat(response.getStatus()).isEqualTo(401);

        // No claim record created in database
        assertThat(idempotencyRepository.count()).isEqualTo(0);

        // No execution created
        assertThat(executionRepository.count()).isEqualTo(0);

        // Music destination and background worker never invoked
        verify(musicDestination, times(0)).createPlaylist(any(UUID.class), any(CreateDestinationPlaylistRequest.class));
        verify(backgroundWorker, times(0)).processMigration(any(UUID.class), any(UUID.class));
    }
}
