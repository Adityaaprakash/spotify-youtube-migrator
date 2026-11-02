package com.spotifyyoutube.migrator.migration.application.execution.failure;

import com.spotifyyoutube.migrator.common.exception.ExternalProviderException;
import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Phase 11F: ExecutionFailureClassifier Test")
class ExecutionFailureClassifierTest {

    private ExecutionFailureClassifier classifier;

    @BeforeEach
    void setUp() {
        classifier = new ExecutionFailureClassifier();
    }

    @Nested
    @DisplayName("1. Confirmed Retryable Provider Rejections")
    class RetryableRejections {

        @Test
        @DisplayName("Classifies HTTP 429 Too Many Requests as RETRYABLE_PROVIDER_REJECTION")
        void classifies429AsRetryable() {
            var exception = HttpClientErrorException.create(
                    HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded", null, null, null);
            ExecutionFailureAnalysis analysis = classifier.classify(exception, "destination_insertion");

            assertThat(analysis.classification())
                    .isEqualTo(ExecutionFailureClassification.CONFIRMED_RETRYABLE_PROVIDER_REJECTION);
            assertThat(analysis.retryable()).isTrue();
            assertThat(analysis.requiresManualIntervention()).isFalse();
            assertThat(analysis.getProviderStatusCode()).contains(429);
        }

        @Test
        @DisplayName("Classifies HTTP 503 Service Unavailable during read operation as RETRYABLE_PROVIDER_REJECTION")
        void classifies503DuringReadAsRetryable() {
            var exception = HttpServerErrorException.create(
                    HttpStatus.SERVICE_UNAVAILABLE, "Backend unavailable", null, null, null);
            ExecutionFailureAnalysis analysis = classifier.classify(exception, "read_playlist");

            assertThat(analysis.classification())
                    .isEqualTo(ExecutionFailureClassification.CONFIRMED_RETRYABLE_PROVIDER_REJECTION);
            assertThat(analysis.retryable()).isTrue();
            assertThat(analysis.requiresManualIntervention()).isFalse();
        }

        @Test
        @DisplayName("Classifies HTTP 503 Service Unavailable during remote mutation as MANUAL_INTERVENTION_REQUIRED (ambiguous)")
        void classifies503DuringMutationAsManualIntervention() {
            var exception = HttpServerErrorException.create(
                    HttpStatus.SERVICE_UNAVAILABLE, "Backend unavailable", null, null, null);
            ExecutionFailureAnalysis analysis = classifier.classify(exception, "destination_insertion");

            assertThat(analysis.classification())
                    .isEqualTo(ExecutionFailureClassification.MANUAL_INTERVENTION_REQUIRED);
            assertThat(analysis.retryable()).isFalse();
            assertThat(analysis.requiresManualIntervention()).isTrue();
        }

        @Test
        @DisplayName("Classifies network I/O timeout during read operation as RETRYABLE_PROVIDER_REJECTION")
        void classifiesNetworkTimeoutDuringReadAsRetryable() {
            var exception = new ResourceAccessException("Connection timed out", new IOException("Read timed out"));
            ExecutionFailureAnalysis analysis = classifier.classify(exception, "read_playlist");

            assertThat(analysis.classification())
                    .isEqualTo(ExecutionFailureClassification.CONFIRMED_RETRYABLE_PROVIDER_REJECTION);
            assertThat(analysis.retryable()).isTrue();
            assertThat(analysis.requiresManualIntervention()).isFalse();
        }

        @Test
        @DisplayName("Classifies network I/O timeout during remote mutation as MANUAL_INTERVENTION_REQUIRED (ambiguous)")
        void classifiesNetworkTimeoutDuringMutationAsManualIntervention() {
            var exception = new ResourceAccessException("Connection timed out", new IOException("Read timed out"));
            ExecutionFailureAnalysis analysis = classifier.classify(exception, "playlist_item_remove");

            assertThat(analysis.classification())
                    .isEqualTo(ExecutionFailureClassification.MANUAL_INTERVENTION_REQUIRED);
            assertThat(analysis.retryable()).isFalse();
            assertThat(analysis.requiresManualIntervention()).isTrue();
        }

        @Test
        @DisplayName("Classifies retryable ExternalProviderException during read as RETRYABLE_PROVIDER_REJECTION")
        void classifiesRetryableExternalProviderExceptionDuringRead() {
            var exception = new ExternalProviderException("YouTube API quota temporary stall", 429);
            ExecutionFailureAnalysis analysis = classifier.classify(exception, "read_playlist");

            assertThat(analysis.classification())
                    .isEqualTo(ExecutionFailureClassification.CONFIRMED_RETRYABLE_PROVIDER_REJECTION);
            assertThat(analysis.retryable()).isTrue();
        }
    }

