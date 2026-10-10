package com.spotifyyoutube.migrator.spotify.api;

import com.spotifyyoutube.migrator.identity.application.UserService;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.spotify.api.dto.SpotifyPageResponse;
import com.spotifyyoutube.migrator.spotify.api.dto.SpotifyPlaylistSummaryResponse;
import com.spotifyyoutube.migrator.spotify.api.dto.SpotifyProfileResponse;
import com.spotifyyoutube.migrator.spotify.application.SpotifyIdentityService;
import com.spotifyyoutube.migrator.spotify.application.SpotifyPlaylistService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import com.spotifyyoutube.migrator.playlist.api.dto.PlaylistResponse;
import com.spotifyyoutube.migrator.playlist.api.dto.TrackResponse;

@RestController
@RequestMapping("/api/spotify")
public class SpotifyController {

    private final SpotifyIdentityService identityService;
    private final SpotifyPlaylistService playlistService;
    private final UserService userService;

    public SpotifyController(SpotifyIdentityService identityService,
                             SpotifyPlaylistService playlistService,
                             UserService userService) {
        this.identityService = identityService;
        this.playlistService = playlistService;
        this.userService = userService;
    }

    @GetMapping("/me")
    public ResponseEntity<SpotifyProfileResponse> getCurrentProfile(java.security.Principal principal) {
        if (principal == null) {
            return ResponseEntity.status(401).build();
        }
        User user = userService.getUserByEmail(principal.getName());
        return ResponseEntity.ok(identityService.getConnectedProfile(user.getId()));
    }

    @GetMapping("/playlists")
    public ResponseEntity<SpotifyPageResponse<SpotifyPlaylistSummaryResponse>> getPlaylists(
            java.security.Principal principal,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        if (principal == null) {
            return ResponseEntity.status(401).build();
        }
        User user = userService.getUserByEmail(principal.getName());
        return ResponseEntity.ok(playlistService.getPlaylists(user.getId(), limit, offset));
    }

    @GetMapping("/playlists/{playlistId}")
    public ResponseEntity<PlaylistResponse> getPlaylistMetadata(
            java.security.Principal principal,
            @PathVariable("playlistId") String playlistId) {
        if (principal == null) {
            return ResponseEntity.status(401).build();
        }
        User user = userService.getUserByEmail(principal.getName());
        return ResponseEntity.ok(playlistService.getPlaylistMetadata(user.getId(), playlistId));
    }

    @GetMapping("/playlists/{playlistId}/tracks")
    public ResponseEntity<List<TrackResponse>> getPlaylistTracks(
            java.security.Principal principal,
            @PathVariable("playlistId") String playlistId) {
        if (principal == null) {
            return ResponseEntity.status(401).build();
        }
        User user = userService.getUserByEmail(principal.getName());
        return ResponseEntity.ok(playlistService.getPlaylistTracks(user.getId(), playlistId));
    }
}
