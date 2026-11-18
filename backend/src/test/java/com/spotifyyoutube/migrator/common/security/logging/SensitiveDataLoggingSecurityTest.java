package com.spotifyyoutube.migrator.common.security.logging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.io.IOException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
@DisplayName("Phase 16F: Sensitive Data Protection and Logging Security Tests")
class SensitiveDataLoggingSecurityTest {

    private static final Logger log = LoggerFactory.getLogger(SensitiveDataLoggingSecurityTest.class);

    @Nested
    @DisplayName("Credential and Token Redaction")
    class CredentialRedactionTests {

        @Test
        @DisplayName("Redacts Bearer tokens from text, URLs, and error diagnostics")
        void redactsBearerTokens() {
            String raw = "Call failed with Bearer ya29.a0AfH6SMB_secretBearerValue123 and another Bearer tokenABC-123_xyz";
            String sanitized = SensitiveDataSanitizer.sanitize(raw);

            assertThat(sanitized).doesNotContain("ya29.a0AfH6SMB_secretBearerValue123");
            assertThat(sanitized).doesNotContain("tokenABC-123_xyz");
            assertThat(sanitized).contains("Bearer [REDACTED]");
        }

        @Test
        @DisplayName("Redacts Basic authentication credentials")
        void redactsBasicAuthCredentials() {
            String raw = "Basic dXNlcm5hbWU6cGFzc3dvcmQxMjM= authentication failure";
            String sanitized = SensitiveDataSanitizer.sanitize(raw);

            assertThat(sanitized).doesNotContain("dXNlcm5hbWU6cGFzc3dvcmQxMjM=");
            assertThat(sanitized).contains("Basic [REDACTED]");
        }

        @Test
        @DisplayName("Redacts Authorization headers preserving header keyword")
        void redactsAuthorizationHeaders() {
            String raw = "Headers: Authorization: Bearer secretToken and Authorization: Basic secretCredentials";
            String sanitized = SensitiveDataSanitizer.sanitize(raw);

            assertThat(sanitized).doesNotContain("secretToken");
            assertThat(sanitized).doesNotContain("secretCredentials");
            assertThat(sanitized).contains("Authorization: Bearer [REDACTED]");
            assertThat(sanitized).contains("Authorization: Basic [REDACTED]");
        }

        @Test
        @DisplayName("Redacts OAuth query and form parameters (access_token, refresh_token, client_secret, password, code, state, api_key)")
        void redactsQueryAndFormParameters() {
            String raw = "https://oauth2.googleapis.com/token?access_token=ya29.secretAccess"
                    + "&refresh_token=1//04secretRefresh&client_secret=GOCSPX-secretSecret"
                    + "&code=4/0AY0e-authCode&state=secureRandomState123&password=P@ssw0rd123!"
                    + "&api_key=AIzaSySecretApiKey&key=AIzaSyKey123";

            String sanitized = SensitiveDataSanitizer.sanitize(raw);

            assertThat(sanitized).doesNotContain("ya29.secretAccess");
            assertThat(sanitized).doesNotContain("1//04secretRefresh");
            assertThat(sanitized).doesNotContain("GOCSPX-secretSecret");
            assertThat(sanitized).doesNotContain("4/0AY0e-authCode");
            assertThat(sanitized).doesNotContain("secureRandomState123");
            assertThat(sanitized).doesNotContain("P@ssw0rd123!");
            assertThat(sanitized).doesNotContain("AIzaSySecretApiKey");
            assertThat(sanitized).doesNotContain("AIzaSyKey123");

            assertThat(sanitized).contains("access_token=[REDACTED]");
            assertThat(sanitized).contains("refresh_token=[REDACTED]");
            assertThat(sanitized).contains("client_secret=[REDACTED]");
            assertThat(sanitized).contains("code=[REDACTED]");
            assertThat(sanitized).contains("state=[REDACTED]");
            assertThat(sanitized).contains("password=[REDACTED]");
            assertThat(sanitized).contains("api_key=[REDACTED]");
            assertThat(sanitized).contains("key=[REDACTED]");
        }

        @Test
        @DisplayName("Redacts credentials inside JSON payloads")
        void redactsJsonPayloads() {
            String json = "{\"access_token\": \"ya29.secretToken\", \"refresh_token\": \"1//secretRefresh\", "
                    + "\"client_secret\": \"superSecret\", \"password\": \"myPassword\", \"state\": \"state999\"}";

            String sanitized = SensitiveDataSanitizer.sanitize(json);

            assertThat(sanitized).doesNotContain("ya29.secretToken");
            assertThat(sanitized).doesNotContain("1//secretRefresh");
            assertThat(sanitized).doesNotContain("superSecret");
            assertThat(sanitized).doesNotContain("myPassword");
            assertThat(sanitized).doesNotContain("state999");

            assertThat(sanitized).contains("\"access_token\": \"[REDACTED]\"");
            assertThat(sanitized).contains("\"refresh_token\": \"[REDACTED]\"");
            assertThat(sanitized).contains("\"client_secret\": \"[REDACTED]\"");
            assertThat(sanitized).contains("\"password\": \"[REDACTED]\"");
            assertThat(sanitized).contains("\"state\": \"[REDACTED]\"");
        }

