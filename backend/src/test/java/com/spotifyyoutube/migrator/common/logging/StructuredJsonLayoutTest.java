package com.spotifyyoutube.migrator.common.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@DisplayName("StructuredJsonLayout Tests (Phase 18F)")
class StructuredJsonLayoutTest {

    private StructuredJsonLayout layout;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        layout = new StructuredJsonLayout();
        layout.setApplicationName("migrator-test");
        layout.setEnvironment("test-env");
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("Produces valid single-line JSON ending with newline")
    void producesValidJsonLine() throws Exception {
        ILoggingEvent event = createMockEvent(
                Level.INFO,
                "com.spotifyyoutube.migrator.service.TestService",
                "Simple informational message",
                Map.of()
        );

        String jsonLine = layout.doLayout(event);

        assertThat(jsonLine).endsWith("\n");
        JsonNode root = objectMapper.readTree(jsonLine);
        assertThat(root.isObject()).isTrue();
    }

    @Test
    @DisplayName("Outputs all standard top-level telemetry fields")
    void outputsStandardTopLevelFields() throws Exception {
        long timestamp = 1732093500123L; // deterministic epoch millis
        ILoggingEvent event = createMockEvent(
                Level.WARN,
                "com.spotifyyoutube.migrator.sample.SampleLogger",
                "Warning event occurred",
                Map.of(),
                timestamp
        );

        String jsonLine = layout.doLayout(event);
        JsonNode root = objectMapper.readTree(jsonLine);

        assertThat(root.get("timestamp").asText()).isEqualTo(Instant.ofEpochMilli(timestamp).toString());
        assertThat(root.get("level").asText()).isEqualTo("WARN");
        assertThat(root.get("logger").asText()).isEqualTo("com.spotifyyoutube.migrator.sample.SampleLogger");
        assertThat(root.get("thread").asText()).isEqualTo("test-thread-1");
        assertThat(root.get("service").asText()).isEqualTo("migrator-test");
        assertThat(root.get("environment").asText()).isEqualTo("test-env");
        assertThat(root.get("message").asText()).isEqualTo("Warning event occurred");
    }

    @Test
    @DisplayName("Promotes requestId and correlationId to top-level fields and nests remaining MDC in context")
    void processesMdcFieldsCorrectly() throws Exception {
        Map<String, String> mdc = new HashMap<>();
        mdc.put(LoggingConstants.MDC_REQUEST_ID, "req-12345");
        mdc.put(LoggingConstants.MDC_CORRELATION_ID, "corr-67890");
        mdc.put(LoggingConstants.MDC_USER_ID, "user-uuid-1");
        mdc.put(LoggingConstants.MDC_MIGRATION_EXECUTION_ID, "exec-uuid-2");
        mdc.put(LoggingConstants.MDC_OPERATION, "track_matching");
        mdc.put(LoggingConstants.MDC_PROVIDER, "youtube");

        ILoggingEvent event = createMockEvent(Level.INFO, "com.test.Logger", "Action completed", mdc);

        String jsonLine = layout.doLayout(event);
        JsonNode root = objectMapper.readTree(jsonLine);

        assertThat(root.get("requestId").asText()).isEqualTo("req-12345");
        assertThat(root.get("correlationId").asText()).isEqualTo("corr-67890");

        JsonNode context = root.get("context");
        assertThat(context).isNotNull();
        assertThat(context.get("userId").asText()).isEqualTo("user-uuid-1");
        assertThat(context.get("migrationExecutionId").asText()).isEqualTo("exec-uuid-2");
        assertThat(context.get("operation").asText()).isEqualTo("track_matching");
        assertThat(context.get("provider").asText()).isEqualTo("youtube");
        // Ensure promoted fields are not duplicated inside context
        assertThat(context.has("requestId")).isFalse();
        assertThat(context.has("correlationId")).isFalse();
    }

