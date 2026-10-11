package com.spotifyyoutube.migrator.youtube.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateYouTubePlaylistRequest(
        @NotBlank(message = "Playlist title must not be blank")
        @Size(max = 150, message = "Playlist title must not exceed 150 characters")
        String title,

        @Size(max = 5000, message = "Playlist description must not exceed 5000 characters")
        String description,

        YouTubePrivacyStatus privacyStatus
) {
    public CreateYouTubePlaylistRequest {
        if (privacyStatus == null) {
            privacyStatus = YouTubePrivacyStatus.PRIVATE;
        }
    }
}
