package com.spotifyyoutube.migrator.identity.application;

import com.spotifyyoutube.migrator.identity.domain.OAuthProvider;

public interface OAuthProviderAdapter {
    OAuthProvider getProvider();
    String getAuthorizationUrl(String state);
    OAuthTokenResponse exchangeCode(String code);
    OAuthTokenResponse refreshToken(String refreshToken);
    String getProviderIdentity(String accessToken); // returns the provider's user ID
}