    @Nested
    @DisplayName("2. Confirmed Non-Retryable Provider Rejections")
    class NonRetryableRejections {

        @Test
        @DisplayName("Classifies HTTP 400 Bad Request with quotaExceeded as NON_RETRYABLE_PROVIDER_REJECTION")
        void classifies400QuotaExceededAsNonRetryable() {
            var exception = HttpClientErrorException.create(
                    HttpStatus.BAD_REQUEST, "Playlist item limit exceeded", null, null, null);
            ExecutionFailureAnalysis analysis = classifier.classify(exception, "destination_insertion");

            assertThat(analysis.classification())
                    .isEqualTo(ExecutionFailureClassification.CONFIRMED_NON_RETRYABLE_PROVIDER_REJECTION);
            assertThat(analysis.retryable()).isFalse();
        }

        @Test
        @DisplayName("Classifies non-retryable ExternalProviderException as NON_RETRYABLE_PROVIDER_REJECTION")
        void classifiesNonRetryableExternalProviderException() {
            var exception = new ExternalProviderException("YouTube playlist contains maximum allowed videos", 400);
            ExecutionFailureAnalysis analysis = classifier.classify(exception, "insert_track");

            assertThat(analysis.classification())
                    .isEqualTo(ExecutionFailureClassification.CONFIRMED_NON_RETRYABLE_PROVIDER_REJECTION);
            assertThat(analysis.retryable()).isFalse();
        }

        @Test
        @DisplayName("Classifies IllegalArgumentException from provider as NON_RETRYABLE_PROVIDER_REJECTION")
        void classifiesIllegalArgumentExceptionAsNonRetryable() {
            var exception = new IllegalArgumentException("Video ID cannot be empty or blank");
            ExecutionFailureAnalysis analysis = classifier.classify(exception, "track_insert");

            assertThat(analysis.classification())
                    .isEqualTo(ExecutionFailureClassification.CONFIRMED_NON_RETRYABLE_PROVIDER_REJECTION);
            assertThat(analysis.retryable()).isFalse();
        }
    }

    @Nested
    @DisplayName("3. Authentication or Authorization Failures")
    class AuthenticationFailures {

        @Test
        @DisplayName("Classifies HTTP 401 Unauthorized as AUTHENTICATION_OR_AUTHORIZATION_FAILURE")
        void classifies401AsAuthFailure() {
            var exception = HttpClientErrorException.create(
                    HttpStatus.UNAUTHORIZED, "Invalid access token", null, null, null);
            ExecutionFailureAnalysis analysis = classifier.classify(exception, "create_playlist");

            assertThat(analysis.classification())
                    .isEqualTo(ExecutionFailureClassification.AUTHENTICATION_OR_AUTHORIZATION_FAILURE);
            assertThat(analysis.retryable()).isFalse();
            assertThat(analysis.requiresManualIntervention()).isTrue();
        }

        @Test
        @DisplayName("Classifies HTTP 403 Forbidden as AUTHENTICATION_OR_AUTHORIZATION_FAILURE")
        void classifies403AsAuthFailure() {
            var exception = HttpClientErrorException.create(
                    HttpStatus.FORBIDDEN, "Insufficient permissions for playlist scope", null, null, null);
            ExecutionFailureAnalysis analysis = classifier.classify(exception, "insert_track");

            assertThat(analysis.classification())
                    .isEqualTo(ExecutionFailureClassification.AUTHENTICATION_OR_AUTHORIZATION_FAILURE);
            assertThat(analysis.requiresManualIntervention()).isTrue();
        }
    }

    @Nested
    @DisplayName("4. Invalid Destination Resource")
    class InvalidDestinationResource {

        @Test
        @DisplayName("Classifies HTTP 404 Not Found as INVALID_DESTINATION_RESOURCE")
        void classifies404AsInvalidResource() {
            var exception = HttpClientErrorException.create(
                    HttpStatus.NOT_FOUND, "Playlist does not exist", null, null, null);
            ExecutionFailureAnalysis analysis = classifier.classify(exception, "destination_insertion");

            assertThat(analysis.classification())
                    .isEqualTo(ExecutionFailureClassification.INVALID_DESTINATION_RESOURCE);
            assertThat(analysis.retryable()).isFalse();
            assertThat(analysis.requiresManualIntervention()).isTrue();
        }

