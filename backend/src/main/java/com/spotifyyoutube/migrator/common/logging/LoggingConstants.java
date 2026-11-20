package com.spotifyyoutube.migrator.common.logging;

import java.util.regex.Pattern;

/**
 * Centralized constants and conventions for structured logging, MDC context propagation,
 * and HTTP request correlation (Phase 18F).
 */
public final class LoggingConstants {

    // HTTP Header Conventions
    public static final String HEADER_REQUEST_ID = "X-Request-ID";
    public static final String HEADER_CORRELATION_ID = "X-Correlation-ID";

    // Standard MDC Context Property Keys
    public static final String MDC_REQUEST_ID = "requestId";
    public static final String MDC_CORRELATION_ID = "correlationId";
    public static final String MDC_APPLICATION = "application";
    public static final String MDC_ENVIRONMENT = "environment";
    public static final String MDC_USER_ID = "userId";
    public static final String MDC_MIGRATION_EXECUTION_ID = "migrationExecutionId";
    public static final String MDC_OPERATION = "operation";
    public static final String MDC_PROVIDER = "provider";
    public static final String MDC_HTTP_METHOD = "httpMethod";
    public static final String MDC_HTTP_ROUTE = "httpRoute";
    public static final String MDC_HTTP_STATUS = "httpStatus";
    public static final String MDC_DURATION_MS = "durationMs";
    public static final String MDC_ERROR_CATEGORY = "errorCategory";
    public static final String MDC_MODE = "mode";
    public static final String MDC_LEASE_GENERATION = "leaseGeneration";

    // Correlation ID Validation Rules
    public static final int MAX_CORRELATION_ID_LENGTH = 64;
    public static final Pattern CORRELATION_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    // Default Application Identifiers
    public static final String DEFAULT_APPLICATION_NAME = "migrator";
    public static final String DEFAULT_ENVIRONMENT = "local";

    private LoggingConstants() {
        // utility class
    }
}
