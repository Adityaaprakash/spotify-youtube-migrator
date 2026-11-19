package com.spotifyyoutube.migrator.migration.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spotifyyoutube.migrator.common.exception.ExternalProviderException;
import com.spotifyyoutube.migrator.identity.api.dto.LoginRequest;
import com.spotifyyoutube.migrator.identity.api.dto.RegisterRequest;
import com.spotifyyoutube.migrator.identity.application.OAuthTokenService;
import com.spotifyyoutube.migrator.identity.domain.ConnectionStatus;
import com.spotifyyoutube.migrator.identity.domain.OAuthConnection;
import com.spotifyyoutube.migrator.identity.domain.OAuthProvider;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.identity.repository.OAuthConnectionRepository;
import com.spotifyyoutube.migrator.identity.repository.UserRepository;
import com.spotifyyoutube.migrator.migration.api.dto.MigrationPlanPreviewRequest;
import com.spotifyyoutube.migrator.migration.api.dto.RunMigrationApiRequest;
import com.spotifyyoutube.migrator.migration.application.destination.MusicDestination;
import com.spotifyyoutube.migrator.migration.application.destination.model.AddTrackToDestinationPlaylistRequest;
import com.spotifyyoutube.migrator.migration.application.destination.model.CreateDestinationPlaylistRequest;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationPlaylist;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationPlaylistItem;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationSearchResult;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationTrackCandidate;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackRecord;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecution;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus;
import com.spotifyyoutube.migrator.migration.execution.repository.ExecutionTrackRecordRepository;
import com.spotifyyoutube.migrator.migration.execution.repository.MigrationExecutionRepository;
import com.spotifyyoutube.migrator.migration.review.api.dto.CandidateSelectionDto;
import com.spotifyyoutube.migrator.migration.review.api.dto.CreateReviewSessionRequest;
import com.spotifyyoutube.migrator.migration.review.api.dto.ExcludeTrackRequest;
import com.spotifyyoutube.migrator.migration.review.api.dto.SelectCandidateRequest;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSession;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSessionStatus;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewTrack;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewTrackStatus;
import com.spotifyyoutube.migrator.migration.review.repository.ReviewSessionRepository;
import com.spotifyyoutube.migrator.migration.review.repository.ReviewTrackRepository;
import com.spotifyyoutube.migrator.playlist.domain.Platform;
import com.spotifyyoutube.migrator.spotify.infrastructure.SpotifyApiClient;
import com.spotifyyoutube.migrator.spotify.infrastructure.dto.SpotifyArtistDto;
import com.spotifyyoutube.migrator.spotify.infrastructure.dto.SpotifyExternalUrlsDto;
import com.spotifyyoutube.migrator.spotify.infrastructure.dto.SpotifyOwnerDto;
import com.spotifyyoutube.migrator.spotify.infrastructure.dto.SpotifyPagingDto;
import com.spotifyyoutube.migrator.spotify.infrastructure.dto.SpotifyPlaylistSummaryDto;
import com.spotifyyoutube.migrator.spotify.infrastructure.dto.SpotifyPlaylistTrackDto;
import com.spotifyyoutube.migrator.spotify.infrastructure.dto.SpotifyTrackDto;
import com.spotifyyoutube.migrator.spotify.infrastructure.dto.SpotifyTracksDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
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
 * End-to-end integration tests for the full Spotify-to-YouTube Music migration workflow (Phase 17E).
 * <p>
 * Validates the complete user journey across real application components:
 * Registration -&gt; Authentication -&gt; Spotify Retrieval -&gt; Migration Preview/Plan
 * -&gt; Review Session -&gt; Review Decisions &amp; Approval -&gt; YouTube Playlist Creation
 * -&gt; Ordered Track Insertion -&gt; Migration Completion -&gt; Persisted Result Verification.
 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@DisplayName("Phase 17E: End-to-End Migration Workflow Integration Tests")
public class FullMigrationWorkflowE2EIT {

    private static final String SOURCE_PLAYLIST_ID = "sp_pl_rock_legends";
    private static final String USER_EMAIL = "full-e2e-user@example.com";
    private static final String USER_PASSWORD = "Password123!";
    private static final String USER_DISPLAY_NAME = "Full E2E User";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OAuthConnectionRepository oAuthConnectionRepository;

