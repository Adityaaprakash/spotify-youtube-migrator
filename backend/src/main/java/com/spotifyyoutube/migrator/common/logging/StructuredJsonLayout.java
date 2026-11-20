package com.spotifyyoutube.migrator.common.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.LayoutBase;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spotifyyoutube.migrator.common.security.logging.SensitiveDataSanitizer;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Logback layout formatting logging events as single-line JSON records (JSON Lines / NDJSON) (Phase 18F).
 * <p>
 * Supports structured searching across modern observability collectors (CloudWatch, Datadog, Loki).
 * Enforces mandatory credential redaction via {@link SensitiveDataSanitizer} across log messages,
 * MDC context properties, and exception stack traces.
 */
public class StructuredJsonLayout extends LayoutBase<ILoggingEvent> {

    private String applicationName = LoggingConstants.DEFAULT_APPLICATION_NAME;
    private String environment = LoggingConstants.DEFAULT_ENVIRONMENT;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public String getApplicationName() {
        return applicationName;
    }

    public void setApplicationName(String applicationName) {
        this.applicationName = applicationName;
    }

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    @Override
    public String doLayout(ILoggingEvent event) {
        if (event == null) {
            return "";
        }

        Map<String, Object> root = new LinkedHashMap<>();

        // Standard Top-Level Telemetry Fields
        root.put("timestamp", Instant.ofEpochMilli(event.getTimeStamp()).toString());
        root.put("level", event.getLevel().toString());
        root.put("logger", event.getLoggerName());
        root.put("thread", event.getThreadName());
        root.put("service", applicationName);
        root.put("environment", environment);

        // Sanitized Message (neutralizes credentials and tokens)
        String rawMessage = event.getFormattedMessage();
        root.put("message", SensitiveDataSanitizer.sanitize(rawMessage));

        // MDC Context Processing
        Map<String, String> mdc = event.getMDCPropertyMap();
        Map<String, Object> context = new LinkedHashMap<>();

        if (mdc != null && !mdc.isEmpty()) {
            if (mdc.containsKey(LoggingConstants.MDC_REQUEST_ID)) {
                root.put("requestId", mdc.get(LoggingConstants.MDC_REQUEST_ID));
            }
            if (mdc.containsKey(LoggingConstants.MDC_CORRELATION_ID)) {
                root.put("correlationId", mdc.get(LoggingConstants.MDC_CORRELATION_ID));
            }

            for (Map.Entry<String, String> entry : mdc.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                if (key == null || value == null) {
                    continue;
                }
                // Standard top-level fields are already placed or handled
                if (LoggingConstants.MDC_REQUEST_ID.equals(key)
                        || LoggingConstants.MDC_CORRELATION_ID.equals(key)
                        || LoggingConstants.MDC_APPLICATION.equals(key)
                        || LoggingConstants.MDC_ENVIRONMENT.equals(key)) {
                    continue;
                }
                context.put(key, SensitiveDataSanitizer.sanitize(value));
            }
        }

        if (!context.isEmpty()) {
            root.put("context", context);
        }

        // Exception & Stack Trace Handling
        IThrowableProxy throwableProxy = event.getThrowableProxy();
        if (throwableProxy != null) {
            root.put("exceptionClass", throwableProxy.getClassName());
            String exceptionMessage = throwableProxy.getMessage();
            if (exceptionMessage != null) {
                root.put("exceptionMessage", SensitiveDataSanitizer.sanitize(exceptionMessage));
            }
            String stackTrace = ThrowableProxyUtil.asString(throwableProxy);
            if (stackTrace != null) {
                root.put("stackTrace", SensitiveDataSanitizer.sanitize(stackTrace));
            }
        }

        try {
            return objectMapper.writeValueAsString(root) + "\n";
        } catch (JsonProcessingException e) {
            // Failsafe fallback in the impossible event of serialization failure
            return "{\"timestamp\":\"" + Instant.ofEpochMilli(event.getTimeStamp()).toString()
                    + "\",\"level\":\"" + event.getLevel()
                    + "\",\"message\":\"" + SensitiveDataSanitizer.sanitize(rawMessage)
                    + "\",\"error\":\"JSON serialization failed\"}\n";
        }
    }
}
