package com.spotifyyoutube.migrator.youtube.api.dto;

public record YouTubeVideoSummaryResponse(
        String videoId,
        String title,
        String channelTitle,
        String description,
        String thumbnailUrl,
        String publishedAt
) {}
