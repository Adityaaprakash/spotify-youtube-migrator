package com.spotifyyoutube.migrator.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spotifyyoutube.migrator.common.config.SecurityConfig;
import com.spotifyyoutube.migrator.common.exception.GlobalExceptionHandler;
import com.spotifyyoutube.migrator.identity.api.AuthController;
import com.spotifyyoutube.migrator.identity.api.OAuthController;
import com.spotifyyoutube.migrator.identity.api.UserController;
import com.spotifyyoutube.migrator.identity.api.dto.LoginRequest;
import com.spotifyyoutube.migrator.identity.application.AuthenticationService;
import com.spotifyyoutube.migrator.identity.application.OAuthAuthorizationService;
import com.spotifyyoutube.migrator.identity.application.OAuthStateService;
import com.spotifyyoutube.migrator.identity.application.OAuthTokenLifecycleService;
import com.spotifyyoutube.migrator.identity.application.UserService;
import com.spotifyyoutube.migrator.identity.domain.OAuthProvider;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.identity.repository.UserRepository;
import com.spotifyyoutube.migrator.migration.api.MigrationController;
import com.spotifyyoutube.migrator.migration.api.dto.RunMigrationApiRequest;
import com.spotifyyoutube.migrator.migration.application.MigrationService;
import com.spotifyyoutube.migrator.migration.async.MigrationJobDispatcher;
import com.spotifyyoutube.migrator.migration.progress.application.MigrationProgressService;
import com.spotifyyoutube.migrator.migration.progress.application.ProgressPollingRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {
        AuthController.class,
        OAuthController.class,
        UserController.class,
        MigrationController.class
})
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
public class CorsAndCsrfSecurityTest {

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
    private OAuthAuthorizationService authorizationService;

    @MockBean
    private OAuthStateService stateService;

    @MockBean
    private OAuthTokenLifecycleService tokenLifecycleService;

    @MockBean
    private MigrationService migrationService;

    @MockBean
    private MigrationJobDispatcher jobDispatcher;

    @MockBean
    private MigrationProgressService progressService;

    @MockBean
    private ProgressPollingRateLimiter rateLimiter;

    private User alice;
    private final UUID aliceId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        alice = new User("alice@example.com", "hash", "Alice");
        alice.setId(aliceId);
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(alice));
        when(userService.getUserByEmail("alice@example.com")).thenReturn(alice);
    }

    @Nested
    @DisplayName("1. CORS Whitelist & Preflight Verification")
    class CorsTests {

        @Test
        @DisplayName("Preflight OPTIONS request from whitelisted origin is accepted with allowed methods & headers")
        void corsPreflight_AllowedOrigin_Accepted() throws Exception {
            mockMvc.perform(options("/api/auth/me")
                    .header("Origin", "http://localhost:5173")
                    .header("Access-Control-Request-Method", "GET")
                    .header("Access-Control-Request-Headers", "Content-Type,X-XSRF-TOKEN"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                    .andExpect(header().string("Access-Control-Allow-Credentials", "true"))
                    .andExpect(header().string("Access-Control-Allow-Methods", containsString("GET")))
                    .andExpect(header().string("Access-Control-Allow-Methods", containsString("POST")));
        }

        @Test
        @DisplayName("Request from unauthorized origin is rejected by CORS (no Allow-Origin header)")
        void corsRequest_UnauthorizedOrigin_Rejected() throws Exception {
            mockMvc.perform(options("/api/auth/me")
                    .header("Origin", "http://evil-attacker.com")
                    .header("Access-Control-Request-Method", "GET"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(username = "alice@example.com")
        @DisplayName("Actual authenticated GET request from allowed origin returns CORS credentials header")
        void corsActualRequest_AllowedOrigin_ReturnsCredentials() throws Exception {
            mockMvc.perform(get("/api/auth/me")
                    .header("Origin", "http://localhost:5173"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                    .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
        }
    }

    @Nested
    @DisplayName("2. CSRF Token Protection on State-Changing Endpoints")
    class CsrfTests {

        @Test
        @WithMockUser(username = "alice@example.com")
        @DisplayName("Mutating POST request without CSRF token is rejected with 403 Forbidden")
        void mutatingPostWithoutCsrf_Rejected() throws Exception {
            RunMigrationApiRequest request = new RunMigrationApiRequest(UUID.randomUUID());

            mockMvc.perform(post("/api/migrations/submit")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(username = "alice@example.com")
        @DisplayName("Mutating DELETE request without CSRF token is rejected with 403 Forbidden")
        void mutatingDeleteWithoutCsrf_Rejected() throws Exception {
            mockMvc.perform(delete("/api/oauth/spotify"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(username = "alice@example.com")
        @DisplayName("Mutating DELETE request with valid CSRF token is processed successfully")
        void mutatingDeleteWithCsrf_Succeeds() throws Exception {
            mockMvc.perform(delete("/api/oauth/spotify")
                    .with(csrf()))
                    .andExpect(status().isNoContent());
        }

        @Test
        @WithMockUser(username = "alice@example.com")
        @DisplayName("Safe GET requests do not require CSRF token")
        void safeGetWithoutCsrf_Succeeds() throws Exception {
            mockMvc.perform(get("/api/auth/me"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Public authentication bootstrap endpoint POST /api/auth/login is exempt from CSRF")
        void publicAuthLogin_ExemptFromCsrf() throws Exception {
            when(authenticationService.login("alice@example.com", "password123")).thenReturn(alice);

            LoginRequest request = new LoginRequest("alice@example.com", "password123");
            mockMvc.perform(post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("3. HTTP Method Restrictions")
    class HttpMethodRestrictionTests {

        @Test
        @WithMockUser(username = "alice@example.com")
        @DisplayName("Unsupported HTTP method (PATCH /api/auth/login) returns HTTP 405 Method Not Allowed")
        void unsupportedMethodOnLogin_Returns405() throws Exception {
            mockMvc.perform(patch("/api/auth/login"))
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                    .andExpect(jsonPath("$.message").value(containsString("HTTP method PATCH is not supported")));
        }

        @Test
        @WithMockUser(username = "alice@example.com")
        @DisplayName("Unsupported HTTP method (DELETE /api/users/{id}) returns HTTP 405 Method Not Allowed")
        void unsupportedMethodOnUsers_Returns405() throws Exception {
            mockMvc.perform(delete("/api/users/" + aliceId)
                    .with(csrf()))
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
        }
    }
}
