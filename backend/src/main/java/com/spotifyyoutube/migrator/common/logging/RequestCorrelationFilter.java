package com.spotifyyoutube.migrator.common.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Servlet filter for incoming HTTP request correlation and MDC lifecycle management (Phase 18F).
 * <p>
 * Inspects incoming {@code X-Request-ID} (or {@code X-Correlation-ID}) headers, strictly validates
 * against injection attacks and unbounded lengths, establishes structured MDC context, echoes the
 * correlation ID on the HTTP response, and guarantees leak-proof MDC cleanup across thread-pool reuses.
 */
public class RequestCorrelationFilter extends OncePerRequestFilter {

    private final String applicationName;
    private final String environment;

    public RequestCorrelationFilter() {
        this(LoggingConstants.DEFAULT_APPLICATION_NAME, LoggingConstants.DEFAULT_ENVIRONMENT);
    }

    public RequestCorrelationFilter(String applicationName, String environment) {
        this.applicationName = (applicationName != null && !applicationName.isBlank())
                ? applicationName
                : LoggingConstants.DEFAULT_APPLICATION_NAME;
        this.environment = (environment != null && !environment.isBlank())
                ? environment
                : LoggingConstants.DEFAULT_ENVIRONMENT;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String incomingId = extractInboundCorrelationId(request);
        String correlationId = validateOrGenerate(incomingId);

        // Echo the correlation ID in HTTP response headers
        response.setHeader(LoggingConstants.HEADER_REQUEST_ID, correlationId);
        response.setHeader(LoggingConstants.HEADER_CORRELATION_ID, correlationId);

        Map<String, String> previousContext = MDC.getCopyOfContextMap();
        Map<String, String> requestContext = (previousContext != null)
                ? new HashMap<>(previousContext)
                : new HashMap<>();

        requestContext.put(LoggingConstants.MDC_REQUEST_ID, correlationId);
        requestContext.put(LoggingConstants.MDC_CORRELATION_ID, correlationId);
        requestContext.put(LoggingConstants.MDC_APPLICATION, applicationName);
        requestContext.put(LoggingConstants.MDC_ENVIRONMENT, environment);
        if (request.getMethod() != null) {
            requestContext.put(LoggingConstants.MDC_HTTP_METHOD, request.getMethod());
        }
        if (request.getRequestURI() != null) {
            requestContext.put(LoggingConstants.MDC_HTTP_ROUTE, request.getRequestURI());
        }

        MDC.setContextMap(requestContext);

        try {
            filterChain.doFilter(request, response);
        } finally {
            if (previousContext != null && !previousContext.isEmpty()) {
                MDC.setContextMap(previousContext);
            } else {
                MDC.clear();
            }
        }
    }

    /**
     * Extracts inbound correlation identifier from supported headers with priority ordering.
     */
    private String extractInboundCorrelationId(HttpServletRequest request) {
        String requestId = request.getHeader(LoggingConstants.HEADER_REQUEST_ID);
        if (requestId != null && !requestId.isBlank()) {
            return requestId.trim();
        }
        String correlationId = request.getHeader(LoggingConstants.HEADER_CORRELATION_ID);
        if (correlationId != null && !correlationId.isBlank()) {
            return correlationId.trim();
        }
        return null;
    }

    /**
     * Validates that inbound ID matches the safe format: alphanumeric, hyphens, underscores,
     * max 64 characters. Discards malformed, injected, or blank IDs in favor of a fresh UUIDv4.
     *
     * @param rawId raw candidate ID from HTTP header
     * @return validated ID or new UUIDv4
     */
    public String validateOrGenerate(String rawId) {
        if (rawId == null || rawId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        if (rawId.length() > LoggingConstants.MAX_CORRELATION_ID_LENGTH) {
            return UUID.randomUUID().toString();
        }
        if (!LoggingConstants.CORRELATION_ID_PATTERN.matcher(rawId).matches()) {
            return UUID.randomUUID().toString();
        }
        return rawId;
    }
}
