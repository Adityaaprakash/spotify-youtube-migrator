package com.spotifyyoutube.migrator.identity.application;

import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.identity.domain.OAuthConnection;
import com.spotifyyoutube.migrator.identity.domain.OAuthProvider;
import com.spotifyyoutube.migrator.identity.repository.OAuthConnectionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
public class OAuthConnectionServiceImpl implements OAuthConnectionService {

    private final OAuthConnectionRepository connectionRepository;
    private final OAuthTokenService tokenService;

    public OAuthConnectionServiceImpl(OAuthConnectionRepository connectionRepository, OAuthTokenService tokenService) {
        this.connectionRepository = connectionRepository;
        this.tokenService = tokenService;
    }

    @Override
    @Transactional
    public OAuthConnection saveConnection(UUID userId, OAuthProvider provider, String providerUserId, OAuthTokenResponse tokenResponse) {
        OAuthConnection connection = connectionRepository.findByUserIdAndProvider(userId, provider)
                .orElse(new OAuthConnection());
        
        connection.setUserId(userId);
        connection.setProvider(provider);
        connection.setProviderUserId(providerUserId);

        connection.setAccessToken(tokenService.encryptToken(tokenResponse.accessToken()));
        if (tokenResponse.refreshToken() != null) {
            connection.setRefreshToken(tokenService.encryptToken(tokenResponse.refreshToken()));
        }

        connection.setExpiresAt(OffsetDateTime.now().plusSeconds(tokenResponse.expiresIn()));
        connection.setScopes(tokenResponse.scopes());

        return connectionRepository.save(connection);
    }

    @Override
    @Transactional(readOnly = true)
    public OAuthConnection getConnection(UUID userId, OAuthProvider provider) {
        OAuthConnection connection = connectionRepository.findByUserIdAndProvider(userId, provider)
                .orElseThrow(() -> new ResourceNotFoundException("No OAuth connection found for provider: " + provider.name()));
        
        // Return a localized detached copy with decrypted tokens for memory convenience, OR we could keep entities encrypted and just decrypt here.
        // For safety, we keep the entity bounded, we shouldn't necessarily modify the entity in JPA context if it's transient read.
        
        OAuthConnection decrypted = new OAuthConnection();
        decrypted.setId(connection.getId());
        decrypted.setUserId(connection.getUserId());
        decrypted.setProvider(connection.getProvider());
        decrypted.setProviderUserId(connection.getProviderUserId());
        decrypted.setExpiresAt(connection.getExpiresAt());
        decrypted.setScopes(connection.getScopes());

        // Decrypt the tokens
        decrypted.setAccessToken(tokenService.decryptToken(connection.getAccessToken()));
        if (connection.getRefreshToken() != null) {
            decrypted.setRefreshToken(tokenService.decryptToken(connection.getRefreshToken()));
        }
        
        return decrypted;
    }
}
