package com.spotifyyoutube.migrator.identity.api.dto;

import java.util.UUID;

import com.spotifyyoutube.migrator.identity.domain.UserStatus;

public record UserResponse(
        UUID id,
        String email,
        String displayName,
        UserStatus status
) {}
