package com.spotifyyoutube.migrator.migration.reliability.idempotency;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("IdempotencyKeyValidator Unit Tests (Phase 13E)")
class IdempotencyKeyValidatorTest {

    @Test
    @DisplayName("Valid UUID idempotency key is accepted")
    void validUuidKey_accepted() {
        String key = UUID.randomUUID().toString();
        var result = IdempotencyKeyValidator.validate(key);

        assertThat(result.isValid()).isTrue();
        assertThat(result.errorMessage()).isNull();
    }

    @Test
    @DisplayName("Valid custom ASCII string key is accepted")
    void validCustomAsciiKey_accepted() {
        String key = "client-tx-2026-11-09-migrator-user-req";
        var result = IdempotencyKeyValidator.validate(key);

        assertThat(result.isValid()).isTrue();
        assertThat(result.errorMessage()).isNull();
    }

    @Test
    @DisplayName("Key with maximum 255 characters is accepted")
    void maxLength255Key_accepted() {
        String key = "k".repeat(255);
        var result = IdempotencyKeyValidator.validate(key);

        assertThat(result.isValid()).isTrue();
    }

    @Test
    @DisplayName("Oversized key with 256 characters is rejected with 400 validation error message")
    void oversizedKey_rejected() {
        String key = "k".repeat(256);
        var result = IdempotencyKeyValidator.validate(key);

        assertThat(result.isValid()).isFalse();
        assertThat(result.errorMessage()).contains("exceeds maximum length of 255 characters");
    }

    @Test
    @DisplayName("Null key is rejected")
    void nullKey_rejected() {
        var result = IdempotencyKeyValidator.validate(null);

        assertThat(result.isValid()).isFalse();
        assertThat(result.errorMessage()).contains("required");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t", "\n"})
    @DisplayName("Blank or whitespace-only keys are rejected")
    void blankKey_rejected(String blank) {
        var result = IdempotencyKeyValidator.validate(blank);

        assertThat(result.isValid()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"key\r\ninjection", "test\0null", "header\u0007bell", "invalid\u001bkey"})
    @DisplayName("Keys containing control characters or header injection bytes are rejected as malformed")
    void malformedKeyWithControlCharacters_rejected(String malformed) {
        var result = IdempotencyKeyValidator.validate(malformed);

        assertThat(result.isValid()).isFalse();
        assertThat(result.errorMessage()).contains("malformed or control characters");
    }
}
