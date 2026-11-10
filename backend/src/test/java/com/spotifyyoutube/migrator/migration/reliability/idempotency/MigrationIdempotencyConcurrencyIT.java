package com.spotifyyoutube.migrator.migration.reliability.idempotency;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spotifyyoutube.migrator.common.exception.ErrorCode;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.identity.repository.UserRepository;
import com.spotifyyoutube.migrator.migration.api.dto.RunMigrationApiRequest;
import com.spotifyyoutube.migrator.migration.application.destination.MusicDestination;
import com.spotifyyoutube.migrator.migration.application.destination.model.CreateDestinationPlaylistRequest;
import com.spotifyyoutube.migrator.migration.async.MigrationBackgroundWorker;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecution;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus;
import com.spotifyyoutube.migrator.migration.execution.repository.ExecutionTrackRecordRepository;
import com.spotifyyoutube.migrator.migration.execution.repository.MigrationExecutionRepository;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSession;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewTrack;
import com.spotifyyoutube.migrator.migration.review.repository.ReviewSessionRepository;
import com.spotifyyoutube.migrator.migration.review.repository.ReviewTrackRepository;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * API-level PostgreSQL integration tests verifying Phase 13E concurrency safety,
 * database-backed atomic claim reservations, replay caching, and crash recovery.
 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@DisplayName("Phase 13E: API-Level Idempotency Concurrency Integration Tests")
public class MigrationIdempotencyConcurrencyIT {

    @Autowired
    private MockMvc mockMvc;

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
    private User testUser2;
    private ReviewSession testSession1;
    private ReviewSession testSession2;

