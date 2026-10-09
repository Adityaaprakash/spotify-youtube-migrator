package com.spotifyyoutube.migrator.identity.application;

import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.identity.domain.ConnectionStatus;
import com.spotifyyoutube.migrator.identity.domain.OAuthConnection;
import com.spotifyyoutube.migrator.identity.domain.OAuthProvider;
import com.spotifyyoutube.migrator.identity.repository.OAuthConnectionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class OAuthTokenLifecycleServiceImpl implements OAuthTokenLifecycleService {

    private final OAuthConnectionRepository connectionRepository;
    private final OAuthTokenService tokenService;
    private final Map<OAuthProvider, OAuthProviderAdapter> adapters;

    public OAuthTokenLifecycleServiceImpl(
            OAuthConnectionRepository connectionRepository,
            OAuthTokenService tokenService,
            List<OAuthProviderAdapter> adapterList) {
        this.connectionRepository = connectionRepository;
        this.tokenService = tokenService;
        this.adapters = adapterList.stream()
                .collect(Collectors.toMap(OAuthProviderAdapter::getProvider, Function.identity()));
    }

    @Override
    @Transactional
    public OAuthConnection refreshConnectionIfNeeded(UUID userId, OAuthProvider provider) {
        OAuthConnection connection = connectionRepository.findByUserIdAndProvider(userId, provider)
                .orElseThrow(() -> new InvalidStateException("No active OAuth connection found for provider: " + provider.name()));

        if (connection.getStatus() != ConnectionStatus.CONNECTED) {
            return connection;
        }

        // We use a safety window of 5 minutes (300 seconds)
        boolean needsRefresh = connection.getExpiresAt() != null && 
                               OffsetDateTime.now().plusSeconds(300).isAfter(connection.getExpiresAt());

        if (!needsRefresh) {
            return getDecryptedConnection(connection);
        }

        if (connection.getRefreshToken() == null) {
            connection.setStatus(ConnectionStatus.REAUTH_REQUIRED);
            return connectionRepository.save(connection);
        }

        try {
            OAuthProviderAdapter adapter = getAdapter(provider);
            String decryptedRefreshToken = tokenService.decryptToken(connection.getRefreshToken());
            
            OAuthTokenResponse newTokenResponse = adapter.refreshToken(decryptedRefreshToken);

            connection.setAccessToken(tokenService.encryptToken(newTokenResponse.accessToken()));
            if (newTokenResponse.refreshToken() != null && !newTokenResponse.refreshToken().isBlank()) {
                connection.setRefreshToken(tokenService.encryptToken(newTokenResponse.refreshToken()));
            } // Otherwise preserve existing refresh token
            
            connection.setExpiresAt(OffsetDateTime.now().plusSeconds(newTokenResponse.expiresIn()));
            connection.setStatus(ConnectionStatus.CONNECTED);
            
            connectionRepository.save(connection);
        } catch (Exception ex) {
            // For permanent failure or provider outage, we downgrade to REAUTH_REQUIRED if it's considered unrecoverable,
            // but typical OAuth protocol handles invalid_grant gracefully. We will flag REAUTH_REQUIRED.
            connection.setStatus(ConnectionStatus.REAUTH_REQUIRED);
            connectionRepository.save(connection);
        }

        return getDecryptedConnection(connection);
    }

    @Override
    @Transactional
    public void disconnect(UUID userId, OAuthProvider provider) {
        OAuthConnection connection = connectionRepository.findByUserIdAndProvider(userId, provider).orElse(null);
        if (connection != null) {
            connectionRepository.delete(connection);
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
}
