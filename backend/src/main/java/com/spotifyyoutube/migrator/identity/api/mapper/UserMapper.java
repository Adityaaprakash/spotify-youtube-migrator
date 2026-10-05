package com.spotifyyoutube.migrator.identity.api.mapper;

import com.spotifyyoutube.migrator.identity.api.dto.UserResponse;
import com.spotifyyoutube.migrator.identity.domain.User;

public class UserMapper {

    private UserMapper() {
        // Utility class
    }

    public static UserResponse toResponse(User user) {
        if (user == null) {
            return null;
        }
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getSpotifyId(),
                user.getYoutubeId()
        );
    }
}
