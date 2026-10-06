package com.spotifyyoutube.migrator.identity.application;

public interface OAuthTokenService {
    String encryptToken(String plaintextToken);
    String decryptToken(String encryptedToken);
}