    @Autowired
    private OAuthTokenService oAuthTokenService;

    @Autowired
    private ReviewSessionRepository reviewSessionRepository;

    @Autowired
    private ReviewTrackRepository reviewTrackRepository;

    @Autowired
    private MigrationExecutionRepository executionRepository;

    @Autowired
    private ExecutionTrackRecordRepository trackRecordRepository;

    @MockBean
    private SpotifyApiClient spotifyApiClient;

    @MockBean
    private MusicDestination musicDestination;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        setupDestinationMock();
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    private void cleanDatabase() {
        trackRecordRepository.deleteAllInBatch();
        executionRepository.deleteAllInBatch();
        reviewTrackRepository.deleteAllInBatch();
        reviewSessionRepository.deleteAllInBatch();
        oAuthConnectionRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    private void setupDestinationMock() {
        when(musicDestination.getPlatform()).thenReturn(Platform.YOUTUBE);

        // Deterministic candidate search simulation for YouTube destination
        when(musicDestination.searchTracks(anyString(), any(), any())).thenAnswer(invocation -> {
            String query = invocation.getArgument(0, String.class).toLowerCase();
            if (query.contains("bohemian") || query.contains("rhapsody")) {
                return new DestinationSearchResult(List.of(
                        new DestinationTrackCandidate(
                                "yt_queen_1",
                                "Queen - Bohemian Rhapsody (Official Video)",
                                "Queen",
                                "Official Video",
                                "https://img.youtube.com/vi/yt_queen_1/0.jpg",
                                "1975-10-31T00:00:00Z",
                                Duration.ofMillis(354000)
                        )
                ), null, 1);
            } else if (query.contains("california") || query.contains("hotel")) {
                return new DestinationSearchResult(List.of(
                        new DestinationTrackCandidate(
                                "yt_eagles_1",
                                "Eagles - Hotel California (Official Audio)",
                                "Eagles",
                                "Official Audio",
                                "https://img.youtube.com/vi/yt_eagles_1/0.jpg",
                                "1976-12-08T00:00:00Z",
                                Duration.ofMillis(391000)
                        )
                ), null, 1);
            } else if (query.contains("imagine") || query.contains("lennon")) {
                return new DestinationSearchResult(List.of(
                        new DestinationTrackCandidate(
                                "yt_lennon_1",
                                "John Lennon - Imagine",
                                "John Lennon",
                                "Music Video",
                                "https://img.youtube.com/vi/yt_lennon_1/0.jpg",
                                "1971-10-11T00:00:00Z",
                                Duration.ofMillis(187000)
                        )
                ), null, 1);
            } else if (query.contains("sweet child") || query.contains("guns")) {
                // Ambiguous match with multiple candidates triggering review required
                return new DestinationSearchResult(List.of(
                        new DestinationTrackCandidate(
                                "yt_gnr_acoustic",
                                "Guns N' Roses - Sweet Child O' Mine (Acoustic Version)",
                                "Guns N' Roses",
                                "Acoustic",
                                "https://img.youtube.com/vi/yt_gnr_acoustic/0.jpg",
                                "1992-02-22T00:00:00Z",
                                Duration.ofMillis(356000)
                        ),
                        new DestinationTrackCandidate(
                                "yt_gnr_studio",
                                "Guns N' Roses - Sweet Child O' Mine (Official Music Video)",
                                "Guns N' Roses",
                                "Studio Video",
                                "https://img.youtube.com/vi/yt_gnr_studio/0.jpg",
                                "1987-07-21T00:00:00Z",
                                Duration.ofMillis(356000)
                        )
                ), null, 2);
            } else if (query.contains("billie jean") || query.contains("jackson")) {
                return new DestinationSearchResult(List.of(
                        new DestinationTrackCandidate(
                                "yt_mj_1",
                                "Michael Jackson - Billie Jean (Official Video)",
                                "Michael Jackson",
                                "Official Video",
                                "https://img.youtube.com/vi/yt_mj_1/0.jpg",
                                "1983-01-02T00:00:00Z",
                                Duration.ofMillis(294000)
                        )
                ), null, 1);
            } else {
                // Unmatched track (e.g., Obscure Indie Track)
                return new DestinationSearchResult(List.of(), null, 0);
            }
        });
    }

    private void setupSpotifyMockFixtures() {
        SpotifyOwnerDto owner = new SpotifyOwnerDto("spotify-user-e2e", "Spotify E2E User");
        SpotifyPlaylistSummaryDto summary = new SpotifyPlaylistSummaryDto(
                SOURCE_PLAYLIST_ID,
                "Rock Legends",
                "Classic Rock Playlist for Migration",
                owner,
                List.of(),
                new SpotifyTracksDto(null, 6),
                new SpotifyExternalUrlsDto("https://open.spotify.com/playlist/" + SOURCE_PLAYLIST_ID)
        );

        when(spotifyApiClient.getPlaylistMetadata(anyString(), eq(SOURCE_PLAYLIST_ID)))
                .thenReturn(summary);

        // 6 Source tracks: 4 clear matches, 1 ambiguous candidate, 1 unmatched
        List<SpotifyPlaylistTrackDto> items = List.of(
                createSpotifyTrackItem("sp_track_0", "Bohemian Rhapsody", "Queen", 354000),
                createSpotifyTrackItem("sp_track_1", "Hotel California", "Eagles", 391000),
                createSpotifyTrackItem("sp_track_2", "Imagine", "John Lennon", 187000),
                createSpotifyTrackItem("sp_track_3", "Sweet Child O' Mine", "Guns N' Roses", 356000),
                createSpotifyTrackItem("sp_track_4", "Obscure Indie Song XYZ", "Unknown Indie Artist", 210000),
                createSpotifyTrackItem("sp_track_5", "Billie Jean", "Michael Jackson", 294000)
        );

        SpotifyPagingDto<SpotifyPlaylistTrackDto> paging = new SpotifyPagingDto<>(
                null, items, 50, 0, null, null, items.size()
        );

        when(spotifyApiClient.getPlaylistTracks(anyString(), eq(SOURCE_PLAYLIST_ID), anyInt(), anyInt()))
                .thenReturn(paging);
    }

    private SpotifyPlaylistTrackDto createSpotifyTrackItem(
            String id, String name, String artist, int durationMs
    ) {
        SpotifyArtistDto artistDto = new SpotifyArtistDto("art_" + id, artist);
        SpotifyTrackDto trackDto = new SpotifyTrackDto(
                id, name, durationMs, "spotify:track:" + id, false, List.of(artistDto), null
        );
        return new SpotifyPlaylistTrackDto(false, trackDto);
    }

    private void seedSpotifyOAuthConnection(UUID userId) {
        OAuthConnection connection = new OAuthConnection();
        connection.setUserId(userId);
        connection.setProvider(OAuthProvider.SPOTIFY);
        connection.setProviderUserId("spotify-user-" + userId);
        connection.setAccessToken(oAuthTokenService.encryptToken("valid-spotify-access-token"));
        connection.setRefreshToken(oAuthTokenService.encryptToken("valid-spotify-refresh-token"));
        connection.setExpiresAt(OffsetDateTime.now().plusHours(2));
        connection.setStatus(ConnectionStatus.CONNECTED);
        oAuthConnectionRepository.save(connection);
    }

    @Test
    @DisplayName("Primary Successful E2E: Full 9-Stage Migration Workflow from Registration to Completion")
    void fullMigrationWorkflow_happyPath_shouldSucceedThroughAllNineStages() throws Exception {
        // =========================================================================
        // Stage 1: User Registration
        // =========================================================================
        RegisterRequest registerRequest = new RegisterRequest(USER_EMAIL, USER_PASSWORD, USER_DISPLAY_NAME);

        MvcResult registerResult = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.email").value(USER_EMAIL))
                .andExpect(jsonPath("$.displayName").value(USER_DISPLAY_NAME))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn();

        JsonNode userJson = objectMapper.readTree(registerResult.getResponse().getContentAsString());
        UUID userId = UUID.fromString(userJson.get("id").asText());

        // Assert database persistence and password encryption
        User persistedUser = userRepository.findById(userId).orElseThrow();
        assertThat(persistedUser.getEmail()).isEqualTo(USER_EMAIL);
        assertThat(persistedUser.getPasswordHash()).isNotEqualTo(USER_PASSWORD);
        assertThat(persistedUser.getPasswordHash()).startsWith("$2a$");

        // =========================================================================
        // Stage 2: Authentication
        // =========================================================================
        LoginRequest loginRequest = new LoginRequest(USER_EMAIL, USER_PASSWORD);

        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId.toString()))
                .andExpect(jsonPath("$.email").value(USER_EMAIL))
                .andReturn();

        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession();
        assertThat(session).isNotNull();

