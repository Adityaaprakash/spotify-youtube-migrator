package com.spotifyyoutube.migrator.identity.application;

import com.spotifyyoutube.migrator.identity.domain.OAuthConnection;
import com.spotifyyoutube.migrator.identity.domain.OAuthProvider;

import java.util.UUID;

public interface OAuthTokenLifecycleService {
    OAuthConnection refreshConnectionIfNeeded(UUID userId, OAuthProvider provider);
    void disconnect(UUID userId, OAuthProvider provider);
    OAuthConnection getConnectionStatus(UUID userId, OAuthProvider provider);
}
