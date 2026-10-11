package com.spotifyyoutube.migrator.youtube.api.dto;

public record YouTubeVideoDetailsResponse(
        String videoId,
        String title,
        String channelTitle,
        String description,
        String thumbnailUrl,
        String publishedAt,
        String duration
) {}
