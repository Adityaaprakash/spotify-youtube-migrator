package com.spotifyyoutube.migrator.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spotifyyoutube.migrator.common.config.SecurityConfig;
import com.spotifyyoutube.migrator.common.exception.GlobalExceptionHandler;
import com.spotifyyoutube.migrator.common.exception.RateLimitExceededException;
import com.spotifyyoutube.migrator.common.security.ratelimit.ApiRateLimiter;
import com.spotifyyoutube.migrator.common.security.ratelimit.ApiRateLimitingFilter;
import com.spotifyyoutube.migrator.common.security.ratelimit.InMemoryApiRateLimiter;
import com.spotifyyoutube.migrator.common.security.ratelimit.RateLimitProperties;
import com.spotifyyoutube.migrator.common.security.ratelimit.RateLimitingConfig;
import com.spotifyyoutube.migrator.identity.api.AuthController;
import com.spotifyyoutube.migrator.identity.api.OAuthController;
import com.spotifyyoutube.migrator.identity.api.dto.LoginRequest;
import com.spotifyyoutube.migrator.identity.application.AuthenticationService;
import com.spotifyyoutube.migrator.identity.application.OAuthAuthorizationService;
import com.spotifyyoutube.migrator.identity.application.OAuthStateService;
import com.spotifyyoutube.migrator.identity.application.OAuthTokenLifecycleService;
import com.spotifyyoutube.migrator.identity.domain.OAuthProvider;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.identity.repository.UserRepository;
import com.spotifyyoutube.migrator.identity.application.UserService;
import com.spotifyyoutube.migrator.migration.api.MigrationController;
import com.spotifyyoutube.migrator.migration.api.dto.RunMigrationApiRequest;
import com.spotifyyoutube.migrator.migration.application.MigrationService;
import com.spotifyyoutube.migrator.migration.application.execution.contract.MigrationExecutionSummary;
import com.spotifyyoutube.migrator.migration.async.MigrationJobDispatcher;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus;
import com.spotifyyoutube.migrator.migration.progress.application.MigrationProgressService;
import com.spotifyyoutube.migrator.migration.progress.application.ProgressPollingRateLimiter;
import com.spotifyyoutube.migrator.migration.progress.domain.MigrationProgressSnapshot;
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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

public class ApiRateLimitingSecurityTest {

    @Nested
    @DisplayName("1. InMemoryApiRateLimiter Engine Tests")
    class EngineTests {

        private RateLimitProperties properties;
        private TestClock testClock;
        private InMemoryApiRateLimiter rateLimiter;

        @BeforeEach
        void setUp() {
            properties = new RateLimitProperties();
            properties.setEnabled(true);
            properties.setMaxTrackedKeys(5); // Small for bounded capacity testing

            testClock = new TestClock(Instant.parse("2026-11-18T10:00:00Z"));
            rateLimiter = new InMemoryApiRateLimiter(properties, testClock);
        }

        @Test
        @DisplayName("Requests within quota succeed; exceeding quota throws RateLimitExceededException")
        void withinQuotaSucceeds_ExceedingThrows() {
            RateLimitProperties.PolicyProperties policy = new RateLimitProperties.PolicyProperties(3, 60, 30);

            rateLimiter.checkLimit("IP:127.0.0.1", policy, "login");
            rateLimiter.checkLimit("IP:127.0.0.1", policy, "login");
            rateLimiter.checkLimit("IP:127.0.0.1", policy, "login");

            RateLimitExceededException ex = assertThrows(RateLimitExceededException.class, () ->
                    rateLimiter.checkLimit("IP:127.0.0.1", policy, "login"));

            assertEquals(30, ex.getRetryAfterSeconds());
            assertTrue(ex.getMessage().contains("Rate limit exceeded for login"));
        }

        @Test
        @DisplayName("Sliding window: requests older than window are evicted and restore quota")
        void slidingWindow_EvictsOlderTimestamps() {
            RateLimitProperties.PolicyProperties policy = new RateLimitProperties.PolicyProperties(2, 60, 30);

            rateLimiter.checkLimit("USER:alice", policy, "search");
            rateLimiter.checkLimit("USER:alice", policy, "search");

            // Exceeded at T=0
            assertThrows(RateLimitExceededException.class, () ->
                    rateLimiter.checkLimit("USER:alice", policy, "search"));

            // Advance time past the 60s sliding window
            testClock.advanceSeconds(61);

            // Quota is restored
            rateLimiter.checkLimit("USER:alice", policy, "search");
        }