        // Verify authenticated identity resolution on /api/auth/me
        mockMvc.perform(get("/api/auth/me")
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId.toString()))
                .andExpect(jsonPath("$.email").value(USER_EMAIL));

        // =========================================================================
        // Stage 3: Source Playlist Retrieval
        // =========================================================================
        seedSpotifyOAuthConnection(userId);
        setupSpotifyMockFixtures();

        // Verify playlist metadata retrieval via controller
        mockMvc.perform(get("/api/spotify/playlists/" + SOURCE_PLAYLIST_ID)
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Rock Legends"))
                .andExpect(jsonPath("$.externalId").value(SOURCE_PLAYLIST_ID));

        // Verify playlist tracks retrieval via controller
        mockMvc.perform(get("/api/spotify/playlists/" + SOURCE_PLAYLIST_ID + "/tracks")
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$[0].name").value("Bohemian Rhapsody"))
                .andExpect(jsonPath("$[0].artist").value("Queen"))
                .andExpect(jsonPath("$[5].name").value("Billie Jean"));

        // =========================================================================
        // Stage 4: Migration Preview and Plan
        // =========================================================================
        MigrationPlanPreviewRequest previewRequest = new MigrationPlanPreviewRequest(SOURCE_PLAYLIST_ID, userId, true);

        mockMvc.perform(post("/api/migrations/preview")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(previewRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourcePlaylistTitle").value("Rock Legends"))
                .andExpect(jsonPath("$.summary.totalTracks").value(6))
                .andExpect(jsonPath("$.tracks.length()").value(6));

        // =========================================================================
        // Stage 5: Review Session Creation
        // =========================================================================
        CreateReviewSessionRequest createSessionRequest = new CreateReviewSessionRequest(
                SOURCE_PLAYLIST_ID, userId, true
        );

        MvcResult sessionResult = mockMvc.perform(post("/api/review-sessions")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createSessionRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.sourcePlaylistTitle").value("Rock Legends"))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.totalTracks").value(6))
                .andReturn();

        JsonNode sessionJson = objectMapper.readTree(sessionResult.getResponse().getContentAsString());
        UUID reviewSessionId = UUID.fromString(sessionJson.get("id").asText());

        // Verify review tracks via controller
        mockMvc.perform(get("/api/review-sessions/" + reviewSessionId + "/tracks")
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$[0].sourceTitle").value("Bohemian Rhapsody"))
                .andExpect(jsonPath("$[3].sourceTitle").value("Sweet Child O' Mine"))
                .andExpect(jsonPath("$[4].sourceTitle").value("Obscure Indie Song XYZ"));

        // =========================================================================
        // Stage 6: Human Review Decisions & Approval
        // =========================================================================
        // Track 3 (Sweet Child O' Mine): Manual selection of the studio candidate
        CandidateSelectionDto chosenCandidate = new CandidateSelectionDto(
                "yt_gnr_studio",
                "Guns N' Roses - Sweet Child O' Mine (Official Music Video)",
                "Guns N' Roses",
                356000L
        );
        SelectCandidateRequest selectRequest = new SelectCandidateRequest(chosenCandidate, "Selected studio version");

        mockMvc.perform(post("/api/review-sessions/" + reviewSessionId + "/tracks/3/select")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(selectRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.position").value(3))
                .andExpect(jsonPath("$.reviewStatus").value("USER_SELECTED"))
                .andExpect(jsonPath("$.selectedCandidate.id").value("yt_gnr_studio"));

        // Track 4 (Obscure Indie Song XYZ): Exclusion from migration
        ExcludeTrackRequest excludeRequest = new ExcludeTrackRequest("Track unavailable on destination platform");

        mockMvc.perform(post("/api/review-sessions/" + reviewSessionId + "/tracks/4/exclude")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(excludeRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.position").value(4))
                .andExpect(jsonPath("$.excluded").value(true));

        // Complete review session
        mockMvc.perform(post("/api/review-sessions/" + reviewSessionId + "/complete")
                        .session(session)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(reviewSessionId.toString()))
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        // Assert review session state in database
        ReviewSession completedReview = reviewSessionRepository.findById(reviewSessionId).orElseThrow();
        assertThat(completedReview.getStatus()).isEqualTo(ReviewSessionStatus.COMPLETED);
        List<ReviewTrack> reviewTracks = reviewTrackRepository.findBySessionIdOrderByPositionAsc(reviewSessionId);
        assertThat(reviewTracks).hasSize(6);
        long approvedCount = reviewTracks.stream().filter(ReviewTrack::isApprovedForMigration).count();
        assertThat(approvedCount).isEqualTo(5); // 4 auto + 1 selected; 1 excluded

        // =========================================================================
        // Stage 7: Destination Playlist Creation via Migration Submission
        // =========================================================================
        String destinationPlaylistId = "yt_dest_rock_legends_101";
        when(musicDestination.createPlaylist(eq(userId), any(CreateDestinationPlaylistRequest.class)))
                .thenReturn(new DestinationPlaylist(
                        destinationPlaylistId,
                        "Rock Legends",
                        "Migrated from Spotify",
                        "private",
                        "https://music.youtube.com/playlist?list=" + destinationPlaylistId,
                        Platform.YOUTUBE
                ));

        when(musicDestination.addTrackToPlaylist(eq(userId), eq(destinationPlaylistId), any(AddTrackToDestinationPlaylistRequest.class)))
                .thenAnswer(invocation -> {
                    AddTrackToDestinationPlaylistRequest req = invocation.getArgument(2);
                    return new DestinationPlaylistItem(
                            "item_" + UUID.randomUUID(),
                            destinationPlaylistId,
                            req.trackId(),
                            req.position() != null ? req.position() : 0
                    );
                });

        RunMigrationApiRequest migrationRequest = new RunMigrationApiRequest(
                reviewSessionId, null, null, null, null, null
        );

        MvcResult submitResult = mockMvc.perform(post("/api/migrations")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(migrationRequest)))
                .andExpect(status().isAccepted())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.executionId").exists())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.totalTracks").value(5))
                .andReturn();

        JsonNode migrationJson = objectMapper.readTree(submitResult.getResponse().getContentAsString());
        UUID executionId = UUID.fromString(migrationJson.get("executionId").asText());

        // Verify destination playlist creation was invoked on external service double
        verify(musicDestination, times(1)).createPlaylist(eq(userId), any());

        // =========================================================================
        // Stage 8: Ordered Track Insertion & Asynchronous Execution
        // =========================================================================
        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(50))
                .untilAsserted(() -> {
                    MigrationExecution exec = executionRepository.findById(executionId).orElse(null);
                    assertThat(exec).isNotNull();
                    assertThat(exec.getStatus()).isEqualTo(MigrationExecutionStatus.COMPLETED);
                });

        // Verify ordered track insertions in database
        List<ExecutionTrackRecord> persistedRecords =
                trackRecordRepository.findByExecutionIdOrderByTargetPositionAsc(executionId);

        assertThat(persistedRecords).hasSize(5);
        assertThat(persistedRecords.get(0).getSourceTitle()).isEqualTo("Bohemian Rhapsody");
        assertThat(persistedRecords.get(0).getDestinationTrackId()).isEqualTo("yt_queen_1");
        assertThat(persistedRecords.get(1).getSourceTitle()).isEqualTo("Hotel California");
        assertThat(persistedRecords.get(1).getDestinationTrackId()).isEqualTo("yt_eagles_1");
        assertThat(persistedRecords.get(2).getSourceTitle()).isEqualTo("Imagine");
        assertThat(persistedRecords.get(2).getDestinationTrackId()).isEqualTo("yt_lennon_1");
        assertThat(persistedRecords.get(3).getSourceTitle()).isEqualTo("Sweet Child O' Mine");
        assertThat(persistedRecords.get(3).getDestinationTrackId()).isEqualTo("yt_gnr_studio");
        assertThat(persistedRecords.get(4).getSourceTitle()).isEqualTo("Billie Jean");
        assertThat(persistedRecords.get(4).getDestinationTrackId()).isEqualTo("yt_mj_1");

        // Verify external musicDestination added tracks
        verify(musicDestination, times(5)).addTrackToPlaylist(eq(userId), eq(destinationPlaylistId), any());

        // =========================================================================
        // Stage 9: Migration Completion & Persisted Result Verification
        // =========================================================================
        mockMvc.perform(get("/api/migrations/" + executionId + "/progress")
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionId").value(executionId.toString()))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.totalTracks").value(5))
                .andExpect(jsonPath("$.processedTracks").value(5))
                .andExpect(jsonPath("$.remainingTracks").value(0))
                .andExpect(jsonPath("$.progressPercentage").value(100.0));

        mockMvc.perform(get("/api/migrations/" + executionId)
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionId").value(executionId.toString()))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.destinationPlaylistId").value(destinationPlaylistId));

        MigrationExecution finalExecution = executionRepository.findById(executionId).orElseThrow();
        assertThat(finalExecution.getStatus()).isEqualTo(MigrationExecutionStatus.COMPLETED);
        assertThat(finalExecution.getDestinationPlaylistId()).isEqualTo(destinationPlaylistId);
        assertThat(finalExecution.getSuccessfulInsertions()).isEqualTo(5);
        assertThat(finalExecution.getFailedInsertions()).isEqualTo(0);
        assertThat(finalExecution.getSkippedInsertions()).isEqualTo(0);
        assertThat(finalExecution.getUser().getId()).isEqualTo(userId);

        // =========================================================================
        // Idempotency: Resubmission returns 200 OK without re-running playlist creation
        // =========================================================================
        mockMvc.perform(post("/api/migrations")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(migrationRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.executionId").value(executionId.toString()));

        // Playlist was NOT created a second time
        verify(musicDestination, times(1)).createPlaylist(eq(userId), any());
    }

    @Test
    @DisplayName("Failure Scenario 1: Multi-Tenant Anti-IDOR: Unauthorized user cannot access or submit another user's review session")
    void antiIdor_unauthorizedUser_cannotAccessOrSubmitAnotherUsersMigration() throws Exception {
        // Register Alice and Bob
        RegisterRequest registerAlice = new RegisterRequest("alice-e2e@example.com", "Password123!", "Alice");
        MvcResult resAlice = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerAlice)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID aliceId = UUID.fromString(objectMapper.readTree(resAlice.getResponse().getContentAsString()).get("id").asText());
        MockHttpSession sessionAlice = (MockHttpSession) resAlice.getRequest().getSession();

        RegisterRequest registerBob = new RegisterRequest("bob-e2e@example.com", "Password123!", "Bob");
        MvcResult resBob = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerBob)))
                .andExpect(status().isCreated())
                .andReturn();
        MockHttpSession sessionBob = (MockHttpSession) resBob.getRequest().getSession();

        seedSpotifyOAuthConnection(aliceId);
        setupSpotifyMockFixtures();

        // Alice creates and completes a review session
        CreateReviewSessionRequest createRequest = new CreateReviewSessionRequest(SOURCE_PLAYLIST_ID, aliceId, true);
        MvcResult aliceSessionResult = mockMvc.perform(post("/api/review-sessions")
                        .session(sessionAlice)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        UUID aliceReviewId = UUID.fromString(
                objectMapper.readTree(aliceSessionResult.getResponse().getContentAsString()).get("id").asText()
        );

        // Bob attempts to read Alice's review session -> 404 Not Found (Anti-IDOR)
        mockMvc.perform(get("/api/review-sessions/" + aliceReviewId)
                        .session(sessionBob))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        // Bob attempts to submit Alice's review session for migration -> 404 Not Found (Anti-IDOR)
        RunMigrationApiRequest bobSubmitRequest = new RunMigrationApiRequest(aliceReviewId, null, null, null, null, null);
        mockMvc.perform(post("/api/migrations")
                        .session(sessionBob)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bobSubmitRequest)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("Failure Scenario 2: Unfinalized review session cannot be submitted for migration")
    void submissionRejection_unapprovedReviewSession_cannotBeSubmitted() throws Exception {
        RegisterRequest registerRequest = new RegisterRequest("charlie-e2e@example.com", "Password123!", "Charlie");
        MvcResult regRes = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID charlieId = UUID.fromString(objectMapper.readTree(regRes.getResponse().getContentAsString()).get("id").asText());
        MockHttpSession sessionCharlie = (MockHttpSession) regRes.getRequest().getSession();

        seedSpotifyOAuthConnection(charlieId);
        setupSpotifyMockFixtures();

        // Charlie creates a review session with unresolved items (in status IN_PROGRESS)
        CreateReviewSessionRequest createRequest = new CreateReviewSessionRequest(SOURCE_PLAYLIST_ID, charlieId, true);
        MvcResult sessionRes = mockMvc.perform(post("/api/review-sessions")
                        .session(sessionCharlie)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        UUID reviewSessionId = UUID.fromString(
                objectMapper.readTree(sessionRes.getResponse().getContentAsString()).get("id").asText()
        );

        // Submitting directly without resolving decisions or completing session should be rejected with 409 Conflict (INVALID_STATE)
        RunMigrationApiRequest invalidSubmit = new RunMigrationApiRequest(reviewSessionId, null, null, null, null, null);
        mockMvc.perform(post("/api/migrations")
                        .session(sessionCharlie)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidSubmit)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
    }

    @Test
    @DisplayName("Failure Scenario 3: Destination playlist creation failure is handled safely without orphan executions")
    void destinationPlaylistCreationFailure_handledSafely() throws Exception {
        RegisterRequest registerRequest = new RegisterRequest("dave-e2e@example.com", "Password123!", "Dave");
        MvcResult regRes = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID daveId = UUID.fromString(objectMapper.readTree(regRes.getResponse().getContentAsString()).get("id").asText());
        MockHttpSession sessionDave = (MockHttpSession) regRes.getRequest().getSession();

        seedSpotifyOAuthConnection(daveId);
        setupSpotifyMockFixtures();

        // Dave creates and completes review session
        CreateReviewSessionRequest createRequest = new CreateReviewSessionRequest(SOURCE_PLAYLIST_ID, daveId, true);
        MvcResult sessionRes = mockMvc.perform(post("/api/review-sessions")
                        .session(sessionDave)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        UUID reviewSessionId = UUID.fromString(
                objectMapper.readTree(sessionRes.getResponse().getContentAsString()).get("id").asText()
        );

        // Resolve tracks 3 and 4 to allow completion
        mockMvc.perform(post("/api/review-sessions/" + reviewSessionId + "/tracks/3/select")
                .session(sessionDave)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new SelectCandidateRequest(
                        new CandidateSelectionDto("yt_gnr_studio", "Guns N' Roses - Sweet Child O' Mine", "GNR", 356000L),
                        "Select studio version"
                )))).andExpect(status().isOk());

        mockMvc.perform(post("/api/review-sessions/" + reviewSessionId + "/tracks/4/exclude")
                .session(sessionDave)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new ExcludeTrackRequest("Unavailable"))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/review-sessions/" + reviewSessionId + "/complete")
                .session(sessionDave)
                .with(csrf()))
                .andExpect(status().isOk());

        // Simulate destination provider playlist creation failure
        when(musicDestination.createPlaylist(eq(daveId), any(CreateDestinationPlaylistRequest.class)))
                .thenThrow(new ExternalProviderException("YouTube quota exceeded", 403, null));

        RunMigrationApiRequest submitRequest = new RunMigrationApiRequest(reviewSessionId, null, null, null, null, null);
        mockMvc.perform(post("/api/migrations")
                        .session(sessionDave)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(submitRequest)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EXTERNAL_PROVIDER_ERROR"));
    }
}
