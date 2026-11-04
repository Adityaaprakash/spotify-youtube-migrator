package com.spotifyyoutube.migrator.migration.application.execution.failure;

import com.spotifyyoutube.migrator.common.exception.ExternalProviderException;
import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceException;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.util.regex.Pattern;

/**
 * Classifies exceptions and failure outcomes encountered during playlist migration execution,
 * recovery, and rollback operations (Phase 11F).
 * <p>
 * Implements deterministic classification rules without conflating ambiguous mutation outcomes with confirmed failures.
 * Network timeouts (ResourceAccessException) and HTTP 500/503 errors during remote mutations are strictly evaluated
 * for ambiguity and classified as {@link ExecutionFailureClassification#MANUAL_INTERVENTION_REQUIRED} (UNCERTAIN),
 * ensuring they cannot be blindly retried and preserving Phase 11G for dedicated reconciliation.
 */
@Component
public class ExecutionFailureClassifier {

    private static final Pattern TOKEN_PATTERN = Pattern.compile(
            "(?i)(bearer\\s+|(?:access_token|refresh_token|token|api_key|apikey|key|client_secret|secret|password)=)[^\\s&\"',;]+"
    );

    public ExecutionFailureClassifier() {
    }

    /**
     * Classifies a throwable encountered during a specific operation context.
     *
     * @param throwable error to classify
     * @param operationContext descriptor of the operation (e.g. "destination_insertion", "playlist_item_remove", "read")
     * @return structured failure analysis
     */
    public ExecutionFailureAnalysis classify(Throwable throwable, String operationContext) {
        return classifyInternal(throwable, operationContext);
    }

    /**
     * Classifies a throwable in a general (non-specified) operation context.
     *
     * @param throwable error to classify
     * @return structured failure analysis
     */
    public ExecutionFailureAnalysis classify(Throwable throwable) {
        return classifyInternal(throwable, "general");
    }

    /**
     * Static utility convenience for classification.
     */
    public static ExecutionFailureAnalysis classifyFailure(Throwable throwable, String operationContext) {
        return new ExecutionFailureClassifier().classify(throwable, operationContext);
    }

    private static ExecutionFailureAnalysis classifyInternal(Throwable throwable, String operationContext) {
        if (throwable == null) {
            return new ExecutionFailureAnalysis(
                    ExecutionFailureClassification.MANUAL_INTERVENTION_REQUIRED,
                    "Unknown failure (null exception)",
                    false,
                    true,
                    null
            );
        }

        boolean isMutation = isMutationOperation(operationContext);
        String rawMessage = throwable.getMessage() != null ? throwable.getMessage() : throwable.getClass().getSimpleName();
        String sanitizedMessage = sanitize(rawMessage);

        // 1. ExternalProviderException
        if (throwable instanceof ExternalProviderException providerEx) {
            return classifyProviderException(providerEx, sanitizedMessage, isMutation);
        }

        // 2. Spring Web Client Server Error (5xx)
        if (throwable instanceof HttpServerErrorException serverEx) {
            int code = serverEx.getStatusCode().value();
            if (isMutation) {
                // Ambiguous mutation outcome: server failed during write; state on provider is uncertain
                return new ExecutionFailureAnalysis(
                        ExecutionFailureClassification.MANUAL_INTERVENTION_REQUIRED,
                        "Server error HTTP " + code + " during remote mutation; outcome is ambiguous: " + sanitizedMessage,
                        false,
                        true,
                        code
                );
            }
            // Idempotent read retryable
            return new ExecutionFailureAnalysis(
                    ExecutionFailureClassification.CONFIRMED_RETRYABLE_PROVIDER_REJECTION,
                    sanitizedMessage,
                    true,
                    false,
                    code
            );
        }

        // 3. Spring Web Client Error (4xx)
        if (throwable instanceof HttpClientErrorException clientEx) {
            int code = clientEx.getStatusCode().value();
            return classifyClientStatusCode(code, sanitizedMessage);
        }

        // 4. ResourceNotFoundException (404)
        if (throwable instanceof ResourceNotFoundException) {
            return new ExecutionFailureAnalysis(
                    ExecutionFailureClassification.INVALID_DESTINATION_RESOURCE,
                    sanitizedMessage,
                    false,
                    true,
                    404
            );
        }

        // 5. ResourceAccessException (Network I/O timeout or socket reset)
        if (throwable instanceof ResourceAccessException) {
            if (isMutation) {
                // Ambiguous mutation outcome: socket dropped during write; outcome on remote provider cannot be confirmed
                return new ExecutionFailureAnalysis(
                        ExecutionFailureClassification.MANUAL_INTERVENTION_REQUIRED,
                        "Network timeout during remote mutation; remote outcome is uncertain: " + sanitizedMessage,
                        false,
                        true,
                        504
                );
            }
            // Safe to retry on read operations
            return new ExecutionFailureAnalysis(
                    ExecutionFailureClassification.CONFIRMED_RETRYABLE_PROVIDER_REJECTION,
                    "Network timeout during read operation: " + sanitizedMessage,
                    true,
                    false,
                    504
            );
        }

        // 6. InvalidStateException
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
            return new ExecutionFailureAnalysis(
                    ExecutionFailureClassification.MANUAL_INTERVENTION_REQUIRED,
                    sanitizedMessage,
                    false,
                    true,
                    null
            );
        }

