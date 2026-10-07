package com.spotifyyoutube.migrator.identity.api.dto;

import java.time.OffsetDateTime;

public record OAuthConnectionStatusDto(
    String provider,
    boolean connected,
    String providerUserId,
    OffsetDateTime expiresAt,
    String status
) {}