        @Test
        @DisplayName("Redacts session cookies and CSRF tokens")
        void redactsSessionAndCsrfCookies() {
            String cookieHeader = "Cookie: JSESSIONID=9F28D1B6A42E; XSRF-TOKEN=c9a01f4b-22; SESSION=sess-8812; Path=/";
            String sanitized = SensitiveDataSanitizer.sanitize(cookieHeader);

            assertThat(sanitized).doesNotContain("9F28D1B6A42E");
            assertThat(sanitized).doesNotContain("c9a01f4b-22");
            assertThat(sanitized).doesNotContain("sess-8812");

            assertThat(sanitized).contains("JSESSIONID=[REDACTED]");
            assertThat(sanitized).contains("XSRF-TOKEN=[REDACTED]");
            assertThat(sanitized).contains("SESSION=[REDACTED]");
            assertThat(sanitized).contains("Path=/");
        }
    }

    @Nested
    @DisplayName("CRLF Log Injection Protection")
    class LogInjectionProtectionTests {

        @Test
        @DisplayName("Neutralizes CRLF characters to prevent log splitting and log forging")
        void neutralizesCrlfLogInjection() {
            String maliciousInput = "OAuth callback error: invalid_request\r\n2026-11-18 10:00:00 [main] INFO Admin user elevated\r\n";
            String sanitized = SensitiveDataSanitizer.sanitizeSingleLine(maliciousInput);

            assertThat(sanitized).doesNotContain("\r");
            assertThat(sanitized).doesNotContain("\n");
            assertThat(sanitized).isEqualTo("OAuth callback error: invalid_request 2026-11-18 10:00:00 [main] INFO Admin user elevated");
        }

        @Test
        @DisplayName("Neutralizes CRLF combined with sensitive parameter redaction")
        void neutralizesCrlfWithSensitiveParameters() {
            String malicious = "access_token=ya29.evilToken\r\nFAKE_LOG_ENTRY state=evilState\n";
            String sanitized = SensitiveDataSanitizer.sanitizeSingleLine(malicious);

            assertThat(sanitized).doesNotContain("ya29.evilToken");
            assertThat(sanitized).doesNotContain("evilState");
            assertThat(sanitized).doesNotContain("\r");
            assertThat(sanitized).doesNotContain("\n");
            assertThat(sanitized).isEqualTo("access_token=[REDACTED] FAKE_LOG_ENTRY state=[REDACTED]");
        }
    }

    @Nested
    @DisplayName("Exception and Throwable Sanitization")
    class ThrowableSanitizationTests {

        @Test
        @DisplayName("Sanitizes nested exception cause chains without leaking internal tokens")
        void sanitizesNestedThrowableCauseChains() {
            Exception rootCause = new IOException("Connection failed for https://accounts.spotify.com/api/token?client_secret=superSecret123");
            Exception middleCause = new IllegalStateException("Failed to exchange Bearer ya29.temporaryToken", rootCause);
            RuntimeException topException = new RuntimeException("OAuth workflow aborted with access_token=leakedToken", middleCause);

            String sanitized = SensitiveDataSanitizer.sanitize(topException);

            assertThat(sanitized).doesNotContain("superSecret123");
            assertThat(sanitized).doesNotContain("ya29.temporaryToken");
            assertThat(sanitized).doesNotContain("leakedToken");

            assertThat(sanitized).contains("client_secret=[REDACTED]");
            assertThat(sanitized).contains("Bearer [REDACTED]");
            assertThat(sanitized).contains("access_token=[REDACTED]");
            assertThat(sanitized).contains("caused by:");
        }

        @Test
        @DisplayName("Protects against infinite recursion on circular exception cause chains")
        void preventsInfiniteLoopsOnCircularThrowableCauses() {
            CircularException ex1 = new CircularException("Exception 1 with access_token=secretTokenA");
            CircularException ex2 = new CircularException("Exception 2 with refresh_token=secretTokenB");
            ex1.initCustomCause(ex2);
            ex2.initCustomCause(ex1); // circular reference

            String sanitized = SensitiveDataSanitizer.sanitize(ex1);

            assertThat(sanitized).isNotNull();
            assertThat(sanitized).doesNotContain("secretTokenA");
            assertThat(sanitized).doesNotContain("secretTokenB");
            assertThat(sanitized).contains("access_token=[REDACTED]");
            assertThat(sanitized).contains("refresh_token=[REDACTED]");
        }

