package com.spotifyyoutube.migrator.migration.application.execution.failure;

import com.spotifyyoutube.migrator.common.exception.ExternalProviderException;
import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceException;
import org.springframework.dao.DataAccessException;
import org.springframework.web.client.ResourceAccessException;

import java.util.regex.Pattern;

/**
 * Classifies exceptions and failure outcomes encountered during playlist migration execution,
 * recovery, and rollback operations (Phase 11F).
 * <p>
 * Implements deterministic classification rules without conflating ambiguous outcomes with confirmed failures.
 */
public final class ExecutionFailureClassifier {

    private static final Pattern TOKEN_PATTERN = Pattern.compile(
            "(?i)(bearer\\s+|(?:access_token|refresh_token|token|api_key|apikey|key|client_secret|secret|password)=)[^\\s&\"',;]+"
    );

    private ExecutionFailureClassifier() {
        // Utility class
    }

    /**
     * Classifies a throwable encountered during execution, recovery, or rollback.
     *
     * @param throwable error to classify
     * @return structured failure analysis
     */
    public static ExecutionFailureAnalysis classify(Throwable throwable) {
        if (throwable == null) {
            return new ExecutionFailureAnalysis(
                    ExecutionFailureClassification.CONFIRMED_NON_RETRYABLE_PROVIDER_REJECTION,
                    "Unknown failure with null cause",
                    false,
                    false,
                    null
            );
        }

        String rawMessage = throwable.getMessage() != null ? throwable.getMessage() : throwable.getClass().getSimpleName();
        String sanitizedMessage = sanitize(rawMessage);

        if (throwable instanceof ExternalProviderException providerEx) {
            return classifyProviderException(providerEx, sanitizedMessage);
        }

        if (throwable instanceof ResourceNotFoundException) {
            return new ExecutionFailureAnalysis(
                    ExecutionFailureClassification.INVALID_DESTINATION_RESOURCE,
                    sanitizedMessage,
                    false,
                    true,
                    404
            );
        }

        if (throwable instanceof InvalidStateException) {
            String lower = sanitizedMessage.toLowerCase();
            if (lower.contains("google account") || lower.contains("reauthorization") || lower.contains("access token")) {
                return new ExecutionFailureAnalysis(
                        ExecutionFailureClassification.AUTHENTICATION_OR_AUTHORIZATION_FAILURE,
                        sanitizedMessage,
                        false,
                        true,
                        401
                );
            }
            if (lower.contains("uncertain") || lower.contains("recovery") || lower.contains("stale")) {
                return new ExecutionFailureAnalysis(
                        ExecutionFailureClassification.MANUAL_INTERVENTION_REQUIRED,
                        sanitizedMessage,
                        false,
                        true,
                        null
                );
            }
            return new ExecutionFailureAnalysis(
                    ExecutionFailureClassification.CONFIRMED_NON_RETRYABLE_PROVIDER_REJECTION,
                    sanitizedMessage,
                    false,
                    false,
                    null
            );
        }

        if (throwable instanceof ResourceAccessException) {
            // Ambiguous network timeout or connection reset: outcome on remote provider cannot be confirmed
            return new ExecutionFailureAnalysis(
                    ExecutionFailureClassification.MANUAL_INTERVENTION_REQUIRED,
                    "Network timeout or connection failure with destination provider: " + sanitizedMessage,
                    false,
                    true,
                    504
            );
        }

        if (throwable instanceof DataAccessException
                || throwable instanceof OptimisticLockException
                || throwable instanceof PersistenceException) {
            return new ExecutionFailureAnalysis(
                    ExecutionFailureClassification.LOCAL_PERSISTENCE_FAILURE,
                    "Database or persistence error: " + sanitizedMessage,
                    true,
                    false,
                    null
            );
        }

        return new ExecutionFailureAnalysis(
                ExecutionFailureClassification.CONFIRMED_NON_RETRYABLE_PROVIDER_REJECTION,
                sanitizedMessage,
                false,
                false,
                null
        );
    }

    private static ExecutionFailureAnalysis classifyProviderException(ExternalProviderException ex, String message) {
        Integer statusCode = ex.getProviderStatusCode();
        int code = statusCode != null ? statusCode : 500;
        String lowerMsg = message.toLowerCase();

        if (code == 401) {
            return new ExecutionFailureAnalysis(
                    ExecutionFailureClassification.AUTHENTICATION_OR_AUTHORIZATION_FAILURE,
                    message,
                    false,
                    true,
                    401
            );
        }

        if (code == 403) {
            if (lowerMsg.contains("quota")) {
                return new ExecutionFailureAnalysis(
                        ExecutionFailureClassification.CONFIRMED_PROVIDER_OPERATION_FAILURE,
                        message,
                        false,
                        true,
                        403
                );
            }
            if (lowerMsg.contains("permission") || lowerMsg.contains("scope") || lowerMsg.contains("auth")) {
                return new ExecutionFailureAnalysis(
                        ExecutionFailureClassification.AUTHENTICATION_OR_AUTHORIZATION_FAILURE,
                        message,
                        false,
                        true,
                        403
                );
            }
            return new ExecutionFailureAnalysis(
                    ExecutionFailureClassification.CONFIRMED_PROVIDER_OPERATION_FAILURE,
                    message,
                    false,
                    false,
                    403
            );
        }

        if (code == 404) {
            if (lowerMsg.contains("playlist") && !lowerMsg.contains("item")) {
                return new ExecutionFailureAnalysis(
                        ExecutionFailureClassification.INVALID_DESTINATION_RESOURCE,
                        message,
                        false,
                        true,
                        404
                );
            }
            // Track / video not found or item not found
            return new ExecutionFailureAnalysis(
                    ExecutionFailureClassification.CONFIRMED_NON_RETRYABLE_PROVIDER_REJECTION,
                    message,
                    false,
                    false,
                    404
            );
        }

        if (code == 429) {
            return new ExecutionFailureAnalysis(
                    ExecutionFailureClassification.CONFIRMED_RETRYABLE_PROVIDER_REJECTION,
                    message,
                    true,
                    false,
                    429
            );
        }

        if (code == 400) {
            return new ExecutionFailureAnalysis(
                    ExecutionFailureClassification.CONFIRMED_NON_RETRYABLE_PROVIDER_REJECTION,
                    message,
                    false,
                    false,
                    400
            );
        }

        if (code >= 500) {
            return new ExecutionFailureAnalysis(
                    ExecutionFailureClassification.CONFIRMED_RETRYABLE_PROVIDER_REJECTION,
                    message,
                    true,
                    false,
                    code
            );
        }

        return new ExecutionFailureAnalysis(
                ExecutionFailureClassification.CONFIRMED_NON_RETRYABLE_PROVIDER_REJECTION,
                message,
                false,
                false,
                code
        );
    }

    private static String sanitize(String message) {
        if (message == null) {
            return "null";
        }
        return TOKEN_PATTERN.matcher(message).replaceAll("$1[REDACTED]");
    }
}
