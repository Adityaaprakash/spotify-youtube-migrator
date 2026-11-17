package com.spotifyyoutube.migrator.identity.application;

import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class OAuthTokenServiceImpl implements OAuthTokenService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;
    private static final int REQUIRED_KEY_BYTES = 32; // 256-bit AES
    
    private final SecretKeySpec keySpec;
    private final SecureRandom secureRandom = new SecureRandom();

    public OAuthTokenServiceImpl(@Value("${oauth.encryption.key}") String encryptionKeyBase64) {
        if (encryptionKeyBase64 == null || encryptionKeyBase64.isBlank()) {
            throw new IllegalArgumentException("OAuth encryption key must be provided");
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(encryptionKeyBase64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("OAuth encryption key must be valid Base64", e);
        }
        if (key.length != REQUIRED_KEY_BYTES) {
            throw new IllegalArgumentException("OAuth encryption key must be exactly 256 bits (32 bytes), received "
                    + (key.length * 8) + " bits");
        }
        this.keySpec = new SecretKeySpec(key, "AES");
    }

    @Override
    public String encryptToken(String plaintextToken) {
        if (plaintextToken == null || plaintextToken.isBlank()) {
            return null;
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, parameterSpec);

            byte[] cipherText = cipher.doFinal(plaintextToken.getBytes(StandardCharsets.UTF_8));
            
            byte[] message = new byte[GCM_IV_LENGTH + cipherText.length];
            System.arraycopy(iv, 0, message, 0, GCM_IV_LENGTH);
            System.arraycopy(cipherText, 0, message, GCM_IV_LENGTH, cipherText.length);

            return Base64.getEncoder().encodeToString(message);
        } catch (Exception e) {
            throw new InvalidStateException("Failed to encrypt token: encryption failure");
        }
    }

    @Override
    public String decryptToken(String encryptedToken) {
        if (encryptedToken == null || encryptedToken.isBlank()) {
            return null;
        }
        try {
            byte[] message = Base64.getDecoder().decode(encryptedToken.trim());
            if (message.length < GCM_IV_LENGTH + (GCM_TAG_LENGTH / 8)) {
                throw new InvalidStateException("Encrypted token payload is truncated or invalid");
            }

            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(message, 0, iv, 0, GCM_IV_LENGTH);

            byte[] cipherText = new byte[message.length - GCM_IV_LENGTH];
            System.arraycopy(message, GCM_IV_LENGTH, cipherText, 0, cipherText.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, parameterSpec);

            byte[] plainText = cipher.doFinal(cipherText);
            return new String(plainText, StandardCharsets.UTF_8);
        } catch (InvalidStateException e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidStateException("Failed to decrypt token: authentication tag verification failed or corrupted ciphertext");
        }
    }
}