        @Test
        @DisplayName("Cleanup removes expired entries and frees memory")
        void cleanup_RemovesExpiredKeys() {
            RateLimitProperties.PolicyProperties policy = new RateLimitProperties.PolicyProperties(2, 60, 30);

            rateLimiter.checkLimit("KEY1", policy, "op");
            rateLimiter.checkLimit("KEY2", policy, "op");
            assertEquals(2, rateLimiter.getTrackedKeyCount());

            // Advance time past 60s
            testClock.advanceSeconds(61);

            int evicted = rateLimiter.cleanupExpiredEntries();
            assertEquals(2, evicted);
            assertEquals(0, rateLimiter.getTrackedKeyCount());
        }

        @Test
        @DisplayName("Bounded memory: capacity saturation evicts oldest entries to prevent DoS")
        void boundedMemory_PreventsUnboundedGrowth() {
            RateLimitProperties.PolicyProperties policy = new RateLimitProperties.PolicyProperties(5, 60, 30);

            for (int i = 0; i < 10; i++) {
                rateLimiter.checkLimit("KEY_" + i, policy, "op");
            }

            // Total tracked keys must not exceed bounded capacity (5)
            assertTrue(rateLimiter.getTrackedKeyCount() <= properties.getMaxTrackedKeys());
        }
    }

    @Nested
    @DisplayName("2. Web API Rate Limiting Filter Tests")
    @WebMvcTest(controllers = {AuthController.class, OAuthController.class, MigrationController.class})
    @Import({SecurityConfig.class, RateLimitingConfig.class, InMemoryApiRateLimiter.class, RateLimitProperties.class, GlobalExceptionHandler.class})
    class WebFilterTests {

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private ObjectMapper objectMapper;

        @Autowired
        private ApiRateLimiter rateLimiter;

        @Autowired
        private RateLimitProperties properties;

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
        private ProgressPollingRateLimiter pollingRateLimiter;

        private User user;
        private final UUID userId = UUID.randomUUID();