    @BeforeEach
    void setUp() {
        cleanDatabase();

        testUser1 = userRepository.save(new User("idempotency-concurrency-1@test.com"));
        testUser2 = userRepository.save(new User("idempotency-concurrency-2@test.com"));

        testSession1 = createApprovedSession(testUser1, "Spotify Playlist 1");
        testSession2 = createApprovedSession(testUser1, "Spotify Playlist 2");

        DestinationPlaylist mockPlaylist = new DestinationPlaylist(
                "PL_mock_dest_123", "Target Playlist", "Description",
                "https://music.youtube.com/playlist?list=PL_mock_dest_123", 0
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
        session = reviewSessionRepository.save(session);

        ReviewTrack track = new ReviewTrack();
        track.setReviewSession(session);
        track.setPosition(0);
        track.setSourceTrackId("sp_tr_001");
        track.setSourceTitle("Test Song");
        track.setSourceArtist("Test Artist");
        track.setSelectedCandidateId("yt_cand_001");
        track.setDecisionSource(ReviewDecisionSource.AUTOMATIC);
        track.setExcluded(false);
        reviewTrackRepository.save(track);

        return session;
    }

    @Test
    @DisplayName("1. Eight concurrent identical submissions converge on one execution and dispatch worker once")
    void eightConcurrentIdenticalSubmissions_convergeOnSingleExecution() throws Exception {
        int concurrency = 8;
        String idempotencyKey = "concurrency-key-identical-001";
        RunMigrationApiRequest requestDto = new RunMigrationApiRequest(
                testSession1.getId(), testUser1.getId(), null, "Target Playlist", null, true
        );
        String requestJson = objectMapper.writeValueAsString(requestDto);

        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(concurrency);

        Set<String> observedExecutionIds = ConcurrentHashMap.newKeySet();
        List<Integer> statusCodes = Collections.synchronizedList(new ArrayList<>());
        List<String> locationHeaders = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < concurrency; i++) {
            executor.submit(() -> {
                try {
                    startGate.await();
                    MockHttpServletResponse response = mockMvc.perform(
                            post("/api/migrations/submit")
                                    .with(csrf())
                                    .with(user(testUser1.getEmail()))
                                    .header("Idempotency-Key", idempotencyKey)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(requestJson)
                    ).andReturn().getResponse();

                    statusCodes.add(response.getStatus());
                    String loc = response.getHeader(HttpHeaders.LOCATION);
                    if (loc != null) {
                        locationHeaders.add(loc);
                    }
                    JsonNode root = objectMapper.readTree(response.getContentAsString());
                    if (root.hasNonNull("executionId")) {
                        observedExecutionIds.add(root.get("executionId").asText());
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    endGate.countDown();
                }
            });
        }

        startGate.countDown();
        assertThat(endGate.await(15, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        // 1. All 8 requests returned HTTP 202 Accepted
        assertThat(statusCodes).hasSize(concurrency);
        assertThat(statusCodes).allMatch(code -> code == 202);

        // 2. All 8 requests converged on the exact same execution ID
        assertThat(observedExecutionIds).hasSize(1);
        String singleExecutionId = observedExecutionIds.iterator().next();

        // 3. Location headers all match the progress endpoint
        assertThat(locationHeaders).hasSize(concurrency);
        assertThat(locationHeaders).allMatch(loc -> loc.equals("/api/migrations/" + singleExecutionId + "/progress"));

        // 4. Exactly one execution aggregate in database
        List<MigrationExecution> executions = executionRepository.findAll();
        assertThat(executions).hasSize(1);
        assertThat(executions.get(0).getId().toString()).isEqualTo(singleExecutionId);

        // 5. Exactly one completed idempotency record in database
        List<MigrationIdempotencyRecord> records = idempotencyRepository.findAll();
        assertThat(records).hasSize(1);
        MigrationIdempotencyRecord record = records.get(0);
        assertThat(record.getStatus()).isEqualTo(IdempotencyClaimStatus.COMPLETED);
        assertThat(record.getExecutionId().toString()).isEqualTo(singleExecutionId);
        assertThat(record.getClaimExpiresAt()).isNull();

        // 6. External destination playlist creation called exactly once
        verify(musicDestination, times(1)).createPlaylist(any(UUID.class), any(CreateDestinationPlaylistRequest.class));

        // 7. Background worker dispatched exactly once
        verify(backgroundWorker, times(1)).processMigration(eq(UUID.fromString(singleExecutionId)), eq(testUser1.getId()));
    }

    @Test
    @DisplayName("2. Concurrent requests with same key but different fingerprints: winner takes execution, other gets 409")
    void concurrentRequests_sameKeyDifferentFingerprint_oneWinsOtherGets409() throws Exception {
        int concurrency = 2;
        String idempotencyKey = "concurrency-key-mismatch-002";

        RunMigrationApiRequest reqA = new RunMigrationApiRequest(
                testSession1.getId(), testUser1.getId(), null, "Target A", null, true
        );
        RunMigrationApiRequest reqB = new RunMigrationApiRequest(
                testSession2.getId(), testUser1.getId(), null, "Target B", null, true
        );

        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(concurrency);

        List<Integer> statusCodes = Collections.synchronizedList(new ArrayList<>());
        List<String> responseBodies = Collections.synchronizedList(new ArrayList<>());

        executor.submit(() -> {
            try {
                startGate.await();
                MockHttpServletResponse res = mockMvc.perform(
                        post("/api/migrations/submit")
                                .with(csrf())
                                .with(user(testUser1.getEmail()))
                                .header("Idempotency-Key", idempotencyKey)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(reqA))
                ).andReturn().getResponse();
                statusCodes.add(res.getStatus());
                responseBodies.add(res.getContentAsString());
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                endGate.countDown();
            }
        });

        executor.submit(() -> {
            try {
                startGate.await();
                MockHttpServletResponse res = mockMvc.perform(
                        post("/api/migrations/submit")
                                .with(csrf())
                                .with(user(testUser1.getEmail()))
                                .header("Idempotency-Key", idempotencyKey)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(reqB))
                ).andReturn().getResponse();
                statusCodes.add(res.getStatus());
                responseBodies.add(res.getContentAsString());
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                endGate.countDown();
            }
        });

        startGate.countDown();
        assertThat(endGate.await(10, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        // Exactly one 202 and exactly one 409
        assertThat(statusCodes).containsExactlyInAnyOrder(202, 409);

        // 409 response has IDEMPOTENCY_FINGERPRINT_MISMATCH code
        String conflictBody = responseBodies.stream()
                .filter(b -> b.contains(ErrorCode.IDEMPOTENCY_FINGERPRINT_MISMATCH.name()))
                .findFirst().orElseThrow();
        assertThat(conflictBody).contains("previously used with a different request payload");

        // Exactly 1 execution aggregate created
        assertThat(executionRepository.count()).isEqualTo(1);

        // Exactly 1 worker dispatch occurred
        verify(backgroundWorker, times(1)).processMigration(any(UUID.class), any(UUID.class));
    }

    @Test
    @DisplayName("3. Same key used by two different users does not collide and isolates executions")
    void sameKey_twoDifferentUsers_noCollision() throws Exception {
        String sharedKey = "shared-user-key-003";

        ReviewSession sessionUser2 = createApprovedSession(testUser2, "User 2 Playlist");

        RunMigrationApiRequest reqUser1 = new RunMigrationApiRequest(
                testSession1.getId(), testUser1.getId(), null, "User 1 Target", null, true
        );
        RunMigrationApiRequest reqUser2 = new RunMigrationApiRequest(
                sessionUser2.getId(), testUser2.getId(), null, "User 2 Target", null, true
        );

        MockHttpServletResponse res1 = mockMvc.perform(
                post("/api/migrations/submit")
                        .with(csrf())
                        .with(user(testUser1.getEmail()))
                        .header("Idempotency-Key", sharedKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqUser1))
        ).andReturn().getResponse();

        MockHttpServletResponse res2 = mockMvc.perform(
                post("/api/migrations/submit")
                        .with(csrf())
                        .with(user(testUser2.getEmail()))
                        .header("Idempotency-Key", sharedKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqUser2))
        ).andReturn().getResponse();

        assertThat(res1.getStatus()).isEqualTo(202);
        assertThat(res2.getStatus()).isEqualTo(202);

        JsonNode json1 = objectMapper.readTree(res1.getContentAsString());
        JsonNode json2 = objectMapper.readTree(res2.getContentAsString());

        UUID execId1 = UUID.fromString(json1.get("executionId").asText());
        UUID execId2 = UUID.fromString(json2.get("executionId").asText());

        assertThat(execId1).isNotEqualTo(execId2);
        assertThat(executionRepository.count()).isEqualTo(2);
        assertThat(idempotencyRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("4. Winner crashes after acquiring claim before execution persistence: subsequent matching request takes over safely")
    void winnerCrashes_beforeExecutionPersistence_subsequentRequestTakesOver() throws Exception {
        String key = "crash-recovery-key-004";
        CanonicalRequestFingerprinter fingerprinter = new CanonicalRequestFingerprinter();
        CanonicalMigrationRequest canonicalReq = new CanonicalMigrationRequest(
                testUser1.getId(), testSession1.getSourcePlaylistId(), "Target Playlist",
                List.of("sp_tr_001:yt_cand_001")
        );
        String fingerprint = fingerprinter.calculateFingerprint(canonicalReq);

        // Pre-insert an expired PENDING claim without execution_id
        MigrationIdempotencyRecord crashedClaim = new MigrationIdempotencyRecord(
                testUser1.getId(), key, fingerprint, IdempotencyClaimStatus.PENDING,
                null, null, null, null, testSession1.getId(),
                Instant.now().minusSeconds(120),
                Instant.now().minusSeconds(60), // Expired 60 seconds ago
                null
        );
        idempotencyRepository.saveAndFlush(crashedClaim);

        RunMigrationApiRequest req = new RunMigrationApiRequest(
                testSession1.getId(), testUser1.getId(), null, "Target Playlist", null, true
        );

        MockHttpServletResponse response = mockMvc.perform(
                post("/api/migrations/submit")
                        .with(csrf())
                        .with(user(testUser1.getEmail()))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
        ).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(202);

        // Exactly 1 execution created
        assertThat(executionRepository.count()).isEqualTo(1);
        MigrationExecution execution = executionRepository.findAll().get(0);

        // Claim transitioned to COMPLETED
        MigrationIdempotencyRecord completed = idempotencyRepository.findByUserIdAndIdempotencyKey(testUser1.getId(), key).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(IdempotencyClaimStatus.COMPLETED);
        assertThat(completed.getExecutionId()).isEqualTo(execution.getId());

        // Worker dispatched once
        verify(backgroundWorker, times(1)).processMigration(eq(execution.getId()), eq(testUser1.getId()));
    }

    @Test
    @DisplayName("5. Winner crashes after execution persistence but before response persistence: reconciles execution without duplicate dispatch")
    void winnerCrashes_afterExecutionPersistence_reconcilesExecutionWithoutDuplicateDispatch() throws Exception {
        String key = "crash-reconciliation-key-005";
        CanonicalRequestFingerprinter fingerprinter = new CanonicalRequestFingerprinter();
        CanonicalMigrationRequest canonicalReq = new CanonicalMigrationRequest(
                testUser1.getId(), testSession1.getSourcePlaylistId(), "Target Playlist",
                List.of("sp_tr_001:yt_cand_001")
        );
        String fingerprint = fingerprinter.calculateFingerprint(canonicalReq);

        // Pre-create an execution aggregate for testSession1
        MigrationExecution existingExecution = new MigrationExecution(
                testUser1, testSession1, null, "PL_crashed_123",
                "https://music.youtube.com/playlist?list=PL_crashed_123",
                MigrationExecutionStatus.QUEUED
        );
        existingExecution = executionRepository.saveAndFlush(existingExecution);

        // Pre-insert an expired PENDING claim referencing testSession1
        MigrationIdempotencyRecord crashedClaim = new MigrationIdempotencyRecord(
                testUser1.getId(), key, fingerprint, IdempotencyClaimStatus.PENDING,
                null, null, null, null, testSession1.getId(),
                Instant.now().minusSeconds(120),
                Instant.now().minusSeconds(30), // Expired 30 seconds ago
                null
        );
        idempotencyRepository.saveAndFlush(crashedClaim);

        RunMigrationApiRequest req = new RunMigrationApiRequest(
                testSession1.getId(), testUser1.getId(), null, "Target Playlist", null, true
        );

        MockHttpServletResponse response = mockMvc.perform(
                post("/api/migrations/submit")
                        .with(csrf())
                        .with(user(testUser1.getEmail()))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
        ).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(202);
        JsonNode root = objectMapper.readTree(response.getContentAsString());
        assertThat(root.get("executionId").asText()).isEqualTo(existingExecution.getId().toString());

        // Exactly 1 execution still in database (no duplicate!)
        assertThat(executionRepository.count()).isEqualTo(1);

        // No new destination playlist creation
        verify(musicDestination, never()).createPlaylist(any(UUID.class), any(CreateDestinationPlaylistRequest.class));

        // Worker was NOT dispatched again
        verify(backgroundWorker, never()).processMigration(any(UUID.class), any(UUID.class));
    }

    @Test
    @DisplayName("6. Replay after successful completion returns cached status, body, and Location header without dispatcher invocation")
    void replayAfterCompletion_returnsCachedResponseWithoutDispatcher() throws Exception {
        String key = "replay-success-key-006";
        RunMigrationApiRequest req = new RunMigrationApiRequest(
                testSession1.getId(), testUser1.getId(), null, "Target Playlist", null, true
        );
        String requestJson = objectMapper.writeValueAsString(req);

        // 1. Initial submission
        MockHttpServletResponse initialRes = mockMvc.perform(
                post("/api/migrations/submit")
                        .with(csrf())
                        .with(user(testUser1.getEmail()))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson)
        ).andReturn().getResponse();

        assertThat(initialRes.getStatus()).isEqualTo(202);
        String initialBody = initialRes.getContentAsString();
        String initialLocation = initialRes.getHeader(HttpHeaders.LOCATION);

        // 2. Replay submission
        MockHttpServletResponse replayRes = mockMvc.perform(
                post("/api/migrations/submit")
                        .with(csrf())
                        .with(user(testUser1.getEmail()))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson)
        ).andReturn().getResponse();

        assertThat(replayRes.getStatus()).isEqualTo(202);
        assertThat(replayRes.getContentAsString()).isEqualTo(initialBody);
        assertThat(replayRes.getHeader(HttpHeaders.LOCATION)).isEqualTo(initialLocation);

        // Worker and playlist creation must NOT have been called twice!
        verify(musicDestination, times(1)).createPlaylist(any(UUID.class), any(CreateDestinationPlaylistRequest.class));
        verify(backgroundWorker, times(1)).processMigration(any(UUID.class), any(UUID.class));
    }
}
