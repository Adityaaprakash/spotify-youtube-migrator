package com.spotifyyoutube.migrator.common.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import java.net.SocketTimeoutException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies that {@link GlobalExceptionHandler} maps every recognised exception type to the
 * correct HTTP status, {@link ErrorCode}, and sanitised message. A standalone MockMvc
 * instance is used so the test does not require Spring Security or a database.
 */
class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    /**
     * Minimal controller: each path deliberately throws a specific exception so we can
     * assert the handler's response without touching real application beans.
     */
    @RestController
    static class ThrowingController {

        @GetMapping("/test/not-found")
        public void notFound() {
            throw new ResourceNotFoundException("Playlist abc not found");
        }

        @GetMapping("/test/illegal-argument")
        public void illegalArgument() {
            throw new IllegalArgumentException("Invalid argument provided");
        }

        @GetMapping("/test/conflict")
        public void conflict() {
            throw new ConflictException("Resource already exists");
        }

        @GetMapping("/test/invalid-state")
        public void invalidState() {
            throw new InvalidStateException("Requires reauthorization");
        }

        @GetMapping("/test/external-rate-limit")
        public void externalRateLimit() {
            throw new ExternalProviderException("Spotify returned 429", 429, "30");
        }

        @GetMapping("/test/external-5xx")
        public void externalServerError() {
            throw new ExternalProviderException("Spotify server error", 503, null);
        }

        @GetMapping("/test/external-401")
        public void externalUnauthorized() {
            throw new ExternalProviderException("Spotify token rejected", 401, null);
        }

        @GetMapping("/test/external-no-code")
        public void externalNoCode() {
            throw new ExternalProviderException("Upstream failed");
        }

        @GetMapping("/test/timeout")
        public void timeout() {
            throw new ResourceAccessException("Connection timed out",
                    new SocketTimeoutException("Read timed out"));
        }

        @GetMapping("/test/malformed")
        public void malformedResponse() {
            throw new RestClientException("Unexpected body");
        }

        @GetMapping("/test/generic-error")
        public void generic() {
            throw new RuntimeException("Something went very wrong");
        }

        @GetMapping("/test/task-rejected")
        public void taskRejected() {
            throw new org.springframework.core.task.TaskRejectedException("Executor queue saturated");
        }

        @GetMapping("/test/access-denied")
        public void accessDenied() {
            throw new org.springframework.security.access.AccessDeniedException("Access is denied");
        }

        @GetMapping("/test/authentication")
        public void authentication() {
            throw new org.springframework.security.authentication.BadCredentialsException("Bad credentials");
        }

        @GetMapping("/test/rate-limit-exceeded")
        public void rateLimitExceeded() {
            throw new RateLimitExceededException("Progress polling rate limit exceeded; please slow down requests", 5);
        }

        @GetMapping("/test/progress-consistency")
        public void progressConsistency() {
            throw new com.spotifyyoutube.migrator.migration.progress.domain.ProgressConsistencyException("Count conservation violation: total=10, sum=8");
        }
    }


    @BeforeEach
    void setUp() {
        // Standalone MockMvc: only the handler + our controller, no Spring Security filter, no DB
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void resourceNotFoundException_shouldReturn404WithNotFoundCode() throws Exception {
        mockMvc.perform(get("/test/not-found").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Playlist abc not found"))
                .andExpect(jsonPath("$.path").value("/test/not-found"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    void illegalArgumentException_shouldReturn400WithInvalidRequestCode() throws Exception {
        mockMvc.perform(get("/test/illegal-argument").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("Invalid argument provided"));
    }

    @Test
    void conflictException_shouldReturn409WithConflictCode() throws Exception {
        mockMvc.perform(get("/test/conflict").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value("Resource already exists"));
    }

    @Test
    void invalidStateException_shouldReturn409WithInvalidStateCode() throws Exception {
        mockMvc.perform(get("/test/invalid-state").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"))
                .andExpect(jsonPath("$.message").value("Requires reauthorization"));
    }

    @Test
    void externalProviderException_rateLimited_shouldReturn429WithRetryAfterHeader() throws Exception {
        mockMvc.perform(get("/test/external-rate-limit").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(jsonPath("$.code").value("EXTERNAL_PROVIDER_ERROR"));
    }

    @Test
    void externalProviderException_5xx_shouldReturn502BadGateway() throws Exception {
        mockMvc.perform(get("/test/external-5xx").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("EXTERNAL_PROVIDER_ERROR"));
    }

    @Test
    void externalProviderException_401_shouldReturn401Unauthorized() throws Exception {
        mockMvc.perform(get("/test/external-401").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("EXTERNAL_PROVIDER_ERROR"));
    }

    @Test
    void externalProviderException_noCode_shouldReturn502BadGateway() throws Exception {
        mockMvc.perform(get("/test/external-no-code").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("EXTERNAL_PROVIDER_ERROR"));
    }

    @Test
    void resourceAccessException_timeout_shouldReturn504GatewayTimeout() throws Exception {
        mockMvc.perform(get("/test/timeout").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("EXTERNAL_PROVIDER_ERROR"))
                .andExpect(jsonPath("$.message")
                        .value("Network timeout or connection failure with external provider"));
    }

    @Test
    void restClientException_malformedResponse_shouldReturn502AndNotLeakDetails() throws Exception {
        mockMvc.perform(get("/test/malformed").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("EXTERNAL_PROVIDER_ERROR"))
                // Sanitised message — must NOT echo raw upstream body
                .andExpect(jsonPath("$.message")
                        .value("Malformed or unexpected response from external provider"));
    }

    @Test
    void genericException_shouldReturn500AndSanitisedMessage() throws Exception {
        mockMvc.perform(get("/test/generic-error").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
                // Must NOT expose real exception message to the client
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"));
    }

    @Test
    void taskRejectedException_shouldReturn429WithRetryAfterHeader() throws Exception {
        mockMvc.perform(get("/test/task-rejected").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"))
                .andExpect(jsonPath("$.message").value("Migration worker pool is currently saturated; please retry shortly"));
    }

    @Test
    void accessDeniedException_shouldReturn403WithForbiddenCode() throws Exception {
        mockMvc.perform(get("/test/access-denied").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("Access is denied"));
    }

    @Test
    void authenticationException_shouldReturn401WithUnauthorizedCode() throws Exception {
        mockMvc.perform(get("/test/authentication").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("Authentication is required"));
    }

    @Test
    void rateLimitExceededException_shouldReturn429WithRetryAfterHeader() throws Exception {
        mockMvc.perform(get("/test/rate-limit-exceeded").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"))
                .andExpect(jsonPath("$.message").value("Progress polling rate limit exceeded; please slow down requests"));
    }

    @Test
    void progressConsistencyException_shouldReturn500AndSanitisedMessage() throws Exception {
        mockMvc.perform(get("/test/progress-consistency").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"));
    }
}

