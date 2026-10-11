package com.spotifyyoutube.migrator.youtube.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record AddVideoToPlaylistRequest(
        @NotBlank(message = "Video ID must not be blank")
        String videoId,

        @Min(value = 0, message = "Position must be greater than or equal to 0")
        Integer position
) {}
