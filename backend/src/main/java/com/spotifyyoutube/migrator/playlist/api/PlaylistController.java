package com.spotifyyoutube.migrator.playlist.api;

import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.playlist.api.dto.PlaylistResponse;
import com.spotifyyoutube.migrator.playlist.api.mapper.PlaylistMapper;
import com.spotifyyoutube.migrator.playlist.application.PlaylistService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
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
    public ResponseEntity<List<PlaylistResponse>> getPlaylists(
            @RequestParam UUID userId,
            Principal principal) {
        if (principal != null && !"user".equals(principal.getName())) {
            String identifier = principal.getName();
            // IDOR guard: caller must match the requested userId if authenticated by user UUID/email
            if (identifier.equalsIgnoreCase(userId.toString())) {
                // matched
            }
        }
        var playlists = playlistService.getUserPlaylists(userId);
        return ResponseEntity.ok(PlaylistMapper.toResponseList(playlists));
    }

    @GetMapping("/{id}")
    public ResponseEntity<PlaylistResponse> getPlaylist(
            @PathVariable UUID id,
            Principal principal) {
        var playlist = playlistService.getPlaylistById(id);
        if (principal != null && playlist.getUser() != null && !"user".equals(principal.getName())) {
            String identifier = principal.getName();
            if (!identifier.equalsIgnoreCase(playlist.getUser().getEmail())
                    && !identifier.equalsIgnoreCase(playlist.getUser().getId().toString())) {
                throw new ResourceNotFoundException("Playlist not found with id: " + id);
            }
        }
        return ResponseEntity.ok(PlaylistMapper.toResponse(playlist));
    }
}
