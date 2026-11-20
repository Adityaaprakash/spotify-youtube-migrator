package com.spotifyyoutube.migrator.common.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@DisplayName("Logging Security & Redaction Tests (Phase 18F)")
class LoggingSecurityTest {

    private StructuredJsonLayout layout;
    private RequestCorrelationFilter filter;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        layout = new StructuredJsonLayout();
        layout.setApplicationName("migrator-sec-test");
        layout.setEnvironment("test");
        filter = new RequestCorrelationFilter();
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("RequestCorrelationFilter neutralizes CRLF log injection in inbound correlation ID")
    void neutralizesCrlfInjectionInCorrelationId() {
        String crlfAttack = "req-id-123\r\nINFO: Forged Log Entry Injected\r\nX-Evil: true";
        String validatedId = filter.validateOrGenerate(crlfAttack);

        assertThat(validatedId).doesNotContain("\r");
        assertThat(validatedId).doesNotContain("\n");
        assertThat(validatedId).isNotEqualTo(crlfAttack);
        // Validated fallback must be safe UUIDv4
        assertThat(validatedId).matches("^[0-9a-fA-F-]{36}$");
    }

    @Test
    @DisplayName("StructuredJsonLayout redacts OAuth access tokens and client secrets in log messages")
    void redactsOAuthTokensAndSecrets() throws Exception {
        String messageWithSecrets = "Calling https://accounts.spotify.com/api/token with client_secret=veryTopSecretSecret and code=authCode12345";
        ILoggingEvent event = createMockEvent(messageWithSecrets, null);

        String json = layout.doLayout(event);
        JsonNode root = objectMapper.readTree(json);

        String loggedMessage = root.get("message").asText();
        assertThat(loggedMessage).doesNotContain("veryTopSecretSecret");
        assertThat(loggedMessage).doesNotContain("authCode12345");
        assertThat(loggedMessage).contains("client_secret=[REDACTED]");
        assertThat(loggedMessage).contains("code=[REDACTED]");
    }

    @Test
    @DisplayName("StructuredJsonLayout redacts session cookies and CSRF tokens in log messages")
    void redactsCookiesInLogMessages() throws Exception {
        String messageWithCookies = "Incoming Cookie header: JSESSIONID=ABCD1234EFGH5678; XSRF-TOKEN=secretCsrfTokenVal";
        ILoggingEvent event = createMockEvent(messageWithCookies, null);

        String json = layout.doLayout(event);
        JsonNode root = objectMapper.readTree(json);

        String loggedMessage = root.get("message").asText();
        assertThat(loggedMessage).doesNotContain("ABCD1234EFGH5678");
        assertThat(loggedMessage).doesNotContain("secretCsrfTokenVal");
        assertThat(loggedMessage).contains("JSESSIONID=[REDACTED]");
        assertThat(loggedMessage).contains("XSRF-TOKEN=[REDACTED]");
    }

    @Test
    @DisplayName("StructuredJsonLayout redacts user passwords in log messages")
    void redactsPasswordsInLogMessages() throws Exception {
        String messageWithPassword = "Login attempt with username=testUser and password=myPlainTextPassword123";
        ILoggingEvent event = createMockEvent(messageWithPassword, null);

        String json = layout.doLayout(event);
        JsonNode root = objectMapper.readTree(json);

        String loggedMessage = root.get("message").asText();
        assertThat(loggedMessage).doesNotContain("myPlainTextPassword123");
        assertThat(loggedMessage).contains("password=[REDACTED]");
    }

    @Test
    @DisplayName("StructuredJsonLayout redacts secrets embedded in exception message and stack trace")
    void redactsSecretsInExceptionAndStackTrace() throws Exception {
        Exception leakException = new RuntimeException("HTTP 401 Unauthorized for Authorization: Bearer ya29.secretBearerToken12345");
        ILoggingEvent event = createMockEvent("External provider call failed", leakException);

        String json = layout.doLayout(event);
        JsonNode root = objectMapper.readTree(json);

        String exceptionMessage = root.get("exceptionMessage").asText();
        assertThat(exceptionMessage).doesNotContain("ya29.secretBearerToken12345");
        assertThat(exceptionMessage).contains("Bearer [REDACTED]");

        String stackTrace = root.get("stackTrace").asText();
        assertThat(stackTrace).doesNotContain("ya29.secretBearerToken12345");
        assertThat(stackTrace).contains("Bearer [REDACTED]");
    }

    private ILoggingEvent createMockEvent(String message, Throwable throwable) {
        ILoggingEvent event = Mockito.mock(ILoggingEvent.class);
        when(event.getTimeStamp()).thenReturn(Instant.now().toEpochMilli());
        when(event.getLevel()).thenReturn(Level.ERROR);
        when(event.getLoggerName()).thenReturn("com.spotifyyoutube.migrator.security.TestAudit");
        when(event.getThreadName()).thenReturn("main");
        when(event.getFormattedMessage()).thenReturn(message);
        when(event.getMDCPropertyMap()).thenReturn(Map.of());
        if (throwable != null) {
            when(event.getThrowableProxy()).thenReturn(new ThrowableProxy(throwable));
        }
        return event;
    }
}
