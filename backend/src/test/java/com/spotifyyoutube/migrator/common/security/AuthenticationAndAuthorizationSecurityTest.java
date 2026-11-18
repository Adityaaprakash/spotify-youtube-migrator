package com.spotifyyoutube.migrator.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spotifyyoutube.migrator.common.config.SecurityConfig;
import com.spotifyyoutube.migrator.common.exception.GlobalExceptionHandler;
import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.identity.api.AuthController;
import com.spotifyyoutube.migrator.identity.api.UserController;
import com.spotifyyoutube.migrator.identity.api.dto.LoginRequest;
import com.spotifyyoutube.migrator.identity.api.dto.RegisterRequest;
import com.spotifyyoutube.migrator.identity.application.AuthenticationService;
import com.spotifyyoutube.migrator.identity.application.UserService;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.identity.repository.UserRepository;
import com.spotifyyoutube.migrator.migration.api.MigrationController;
import com.spotifyyoutube.migrator.migration.api.dto.RunMigrationApiRequest;
import com.spotifyyoutube.migrator.migration.application.MigrationService;
import com.spotifyyoutube.migrator.migration.async.MigrationJobDispatcher;
import com.spotifyyoutube.migrator.migration.domain.MigrationJob;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecution;
import com.spotifyyoutube.migrator.migration.execution.repository.ExecutionTrackRecordRepository;
import com.spotifyyoutube.migrator.migration.execution.repository.MigrationExecutionRepository;
import com.spotifyyoutube.migrator.migration.progress.application.MigrationProgressService;
import com.spotifyyoutube.migrator.migration.progress.application.ProgressPollingRateLimiter;
import com.spotifyyoutube.migrator.playlist.api.PlaylistController;
import com.spotifyyoutube.migrator.playlist.application.PlaylistService;
import com.spotifyyoutube.migrator.playlist.domain.Playlist;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {
        AuthController.class,
        UserController.class,
        PlaylistController.class,
        MigrationController.class
})
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
public class AuthenticationAndAuthorizationSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AuthenticationService authenticationService;

    @MockBean
    private UserRepository userRepository;

    @MockBean
    private UserService userService;

    @MockBean
    private PlaylistService playlistService;

    @MockBean
    private MigrationService migrationService;

    @MockBean
    private MigrationJobDispatcher jobDispatcher;

    @MockBean
    private MigrationProgressService progressService;

    @MockBean
    private ProgressPollingRateLimiter rateLimiter;

    @MockBean
    private MigrationExecutionRepository executionRepository;

    @MockBean
    private ExecutionTrackRecordRepository trackRecordRepository;

    private User alice;
    private User bob;
    private final UUID aliceId = UUID.randomUUID();
    private final UUID bobId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        alice = new User("alice@example.com", "hash1", "Alice");
        alice.setId(aliceId);

        bob = new User("bob@example.com", "hash2", "Bob");
        bob.setId(bobId);
    }

    @Nested
    @DisplayName("1. Authentication Enforcement")
    class AuthenticationEnforcementTests {

        @Test
        @WithAnonymousUser
        @DisplayName("Anonymous access to protected endpoint is rejected with 403 Forbidden")
        void anonymousAccessToProtectedEndpoint_Rejected() throws Exception {
            mockMvc.perform(get("/api/playlists")
                    .param("userId", aliceId.toString()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithAnonymousUser
        @DisplayName("Anonymous access to public login endpoint is permitted")
        void anonymousAccessToLogin_Permitted() throws Exception {
            when(authenticationService.login("alice@example.com", "password123")).thenReturn(alice);

            LoginRequest request = new LoginRequest("alice@example.com", "password123");
            mockMvc.perform(post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.email").value("alice@example.com"));
        }

        @Test
        @WithAnonymousUser
        @DisplayName("Anonymous access to public registration endpoint is permitted")
        void anonymousAccessToRegister_Permitted() throws Exception {
            when(authenticationService.register("new@example.com", "password123", "New User")).thenReturn(alice);

            RegisterRequest request = new RegisterRequest("new@example.com", "password123", "New User");
            mockMvc.perform(post("/api/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated());
        }

        @Test
        @WithAnonymousUser
        @DisplayName("Anonymous access to /api/auth/me returns 403 Forbidden per entry point convention")
        void anonymousAccessToAuthMe_ReturnsForbidden() throws Exception {
            mockMvc.perform(get("/api/auth/me"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(username = "alice@example.com")
        @DisplayName("Authenticated user accessing /api/auth/me returns user profile")
        void authenticatedAccessToAuthMe_ReturnsProfile() throws Exception {
            when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(alice));

            mockMvc.perform(get("/api/auth/me"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.email").value("alice@example.com"))
                    .andExpect(jsonPath("$.id").value(aliceId.toString()));
        }
    }

    @Nested
    @DisplayName("2. Session Security & Fixation Protection")
    class SessionSecurityTests {

        @Test
        @DisplayName("Login creates new session and invalidates pre-existing anonymous session")
        void login_InvalidatesPreExistingSession() throws Exception {
            MockHttpSession preExistingSession = new MockHttpSession();
            String oldSessionId = preExistingSession.getId();

            when(authenticationService.login("alice@example.com", "password123")).thenReturn(alice);

            LoginRequest request = new LoginRequest("alice@example.com", "password123");
            mockMvc.perform(post("/api/auth/login")
                    .session(preExistingSession)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());

            assertTrue(preExistingSession.isInvalid(), "Pre-existing session must be invalidated on login");
        }

        @Test
        @WithMockUser(username = "alice@example.com")
        @DisplayName("Logout invalidates session and sets cookie expiry header")
        void logout_InvalidatesSessionAndClearsCookie() throws Exception {
            MockHttpSession session = new MockHttpSession();

            mockMvc.perform(post("/api/auth/logout")
                    .session(session))
                    .andExpect(status().isNoContent())
                    .andExpect(header().string("Set-Cookie", containsString("JSESSIONID=")))
                    .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));

            assertTrue(session.isInvalid(), "Session must be invalidated upon logout");
        }
    }

    @Nested
    @DisplayName("3. IDOR Prevention & Non-Disclosure")
    class IdorPreventionTests {

        @Test
        @WithMockUser(username = "alice@example.com")
        @DisplayName("User A querying User B's profile returns 404 Not Found non-disclosure")
        void userCannotAccessForeignUserProfile() throws Exception {
            when(userService.getUserById(bobId)).thenReturn(bob);
            when(userService.getUserByEmail("alice@example.com")).thenReturn(alice);

            mockMvc.perform(get("/api/users/" + bobId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }

        @Test
        @WithMockUser(username = "alice@example.com")
        @DisplayName("User A querying own profile returns 200 OK")
        void userCanAccessOwnProfile() throws Exception {
            when(userService.getUserById(aliceId)).thenReturn(alice);
            when(userService.getUserByEmail("alice@example.com")).thenReturn(alice);

            mockMvc.perform(get("/api/users/" + aliceId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.email").value("alice@example.com"));
        }

        @Test
        @WithMockUser(username = "alice@example.com")
        @DisplayName("User A querying User B's playlist returns 404 Not Found non-disclosure")
        void userCannotAccessForeignPlaylist() throws Exception {
            UUID playlistId = UUID.randomUUID();
            Playlist bobPlaylist = new Playlist();
            bobPlaylist.setName("Bob's Playlist");
            bobPlaylist.setUser(bob);

            when(playlistService.getPlaylistById(playlistId)).thenReturn(bobPlaylist);

            mockMvc.perform(get("/api/playlists/" + playlistId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }

        @Test
        @WithMockUser(username = "alice@example.com")
        @DisplayName("User A querying User B's migration execution returns 404 Not Found non-disclosure")
        void userCannotAccessForeignMigrationExecution() throws Exception {
            UUID executionId = UUID.randomUUID();
            MigrationExecution bobExecution = org.mockito.Mockito.mock(MigrationExecution.class);
            when(bobExecution.getUser()).thenReturn(bob);
            when(bobExecution.getId()).thenReturn(executionId);

            when(userService.getUserByEmail("alice@example.com")).thenReturn(alice);
            when(executionRepository.findById(executionId)).thenReturn(Optional.of(bobExecution));

            mockMvc.perform(get("/api/migrations/" + executionId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }

        @Test
        @WithMockUser(username = "alice@example.com")
        @DisplayName("User A querying User B's migration tasks returns 404 Not Found non-disclosure")
        void userCannotAccessForeignMigrationTasks() throws Exception {
            UUID jobId = UUID.randomUUID();
            MigrationJob bobJob = new MigrationJob();
            bobJob.setUser(bob);

            when(userService.getUserByEmail("alice@example.com")).thenReturn(alice);
            when(migrationService.getMigrationJobById(jobId)).thenReturn(bobJob);

            mockMvc.perform(get("/api/migrations/" + jobId + "/tasks"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
    }

    @Nested
    @DisplayName("4. Anti-Impersonation & Privilege Escalation")
    class AntiImpersonationTests {

        @Test
        @WithMockUser(username = "alice@example.com")
        @DisplayName("User A submitting migration under User B's userId is rejected with 400 Bad Request")
        void submitMigration_RejectImpersonation() throws Exception {
            when(userService.getUserByEmail("alice@example.com")).thenReturn(alice);

            RunMigrationApiRequest request = new RunMigrationApiRequest(
                    UUID.randomUUID(),
                    bobId, // Attacker passes victim's user ID!
                    "source-pl-1",
                    "Title",
                    "Description",
                    true
            );

            mockMvc.perform(post("/api/migrations/submit")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.message").value("Authenticated user does not match the requested user ID"));
        }
    }
}
