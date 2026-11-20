package com.spotifyyoutube.migrator.common.logging;

import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Thread-safe logging context and MDC lifecycle manager (Phase 18F).
 * <p>
 * Provides scoped {@link ContextScope} blocks that restore the previous thread MDC state
 * upon completion, preventing thread-local contamination across pooled servlet and worker threads.
 */
public final class LoggingContextHolder {

    /**
     * Scoped context closeable that restores MDC on close without throwing checked exceptions.
     */
    @FunctionalInterface
    public interface ContextScope extends AutoCloseable {
        @Override
        void close();
    }

    private LoggingContextHolder() {
        // utility class
    }

    /**
     * Executes a block of code with the provided key-value pairs added to MDC.
     * Restores previous context upon closure.
     *
     * @param context map of key-value pairs to set in MDC
     * @return a {@link ContextScope} that restores previous MDC upon close
     */
    public static ContextScope withContext(Map<String, String> context) {
        if (context == null || context.isEmpty()) {
            return () -> { };
        }
        Map<String, String> previousContext = MDC.getCopyOfContextMap();
        Map<String, String> newContext = (previousContext != null)
                ? new HashMap<>(previousContext)
                : new HashMap<>();
        newContext.putAll(context);
        MDC.setContextMap(newContext);

        return () -> {
            if (previousContext != null && !previousContext.isEmpty()) {
                MDC.setContextMap(previousContext);
            } else {
                MDC.clear();
            }
        };
    }

    /**
     * Executes a block of code with a single property added to MDC.
     * Restores previous context upon closure.
     *
     * @param key property key
     * @param value property value
     * @return a {@link ContextScope} that restores previous MDC upon close
     */
    public static ContextScope withProperty(String key, String value) {
        if (key == null) {
            return () -> { };
        }
        String previousValue = MDC.get(key);
        if (value != null) {
            MDC.put(key, value);
        } else {
            MDC.remove(key);
        }

        return () -> {
            if (previousValue != null) {
                MDC.put(key, previousValue);
            } else {
                MDC.remove(key);
            }
        };
    }

    /**
     * Creates an execution logging context scope for interactive or manual migration tasks.
     * Preserves originating request correlation ID if present, or generates a dedicated execution correlation ID.
     *
     * @param executionId durable execution ID
     * @param userId owner user ID
     * @param operation operation name (e.g. "migration_execution")
     * @param mode execution mode ("manual" or "automated")
     * @return a {@link ContextScope} restoring previous MDC upon close
     */
    public static ContextScope forExecution(UUID executionId, UUID userId, String operation, String mode) {
        Map<String, String> context = new HashMap<>();
        if (executionId != null) {
            context.put(LoggingConstants.MDC_MIGRATION_EXECUTION_ID, executionId.toString());
        }
        if (userId != null) {
            context.put(LoggingConstants.MDC_USER_ID, userId.toString());
        }
        if (operation != null) {
            context.put(LoggingConstants.MDC_OPERATION, operation);
        }
        if (mode != null) {
            context.put(LoggingConstants.MDC_MODE, mode);
        }

        // If no correlation ID was propagated, establish an execution correlation ID
        if (MDC.get(LoggingConstants.MDC_CORRELATION_ID) == null) {
            String corrId = "exec-" + (executionId != null ? executionId : UUID.randomUUID());
            context.put(LoggingConstants.MDC_CORRELATION_ID, corrId);
        }

        return withContext(context);
    }

    /**
     * Creates an execution logging context scope for scheduled background migrations.
     * Strictly avoids fabricating fake HTTP request IDs while providing deterministic correlation.
     *
     * @param executionId durable execution ID
     * @param userId owner user ID
     * @param leaseGeneration acquired fencing generation
     * @return a {@link ContextScope} restoring previous MDC upon close
     */
    public static ContextScope forScheduledExecution(UUID executionId, UUID userId, long leaseGeneration) {
        Map<String, String> context = new HashMap<>();
        if (executionId != null) {
            context.put(LoggingConstants.MDC_MIGRATION_EXECUTION_ID, executionId.toString());
        }
        if (userId != null) {
            context.put(LoggingConstants.MDC_USER_ID, userId.toString());
        }
        context.put(LoggingConstants.MDC_LEASE_GENERATION, String.valueOf(leaseGeneration));
        context.put(LoggingConstants.MDC_OPERATION, "scheduled_migration_execution");
        context.put(LoggingConstants.MDC_MODE, "automated");

        // Scheduled execution has no HTTP request; establish a distinct scheduled correlation identifier
        String corrId = "sched-" + leaseGeneration + "-" + (executionId != null ? executionId : UUID.randomUUID());
        context.put(LoggingConstants.MDC_CORRELATION_ID, corrId);

        // Ensure no stale HTTP request ID is attached to background execution
        Map<String, String> previousContext = MDC.getCopyOfContextMap();
        Map<String, String> newContext = (previousContext != null)
                ? new HashMap<>(previousContext)
                : new HashMap<>();
        newContext.remove(LoggingConstants.MDC_REQUEST_ID);
        newContext.putAll(context);
        MDC.setContextMap(newContext);

        return () -> {
            if (previousContext != null && !previousContext.isEmpty()) {
                MDC.setContextMap(previousContext);
            } else {
                MDC.clear();
            }
        };
    }

    /**
     * Clears all MDC properties on the current thread.
     */
    public static void clear() {
        MDC.clear();
    }
}
