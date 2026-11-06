package com.spotifyyoutube.migrator.migration.reliability.classification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spotifyyoutube.migrator.common.exception.ExternalProviderException;
import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Phase 13B: ProviderFailureClassifier Unit Tests")
class ProviderFailureClassifierTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-11-06T10:00:00Z");
    private ProviderFailureClassifier classifier;
    private RetryAfterParser retryAfterParser;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        retryAfterParser = new RetryAfterParser(fixedClock);
        objectMapper = new ObjectMapper();
        classifier = new ProviderFailureClassifier(retryAfterParser, objectMapper);
    }

    @Nested
    @DisplayName("1. Rate Limiting (429 & 403 User Rate Limits)")
    class RateLimitingTests {

        @Test
        @DisplayName("Classifies HTTP 429 as RATE_LIMITED with RETRYABLE eligibility and parsed Retry-After")
        void classifies429RateLimit() {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.RETRY_AFTER, "60");

            ProviderFailureClassification result = classifier.classify(
                    429,
                    "{\"error\": {\"code\": 429, \"message\": \"Rate limit exceeded\", \"errors\": [{\"reason\": \"rateLimitExceeded\"}]}}",
                    headers
            );

            assertThat(result.category()).isEqualTo(ProviderFailureCategory.RATE_LIMITED);
            assertThat(result.retryEligibility()).isEqualTo(RetryEligibility.RETRYABLE);
            assertThat(result.isRetryEligible()).isTrue();
            assertThat(result.isRateLimited()).isTrue();
            assertThat(result.httpStatus()).isEqualTo(429);
            assertThat(result.providerReason()).isEqualTo("rateLimitExceeded");
            assertThat(result.retryAfter()).contains(Duration.ofSeconds(60));
        }

        @Test
        @DisplayName("Classifies HTTP 403 userRateLimitExceeded as USER_RATE_LIMITED with RETRYABLE eligibility")
        void classifies403UserRateLimitExceeded() {
            String json = """
                    {
                      "error": {
                        "code": 403,
                        "message": "User Rate Limit Exceeded",
                        "errors": [
                          {
                            "domain": "usageLimits",
                            "reason": "userRateLimitExceeded",
                            "message": "User Rate Limit Exceeded"
                          }
                        ]
                      }
                    }
                    """;

            ProviderFailureClassification result = classifier.classify(403, json, "15");

            assertThat(result.category()).isEqualTo(ProviderFailureCategory.USER_RATE_LIMITED);
            assertThat(result.retryEligibility()).isEqualTo(RetryEligibility.RETRYABLE);
            assertThat(result.isRetryEligible()).isTrue();
            assertThat(result.isUserRateLimited()).isTrue();
            assertThat(result.isRateLimited()).isTrue();
            assertThat(result.httpStatus()).isEqualTo(403);
            assertThat(result.providerReason()).isEqualTo("userRateLimitExceeded");
            assertThat(result.retryAfter()).contains(Duration.ofSeconds(15));
        }

        @Test
        @DisplayName("Classifies HTTP 403 rateLimitExceeded as USER_RATE_LIMITED with RETRYABLE eligibility")
        void classifies403RateLimitExceeded() {
            ProviderFailureClassification result = classifier.classify(
                    403,
                    "rateLimitExceeded",
                    null,
                    "Short-term provider rate limit"
            );

            assertThat(result.category()).isEqualTo(ProviderFailureCategory.USER_RATE_LIMITED);
            assertThat(result.retryEligibility()).isEqualTo(RetryEligibility.RETRYABLE);
            assertThat(result.isRetryEligible()).isTrue();
            assertThat(result.isRateLimited()).isTrue();
        }
    }

    @Nested
    @DisplayName("2. Quota Exceeded (HTTP 403)")
    class QuotaExceededTests {

        @Test
        @DisplayName("Classifies HTTP 403 quotaExceeded as QUOTA_EXCEEDED with DEFERRED_RESET eligibility")
        void classifies403QuotaExceeded() {
            String json = """
                    {
                      "error": {
                        "code": 403,
                        "message": "The request cannot be completed because you have exceeded your quota.",
                        "errors": [
                          {
                            "domain": "youtube.quota",
                            "reason": "quotaExceeded",
                            "message": "The request cannot be completed because you have exceeded your quota."
                          }
                        ]
                      }
                    }
                    """;

            ProviderFailureClassification result = classifier.classify(403, json, (String) null);

            assertThat(result.category()).isEqualTo(ProviderFailureCategory.QUOTA_EXCEEDED);
            assertThat(result.retryEligibility()).isEqualTo(RetryEligibility.DEFERRED_RESET);
            assertThat(result.isRetryEligible()).isFalse();
            assertThat(result.isQuotaExceeded()).isTrue();
            assertThat(result.httpStatus()).isEqualTo(403);
            assertThat(result.providerReason()).isEqualTo("quotaExceeded");
        }

        @Test
        @DisplayName("Distinguishes 403 quotaExceeded from 403 userRateLimitExceeded")
        void distinguishesQuotaFromRateLimit() {
            ProviderFailureClassification quota = classifier.classify(403, "quotaExceeded", null, "Quota out");
            ProviderFailureClassification rate = classifier.classify(403, "userRateLimitExceeded", null, "Rate slow down");

            assertThat(quota.category()).isEqualTo(ProviderFailureCategory.QUOTA_EXCEEDED);
            assertThat(quota.retryEligibility()).isEqualTo(RetryEligibility.DEFERRED_RESET);
            assertThat(quota.isRetryEligible()).isFalse();

            assertThat(rate.category()).isEqualTo(ProviderFailureCategory.USER_RATE_LIMITED);
            assertThat(rate.retryEligibility()).isEqualTo(RetryEligibility.RETRYABLE);
            assertThat(rate.isRetryEligible()).isTrue();
        }
    }

    @Nested
    @DisplayName("3. Permission Denied & Authentication Expired (403 & 401)")
    class PermissionAndAuthTests {

        @Test
        @DisplayName("Classifies HTTP 403 insufficientFilePermissions as PERMISSION_DENIED with NON_RETRYABLE eligibility")
        void classifies403InsufficientFilePermissions() {
            String json = """
                    {
                      "error": {
                        "code": 403,
                        "message": "The user does not have permission to modify this playlist.",
                        "errors": [
                          {
                            "domain": "youtube.playlist",
                            "reason": "insufficientFilePermissions",
                            "message": "The user does not have permission to modify this playlist."
                          }
                        ]
                      }
                    }
                    """;

            ProviderFailureClassification result = classifier.classify(403, json, (String) null);

            assertThat(result.category()).isEqualTo(ProviderFailureCategory.PERMISSION_DENIED);
            assertThat(result.retryEligibility()).isEqualTo(RetryEligibility.NON_RETRYABLE);
            assertThat(result.isRetryEligible()).isFalse();
            assertThat(result.isPermissionDenied()).isTrue();
        }

        @Test
        @DisplayName("Classifies HTTP 403 forbidden as PERMISSION_DENIED")
        void classifies403Forbidden() {
            ProviderFailureClassification result = classifier.classify(403, "forbidden", null, "Access denied");

            assertThat(result.category()).isEqualTo(ProviderFailureCategory.PERMISSION_DENIED);
            assertThat(result.retryEligibility()).isEqualTo(RetryEligibility.NON_RETRYABLE);
        }

        @Test
        @DisplayName("Classifies HTTP 401 authError as AUTH_EXPIRED with REQUIRES_REAUTHORIZATION eligibility")
        void classifies401AuthError() {
            String json = """
                    {
                      "error": {
                        "code": 401,
                        "message": "Invalid Credentials",
                        "errors": [
                          {
                            "domain": "global",
                            "reason": "authError",
                            "message": "Invalid Credentials"
                          }
                        ]
                      }
                    }
                    """;

            ProviderFailureClassification result = classifier.classify(401, json, (String) null);

            assertThat(result.category()).isEqualTo(ProviderFailureCategory.AUTH_EXPIRED);
            assertThat(result.retryEligibility()).isEqualTo(RetryEligibility.REQUIRES_REAUTHORIZATION);
            assertThat(result.isAuthExpired()).isTrue();
            assertThat(result.isRetryEligible()).isFalse();
            assertThat(result.httpStatus()).isEqualTo(401);
            assertThat(result.providerReason()).isEqualTo("authError");
        }

        @Test
        @DisplayName("Classifies HTTP 401 invalid_token as AUTH_EXPIRED")
        void classifies401InvalidToken() {
            ProviderFailureClassification result = classifier.classify(401, "invalid_token", null, "Token has expired");

            assertThat(result.category()).isEqualTo(ProviderFailureCategory.AUTH_EXPIRED);
            assertThat(result.retryEligibility()).isEqualTo(RetryEligibility.REQUIRES_REAUTHORIZATION);
        }
    }

    @Nested
    @DisplayName("4. Resource Not Found & Invalid Requests (404 & 400)")
    class NotFoundAndBadRequestTests {

        @Test
        @DisplayName("Classifies HTTP 404 videoNotFound as NOT_FOUND with NON_RETRYABLE eligibility")
        void classifies404VideoNotFound() {
            String json = """
                    {
                      "error": {
                        "code": 404,
                        "message": "Video not found",
                        "errors": [
                          {
                            "domain": "youtube.video",
                            "reason": "videoNotFound",
                            "message": "Video not found"
                          }
                        ]
                      }
                    }
                    """;

            ProviderFailureClassification result = classifier.classify(404, json, (String) null);

            assertThat(result.category()).isEqualTo(ProviderFailureCategory.NOT_FOUND);
            assertThat(result.retryEligibility()).isEqualTo(RetryEligibility.NON_RETRYABLE);
            assertThat(result.isNotFound()).isTrue();
            assertThat(result.isRetryEligible()).isFalse();
            assertThat(result.httpStatus()).isEqualTo(404);
            assertThat(result.providerReason()).isEqualTo("videoNotFound");
        }

        @Test
        @DisplayName("Classifies HTTP 404 playlistNotFound as NOT_FOUND")
        void classifies404PlaylistNotFound() {
            ProviderFailureClassification result = classifier.classify(404, "playlistNotFound", null, "Playlist deleted");

            assertThat(result.category()).isEqualTo(ProviderFailureCategory.NOT_FOUND);
            assertThat(result.retryEligibility()).isEqualTo(RetryEligibility.NON_RETRYABLE);
            assertThat(result.isNotFound()).isTrue();
        }

        @Test
        @DisplayName("Classifies HTTP 400 invalid request as INVALID_REQUEST with NON_RETRYABLE eligibility")
        void classifies400BadRequest() {
            String json = """
                    {
                      "error": {
                        "code": 400,
                        "message": "Bad Request",
                        "errors": [
                          {
                            "domain": "global",
                            "reason": "badRequest",
                            "message": "Required parameter is missing"
                          }
                        ]
                      }
                    }
                    """;

            ProviderFailureClassification result = classifier.classify(400, json, (String) null);

            assertThat(result.category()).isEqualTo(ProviderFailureCategory.INVALID_REQUEST);
            assertThat(result.retryEligibility()).isEqualTo(RetryEligibility.NON_RETRYABLE);
            assertThat(result.isInvalidRequest()).isTrue();
            assertThat(result.isRetryEligible()).isFalse();
        }
    }

    @Nested
    @DisplayName("5. Transient Server Errors (500, 502, 503)")
    class ServerErrorTests {

        @Test
        @DisplayName("Classifies HTTP 500 backendError as TRANSIENT_SERVER_ERROR with RETRYABLE eligibility")
        void classifies500BackendError() {
            String json = """
                    {
                      "error": {
                        "code": 500,
                        "message": "Backend Error",
                        "errors": [
                          {
                            "domain": "global",
                            "reason": "backendError",
                            "message": "Backend Error"
                          }
                        ]
                      }
                    }
                    """;

            ProviderFailureClassification result = classifier.classify(500, json, "5");

            assertThat(result.category()).isEqualTo(ProviderFailureCategory.TRANSIENT_SERVER_ERROR);
            assertThat(result.retryEligibility()).isEqualTo(RetryEligibility.RETRYABLE);
            assertThat(result.isTransientServerError()).isTrue();
            assertThat(result.isRetryEligible()).isTrue();
            assertThat(result.retryAfter()).contains(Duration.ofSeconds(5));
        }

        @Test
        @DisplayName("Classifies HTTP 502 Bad Gateway as TRANSIENT_SERVER_ERROR")
        void classifies502BadGateway() {
            ProviderFailureClassification result = classifier.classify(502, (String) null, (String) null);

            assertThat(result.category()).isEqualTo(ProviderFailureCategory.TRANSIENT_SERVER_ERROR);
            assertThat(result.retryEligibility()).isEqualTo(RetryEligibility.RETRYABLE);
        }

        @Test
        @DisplayName("Classifies HTTP 503 Service Unavailable backendError as TRANSIENT_SERVER_ERROR")
        void classifies503ServiceUnavailable() {
            ProviderFailureClassification result = classifier.classify(503, "backendError", "30", "Service unavailable");

            assertThat(result.category()).isEqualTo(ProviderFailureCategory.TRANSIENT_SERVER_ERROR);
            assertThat(result.retryEligibility()).isEqualTo(RetryEligibility.RETRYABLE);
            assertThat(result.isRetryEligible()).isTrue();
            assertThat(result.retryAfter()).contains(Duration.ofSeconds(30));
        }
    }

    @Nested
    @DisplayName("6. Unknown HTTP Status & Unknown Provider Reasons")
    class UnknownFailureTests {

        @Test
        @DisplayName("Classifies HTTP 403 with unknown reason as UNKNOWN and NON_RETRYABLE")
        void classifiesUnknown403ReasonAsUnknownNonRetryable() {
            ProviderFailureClassification result = classifier.classify(
                    403,
                    "unrecognizedPolicyViolation",
                    null,
                    "Some strange policy violation"
            );

            assertThat(result.category()).isEqualTo(ProviderFailureCategory.UNKNOWN);
            assertThat(result.retryEligibility()).isEqualTo(RetryEligibility.NON_RETRYABLE);
            assertThat(result.isRetryEligible()).isFalse();
            assertThat(result.isUnknown()).isTrue();
            assertThat(result.httpStatus()).isEqualTo(403);
            assertThat(result.providerReason()).isEqualTo("unrecognizedPolicyViolation");
        }

        @Test
        @DisplayName("Classifies unknown HTTP status code (e.g. 418) as UNKNOWN and NON_RETRYABLE")
        void classifiesUnknownStatusCodeAsUnknown() {
            ProviderFailureClassification result = classifier.classify(418, "imATeapot", null, "I'm a teapot");

            assertThat(result.category()).isEqualTo(ProviderFailureCategory.UNKNOWN);
            assertThat(result.retryEligibility()).isEqualTo(RetryEligibility.NON_RETRYABLE);
            assertThat(result.isRetryEligible()).isFalse();
            assertThat(result.isUnknown()).isTrue();
        }
    }

    @Nested
    @DisplayName("7. Incomplete, Missing & Malformed Response Payloads")
    class PayloadResilienceTests {

        @Test
        @DisplayName("Handles null response body gracefully using status code")
        void handlesNullResponseBody() {
            ProviderFailureClassification result = classifier.classify(503, (String) null, "10");

            assertThat(result.category()).isEqualTo(ProviderFailureCategory.TRANSIENT_SERVER_ERROR);
            assertThat(result.retryEligibility()).isEqualTo(RetryEligibility.RETRYABLE);
            assertThat(result.retryAfter()).contains(Duration.ofSeconds(10));
        }

        @Test
        @DisplayName("Handles blank response body gracefully")
        void handlesBlankResponseBody() {
            ProviderFailureClassification result = classifier.classify(404, "    ", (String) null);

            assertThat(result.category()).isEqualTo(ProviderFailureCategory.NOT_FOUND);
            assertThat(result.retryEligibility()).isEqualTo(RetryEligibility.NON_RETRYABLE);
        }

        @Test
        @DisplayName("Handles malformed JSON response body gracefully without throwing")
        void handlesMalformedJsonBody() {
            String malformed = "{ this is definitely not valid json <xml> }";
            ProviderFailureClassification result = classifier.classify(500, malformed, (String) null);

            assertThat(result.category()).isEqualTo(ProviderFailureCategory.TRANSIENT_SERVER_ERROR);
            assertThat(result.retryEligibility()).isEqualTo(RetryEligibility.RETRYABLE);
        }

        @Test
        @DisplayName("Handles JSON error body missing errors array")
        void handlesJsonMissingErrorsArray() {
            String json = "{\"error\": {\"code\": 403, \"message\": \"Forbidden without details\"}}";
            ProviderFailureClassification result = classifier.classify(403, json, (String) null);

            assertThat(result.category()).isEqualTo(ProviderFailureCategory.UNKNOWN);
            assertThat(result.retryEligibility()).isEqualTo(RetryEligibility.NON_RETRYABLE);
        }
    }

    @Nested
    @DisplayName("8. Throwable & Exception Unwrapping")
    class ExceptionUnwrappingTests {

        @Test
        @DisplayName("Unwraps ExternalProviderException with provider reason and retry-after")
        void unwrapsExternalProviderException() {
            var ex = new ExternalProviderException(
                    "YouTube rate limit", 429, "45", "rateLimitExceeded"
            );

            ProviderFailureClassification result = classifier.classify(ex);

            assertThat(result.category()).isEqualTo(ProviderFailureCategory.RATE_LIMITED);
            assertThat(result.isRetryEligible()).isTrue();
            assertThat(result.retryAfter()).contains(Duration.ofSeconds(45));
            assertThat(result.providerReason()).isEqualTo("rateLimitExceeded");
        }

        @Test
        @DisplayName("Unwraps HttpClientErrorException with JSON error body and headers")
        void unwrapsHttpClientErrorException() {
            String json = "{\"error\": {\"code\": 403, \"errors\": [{\"reason\": \"quotaExceeded\"}]}}";
            HttpHeaders headers = new HttpHeaders();
            headers.set("Content-Type", "application/json");

            var ex = HttpClientErrorException.create(
                    HttpStatus.FORBIDDEN,
                    "Forbidden",
                    headers,
                    json.getBytes(StandardCharsets.UTF_8),
                    StandardCharsets.UTF_8
            );

            ProviderFailureClassification result = classifier.classify(ex);

            assertThat(result.category()).isEqualTo(ProviderFailureCategory.QUOTA_EXCEEDED);
            assertThat(result.retryEligibility()).isEqualTo(RetryEligibility.DEFERRED_RESET);
            assertThat(result.isQuotaExceeded()).isTrue();
        }

        @Test
        @DisplayName("Unwraps HttpServerErrorException 503")
        void unwrapsHttpServerErrorException() {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.RETRY_AFTER, "12");

            var ex = HttpServerErrorException.create(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Service Unavailable",
                    headers,
                    new byte[0],
                    StandardCharsets.UTF_8
            );

            ProviderFailureClassification result = classifier.classify(ex);

            assertThat(result.category()).isEqualTo(ProviderFailureCategory.TRANSIENT_SERVER_ERROR);
            assertThat(result.isRetryEligible()).isTrue();
            assertThat(result.retryAfter()).contains(Duration.ofSeconds(12));
        }

        @Test
        @DisplayName("Unwraps ResourceNotFoundException as NOT_FOUND")
        void unwrapsResourceNotFoundException() {
            var ex = new ResourceNotFoundException("Playlist not found on YouTube");

            ProviderFailureClassification result = classifier.classify(ex);

            assertThat(result.category()).isEqualTo(ProviderFailureCategory.NOT_FOUND);
            assertThat(result.isNotFound()).isTrue();
            assertThat(result.isRetryEligible()).isFalse();
        }

        @Test
        @DisplayName("Unwraps ResourceAccessException as TRANSIENT_SERVER_ERROR network timeout")
        void unwrapsResourceAccessException() {
            var ex = new ResourceAccessException("Connection timed out", new IOException("Read timed out"));

            ProviderFailureClassification result = classifier.classify(ex);

            assertThat(result.category()).isEqualTo(ProviderFailureCategory.TRANSIENT_SERVER_ERROR);
            assertThat(result.isRetryEligible()).isTrue();
            assertThat(result.providerReason()).isEqualTo("networkTimeout");
        }

        @Test
        @DisplayName("Handles null throwable safely")
        void handlesNullThrowable() {
            ProviderFailureClassification result = classifier.classify((Throwable) null);

            assertThat(result.category()).isEqualTo(ProviderFailureCategory.UNKNOWN);
            assertThat(result.isRetryEligible()).isFalse();
        }
    }

    @Nested
    @DisplayName("9. Credential Sanitization")
    class CredentialSanitizationTests {

        @Test
        @DisplayName("Redacts Bearer tokens, access tokens, API keys and secrets from diagnostic messages")
        void redactsSensitiveInformation() {
            String rawMessage = "Failed with Bearer ya29.a0AfH6SMB... and access_token=secretToken123 with api_key=AIzaSyD-xxx";
            ProviderFailureClassification result = classifier.classify(500, "backendError", null, rawMessage);

            assertThat(result.diagnosticMessage()).doesNotContain("ya29.a0AfH6SMB");
            assertThat(result.diagnosticMessage()).doesNotContain("secretToken123");
            assertThat(result.diagnosticMessage()).doesNotContain("AIzaSyD-xxx");
            assertThat(result.diagnosticMessage()).contains("Bearer [REDACTED]");
            assertThat(result.diagnosticMessage()).contains("access_token=[REDACTED]");
            assertThat(result.diagnosticMessage()).contains("api_key=[REDACTED]");
        }
    }
}
