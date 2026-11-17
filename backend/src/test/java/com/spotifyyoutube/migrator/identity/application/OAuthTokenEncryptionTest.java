package com.spotifyyoutube.migrator.identity.application;

import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class OAuthTokenEncryptionTest {

    // Valid 32-byte (256-bit) Base64 encoded key
    private static final String VALID_256_BIT_KEY = "1234567890abcdef1234567890abcdef12345678123=";

    private OAuthTokenServiceImpl tokenService;

    @BeforeEach
    void setUp() {
        tokenService = new OAuthTokenServiceImpl(VALID_256_BIT_KEY);
    }

    @Test
    void constructor_AcceptsValid256BitKey() {
        assertDoesNotThrow(() -> new OAuthTokenServiceImpl(VALID_256_BIT_KEY));
    }

    @Test
    void constructor_RejectsShortKey_128Bit() {
        // 16 bytes (128 bits)
        String key128 = Base64.getEncoder().encodeToString(new byte[16]);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                new OAuthTokenServiceImpl(key128));
        assertTrue(ex.getMessage().contains("must be exactly 256 bits"));
    }

    @Test
    void constructor_RejectsShortKey_192Bit() {
        // 24 bytes (192 bits)
        String key192 = Base64.getEncoder().encodeToString(new byte[24]);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                new OAuthTokenServiceImpl(key192));
        assertTrue(ex.getMessage().contains("must be exactly 256 bits"));
    }

    @Test
    void constructor_RejectsArbitraryWeakKey() {
        String weakKey = Base64.getEncoder().encodeToString("short-secret".getBytes());
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                new OAuthTokenServiceImpl(weakKey));
        assertTrue(ex.getMessage().contains("must be exactly 256 bits"));
    }

    @Test
    void constructor_RejectsNullOrBlankKey() {
        assertThrows(IllegalArgumentException.class, () -> new OAuthTokenServiceImpl(null));
        assertThrows(IllegalArgumentException.class, () -> new OAuthTokenServiceImpl("   "));
    }

    @Test
    void constructor_RejectsInvalidBase64Key() {
        assertThrows(IllegalArgumentException.class, () -> new OAuthTokenServiceImpl("not-valid-base64-!@#$%^"));
    }

    @Test
    void encryptAndDecrypt_RoundTrip_PreservesPlaintext() {
        String originalToken = "ya29.a0AfH6SMD_SampleAccessToken_1234567890_VerySecret";
        String encrypted = tokenService.encryptToken(originalToken);

        assertNotNull(encrypted);
        assertNotEquals(originalToken, encrypted, "Ciphertext must not match plaintext");

        String decrypted = tokenService.decryptToken(encrypted);
        assertEquals(originalToken, decrypted, "Decrypted token must exactly match original plaintext");
    }

    @Test
    void encrypt_GeneratesUniqueIvPerEncryption_NoIvReuse() {
        String token = "constant-secret-token";
        Set<String> ciphertexts = new HashSet<>();

        for (int i = 0; i < 20; i++) {
            String encrypted = tokenService.encryptToken(token);
            assertNotNull(encrypted);
            assertTrue(ciphertexts.add(encrypted), "Each encryption operation must generate a unique IV and ciphertext");
        }
    }

    @Test
    void decrypt_DetectsTampering_FailsAuthenticationTagVerification() {
        String token = "sensitive-oauth-refresh-token";
        String encrypted = tokenService.encryptToken(token);

        byte[] raw = Base64.getDecoder().decode(encrypted);
        // Tamper with one byte in the ciphertext payload (after the 12-byte IV)
        raw[15] ^= 0xFF;
        String tampered = Base64.getEncoder().encodeToString(raw);

        InvalidStateException ex = assertThrows(InvalidStateException.class, () ->
                tokenService.decryptToken(tampered));

        assertTrue(ex.getMessage().contains("authentication tag verification failed"));
        assertFalse(ex.getMessage().contains(token), "Error message must never leak token content");
    }

    @Test
    void decrypt_RejectsTruncatedPayload() {
        // Less than 28 bytes (12-byte IV + 16-byte GCM tag)
        byte[] truncated = new byte[10];
        new SecureRandom().nextBytes(truncated);
        String truncatedBase64 = Base64.getEncoder().encodeToString(truncated);

        InvalidStateException ex = assertThrows(InvalidStateException.class, () ->
                tokenService.decryptToken(truncatedBase64));

        assertTrue(ex.getMessage().contains("truncated or invalid"));
    }

    @Test
    void nullAndBlankInputs_HandledSafely() {
        assertNull(tokenService.encryptToken(null));
        assertNull(tokenService.encryptToken(""));
        assertNull(tokenService.encryptToken("   "));

        assertNull(tokenService.decryptToken(null));
        assertNull(tokenService.decryptToken(""));
        assertNull(tokenService.decryptToken("   "));
    }
}
