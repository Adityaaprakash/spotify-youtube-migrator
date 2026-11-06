package com.spotifyyoutube.migrator.migration.reliability.classification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spotifyyoutube.migrator.common.exception.ExternalProviderException;
import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Provider failure classification engine for external APIs (YouTube, Spotify) (Phase 13B).
 * <p>
 * Classifies HTTP errors and provider response payloads into structured categories and retry eligibility.
 * <p>
 * Invariant rules:
 * <ul>
 *   <li>HTTP 403 is never treated as a single category: distinguishes {@code quotaExceeded} from {@code userRateLimitExceeded} and {@code forbidden}.</li>
 *   <li>Unknown provider reasons and status codes are never automatically assumed to be retryable.</li>
 *   <li>Parses standard {@code Retry-After} headers via {@link RetryAfterParser} without truncation.</li>
 *   <li>Redacts OAuth tokens, credentials, and API keys from diagnostic messages.</li>
 *   <li>Deterministic, immutable, and independently unit-testable.</li>
 * </ul>
 */
@Component
public class ProviderFailureClassifier {

    private static final Pattern TOKEN_PATTERN = Pattern.compile(
            "(?i)(bearer\\s+|(?:access_token|refresh_token|token|api_key|apikey|key|client_secret|secret|password)=)[^\\s&\"',;]+"
    );

    private final RetryAfterParser retryAfterParser;
    private final ObjectMapper objectMapper;

    /**
     * Constructs a classifier with default parser and Jackson object mapper.
     */
    public ProviderFailureClassifier() {
        this(new RetryAfterParser(), new ObjectMapper());
    }