        @BeforeEach
        void setUp() {
            rateLimiter.reset();
            properties.setEnabled(true);
            properties.setTrustForwardedHeaders(true);
            properties.getLogin().setMaxRequests(3);
            properties.getLogin().setRetryAfterSeconds(45);

            properties.getOauth().setMaxRequests(2);
            properties.getOauth().setRetryAfterSeconds(20);

            properties.getMigration().setMaxRequests(2);
            properties.getMigration().setRetryAfterSeconds(30);

            user = new User("alice@example.com", "hash", "Alice");
            user.setId(userId);
            when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user));
            when(userService.getUserByEmail("alice@example.com")).thenReturn(user);
        }

        @Test
        @DisplayName("Brute force on /api/auth/login triggers HTTP 429 with Retry-After header")
        void loginBruteForce_Triggers429() throws Exception {
            when(authenticationService.login("alice@example.com", "wrong")).thenReturn(user);

            LoginRequest request = new LoginRequest("alice@example.com", "wrong");
            String payload = objectMapper.writeValueAsString(request);

            for (int i = 0; i < 3; i++) {
                mockMvc.perform(post("/api/auth/login")
                        .header("X-Forwarded-For", "198.51.100.1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                        .andExpect(status().isOk());
            }

            // 4th request exceeds rate limit
            mockMvc.perform(post("/api/auth/login")
                    .header("X-Forwarded-For", "198.51.100.1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(payload))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().string("Retry-After", "45"))
                    .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Rate limit exceeded for login")));
        }

        @Test
        @DisplayName("Rate limit isolation: throttling IP A does not throttle IP B")
        void rateLimitIsolation_DifferentIps() throws Exception {
            when(authenticationService.login("alice@example.com", "password")).thenReturn(user);

            LoginRequest request = new LoginRequest("alice@example.com", "password");
            String payload = objectMapper.writeValueAsString(request);

            // Exhaust quota for IP A
            for (int i = 0; i < 3; i++) {
                mockMvc.perform(post("/api/auth/login")
                        .header("X-Forwarded-For", "203.0.113.1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                        .andExpect(status().isOk());
            }

            // IP A is blocked
            mockMvc.perform(post("/api/auth/login")
                    .header("X-Forwarded-For", "203.0.113.1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(payload))
                    .andExpect(status().isTooManyRequests());

            // IP B is still permitted!
            mockMvc.perform(post("/api/auth/login")
                    .header("X-Forwarded-For", "203.0.113.2")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(payload))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("When trustForwardedHeaders is false, arbitrary X-Forwarded-For header is ignored")
        void untrustedForwardedHeaders_Ignored() throws Exception {
            properties.setTrustForwardedHeaders(false);
            when(authenticationService.login("alice@example.com", "password")).thenReturn(user);

            LoginRequest request = new LoginRequest("alice@example.com", "password");
            String payload = objectMapper.writeValueAsString(request);

            // Exhaust quota with spoofed X-Forwarded-For headers
            for (int i = 0; i < 3; i++) {
                mockMvc.perform(post("/api/auth/login")
                        .header("X-Forwarded-For", "spoofed-ip-" + i)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                        .andExpect(status().isOk());
            }

            // Since trustForwardedHeaders is false, all requests map to remoteAddr (127.0.0.1)
            // The 4th request must be blocked even with another spoofed IP!
            mockMvc.perform(post("/api/auth/login")
                    .header("X-Forwarded-For", "spoofed-ip-999")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(payload))
                    .andExpect(status().isTooManyRequests());
        }

        @Test
        @WithMockUser(username = "alice@example.com")
        @DisplayName("Authenticated user hitting OAuth URL generation quota triggers 429")
        void oauthAuthorize_ExceedingQuota_Triggers429() throws Exception {
            when(authorizationService.generateAuthorizationUrl(eq(userId), eq(OAuthProvider.SPOTIFY)))
                    .thenReturn("https://accounts.spotify.com/authorize?state=abc");

            for (int i = 0; i < 2; i++) {
                mockMvc.perform(get("/api/oauth/spotify/authorize"))
                        .andExpect(status().isOk());
            }

            // 3rd request exceeds user quota
            mockMvc.perform(get("/api/oauth/spotify/authorize"))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().string("Retry-After", "20"))
                    .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"));
        }

        @Test
        @WithMockUser(username = "alice@example.com")
        @DisplayName("Migration mutation rate limiting: POST /api/migrations/submit throttled after limit")
        void migrationSubmit_ThrottledAfterLimit() throws Exception {
            RunMigrationApiRequest request = new RunMigrationApiRequest(UUID.randomUUID());
            String payload = objectMapper.writeValueAsString(request);

            UUID executionId = UUID.randomUUID();
            MigrationExecutionSummary summary = new MigrationExecutionSummary(
                    executionId,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "sp-pl-1",
                    "Source Title",
                    "PL_target",
                    "https://music.youtube.com/playlist?list=PL_target",
                    MigrationExecutionStatus.QUEUED,
                    10,
                    0, 0, 0, 0, 0,
                    Instant.now(),
                    null,
                    Collections.emptyList()
            );

            when(jobDispatcher.dispatchMigration(any(), any())).thenReturn(summary);

            for (int i = 0; i < 2; i++) {
                mockMvc.perform(post("/api/migrations/submit")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                        .andExpect(status().isAccepted());
            }

            // 3rd request exceeds limit
            mockMvc.perform(post("/api/migrations/submit")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(payload))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().string("Retry-After", "30"))
                    .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"));
        }

        @Test
        @WithMockUser(username = "alice@example.com")
        @DisplayName("Read-only polling GET /api/migrations/{id}/progress is NOT throttled by migration mutation limiter")
        void readOnlyProgressPolling_NotThrottledByMigrationFilter() throws Exception {
            UUID executionId = UUID.randomUUID();
            MigrationProgressSnapshot snapshot = MigrationProgressSnapshot.calculate(
                    executionId,
                    MigrationExecutionStatus.RUNNING,
                    10,
                    5, 0, 5, 0, 0, 0, 0, 0
            );
            when(progressService.getProgress(eq(executionId), any())).thenReturn(snapshot);

            // Exhaust mutation quota
            RunMigrationApiRequest request = new RunMigrationApiRequest(UUID.randomUUID());
            String payload = objectMapper.writeValueAsString(request);
            MigrationExecutionSummary summary = new MigrationExecutionSummary(
                    executionId,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "sp-pl-1",
                    "Source Title",
                    "PL_target",
                    "https://music.youtube.com/playlist?list=PL_target",
                    MigrationExecutionStatus.QUEUED,
                    10,
                    0, 0, 0, 0, 0,
                    Instant.now(),
                    null,
                    Collections.emptyList()
            );
            when(jobDispatcher.dispatchMigration(any(), any())).thenReturn(summary);

            for (int i = 0; i < 2; i++) {
                mockMvc.perform(post("/api/migrations/submit")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                        .andExpect(status().isAccepted());
            }

            // High-frequency read-only polling succeeds normally even when mutation quota is exhausted!
            for (int i = 0; i < 5; i++) {
                mockMvc.perform(get("/api/migrations/" + executionId + "/progress"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value("RUNNING"));
            }
        }
    }

    private static class TestClock extends Clock {
        private Instant currentInstant;
        private final ZoneId zone = ZoneId.of("UTC");

        public TestClock(Instant initialInstant) {
            this.currentInstant = initialInstant;
        }

        public void advanceSeconds(long seconds) {
            this.currentInstant = this.currentInstant.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return currentInstant;
        }
    }
}
