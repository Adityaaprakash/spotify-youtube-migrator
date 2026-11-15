package com.spotifyyoutube.migrator.youtube.api.dto;

public record YouTubePlaylistResponse(
        String id,
        String title,
        String description,
        String privacyStatus,
        String publishedAt,
        String channelTitle
) {
    public YouTubePlaylistResponse(String id, String title, String description, String privacyStatus, int itemCount, String url) {
        this(id, title, description, privacyStatus, null, null);
    }
}
