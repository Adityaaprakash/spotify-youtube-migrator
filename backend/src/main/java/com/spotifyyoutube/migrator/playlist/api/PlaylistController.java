package com.spotifyyoutube.migrator.playlist.api;

import com.spotifyyoutube.migrator.playlist.api.dto.PlaylistResponse;
import com.spotifyyoutube.migrator.playlist.api.mapper.PlaylistMapper;
import com.spotifyyoutube.migrator.playlist.application.PlaylistService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/playlists")
public class PlaylistController {

    private final PlaylistService playlistService;

    public PlaylistController(PlaylistService playlistService) {
        this.playlistService = playlistService;
    }

    @GetMapping
    public ResponseEntity<List<PlaylistResponse>> getPlaylists(@RequestParam UUID userId) {
        // In this phase, we require userId explicitly since we don't have OAuth session extraction
        var playlists = playlistService.getUserPlaylists(userId);
        return ResponseEntity.ok(PlaylistMapper.toResponseList(playlists));
    }

    @GetMapping("/{id}")
    public ResponseEntity<PlaylistResponse> getPlaylist(@PathVariable UUID id) {
        var playlist = playlistService.getPlaylistById(id);
        return ResponseEntity.ok(PlaylistMapper.toResponse(playlist));
    }
}
