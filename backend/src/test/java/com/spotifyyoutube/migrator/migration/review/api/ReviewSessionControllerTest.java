package com.spotifyyoutube.migrator.migration.review.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spotifyyoutube.migrator.common.config.SecurityConfig;
import com.spotifyyoutube.migrator.common.exception.ExternalProviderException;
import com.spotifyyoutube.migrator.common.exception.GlobalExceptionHandler;
import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.identity.application.UserService;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.matching.engine.MatchDecision;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationTrackCandidate;
import com.spotifyyoutube.migrator.migration.application.plan.MigrationPlan;
import com.spotifyyoutube.migrator.migration.application.plan.MigrationPlanService;
import com.spotifyyoutube.migrator.migration.application.plan.MigrationPlanSummary;
import com.spotifyyoutube.migrator.migration.application.plan.PlanTrackClassification;
import com.spotifyyoutube.migrator.migration.review.api.dto.CandidateSelectionDto;
import com.spotifyyoutube.migrator.migration.review.api.dto.CreateReviewSessionRequest;
import com.spotifyyoutube.migrator.migration.review.api.dto.ExcludeTrackRequest;
import com.spotifyyoutube.migrator.migration.review.api.dto.ReviewCandidateSearchApiRequest;
import com.spotifyyoutube.migrator.migration.review.api.dto.ReviewDecisionType;
import com.spotifyyoutube.migrator.migration.review.api.dto.SelectCandidateRequest;
import com.spotifyyoutube.migrator.migration.review.api.dto.SubmitReviewDecisionRequest;
import com.spotifyyoutube.migrator.migration.review.application.ReviewCandidateSearchRequest;
import com.spotifyyoutube.migrator.migration.review.application.ReviewCandidateSearchResult;
import com.spotifyyoutube.migrator.migration.review.application.ReviewSessionService;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewDecisionSource;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewProgressSummary;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSession;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSessionStatus;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewTrack;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewTrackStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReviewSessionController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@WithMockUser(username = "tester@example.com")
@DisplayName("ReviewSessionController WebMvc Tests")
public class ReviewSessionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ReviewSessionService reviewSessionService;

    @MockBean
    private MigrationPlanService migrationPlanService;

    @MockBean
    private UserService userService;

    @Autowired
    private ObjectMapper objectMapper;

    private User mockAuthenticatedUser(UUID userId) {
        User user = new User();
        user.setId(userId);
        user.setEmail("tester@example.com");
        when(userService.getUserByEmail("tester@example.com")).thenReturn(user);
        return user;
    }

    private ReviewSession createSampleSession(UUID sessionId, UUID userId, String playlistId) {
        User user = new User();
        user.setId(userId);
        user.setEmail("tester@example.com");

        ReviewSession session = new ReviewSession(user, playlistId, "Rock Classics", UUID.randomUUID());
        session.setId(sessionId);
        session.setCreatedAt(Instant.now());
        session.setUpdatedAt(Instant.now());
        return session;
    }

    private ReviewTrack createSampleTrack(ReviewSession session, int position) {
        ReviewTrack track = new ReviewTrack();
        track.setId(UUID.randomUUID());
        track.setSession(session);
        track.setPosition(position);
        track.setSourceTrackId("sp-" + position);
        track.setSourceTitle("Track " + position);
        track.setSourceArtist("Artist " + position);
        track.setOriginalClassification(PlanTrackClassification.AUTO_MATCH);
        track.setOriginalCandidateId("yt-" + position);
        track.setOriginalCandidateTitle("Video " + position);
        track.setOriginalCandidateChannel("Artist " + position);
        track.setOriginalCandidateDurationMs(200000L);
        track.setOriginalMatchScore(0.95);
        track.setOriginalCalibratedProbability(0.98);
        track.setOriginalMatchDecision(MatchDecision.MATCH);
        track.setOriginalMatchReasons("High confidence title and artist match");
        track.setReviewStatus(ReviewTrackStatus.ACCEPTED);
        track.setDecisionSource(ReviewDecisionSource.AUTOMATIC);
        track.setSelectedCandidateId("yt-" + position);
        track.setSelectedCandidateTitle("Video " + position);
        track.setSelectedCandidateChannel("Artist " + position);
        track.setSelectedCandidateDurationMs(200000L);
        track.setExcluded(false);
        track.setHasConflict(false);
        return track;
    }

    @Nested
    @DisplayName("POST /api/review-sessions — Session Creation")
    class SessionCreationTests {

        @Test
        @DisplayName("Valid creation request creates review session and returns 201 Created")
        void createSessionSuccess() throws Exception {
            UUID userId = UUID.randomUUID();
            UUID sessionId = UUID.randomUUID();
            mockAuthenticatedUser(userId);

            CreateReviewSessionRequest request = new CreateReviewSessionRequest("spotify-123", userId, true);
            MigrationPlan mockPlan = new MigrationPlan(
                    UUID.randomUUID(), "spotify-123", "Rock Classics",
                    Instant.now(), List.of(),
                    new MigrationPlanSummary(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
            );
            ReviewSession session = createSampleSession(sessionId, userId, "spotify-123");

            when(migrationPlanService.generatePlan(eq(userId), eq("spotify-123"), eq(true))).thenReturn(mockPlan);
            when(reviewSessionService.createSessionFromPlan(eq(userId), eq(mockPlan))).thenReturn(session);

            mockMvc.perform(post("/api/review-sessions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request))
                            .with(csrf()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").value(sessionId.toString()))
                    .andExpect(jsonPath("$.userId").value(userId.toString()))
                    .andExpect(jsonPath("$.sourcePlaylistId").value("spotify-123"))
                    .andExpect(jsonPath("$.sourcePlaylistTitle").value("Rock Classics"))
                    .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                    .andExpect(jsonPath("$.progress.totalTracks").value(0))
                    .andExpect(jsonPath("$.progress.eligibleForCompletion").value(true));
        }

        @Test
        @DisplayName("Alias route /api/reviews also creates review session")
        void createSessionAliasRouteSuccess() throws Exception {
            UUID userId = UUID.randomUUID();
            UUID sessionId = UUID.randomUUID();
            mockAuthenticatedUser(userId);

            CreateReviewSessionRequest request = new CreateReviewSessionRequest("spotify-456");
            MigrationPlan mockPlan = new MigrationPlan(
                    UUID.randomUUID(), "spotify-456", "Indie Pop",
                    Instant.now(), List.of(),
                    new MigrationPlanSummary(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
            );
            ReviewSession session = createSampleSession(sessionId, userId, "spotify-456");

            when(migrationPlanService.generatePlan(eq(userId), eq("spotify-456"), eq(true))).thenReturn(mockPlan);
            when(reviewSessionService.createSessionFromPlan(eq(userId), eq(mockPlan))).thenReturn(session);

            mockMvc.perform(post("/api/reviews")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request))
                            .with(csrf()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").value(sessionId.toString()));
        }

        @Test
        @DisplayName("Blank sourcePlaylistId returns 400 Bad Request")
        void createSessionBlankPlaylistId() throws Exception {
            UUID userId = UUID.randomUUID();
            mockAuthenticatedUser(userId);

            String payload = "{\"sourcePlaylistId\":\"   \"}";

            mockMvc.perform(post("/api/review-sessions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload)
                            .with(csrf()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }

        @Test
        @DisplayName("Missing request body returns 400 Bad Request")
        void createSessionMissingBody() throws Exception {
            mockMvc.perform(post("/api/review-sessions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .with(csrf()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }

        @Test
        @DisplayName("Anti-impersonation: mismatched requested userId returns 400 Bad Request")
        void createSessionMismatchedUserId() throws Exception {
            UUID authenticatedUserId = UUID.randomUUID();
            UUID imposterUserId = UUID.randomUUID();
            mockAuthenticatedUser(authenticatedUserId);

            CreateReviewSessionRequest request = new CreateReviewSessionRequest("spotify-123", imposterUserId, true);

            mockMvc.perform(post("/api/review-sessions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request))
                            .with(csrf()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.message").value("Authenticated user does not match the requested user ID"));
        }

        @Test
        @WithAnonymousUser
        @DisplayName("Unauthenticated request returns 401 or 403")
        void createSessionUnauthenticated() throws Exception {
            CreateReviewSessionRequest request = new CreateReviewSessionRequest("spotify-123");

            mockMvc.perform(post("/api/review-sessions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request))
                            .with(csrf()))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("GET /api/review-sessions — Session Retrieval")
    class SessionRetrievalTests {

        @Test
        @DisplayName("GET /api/review-sessions returns user review sessions list")
        void getUserSessionsSuccess() throws Exception {
            UUID userId = UUID.randomUUID();
            mockAuthenticatedUser(userId);

            ReviewSession s1 = createSampleSession(UUID.randomUUID(), userId, "pl-1");
            ReviewSession s2 = createSampleSession(UUID.randomUUID(), userId, "pl-2");

            when(reviewSessionService.getUserSessions(userId)).thenReturn(List.of(s1, s2));
            when(reviewSessionService.getReviewProgress(s1.getId())).thenReturn(ReviewProgressSummary.empty());
            when(reviewSessionService.getReviewProgress(s2.getId())).thenReturn(ReviewProgressSummary.empty());

            mockMvc.perform(get("/api/review-sessions"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[0].id").value(s1.getId().toString()))
                    .andExpect(jsonPath("$[1].id").value(s2.getId().toString()));
        }

        @Test
        @DisplayName("GET /api/review-sessions/{id} returns single session with progress")
        void getSessionByIdSuccess() throws Exception {
            UUID userId = UUID.randomUUID();
            UUID sessionId = UUID.randomUUID();
            mockAuthenticatedUser(userId);

            ReviewSession session = createSampleSession(sessionId, userId, "pl-1");
            ReviewProgressSummary summary = new ReviewProgressSummary(1, 0, 1, 0, 0, 0, 0, true);

            when(reviewSessionService.getSessionForUser(sessionId, userId)).thenReturn(session);
            when(reviewSessionService.getReviewProgressForUser(sessionId, userId)).thenReturn(summary);

            mockMvc.perform(get("/api/review-sessions/" + sessionId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(sessionId.toString()))
                    .andExpect(jsonPath("$.progress.totalTracks").value(1))
                    .andExpect(jsonPath("$.progress.acceptedCount").value(1));
        }

        @Test
        @DisplayName("GET /api/review-sessions/{id} for non-existent session returns 404")
        void getSessionNotFound() throws Exception {
            UUID userId = UUID.randomUUID();
            UUID sessionId = UUID.randomUUID();
            mockAuthenticatedUser(userId);

            when(reviewSessionService.getSessionForUser(sessionId, userId))
                    .thenThrow(new ResourceNotFoundException("Review session not found"));

            mockMvc.perform(get("/api/review-sessions/" + sessionId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }

        @Test
        @DisplayName("GET /api/review-sessions/{id}/progress returns real-time progress summary")
        void getSessionProgressSuccess() throws Exception {
            UUID userId = UUID.randomUUID();
            UUID sessionId = UUID.randomUUID();
            mockAuthenticatedUser(userId);

            ReviewProgressSummary summary = new ReviewProgressSummary(5, 2, 2, 1, 0, 0, 2, false);
            when(reviewSessionService.getReviewProgressForUser(sessionId, userId)).thenReturn(summary);

            mockMvc.perform(get("/api/review-sessions/" + sessionId + "/progress"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalTracks").value(5))
                    .andExpect(jsonPath("$.awaitingReviewCount").value(2))
                    .andExpect(jsonPath("$.acceptedCount").value(2))
                    .andExpect(jsonPath("$.manuallyCorrectedCount").value(1))
                    .andExpect(jsonPath("$.remainingRequiredDecisions").value(2))
                    .andExpect(jsonPath("$.eligibleForCompletion").value(false));
        }
    }

    @Nested
    @DisplayName("GET /api/review-sessions/{id}/tracks — Tracks Retrieval")
    class TrackRetrievalTests {

        @Test
        @DisplayName("GET tracks returns ordered track responses")
        void getTracksSuccess() throws Exception {
            UUID userId = UUID.randomUUID();
            UUID sessionId = UUID.randomUUID();
            mockAuthenticatedUser(userId);

            ReviewSession session = createSampleSession(sessionId, userId, "pl-1");
            ReviewTrack t0 = createSampleTrack(session, 0);
            ReviewTrack t1 = createSampleTrack(session, 1);

            when(reviewSessionService.getTracksForUser(sessionId, userId)).thenReturn(List.of(t0, t1));

            mockMvc.perform(get("/api/review-sessions/" + sessionId + "/tracks"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[0].position").value(0))
                    .andExpect(jsonPath("$[0].sourceTitle").value("Track 0"))
                    .andExpect(jsonPath("$[0].originalClassification").value("AUTO_MATCH"))
                    .andExpect(jsonPath("$[0].selectedCandidate.id").value("yt-0"))
                    .andExpect(jsonPath("$[1].position").value(1))
                    .andExpect(jsonPath("$[1].sourceTitle").value("Track 1"));
        }

        @Test
        @DisplayName("GET single track returns track response at position")
        void getSingleTrackSuccess() throws Exception {
            UUID userId = UUID.randomUUID();
            UUID sessionId = UUID.randomUUID();
            mockAuthenticatedUser(userId);

            ReviewSession session = createSampleSession(sessionId, userId, "pl-1");
            ReviewTrack t0 = createSampleTrack(session, 0);

            when(reviewSessionService.getTrackForUser(sessionId, 0, userId)).thenReturn(t0);

            mockMvc.perform(get("/api/review-sessions/" + sessionId + "/tracks/0"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.position").value(0))
                    .andExpect(jsonPath("$.sourceTrackId").value("sp-0"))
                    .andExpect(jsonPath("$.reviewStatus").value("ACCEPTED"))
                    .andExpect(jsonPath("$.approvedForMigration").value(true));
        }

        @Test
        @DisplayName("GET track with invalid position returns 404 if not found")
        void getSingleTrackNotFound() throws Exception {
            UUID userId = UUID.randomUUID();
            UUID sessionId = UUID.randomUUID();
            mockAuthenticatedUser(userId);

            when(reviewSessionService.getTrackForUser(sessionId, 99, userId))
                    .thenThrow(new ResourceNotFoundException("Track not found at position 99"));

            mockMvc.perform(get("/api/review-sessions/" + sessionId + "/tracks/99"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
    }

    @Nested
    @DisplayName("Candidate Search Endpoints")
    class CandidateSearchTests {

        @Test
        @DisplayName("POST search candidates with default automated query returns 200 OK")
        void searchCandidatesAutomatedSuccess() throws Exception {
            UUID userId = UUID.randomUUID();
            UUID sessionId = UUID.randomUUID();
            mockAuthenticatedUser(userId);

            DestinationTrackCandidate cand = new DestinationTrackCandidate(
                    "yt-alt-1", "Alternative Video", "Artist Channel",
                    "Description", "thumb.jpg", "2024-01-01", Duration.ofSeconds(210)
            );
            ReviewCandidateSearchResult result = new ReviewCandidateSearchResult(
                    sessionId, 0, "sp-0", "Song", "Artist",
                    "Song Artist", List.of(cand), false, 1
            );

            when(reviewSessionService.searchCandidates(eq(sessionId), eq(0), any(ReviewCandidateSearchRequest.class), eq(userId)))
                    .thenReturn(result);

            mockMvc.perform(post("/api/review-sessions/" + sessionId + "/tracks/0/candidates/search")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}")
                            .with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sessionId").value(sessionId.toString()))
                    .andExpect(jsonPath("$.trackPosition").value(0))
                    .andExpect(jsonPath("$.isCustomQuery").value(false))
                    .andExpect(jsonPath("$.totalResults").value(1))
                    .andExpect(jsonPath("$.candidates[0].id").value("yt-alt-1"))
                    .andExpect(jsonPath("$.candidates[0].title").value("Alternative Video"));
        }

        @Test
        @DisplayName("POST search candidates with custom query and limit returns 200 OK")
        void searchCandidatesCustomQuerySuccess() throws Exception {
            UUID userId = UUID.randomUUID();
            UUID sessionId = UUID.randomUUID();
            mockAuthenticatedUser(userId);

            ReviewCandidateSearchApiRequest apiReq = new ReviewCandidateSearchApiRequest("Custom Song Live", 5);
            DestinationTrackCandidate cand = new DestinationTrackCandidate("yt-live-1", "Song Live 2024", "Artist", (Duration) null);
            ReviewCandidateSearchResult result = new ReviewCandidateSearchResult(
                    sessionId, 1, "sp-1", "Song", "Artist",
                    "Custom Song Live", List.of(cand), true, 1
            );

            when(reviewSessionService.searchCandidates(eq(sessionId), eq(1), any(ReviewCandidateSearchRequest.class), eq(userId)))
                    .thenReturn(result);

            mockMvc.perform(post("/api/review-sessions/" + sessionId + "/tracks/1/candidates/search")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(apiReq))
                            .with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.isCustomQuery").value(true))
                    .andExpect(jsonPath("$.queryUsed").value("Custom Song Live"))
                    .andExpect(jsonPath("$.totalResults").value(1));
        }

        @Test
        @DisplayName("POST search candidates with zero results returns 200 OK with empty array")
        void searchCandidatesZeroResults() throws Exception {
            UUID userId = UUID.randomUUID();
            UUID sessionId = UUID.randomUUID();
            mockAuthenticatedUser(userId);

            ReviewCandidateSearchResult result = ReviewCandidateSearchResult.empty(
                    sessionId, 0, "sp-0", "Unknown", "Unknown", "Unknown Unknown", false
            );

            when(reviewSessionService.searchCandidates(eq(sessionId), eq(0), any(ReviewCandidateSearchRequest.class), eq(userId)))
                    .thenReturn(result);

            mockMvc.perform(post("/api/review-sessions/" + sessionId + "/tracks/0/candidates/search")
                            .contentType(MediaType.APPLICATION_JSON)
                            .with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalResults").value(0))
                    .andExpect(jsonPath("$.candidates").isArray())
                    .andExpect(jsonPath("$.candidates.length()").value(0));
        }

        @Test
        @DisplayName("POST search candidates with limit exceeding max returns 400 Bad Request")
        void searchCandidatesLimitTooHigh() throws Exception {
            UUID userId = UUID.randomUUID();
            UUID sessionId = UUID.randomUUID();
            mockAuthenticatedUser(userId);

            ReviewCandidateSearchApiRequest apiReq = new ReviewCandidateSearchApiRequest("query", 999);

            mockMvc.perform(post("/api/review-sessions/" + sessionId + "/tracks/0/candidates/search")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(apiReq))
                            .with(csrf()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }

        @Test
        @DisplayName("POST search candidates on closed session returns 409 Conflict")
        void searchCandidatesClosedSession() throws Exception {
            UUID userId = UUID.randomUUID();
            UUID sessionId = UUID.randomUUID();
            mockAuthenticatedUser(userId);

            when(reviewSessionService.searchCandidates(eq(sessionId), eq(0), any(ReviewCandidateSearchRequest.class), eq(userId)))
                    .thenThrow(new InvalidStateException("Cannot search candidates for review session in COMPLETED status"));

            mockMvc.perform(post("/api/review-sessions/" + sessionId + "/tracks/0/candidates/search")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}")
                            .with(csrf()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INVALID_STATE"));
        }

        @Test
        @DisplayName("POST search candidates handling upstream rate limit returns 429 with Retry-After header")
        void searchCandidatesUpstreamRateLimit() throws Exception {
            UUID userId = UUID.randomUUID();
            UUID sessionId = UUID.randomUUID();
            mockAuthenticatedUser(userId);

            ExternalProviderException rateLimitEx = new ExternalProviderException(
                    "YouTube rate limit exceeded", "youtube", 429, "60"
            );
            when(reviewSessionService.searchCandidates(eq(sessionId), eq(0), any(ReviewCandidateSearchRequest.class), eq(userId)))
                    .thenThrow(rateLimitEx);

            mockMvc.perform(post("/api/review-sessions/" + sessionId + "/tracks/0/candidates/search")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}")
                            .with(csrf()))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().string("Retry-After", "60"))
                    .andExpect(jsonPath("$.code").value("EXTERNAL_PROVIDER_ERROR"));
        }
    }

    @Nested
    @DisplayName("Review Decisions Endpoints")
    class DecisionEndpointsTests {

        @Test
        @DisplayName("PUT /decision with ACCEPT accepts automatic match")
        void submitDecisionAcceptSuccess() throws Exception {
            UUID userId = UUID.randomUUID();
            UUID sessionId = UUID.randomUUID();
            mockAuthenticatedUser(userId);

            ReviewSession session = createSampleSession(sessionId, userId, "pl-1");
            ReviewTrack track = createSampleTrack(session, 0);

            when(reviewSessionService.acceptAutomaticMatch(sessionId, 0, userId)).thenReturn(track);

            SubmitReviewDecisionRequest req = SubmitReviewDecisionRequest.accept();

            mockMvc.perform(put("/api/review-sessions/" + sessionId + "/tracks/0/decision")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req))
                            .with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.reviewStatus").value("ACCEPTED"))
                    .andExpect(jsonPath("$.decisionSource").value("AUTOMATIC"));
        }

        @Test
        @DisplayName("POST /decision with SELECT_CANDIDATE selects manual candidate")
        void submitDecisionSelectSuccess() throws Exception {
            UUID userId = UUID.randomUUID();
            UUID sessionId = UUID.randomUUID();
            mockAuthenticatedUser(userId);

            ReviewSession session = createSampleSession(sessionId, userId, "pl-1");
            ReviewTrack track = createSampleTrack(session, 0);
            track.setReviewStatus(ReviewTrackStatus.USER_SELECTED);
            track.setDecisionSource(ReviewDecisionSource.USER_SELECTED);
            track.setSelectedCandidateId("yt-manual-99");
            track.setSelectedCandidateTitle("Manual Video");

            CandidateSelectionDto candDto = new CandidateSelectionDto("yt-manual-99", "Manual Video", "Artist", 210000L);
            SubmitReviewDecisionRequest req = SubmitReviewDecisionRequest.select(candDto, "User choice");

            when(reviewSessionService.selectCandidate(eq(sessionId), eq(0), any(DestinationTrackCandidate.class), eq("User choice"), eq(userId)))
                    .thenReturn(track);

            mockMvc.perform(post("/api/review-sessions/" + sessionId + "/tracks/0/decision")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req))
                            .with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.reviewStatus").value("USER_SELECTED"))
                    .andExpect(jsonPath("$.decisionSource").value("USER_SELECTED"))
                    .andExpect(jsonPath("$.selectedCandidate.id").value("yt-manual-99"));
        }

        @Test
        @DisplayName("PUT /decision with EXCLUDE excludes track")
        void submitDecisionExcludeSuccess() throws Exception {
            UUID userId = UUID.randomUUID();
            UUID sessionId = UUID.randomUUID();
            mockAuthenticatedUser(userId);

            ReviewSession session = createSampleSession(sessionId, userId, "pl-1");
            ReviewTrack track = createSampleTrack(session, 0);
            track.setReviewStatus(ReviewTrackStatus.EXCLUDED);
            track.setExcluded(true);
            track.setDecisionSource(ReviewDecisionSource.USER_SELECTED);
            track.setSelectedCandidateId(null);

            SubmitReviewDecisionRequest req = SubmitReviewDecisionRequest.exclude("Live version unwanted");

            when(reviewSessionService.excludeTrack(sessionId, 0, "Live version unwanted", userId)).thenReturn(track);

            mockMvc.perform(put("/api/review-sessions/" + sessionId + "/tracks/0/decision")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req))
                            .with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.reviewStatus").value("EXCLUDED"))
                    .andExpect(jsonPath("$.excluded").value(true))
                    .andExpect(jsonPath("$.approvedForMigration").value(false));
        }

        @Test
        @DisplayName("Dedicated POST /accept endpoint accepts match")
        void dedicatedAcceptEndpoint() throws Exception {
            UUID userId = UUID.randomUUID();
            UUID sessionId = UUID.randomUUID();
            mockAuthenticatedUser(userId);

            ReviewSession session = createSampleSession(sessionId, userId, "pl-1");
            ReviewTrack track = createSampleTrack(session, 0);

            when(reviewSessionService.acceptAutomaticMatch(sessionId, 0, userId)).thenReturn(track);

            mockMvc.perform(post("/api/review-sessions/" + sessionId + "/tracks/0/accept")
                            .with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.reviewStatus").value("ACCEPTED"));
        }

        @Test
        @DisplayName("Dedicated POST /select endpoint selects candidate")
        void dedicatedSelectEndpoint() throws Exception {
            UUID userId = UUID.randomUUID();
            UUID sessionId = UUID.randomUUID();
            mockAuthenticatedUser(userId);

            ReviewSession session = createSampleSession(sessionId, userId, "pl-1");
            ReviewTrack track = createSampleTrack(session, 0);
            track.setReviewStatus(ReviewTrackStatus.USER_SELECTED);
            track.setSelectedCandidateId("yt-dedicated-1");

            CandidateSelectionDto candDto = new CandidateSelectionDto("yt-dedicated-1", "Title", "Channel", 180000L);
            SelectCandidateRequest req = new SelectCandidateRequest(candDto, "Note");

            when(reviewSessionService.selectCandidate(eq(sessionId), eq(0), any(DestinationTrackCandidate.class), eq("Note"), eq(userId)))
                    .thenReturn(track);

            mockMvc.perform(post("/api/review-sessions/" + sessionId + "/tracks/0/select")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req))
                            .with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.reviewStatus").value("USER_SELECTED"))
                    .andExpect(jsonPath("$.selectedCandidate.id").value("yt-dedicated-1"));
        }

        @Test
        @DisplayName("Dedicated POST /exclude endpoint excludes track")
        void dedicatedExcludeEndpoint() throws Exception {
            UUID userId = UUID.randomUUID();
            UUID sessionId = UUID.randomUUID();
            mockAuthenticatedUser(userId);

            ReviewSession session = createSampleSession(sessionId, userId, "pl-1");
            ReviewTrack track = createSampleTrack(session, 0);
            track.setReviewStatus(ReviewTrackStatus.EXCLUDED);
            track.setExcluded(true);

            ExcludeTrackRequest req = new ExcludeTrackRequest("Not wanted");

            when(reviewSessionService.excludeTrack(sessionId, 0, "Not wanted", userId)).thenReturn(track);

            mockMvc.perform(post("/api/review-sessions/" + sessionId + "/tracks/0/exclude")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req))
                            .with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.reviewStatus").value("EXCLUDED"))
                    .andExpect(jsonPath("$.excluded").value(true));
        }

        @Test
        @DisplayName("Accept on track without automatic candidate returns 409 Conflict")
        void acceptWithoutAutomaticCandidateReturnsConflict() throws Exception {
            UUID userId = UUID.randomUUID();
            UUID sessionId = UUID.randomUUID();
            mockAuthenticatedUser(userId);

            when(reviewSessionService.acceptAutomaticMatch(sessionId, 0, userId))
                    .thenThrow(new InvalidStateException("Cannot accept automatic match for track at position 0 because no automatic candidate exists"));

            mockMvc.perform(post("/api/review-sessions/" + sessionId + "/tracks/0/accept")
                            .with(csrf()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INVALID_STATE"));
        }

        @Test
        @DisplayName("SELECT_CANDIDATE with blank candidate ID returns 400 Bad Request")
        void selectCandidateBlankId() throws Exception {
            UUID userId = UUID.randomUUID();
            UUID sessionId = UUID.randomUUID();
            mockAuthenticatedUser(userId);

            String payload = "{\"candidate\":{\"id\":\" \",\"title\":\"Valid Title\"}}";

            mockMvc.perform(post("/api/review-sessions/" + sessionId + "/tracks/0/select")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload)
                            .with(csrf()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
    }

    @Nested
    @DisplayName("Session Completion & Cancellation Endpoints")
    class LifecycleEndpointsTests {

        @Test
        @DisplayName("POST /complete completes session and returns 200 OK")
        void completeSessionSuccess() throws Exception {
            UUID userId = UUID.randomUUID();
            UUID sessionId = UUID.randomUUID();
            mockAuthenticatedUser(userId);

            ReviewSession session = createSampleSession(sessionId, userId, "pl-1");
            session.setStatus(ReviewSessionStatus.COMPLETED);
            session.setCompletedAt(Instant.now());

            when(reviewSessionService.completeSession(sessionId, userId)).thenReturn(session);

            mockMvc.perform(post("/api/review-sessions/" + sessionId + "/complete")
                            .with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("COMPLETED"));
        }

        @Test
        @DisplayName("POST /complete on session with unresolved decisions returns 409 Conflict")
        void completeSessionUnresolvedReturnsConflict() throws Exception {
            UUID userId = UUID.randomUUID();
            UUID sessionId = UUID.randomUUID();
            mockAuthenticatedUser(userId);

            when(reviewSessionService.completeSession(sessionId, userId))
                    .thenThrow(new InvalidStateException("Cannot complete review session: 2 required decisions remain unresolved"));

            mockMvc.perform(post("/api/review-sessions/" + sessionId + "/complete")
                            .with(csrf()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INVALID_STATE"));
        }

        @Test
        @DisplayName("POST /cancel cancels session and returns 200 OK")
        void cancelSessionSuccess() throws Exception {
            UUID userId = UUID.randomUUID();
            UUID sessionId = UUID.randomUUID();
            mockAuthenticatedUser(userId);

            ReviewSession session = createSampleSession(sessionId, userId, "pl-1");
            session.setStatus(ReviewSessionStatus.CANCELLED);

            when(reviewSessionService.cancelSession(sessionId, userId)).thenReturn(session);

            mockMvc.perform(post("/api/review-sessions/" + sessionId + "/cancel")
                            .with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CANCELLED"));
        }

        @Test
        @DisplayName("POST /cancel on already completed session returns 409 Conflict")
        void cancelSessionAlreadyCompleted() throws Exception {
            UUID userId = UUID.randomUUID();
            UUID sessionId = UUID.randomUUID();
            mockAuthenticatedUser(userId);

            when(reviewSessionService.cancelSession(sessionId, userId))
                    .thenThrow(new InvalidStateException("Cannot cancel review session in status: COMPLETED"));

            mockMvc.perform(post("/api/review-sessions/" + sessionId + "/cancel")
                            .with(csrf()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INVALID_STATE"));
        }
    }
}
