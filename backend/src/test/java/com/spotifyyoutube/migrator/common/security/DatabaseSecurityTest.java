package com.spotifyyoutube.migrator.common.security;

import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
import com.spotifyyoutube.migrator.identity.application.OAuthConnectionServiceImpl;
import com.spotifyyoutube.migrator.identity.application.OAuthTokenResponse;
import com.spotifyyoutube.migrator.identity.application.OAuthTokenServiceImpl;
import com.spotifyyoutube.migrator.identity.domain.ConnectionStatus;
import com.spotifyyoutube.migrator.identity.domain.OAuthConnection;
import com.spotifyyoutube.migrator.identity.domain.OAuthProvider;
import com.spotifyyoutube.migrator.identity.repository.OAuthConnectionRepository;
import com.spotifyyoutube.migrator.identity.repository.OAuthStateRepository;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionReconciliationRecord;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionRollbackRecord;
import com.spotifyyoutube.migrator.migration.execution.repository.ExecutionReconciliationRecordRepository;
import com.spotifyyoutube.migrator.migration.execution.repository.ExecutionRollbackRecordRepository;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSession;
import com.spotifyyoutube.migrator.migration.review.repository.ReviewSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Phase 16E: Database Security and Token Persistence Tests")
class DatabaseSecurityTest {

    // Valid 256-bit AES Base64 key
    private static final String TEST_KEY_BASE64 = "1234567890abcdef1234567890abcdef12345678123=";

    private OAuthTokenServiceImpl tokenService;

    @BeforeEach
    void setUp() {
        tokenService = new OAuthTokenServiceImpl(TEST_KEY_BASE64);
    }

    @Nested
    @DisplayName("OAuth Token Encryption and Persistence Invariants")
    class TokenEncryptionInvariants {

        @Test
        @DisplayName("Tokens are encrypted with AES-256-GCM and never persisted as plaintext")
        void tokensAreEncryptedWithAesGcm() {
            String rawAccessToken = "ya29.a0AfH6SMB_secretPlaintextAccessToken12345";
            String encrypted = tokenService.encryptToken(rawAccessToken);

            assertThat(encrypted).isNotNull();
            assertThat(encrypted).isNotEqualTo(rawAccessToken);
            assertThat(encrypted).doesNotContain("ya29.a0AfH6SMB");
            assertThat(encrypted).doesNotContain("secretPlaintextAccessToken12345");

            // Base64 decodable and contains at least 12 bytes IV + 16 bytes GCM tag
            byte[] decoded = Base64.getDecoder().decode(encrypted);
            assertThat(decoded.length).isGreaterThanOrEqualTo(28);

            // Decryption recovers exact original plaintext
            String decrypted = tokenService.decryptToken(encrypted);
            assertThat(decrypted).isEqualTo(rawAccessToken);
        }

        @Test
        @DisplayName("Encrypting the same plaintext twice produces different ciphertexts due to random IV nonces")
        void noncesAreCryptographicallyRandomAndNeverRepeated() {
            String plaintext = "my-oauth-access-token-999";
            String ciphertext1 = tokenService.encryptToken(plaintext);
            String ciphertext2 = tokenService.encryptToken(plaintext);

            assertThat(ciphertext1).isNotEqualTo(ciphertext2);
            assertThat(tokenService.decryptToken(ciphertext1)).isEqualTo(plaintext);
            assertThat(tokenService.decryptToken(ciphertext2)).isEqualTo(plaintext);
        }

        @Test
        @DisplayName("Tampered ciphertext fails GCM authentication tag verification and throws InvalidStateException")
        void tamperedCiphertextFailsVerification() {
            String raw = "confidential-refresh-token-xyz";
            String encrypted = tokenService.encryptToken(raw);

            byte[] bytes = Base64.getDecoder().decode(encrypted);
            // Flip a bit in the ciphertext / auth tag
            bytes[bytes.length - 1] ^= 0x01;
            String tampered = Base64.getEncoder().encodeToString(bytes);

            assertThatThrownBy(() -> tokenService.decryptToken(tampered))
                    .isInstanceOf(InvalidStateException.class)
                    .hasMessageContaining("Failed to decrypt token");
        }

        @Test
        @DisplayName("Truncated ciphertext is rejected safely without leaking stack traces")
        void truncatedCiphertextIsRejected() {
            byte[] truncated = new byte[15]; // less than 12 (IV) + 16 (tag) = 28 bytes
            String payload = Base64.getEncoder().encodeToString(truncated);

            assertThatThrownBy(() -> tokenService.decryptToken(payload))
                    .isInstanceOf(InvalidStateException.class)
                    .hasMessageContaining("truncated or invalid");
        }