    /**
     * Constructs a classifier with injected RetryAfterParser and ObjectMapper.
     *
     * @param retryAfterParser parser for Retry-After headers
     * @param objectMapper Jackson mapper for provider error JSON parsing
     */
    @Autowired
    public ProviderFailureClassifier(RetryAfterParser retryAfterParser, ObjectMapper objectMapper) {
        this.retryAfterParser = Objects.requireNonNull(retryAfterParser, "retryAfterParser must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    /**
     * Classifies a throwable encountered during external provider communication.
     *
     * @param throwable error to classify
     * @return structured classification result
     */
    public ProviderFailureClassification classify(Throwable throwable) {
        if (throwable == null) {
            return new ProviderFailureClassification(
                    ProviderFailureCategory.UNKNOWN,
                    null,
                    null,
                    RetryEligibility.NON_RETRYABLE,
                    Optional.empty(),
                    "Unknown failure (null exception)"
            );
        }

        String rawMessage = throwable.getMessage() != null ? throwable.getMessage() : throwable.getClass().getSimpleName();
        String sanitizedMessage = sanitize(rawMessage);

        // 1. ExternalProviderException
        if (throwable instanceof ExternalProviderException providerEx) {
            Integer statusCode = providerEx.getProviderStatusCode();
            String reason = providerEx.getProviderReason();
            String retryAfter = providerEx.getRetryAfter();

            // If reason is not explicitly set on the exception, inspect sanitized message as fallback
            if (reason == null) {
                reason = extractReasonFromMessage(sanitizedMessage);
            }

            return classify(statusCode, reason, retryAfter, sanitizedMessage);
        }

        // 2. Spring Web Client HttpStatusCodeException (HttpClientErrorException, HttpServerErrorException)
        if (throwable instanceof HttpStatusCodeException httpEx) {
            int statusCode = httpEx.getStatusCode().value();
            HttpHeaders headers = httpEx.getResponseHeaders();
            String retryAfterHeader = headers != null ? headers.getFirst(HttpHeaders.RETRY_AFTER) : null;
            String responseBody = httpEx.getResponseBodyAsString();

            return classify(statusCode, responseBody, retryAfterHeader);
        }

        // 3. ResourceNotFoundException
        if (throwable instanceof ResourceNotFoundException) {
            return new ProviderFailureClassification(
                    ProviderFailureCategory.NOT_FOUND,
                    404,
                    "resourceNotFound",
                    RetryEligibility.NON_RETRYABLE,
                    Optional.empty(),
                    sanitizedMessage
            );
        }

        // 4. ResourceAccessException (I/O connection or socket timeout)
        if (throwable instanceof ResourceAccessException) {
            return new ProviderFailureClassification(
                    ProviderFailureCategory.TRANSIENT_SERVER_ERROR,
                    null,
                    "networkTimeout",
                    RetryEligibility.RETRYABLE,
                    Optional.empty(),
                    "Network I/O timeout during provider communication: " + sanitizedMessage
            );
        }

        // 5. Unrecognized Throwable
        return new ProviderFailureClassification(
                ProviderFailureCategory.UNKNOWN,
                null,
                null,
                RetryEligibility.NON_RETRYABLE,
                Optional.empty(),
                sanitizedMessage
        );
    }

    /**
     * Classifies an error using HTTP status code, raw JSON response body, and Spring HttpHeaders.
     *
     * @param httpStatus HTTP status code
     * @param responseBodyJson raw JSON response body
     * @param headers HTTP response headers
     * @return structured classification result
     */
    public ProviderFailureClassification classify(int httpStatus, String responseBodyJson, HttpHeaders headers) {
        String retryAfterHeader = headers != null ? headers.getFirst(HttpHeaders.RETRY_AFTER) : null;
        return classify(httpStatus, responseBodyJson, retryAfterHeader);
    }

    /**
     * Classifies an error using HTTP status code, raw JSON response body, and raw Retry-After header.
     *
     * @param httpStatus HTTP status code
     * @param responseBodyJson raw JSON response body
     * @param retryAfterHeader raw Retry-After header value
     * @return structured classification result
     */
    public ProviderFailureClassification classify(int httpStatus, String responseBodyJson, String retryAfterHeader) {
        ParsedErrorPayload parsedPayload = parseJsonErrorBody(responseBodyJson);
        String reason = parsedPayload.reason();
        String diagnostic = parsedPayload.message() != null
                ? sanitize(parsedPayload.message())
                : "HTTP " + httpStatus + (reason != null ? " (" + reason + ")" : "");

        return classify(httpStatus, reason, retryAfterHeader, diagnostic);
    }

    /**
     * Classifies an error using discrete status code, provider error reason, Retry-After header, and message.
     *
     * @param httpStatus HTTP status code (may be null)
     * @param providerReason provider-specific error reason (e.g. "quotaExceeded", "userRateLimitExceeded")
     * @param retryAfterHeader raw Retry-After header value
     * @param diagnosticMessage human-readable diagnostic message
     * @return structured classification result
     */
    public ProviderFailureClassification classify(
            Integer httpStatus,
            String providerReason,
            String retryAfterHeader,
            String diagnosticMessage
    ) {
        String sanitizedMessage = sanitize(diagnosticMessage);
        Optional<Duration> parsedRetryAfter = retryAfterParser.parseDelay(retryAfterHeader);

        int code = httpStatus != null ? httpStatus : 0;
        String normalizedReason = providerReason != null ? providerReason.trim() : null;

        // 1. HTTP 429 Too Many Requests
        if (code == 429) {
            return new ProviderFailureClassification(
                    ProviderFailureCategory.RATE_LIMITED,
                    429,
                    normalizedReason != null ? normalizedReason : "rateLimitExceeded",
                    RetryEligibility.RETRYABLE,
                    parsedRetryAfter,
                    sanitizedMessage != null ? sanitizedMessage : "Provider rate limit exceeded (HTTP 429)"
            );
        }

        // 2. HTTP 403 Forbidden / Quota / Rate Limit
        if (code == 403) {
            return classify403(normalizedReason, parsedRetryAfter, sanitizedMessage);
        }

        // 3. HTTP 401 Unauthorized / Token Expired
        if (code == 401) {
            return new ProviderFailureClassification(
                    ProviderFailureCategory.AUTH_EXPIRED,
                    401,
                    normalizedReason != null ? normalizedReason : "authError",
                    RetryEligibility.REQUIRES_REAUTHORIZATION,
                    parsedRetryAfter,
                    sanitizedMessage != null ? sanitizedMessage : "Provider authentication failed or token expired (HTTP 401)"
            );
        }

        // 4. HTTP 404 Not Found
        if (code == 404) {
            return new ProviderFailureClassification(
                    ProviderFailureCategory.NOT_FOUND,
                    404,
                    normalizedReason != null ? normalizedReason : "notFound",
                    RetryEligibility.NON_RETRYABLE,
                    Optional.empty(),
                    sanitizedMessage != null ? sanitizedMessage : "Provider resource not found (HTTP 404)"
            );
        }

        // 5. HTTP 400 Bad Request / Invalid Request
        if (code == 400) {
            return new ProviderFailureClassification(
                    ProviderFailureCategory.INVALID_REQUEST,
                    400,
                    normalizedReason != null ? normalizedReason : "badRequest",
                    RetryEligibility.NON_RETRYABLE,
                    Optional.empty(),
                    sanitizedMessage != null ? sanitizedMessage : "Provider rejected request as invalid (HTTP 400)"
            );
        }

        // 6. HTTP 500, 502, 503 Server Errors
        if (code == 500 || code == 502 || code == 503) {
            return new ProviderFailureClassification(
                    ProviderFailureCategory.TRANSIENT_SERVER_ERROR,
                    code,
                    normalizedReason != null ? normalizedReason : "backendError",
                    RetryEligibility.RETRYABLE,
                    parsedRetryAfter,
                    sanitizedMessage != null ? sanitizedMessage : "Transient provider server error (HTTP " + code + ")"
            );
        }

        // 7. Unknown or Unmapped HTTP Status / Reason
        return new ProviderFailureClassification(
                ProviderFailureCategory.UNKNOWN,
                httpStatus,
                normalizedReason,
                RetryEligibility.NON_RETRYABLE,
                parsedRetryAfter,
                sanitizedMessage != null ? sanitizedMessage : "Unknown provider failure (HTTP " + code + ")"
        );
    }

    private ProviderFailureClassification classify403(
            String reason,
            Optional<Duration> retryAfter,
            String message
    ) {
        String lowerReason = reason != null ? reason.toLowerCase() : "";

        // Case A: Quota Exceeded (non-retryable in current daily quota window)
        if (lowerReason.contains("quotaexceeded") || lowerReason.contains("dailyquotaexceeded") || lowerReason.equals("quota")) {
            return new ProviderFailureClassification(
                    ProviderFailureCategory.QUOTA_EXCEEDED,
                    403,
                    reason != null ? reason : "quotaExceeded",
                    RetryEligibility.DEFERRED_RESET,
                    retryAfter,
                    message != null ? message : "Provider daily quota exceeded (HTTP 403 quotaExceeded)"
            );
        }

        // Case B: User Rate Limit Exceeded (short-term QPS limit; retryable with backoff)
        if (lowerReason.contains("userratelimitexceeded") || lowerReason.contains("ratelimitexceeded")) {
            return new ProviderFailureClassification(
                    ProviderFailureCategory.USER_RATE_LIMITED,
                    403,
                    reason != null ? reason : "userRateLimitExceeded",
                    RetryEligibility.RETRYABLE,
                    retryAfter,
                    message != null ? message : "Provider user rate limit exceeded (HTTP 403 userRateLimitExceeded)"
            );
        }

        // Case C: Permission Denied / Scope Insufficient / Forbidden
        if (lowerReason.contains("insufficientfilepermissions")
                || lowerReason.contains("forbidden")
                || lowerReason.contains("insufficientpermissions")
                || lowerReason.contains("permissiondenied")
                || lowerReason.contains("accessdenied")) {
            return new ProviderFailureClassification(
                    ProviderFailureCategory.PERMISSION_DENIED,
                    403,
                    reason != null ? reason : "forbidden",
                    RetryEligibility.NON_RETRYABLE,
                    Optional.empty(),
                    message != null ? message : "Provider access forbidden or insufficient permissions (HTTP 403)"
            );
        }

        // Case D: Unknown 403 Reason -> Must NOT automatically become retryable
        return new ProviderFailureClassification(
                ProviderFailureCategory.UNKNOWN,
                403,
                reason,
                RetryEligibility.NON_RETRYABLE,
                retryAfter,
                message != null ? message : "Unrecognized HTTP 403 failure from provider"
        );
    }

    /**
     * Parses Google/YouTube standard API error JSON format:
     * <pre>{@code
     * {
     *   "error": {
     *     "code": 403,
     *     "message": "...",
     *     "errors": [
     *       { "reason": "quotaExceeded", "message": "..." }
     *     ]
     *   }
     * }
     * }</pre>
     */
    private ParsedErrorPayload parseJsonErrorBody(String jsonBody) {
        if (jsonBody == null || jsonBody.isBlank()) {
            return new ParsedErrorPayload(null, null);
        }

        try {
            JsonNode root = objectMapper.readTree(jsonBody);
            JsonNode errorNode = root.path("error");
            if (errorNode.isMissingNode()) {
                errorNode = root; // Handle direct JSON payload
            }

            String message = errorNode.path("message").asText(null);
            String reason = null;

            JsonNode errorsArray = errorNode.path("errors");
            if (errorsArray.isArray() && !errorsArray.isEmpty()) {
                JsonNode firstError = errorsArray.get(0);
                reason = firstError.path("reason").asText(null);
                if (message == null) {
                    message = firstError.path("message").asText(null);
                }
            }

            if (reason == null) {
                // Check if reason is directly on errorNode
                reason = errorNode.path("reason").asText(null);
            }

            return new ParsedErrorPayload(reason, message);
        } catch (Exception e) {
            // Malformed JSON: return null fields gracefully without failing
            return new ParsedErrorPayload(null, null);
        }
    }

    private String extractReasonFromMessage(String message) {
        if (message == null) {
            return null;
        }
        String lower = message.toLowerCase();
        if (lower.contains("quotaexceeded") || lower.contains("quota exceeded")) {
            return "quotaExceeded";
        }
        if (lower.contains("userratelimitexceeded") || lower.contains("user rate limit")) {
            return "userRateLimitExceeded";
        }
        if (lower.contains("ratelimitexceeded") || lower.contains("rate limit")) {
            return "rateLimitExceeded";
        }
        if (lower.contains("insufficientfilepermissions") || lower.contains("forbidden") || lower.contains("permission")) {
            return "forbidden";
        }
        if (lower.contains("autherror") || lower.contains("invalid token") || lower.contains("invalid api key")) {
            return "authError";
        }
        if (lower.contains("not found")) {
            return "notFound";
        }
        if (lower.contains("backenderror") || lower.contains("backend error")) {
            return "backendError";
        }
        return null;
    }

    /**
     * Sanitizes sensitive credentials, bearer tokens, and API keys from error messages.
     *
     * @param message raw message
     * @return sanitized message
     */
    public static String sanitize(String message) {
        if (message == null) {
            return "null";
        }
        return TOKEN_PATTERN.matcher(message).replaceAll("$1[REDACTED]");
    }

    private record ParsedErrorPayload(String reason, String message) {
    }
}
