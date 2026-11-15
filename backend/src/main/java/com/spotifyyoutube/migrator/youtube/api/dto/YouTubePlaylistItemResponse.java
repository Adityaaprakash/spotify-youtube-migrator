package com.spotifyyoutube.migrator.youtube.api.dto;

public record YouTubePlaylistItemResponse(
        String playlistItemId,
        String playlistId,
        String videoId,
        Integer position,
        boolean success
) {
    public YouTubePlaylistItemResponse(String playlistItemId, String playlistId, String videoId, Integer position, java.time.Instant timestamp) {
        this(playlistItemId, playlistId, videoId, position, true);
    }
}
