package com.spotifyyoutube.migrator.migration.application.execution.failure;

/**
 * Failure classification categories for migration execution, recovery, and rollback (Phase 11F).
 * <p>
 * Distinguishes confirmed provider rejections, retryable provider states, authentication failures,
 * invalid resources, local persistence failures, and ambiguous outcomes requiring manual intervention.
 */
public enum ExecutionFailureClassification {

    /**
     * Confirmed provider rejection that cannot succeed without modifying request data
     * (e.g. video deleted, private, regional restriction, malformed video ID).
     */
    CONFIRMED_NON_RETRYABLE_PROVIDER_REJECTION,

    /**
     * Confirmed provider rejection that may succeed upon subsequent retry
     * (e.g. HTTP 429 rate limit with Retry-After header, temporary upstream 503 unavailable).
     */
    CONFIRMED_RETRYABLE_PROVIDER_REJECTION,

    /**
     * Authentication or authorization failure requiring credential refresh or re-login
     * (e.g. HTTP 401 invalid/expired token, 403 insufficient scopes, missing Google OAuth connection).
     */
    AUTHENTICATION_OR_AUTHORIZATION_FAILURE,

    /**
     * Destination playlist or track reference is invalid or inaccessible
     * (e.g. destination playlist deleted or not found 404).
     */
    INVALID_DESTINATION_RESOURCE,

    /**
     * Local database transaction, optimistic locking, or persistence failure.
     */
    LOCAL_PERSISTENCE_FAILURE,

    /**
     * Provider operation failure with a confirmed outcome
     * (e.g. daily quota exceeded 403).
     */
    CONFIRMED_PROVIDER_OPERATION_FAILURE,

    /**
     * Ambiguous or interrupted state requiring manual intervention
     * (e.g. socket timeout, network reset, crash recovery with uncertain outcome).
     */
    MANUAL_INTERVENTION_REQUIRED
}