        @Test
        @DisplayName("Classifies ResourceNotFoundException as INVALID_DESTINATION_RESOURCE")
        void classifiesResourceNotFoundException() {
            var exception = new ResourceNotFoundException("Target playlist not found on YouTube");
            ExecutionFailureAnalysis analysis = classifier.classify(exception, "verify_playlist");

            assertThat(analysis.classification())
                    .isEqualTo(ExecutionFailureClassification.INVALID_DESTINATION_RESOURCE);
            assertThat(analysis.requiresManualIntervention()).isTrue();
        }
    }

    @Nested
    @DisplayName("5. Local Persistence Failures")
    class LocalPersistenceFailures {

        @Test
        @DisplayName("Classifies OptimisticLockingFailureException as LOCAL_PERSISTENCE_FAILURE")
        void classifiesOptimisticLockAsPersistenceFailure() {
            var exception = new OptimisticLockingFailureException("Version mismatch on execution");
            ExecutionFailureAnalysis analysis = classifier.classify(exception, "commit_track_record");

            assertThat(analysis.classification())
                    .isEqualTo(ExecutionFailureClassification.LOCAL_PERSISTENCE_FAILURE);
            assertThat(analysis.retryable()).isTrue();
            assertThat(analysis.requiresManualIntervention()).isFalse();
        }

        @Test
        @DisplayName("Classifies CannotAcquireLockException as LOCAL_PERSISTENCE_FAILURE")
        void classifiesCannotAcquireLockAsPersistenceFailure() {
            var exception = new CannotAcquireLockException("Database lock acquisition timeout");
            ExecutionFailureAnalysis analysis = classifier.classify(exception, "acquire_execution_lock");

            assertThat(analysis.classification())
                    .isEqualTo(ExecutionFailureClassification.LOCAL_PERSISTENCE_FAILURE);
            assertThat(analysis.retryable()).isTrue();
        }

        @Test
        @DisplayName("Classifies DataIntegrityViolationException as LOCAL_PERSISTENCE_FAILURE requiring manual intervention")
        void classifiesDataIntegrityViolation() {
            var exception = new DataIntegrityViolationException("Unique constraint violation on execution_track_records");
            ExecutionFailureAnalysis analysis = classifier.classify(exception, "insert_track_record");

            assertThat(analysis.classification())
                    .isEqualTo(ExecutionFailureClassification.LOCAL_PERSISTENCE_FAILURE);
            assertThat(analysis.retryable()).isFalse();
            assertThat(analysis.requiresManualIntervention()).isTrue();
        }

        @Test
        @DisplayName("Classifies TransactionSystemException as LOCAL_PERSISTENCE_FAILURE")
        void classifiesTransactionException() {
            var exception = new TransactionSystemException("Could not commit JPA transaction");
            ExecutionFailureAnalysis analysis = classifier.classify(exception, "transaction_commit");

            assertThat(analysis.classification())
                    .isEqualTo(ExecutionFailureClassification.LOCAL_PERSISTENCE_FAILURE);
        }
    }

    @Nested
    @DisplayName("6. Manual Intervention & Confirmed Operation Failure")
    class ManualInterventionFailures {

        @Test
        @DisplayName("Classifies InvalidStateException as MANUAL_INTERVENTION_REQUIRED")
        void classifiesInvalidStateAsManualIntervention() {
            var exception = new InvalidStateException("Execution contains unresolved UNCERTAIN tracks");
            ExecutionFailureAnalysis analysis = classifier.classify(exception, "execution_recovery");

            assertThat(analysis.classification())
                    .isEqualTo(ExecutionFailureClassification.MANUAL_INTERVENTION_REQUIRED);
            assertThat(analysis.requiresManualIntervention()).isTrue();
            assertThat(analysis.retryable()).isFalse();
        }

        @Test
        @DisplayName("Classifies null throwable gracefully as MANUAL_INTERVENTION_REQUIRED")
        void classifiesNullThrowable() {
            ExecutionFailureAnalysis analysis = classifier.classify(null, "unknown_operation");

            assertThat(analysis.classification())
                    .isEqualTo(ExecutionFailureClassification.MANUAL_INTERVENTION_REQUIRED);
            assertThat(analysis.reason()).isEqualTo("Unknown failure (null exception)");
        }
    }
}