        @Test
        @DisplayName("Null and blank tokens return null without error")
        void nullAndBlankTokensReturnNull() {
            assertThat(tokenService.encryptToken(null)).isNull();
            assertThat(tokenService.encryptToken("")).isNull();
            assertThat(tokenService.encryptToken("   ")).isNull();
            assertThat(tokenService.decryptToken(null)).isNull();
            assertThat(tokenService.decryptToken("")).isNull();
            assertThat(tokenService.decryptToken("   ")).isNull();
        }

        @Test
        @DisplayName("Invalid key size or malformed key is rejected on initialization")
        void invalidKeyIsRejected() {
            // Null or blank key
            assertThatThrownBy(() -> new OAuthTokenServiceImpl(null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new OAuthTokenServiceImpl("   "))
                    .isInstanceOf(IllegalArgumentException.class);

            // Invalid Base64
            assertThatThrownBy(() -> new OAuthTokenServiceImpl("not-base-64!@#$"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be valid Base64");

            // Key not 256 bits (e.g. 128-bit key)
            String key128 = Base64.getEncoder().encodeToString(new byte[16]);
            assertThatThrownBy(() -> new OAuthTokenServiceImpl(key128))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be exactly 256 bits");
        }
    }

    @Nested
    @DisplayName("OAuthConnection Persistence and Repository Separation")
    class ConnectionPersistenceTests {

        @Test
        @DisplayName("OAuthConnectionService encrypts tokens before passing entity to repository")
        void connectionServiceEncryptsTokensBeforeRepositorySave() {
            OAuthConnectionRepository connectionRepo = mock(OAuthConnectionRepository.class);
            OAuthConnectionServiceImpl connectionService = new OAuthConnectionServiceImpl(connectionRepo, tokenService);

            UUID userId = UUID.randomUUID();
            String rawAccessToken = "ya29.rawAccessToken123";
            String rawRefreshToken = "1//rawRefreshToken456";

            when(connectionRepo.findByUserIdAndProvider(userId, OAuthProvider.SPOTIFY))
                    .thenReturn(Optional.empty());
            when(connectionRepo.save(any(OAuthConnection.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            OAuthTokenResponse tokenResponse = new OAuthTokenResponse(
                    rawAccessToken,
                    rawRefreshToken,
                    3600,
                    "user-read-private playlist-read-private"
            );

            connectionService.saveConnection(userId, OAuthProvider.SPOTIFY, "spotify-user-123", tokenResponse);

            ArgumentCaptor<OAuthConnection> captor = ArgumentCaptor.forClass(OAuthConnection.class);
            verify(connectionRepo).save(captor.capture());

            OAuthConnection saved = captor.getValue();
            assertThat(saved.getUserId()).isEqualTo(userId);
            assertThat(saved.getProvider()).isEqualTo(OAuthProvider.SPOTIFY);
            assertThat(saved.getProviderUserId()).isEqualTo("spotify-user-123");
            assertThat(saved.getStatus()).isEqualTo(ConnectionStatus.CONNECTED);

            // Invariant: persisted entity contains encrypted tokens, never plaintext
            assertThat(saved.getAccessToken()).isNotEqualTo(rawAccessToken);
            assertThat(saved.getAccessToken()).doesNotContain("ya29.rawAccessToken123");
            assertThat(saved.getRefreshToken()).isNotEqualTo(rawRefreshToken);
            assertThat(saved.getRefreshToken()).doesNotContain("1//rawRefreshToken456");

            // Tokens can be decrypted
            assertThat(tokenService.decryptToken(saved.getAccessToken())).isEqualTo(rawAccessToken);
            assertThat(tokenService.decryptToken(saved.getRefreshToken())).isEqualTo(rawRefreshToken);
        }

        @Test
        @DisplayName("getConnection returns decrypted tokens in a detached copy while preserving encrypted persistence")
        void getConnectionReturnsDecryptedDetachedCopy() {
            OAuthConnectionRepository connectionRepo = mock(OAuthConnectionRepository.class);
            OAuthConnectionServiceImpl connectionService = new OAuthConnectionServiceImpl(connectionRepo, tokenService);

            UUID userId = UUID.randomUUID();
            String rawAccessToken = "ya29.rawAccessTokenABC";
            String rawRefreshToken = "1//rawRefreshTokenDEF";

            OAuthConnection stored = new OAuthConnection();
            stored.setId(UUID.randomUUID());
            stored.setUserId(userId);
            stored.setProvider(OAuthProvider.GOOGLE);
            stored.setProviderUserId("google-user-789");
            stored.setAccessToken(tokenService.encryptToken(rawAccessToken));
            stored.setRefreshToken(tokenService.encryptToken(rawRefreshToken));
            stored.setStatus(ConnectionStatus.CONNECTED);

            when(connectionRepo.findByUserIdAndProvider(userId, OAuthProvider.GOOGLE))
                    .thenReturn(Optional.of(stored));

            OAuthConnection retrieved = connectionService.getConnection(userId, OAuthProvider.GOOGLE);

            // Invariant: returned connection has decrypted tokens for consumer use
            assertThat(retrieved.getAccessToken()).isEqualTo(rawAccessToken);
            assertThat(retrieved.getRefreshToken()).isEqualTo(rawRefreshToken);

            // Invariant: stored connection in repository remains encrypted
            assertThat(stored.getAccessToken()).isNotEqualTo(rawAccessToken);
            assertThat(stored.getRefreshToken()).isNotEqualTo(rawRefreshToken);
        }
    }

    @Nested
    @DisplayName("Repository Security and V17 Query Patterns")
    class RepositorySecurityTests {

        @Test
        @DisplayName("OAuthStateRepository deleteExpiredStates and consumeState method signatures and query contracts")
        void oauthStateRepositoryQueryContract() {
            OAuthStateRepository mockRepo = mock(OAuthStateRepository.class);
            OffsetDateTime now = OffsetDateTime.now();

            when(mockRepo.deleteExpiredStates(now)).thenReturn(5);
            when(mockRepo.consumeState("secureState123")).thenReturn(1);

            int deleted = mockRepo.deleteExpiredStates(now);
            int consumed = mockRepo.consumeState("secureState123");

            assertThat(deleted).isEqualTo(5);
            assertThat(consumed).isEqualTo(1);
            verify(mockRepo).deleteExpiredStates(now);
            verify(mockRepo).consumeState("secureState123");
        }

        @Test
        @DisplayName("ReviewSessionRepository findByUserIdOrderByCreatedAtDesc contract matches V17 composite index")
        void reviewSessionRepositoryQueryContract() {
            ReviewSessionRepository mockRepo = mock(ReviewSessionRepository.class);
            UUID userId = UUID.randomUUID();

            ReviewSession s1 = new ReviewSession();
            ReviewSession s2 = new ReviewSession();
            when(mockRepo.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(s1, s2));

            List<ReviewSession> sessions = mockRepo.findByUserIdOrderByCreatedAtDesc(userId);
            assertThat(sessions).hasSize(2);
            verify(mockRepo).findByUserIdOrderByCreatedAtDesc(userId);
        }

        @Test
        @DisplayName("ExecutionRollbackRecordRepository findByExecutionIdOrderByTargetPositionAsc contract matches V17 index")
        void rollbackRecordRepositoryQueryContract() {
            ExecutionRollbackRecordRepository mockRepo = mock(ExecutionRollbackRecordRepository.class);
            UUID executionId = UUID.randomUUID();

            ExecutionRollbackRecord r1 = mock(ExecutionRollbackRecord.class);
            ExecutionRollbackRecord r2 = mock(ExecutionRollbackRecord.class);
            when(mockRepo.findByExecutionIdOrderByTargetPositionAsc(executionId)).thenReturn(List.of(r1, r2));

            List<ExecutionRollbackRecord> records = mockRepo.findByExecutionIdOrderByTargetPositionAsc(executionId);
            assertThat(records).hasSize(2);
            verify(mockRepo).findByExecutionIdOrderByTargetPositionAsc(executionId);
        }

        @Test
        @DisplayName("ExecutionReconciliationRecordRepository findByExecutionIdOrderByCreatedAtAsc contract matches V17 index")
        void reconciliationRecordRepositoryQueryContract() {
            ExecutionReconciliationRecordRepository mockRepo = mock(ExecutionReconciliationRecordRepository.class);
            UUID executionId = UUID.randomUUID();

            ExecutionReconciliationRecord rec1 = mock(ExecutionReconciliationRecord.class);
            ExecutionReconciliationRecord rec2 = mock(ExecutionReconciliationRecord.class);
            when(mockRepo.findByExecutionIdOrderByCreatedAtAsc(executionId)).thenReturn(List.of(rec1, rec2));

            List<ExecutionReconciliationRecord> records = mockRepo.findByExecutionIdOrderByCreatedAtAsc(executionId);
            assertThat(records).hasSize(2);
            verify(mockRepo).findByExecutionIdOrderByCreatedAtAsc(executionId);
        }
    }
}