    @Test
    @DisplayName("Redacts sensitive Bearer tokens in formatted log message")
    void redactsBearerTokensInMessage() throws Exception {
        String sensitiveMessage = "Failed API call with Bearer ya29.a0AfH6SMDI9834271890 and access_token=secretToken123";
        ILoggingEvent event = createMockEvent(Level.ERROR, "com.test.Client", sensitiveMessage, Map.of());

        String jsonLine = layout.doLayout(event);
        JsonNode root = objectMapper.readTree(jsonLine);

        String loggedMessage = root.get("message").asText();
        assertThat(loggedMessage).doesNotContain("ya29.a0AfH6SMDI9834271890");
        assertThat(loggedMessage).doesNotContain("secretToken123");
        assertThat(loggedMessage).contains("Bearer [REDACTED]");
        assertThat(loggedMessage).contains("access_token=[REDACTED]");
    }

    @Test
    @DisplayName("Redacts sensitive credentials in MDC context properties")
    void redactsSensitiveMdcProperties() throws Exception {
        Map<String, String> mdc = new HashMap<>();
        mdc.put("safeKey", "safeValue");
        mdc.put("leakedToken", "Bearer secret-token-xyz");

        ILoggingEvent event = createMockEvent(Level.INFO, "com.test.Logger", "Safe message", mdc);

        String jsonLine = layout.doLayout(event);
        JsonNode root = objectMapper.readTree(jsonLine);

        JsonNode context = root.get("context");
        assertThat(context.get("safeKey").asText()).isEqualTo("safeValue");
        assertThat(context.get("leakedToken").asText()).isEqualTo("Bearer [REDACTED]");
    }

    @Test
    @DisplayName("Serializes exception details with sanitized message and stack trace")
    void serializesExceptionDetailsWithSanitization() throws Exception {
        Exception cause = new IllegalArgumentException("Invalid parameter client_secret=verySecretSecretValue");
        IThrowableProxy throwableProxy = new ThrowableProxy(cause);

        ILoggingEvent event = Mockito.mock(ILoggingEvent.class);
        when(event.getTimeStamp()).thenReturn(Instant.now().toEpochMilli());
        when(event.getLevel()).thenReturn(Level.ERROR);
        when(event.getLoggerName()).thenReturn("com.test.ExceptionLogger");
        when(event.getThreadName()).thenReturn("test-thread");
        when(event.getFormattedMessage()).thenReturn("An unexpected exception was caught");
        when(event.getMDCPropertyMap()).thenReturn(Map.of());
        when(event.getThrowableProxy()).thenReturn(throwableProxy);

        String jsonLine = layout.doLayout(event);
        JsonNode root = objectMapper.readTree(jsonLine);

        assertThat(root.get("exceptionClass").asText()).isEqualTo("java.lang.IllegalArgumentException");
        String exceptionMessage = root.get("exceptionMessage").asText();
        assertThat(exceptionMessage).doesNotContain("verySecretSecretValue");
        assertThat(exceptionMessage).contains("client_secret=[REDACTED]");

        String stackTrace = root.get("stackTrace").asText();
        assertThat(stackTrace).doesNotContain("verySecretSecretValue");
        assertThat(stackTrace).contains("client_secret=[REDACTED]");
    }

    @Test
    @DisplayName("Returns empty string when event is null")
    void handlesNullEventGracefully() {
        assertThat(layout.doLayout(null)).isEmpty();
    }

    private ILoggingEvent createMockEvent(Level level, String loggerName, String message, Map<String, String> mdc) {
        return createMockEvent(level, loggerName, message, mdc, Instant.now().toEpochMilli());
    }

    private ILoggingEvent createMockEvent(Level level, String loggerName, String message, Map<String, String> mdc, long timestamp) {
        ILoggingEvent event = Mockito.mock(ILoggingEvent.class);
        when(event.getTimeStamp()).thenReturn(timestamp);
        when(event.getLevel()).thenReturn(level);
        when(event.getLoggerName()).thenReturn(loggerName);
        when(event.getThreadName()).thenReturn("test-thread-1");
        when(event.getFormattedMessage()).thenReturn(message);
        when(event.getMDCPropertyMap()).thenReturn(mdc);
        return event;
    }
}
