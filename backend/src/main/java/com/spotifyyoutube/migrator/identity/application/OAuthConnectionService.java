package com.spotifyyoutube.migrator.identity.application;

import com.spotifyyoutube.migrator.identity.domain.OAuthConnection;
import com.spotifyyoutube.migrator.identity.domain.OAuthProvider;
import java.util.UUID;

public interface OAuthConnectionService {
    OAuthConnection saveConnection(UUID userId, OAuthProvider provider, String providerUserId, OAuthTokenResponse tokenResponse);
    OAuthConnection getConnection(UUID userId, OAuthProvider provider);
}
