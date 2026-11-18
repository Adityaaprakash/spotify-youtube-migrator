package com.spotifyyoutube.migrator.identity.application;

import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.common.security.logging.SensitiveDataSanitizer;
import com.spotifyyoutube.migrator.identity.domain.ConnectionStatus;
import com.spotifyyoutube.migrator.identity.domain.OAuthConnection;
import com.spotifyyoutube.migrator.identity.domain.OAuthProvider;
import com.spotifyyoutube.migrator.identity.repository.OAuthConnectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Service managing OAuth connection lifecycle, token refreshes, and disconnection.
 *
 * Concurrency Note: In-memory lock stripes coordinate thread concurrency within a single JVM process
 * to prevent duplicate upstream token refreshes and race conditions with disconnects.
 * They do not coordinate across multiple distributed backend instances (distributed locking
 * is deferred to multi-node clustering).
 */
@Service
public class OAuthTokenLifecycleServiceImpl implements OAuthTokenLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(OAuthTokenLifecycleServiceImpl.class);
    private static final int EXPIRY_BUFFER_SECONDS = 300;
    private static final int NUM_LOCK_STRIPES = 256;

    private final OAuthConnectionRepository connectionRepository;
    private final OAuthTokenService tokenService;
    private final Map<OAuthProvider, OAuthProviderAdapter> adapters;
    private final Object[] lockStripes;

    public OAuthTokenLifecycleServiceImpl(
            OAuthConnectionRepository connectionRepository,
            OAuthTokenService tokenService,
            List<OAuthProviderAdapter> adapterList) {
        this.connectionRepository = connectionRepository;
        this.tokenService = tokenService;
        this.adapters = adapterList.stream()
                .collect(Collectors.toMap(OAuthProviderAdapter::getProvider, Function.identity()));
        this.lockStripes = new Object[NUM_LOCK_STRIPES];
        for (int i = 0; i < NUM_LOCK_STRIPES; i++) {
            this.lockStripes[i] = new Object();
        }
    }

    private Object getLock(UUID userId, OAuthProvider provider) {
        String key = userId + ":" + provider.name();
        int stripe = (key.hashCode() & 0x7FFFFFFF) % NUM_LOCK_STRIPES;
        return lockStripes[stripe];
    }

    @Override
    @Transactional
    public OAuthConnection refreshConnectionIfNeeded(UUID userId, OAuthProvider provider) {
        Object lock = getLock(userId, provider);

        synchronized (lock) {
            OAuthConnection connection = connectionRepository.findByUserIdAndProvider(userId, provider)
                    .orElseThrow(() -> new InvalidStateException("No active OAuth connection found for provider: " + provider.name()));

            if (connection.getStatus() != ConnectionStatus.CONNECTED) {
                return connection;
            }

            // Safety window of 5 minutes (300 seconds)
            boolean needsRefresh = connection.getExpiresAt() != null
                    && OffsetDateTime.now().plusSeconds(EXPIRY_BUFFER_SECONDS).isAfter(connection.getExpiresAt());

            if (!needsRefresh) {
                return getDecryptedConnection(connection);
            }

            if (connection.getRefreshToken() == null) {
                log.warn("OAuth connection for user {} and provider {} has expired access token and no refresh token",
                        userId, provider);
                connection.setStatus(ConnectionStatus.REAUTH_REQUIRED);
                return connectionRepository.save(connection);
            }

            try {
                OAuthProviderAdapter adapter = getAdapter(provider);
                String decryptedRefreshToken = tokenService.decryptToken(connection.getRefreshToken());

                OAuthTokenResponse newTokenResponse = adapter.refreshToken(decryptedRefreshToken);

                // Check that the connection was not deleted by a concurrent disconnect while awaiting upstream response
                if (connection.getId() != null && !connectionRepository.existsById(connection.getId())) {
                    log.warn("OAuth connection for user {} and provider {} was deleted during refresh; discarding refreshed token",
                            userId, provider);
                    throw new ConnectionDisconnectedException("OAuth connection was disconnected during refresh");
                }

                connection.setAccessToken(tokenService.encryptToken(newTokenResponse.accessToken()));
                if (newTokenResponse.refreshToken() != null && !newTokenResponse.refreshToken().isBlank()) {
                    connection.setRefreshToken(tokenService.encryptToken(newTokenResponse.refreshToken()));
                } // Otherwise preserve existing refresh token

                connection.setExpiresAt(OffsetDateTime.now().plusSeconds(newTokenResponse.expiresIn()));
                connection.setStatus(ConnectionStatus.CONNECTED);

                connectionRepository.save(connection);
                log.info("OAuth token refreshed successfully userId={} provider={}", userId, provider.name().toLowerCase());
            } catch (ConnectionDisconnectedException ex) {
                throw new InvalidStateException(ex.getMessage());
            } catch (Exception ex) {
                log.warn("Failed to refresh OAuth token for user {} and provider {}. Flagging REAUTH_REQUIRED: {}",
                        userId, provider, SensitiveDataSanitizer.sanitize(ex.getMessage()));
                if (connection.getId() == null || connectionRepository.existsById(connection.getId())) {
                    connection.setStatus(ConnectionStatus.REAUTH_REQUIRED);
                    connectionRepository.save(connection);
                }
            }

            return getDecryptedConnection(connection);
        }
    }

    @Override
    @Transactional
    public void disconnect(UUID userId, OAuthProvider provider) {
        Object lock = getLock(userId, provider);
        synchronized (lock) {
            OAuthConnection connection = connectionRepository.findByUserIdAndProvider(userId, provider).orElse(null);
            if (connection != null) {
                connectionRepository.delete(connection);
                log.info("Disconnected OAuth connection for user {} and provider {}", userId, provider);
            }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public OAuthConnection getConnectionStatus(UUID userId, OAuthProvider provider) {
        return connectionRepository.findByUserIdAndProvider(userId, provider)
                .orElse(null);
    }

    private OAuthProviderAdapter getAdapter(OAuthProvider provider) {
        OAuthProviderAdapter adapter = adapters.get(provider);
        if (adapter == null) {
            throw new ResourceNotFoundException("OAuth provider adapter not configured for: " + provider);
        }
        return adapter;
    }

    private OAuthConnection getDecryptedConnection(OAuthConnection connection) {
        OAuthConnection decrypted = new OAuthConnection();
        decrypted.setId(connection.getId());
        decrypted.setUserId(connection.getUserId());
        decrypted.setProvider(connection.getProvider());
        decrypted.setProviderUserId(connection.getProviderUserId());
        decrypted.setExpiresAt(connection.getExpiresAt());
        decrypted.setScopes(connection.getScopes());
        decrypted.setStatus(connection.getStatus());

        decrypted.setAccessToken(tokenService.decryptToken(connection.getAccessToken()));
        if (connection.getRefreshToken() != null) {
            decrypted.setRefreshToken(tokenService.decryptToken(connection.getRefreshToken()));
        }
        return decrypted;
    }

    private static class ConnectionDisconnectedException extends RuntimeException {
        ConnectionDisconnectedException(String message) {
            super(message);
        }
    }
}
