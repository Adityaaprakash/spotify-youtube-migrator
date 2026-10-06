package com.spotifyyoutube.migrator.identity.application;

import com.spotifyyoutube.migrator.identity.domain.OAuthProvider;
import com.spotifyyoutube.migrator.identity.domain.OAuthState;
import java.util.UUID;

public interface OAuthStateService {
    OAuthState generateState(UUID userId, OAuthProvider provider);
    OAuthState validateAndConsumeState(String state, UUID expectedUserId, OAuthProvider provider);
}
