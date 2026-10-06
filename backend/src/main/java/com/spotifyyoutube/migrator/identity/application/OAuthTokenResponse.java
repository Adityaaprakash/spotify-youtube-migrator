package com.spotifyyoutube.migrator.identity.application;

public record OAuthTokenResponse(
    String accessToken,
    String refreshToken,
    int expiresIn,
    String scopes
) {}