        @Test
        @DisplayName("Extracts and sanitizes throwable direct message")
        void extractsAndSanitizesThrowableMessage() {
            Exception ex = new RuntimeException("Failed with api_key=AIzaSySecret");
            assertThat(SensitiveDataSanitizer.sanitizeThrowableMessage(ex))
                    .isEqualTo("Failed with api_key=[REDACTED]");

            Exception noMessage = new RuntimeException();
            assertThat(SensitiveDataSanitizer.sanitizeThrowableMessage(noMessage))
                    .isEqualTo("RuntimeException");

            assertThat(SensitiveDataSanitizer.sanitizeThrowableMessage(null))
                    .isEqualTo("null");
        }
    }

    @Nested
    @DisplayName("Idempotency, Null Safety and Custom Masking")
    class InvariantTests {

        @Test
        @DisplayName("Sanitization is idempotent: repeat calls never double-mask or mangle text")
        void isIdempotent() {
            String raw = "Failed Bearer secretToken with access_token=sec123 and JSESSIONID=abc";
            String once = SensitiveDataSanitizer.sanitize(raw);
            String twice = SensitiveDataSanitizer.sanitize(once);
            String thrice = SensitiveDataSanitizer.sanitize(twice);

            assertThat(once).isEqualTo("Failed Bearer [REDACTED] with access_token=[REDACTED] and JSESSIONID=[REDACTED]");
            assertThat(twice).isEqualTo(once);
            assertThat(thrice).isEqualTo(once);
        }

        @Test
        @DisplayName("Handles null and blank inputs safely")
        void handlesNullAndBlankStrings() {
            assertThat(SensitiveDataSanitizer.sanitize((String) null)).isNull();
            assertThat(SensitiveDataSanitizer.sanitize("")).isEqualTo("");
            assertThat(SensitiveDataSanitizer.sanitize("   ")).isEqualTo("   ");
            assertThat(SensitiveDataSanitizer.sanitizeSingleLine(null)).isNull();
            assertThat(SensitiveDataSanitizer.sanitize((Throwable) null)).isNull();
        }

        @Test
        @DisplayName("Supports custom replacement mask (e.g. ***)")
        void supportsCustomReplacementMask() {
            String raw = "Authorization: Bearer mySecret and api_key=keyXYZ";
            String sanitized = SensitiveDataSanitizer.sanitize(raw, "***");

            assertThat(sanitized).contains("Authorization: Bearer ***");
            assertThat(sanitized).contains("api_key=***");
            assertThat(sanitized).doesNotContain("mySecret");
            assertThat(sanitized).doesNotContain("keyXYZ");
        }
    }

    @Nested
    @DisplayName("Log Capture Verification")
    class LogCaptureVerificationTests {

        @Test
        @DisplayName("Verifies actual log output contains sanitized contextual messages and zero raw tokens")
        void logAppenderCaptureNeverLeaksSecrets(CapturedOutput output) {
            String rawAccessToken = "ya29.a0AfH6SMB_secretAccessToken999";
            String rawRefreshToken = "1//04secretRefreshToken888";
            String rawClientSecret = "GOCSPX-superSecretAppCredential";
            UUID testUserId = UUID.randomUUID();

            // Simulate safe contextual message as mandated by roadmap
            log.info("OAuth token refreshed successfully userId={} provider=spotify", testUserId);

            // Simulate error logging with sanitization
            String rawError = "Failed downstream request to https://accounts.spotify.com/api/token?client_secret="
                    + rawClientSecret + " with access_token=" + rawAccessToken + " and refresh_token=" + rawRefreshToken;
            log.warn("OAuth refresh failed: {}", SensitiveDataSanitizer.sanitize(rawError));

            String capturedLogs = output.getAll();

            // Assert raw secrets never leak into log output
            assertThat(capturedLogs).doesNotContain(rawAccessToken);
            assertThat(capturedLogs).doesNotContain(rawRefreshToken);
            assertThat(capturedLogs).doesNotContain(rawClientSecret);

            // Assert safe contextual message and redacted placeholders are present
            assertThat(capturedLogs).contains("OAuth token refreshed successfully userId=" + testUserId + " provider=spotify");
            assertThat(capturedLogs).contains("client_secret=[REDACTED]");
            assertThat(capturedLogs).contains("access_token=[REDACTED]");
            assertThat(capturedLogs).contains("refresh_token=[REDACTED]");
        }
    }

    private static class CircularException extends RuntimeException {
        private Throwable customCause;

        public CircularException(String message) {
            super(message);
        }

        public void initCustomCause(Throwable cause) {
            this.customCause = cause;
        }

        @Override
        public synchronized Throwable getCause() {
            return customCause != null ? customCause : super.getCause();
        }
    }
}
