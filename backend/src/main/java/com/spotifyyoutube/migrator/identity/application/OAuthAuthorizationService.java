package com.spotifyyoutube.migrator.identity.application;

import com.spotifyyoutube.migrator.identity.domain.OAuthProvider;
import java.util.UUID;

public interface OAuthAuthorizationService {
    String generateAuthorizationUrl(UUID userId, OAuthProvider provider);
    void handleCallback(String code, String state, UUID expectedUserId, OAuthProvider provider);
}