        // 7. Local Persistence Failures
        if (throwable instanceof DataAccessException
                || throwable instanceof OptimisticLockException
                || throwable instanceof PersistenceException
                || throwable instanceof TransactionException) {
            boolean isIntegrity = throwable.getClass().getSimpleName().contains("DataIntegrity")
                    || throwable.getClass().getSimpleName().contains("ConstraintViolation");
            return new ExecutionFailureAnalysis(
                    ExecutionFailureClassification.LOCAL_PERSISTENCE_FAILURE,
                    "Database or persistence error: " + sanitizedMessage,
                    !isIntegrity,
                    isIntegrity,
                    null
            );
        }

        // 8. Confirmed non-retryable arguments
        if (throwable instanceof IllegalArgumentException) {
            return new ExecutionFailureAnalysis(
                    ExecutionFailureClassification.CONFIRMED_NON_RETRYABLE_PROVIDER_REJECTION,
                    sanitizedMessage,
                    false,
                    false,
                    400
            );
        }

        return new ExecutionFailureAnalysis(
                ExecutionFailureClassification.MANUAL_INTERVENTION_REQUIRED,
                sanitizedMessage,
                false,
                true,
                null
        );
    }

    private static ExecutionFailureAnalysis classifyProviderException(
            ExternalProviderException ex,
            String message,
            boolean isMutation
    ) {
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
            return new ExecutionFailureAnalysis(
                    ExecutionFailureClassification.INVALID_DESTINATION_RESOURCE,
                    message,
                    false,
                    true,
                    404
            );
        }

        if (code == 429) {
            // Rate limit rejection occurs prior to mutation processing; confirmed retryable
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
            if (isMutation) {
                // Ambiguous mutation outcome: 5xx during mutation cannot be confirmed as not committed
                return new ExecutionFailureAnalysis(
                        ExecutionFailureClassification.MANUAL_INTERVENTION_REQUIRED,
                        "Server error HTTP " + code + " during remote mutation; outcome is ambiguous: " + message,
                        false,
                        true,
                        code
                );
            }
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

    private static ExecutionFailureAnalysis classifyClientStatusCode(int code, String message) {
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
            return new ExecutionFailureAnalysis(
                    ExecutionFailureClassification.AUTHENTICATION_OR_AUTHORIZATION_FAILURE,
                    message,
                    false,
                    true,
                    403
            );
        }
        if (code == 404) {
            return new ExecutionFailureAnalysis(
                    ExecutionFailureClassification.INVALID_DESTINATION_RESOURCE,
                    message,
                    false,
                    true,
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
        return new ExecutionFailureAnalysis(
                ExecutionFailureClassification.CONFIRMED_NON_RETRYABLE_PROVIDER_REJECTION,
                message,
                false,
                false,
                code
        );
    }

    private static boolean isMutationOperation(String operationContext) {
        if (operationContext == null) {
            return false;
        }
        String lower = operationContext.toLowerCase();
        return lower.contains("insert")
                || lower.contains("remove")
                || lower.contains("delete")
                || lower.contains("mutation")
                || lower.contains("rollback");
    }

    private static String sanitize(String message) {
        if (message == null) {
            return "null";
        }
        return TOKEN_PATTERN.matcher(message).replaceAll("$1[REDACTED]");
    }
}
