package com.spotifyyoutube.migrator.youtube.api;

import com.spotifyyoutube.migrator.identity.application.UserService;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.youtube.api.dto.AddVideoToPlaylistRequest;
import com.spotifyyoutube.migrator.youtube.api.dto.CreateYouTubePlaylistRequest;
import com.spotifyyoutube.migrator.youtube.api.dto.YouTubePlaylistItemResponse;
import com.spotifyyoutube.migrator.youtube.api.dto.YouTubePlaylistResponse;
import com.spotifyyoutube.migrator.youtube.api.dto.YouTubeVideoDetailsResponse;
import com.spotifyyoutube.migrator.youtube.api.dto.YouTubeVideoSearchResponse;
import com.spotifyyoutube.migrator.youtube.application.YouTubePlaylistService;
import com.spotifyyoutube.migrator.youtube.application.YouTubeSearchService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

@RestController
@RequestMapping("/api/youtube")
public class YouTubeController {

    private final YouTubeSearchService searchService;
    private final YouTubePlaylistService playlistService;
    private final UserService userService;

    public YouTubeController(YouTubeSearchService searchService,
                             YouTubePlaylistService playlistService,
                             UserService userService) {
        this.searchService = searchService;
        this.playlistService = playlistService;
        this.userService = userService;
    }

    @GetMapping("/videos/search")
    public ResponseEntity<YouTubeVideoSearchResponse> searchVideos(
            Principal principal,
            @RequestParam("q") String query,
            @RequestParam(value = "pageToken", required = false) String pageToken,
            @RequestParam(value = "maxResults", required = false) Integer maxResults) {

        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        YouTubeVideoSearchResponse response = searchService.searchVideos(query, pageToken, maxResults);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/videos/{videoId}")
    public ResponseEntity<YouTubeVideoDetailsResponse> getVideoDetails(
            Principal principal,
            @PathVariable("videoId") String videoId) {

        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        YouTubeVideoDetailsResponse response = searchService.getVideoDetails(videoId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/playlists")
    public ResponseEntity<YouTubePlaylistResponse> createPlaylist(
            Principal principal,
            @Valid @RequestBody CreateYouTubePlaylistRequest request) {

        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        User user = userService.getUserByEmail(principal.getName());
        YouTubePlaylistResponse response = playlistService.createPlaylist(user.getId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/playlists/{playlistId}/videos")
    public ResponseEntity<YouTubePlaylistItemResponse> addVideoToPlaylist(
            Principal principal,
            @PathVariable("playlistId") String playlistId,
            @Valid @RequestBody AddVideoToPlaylistRequest request) {

        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        User user = userService.getUserByEmail(principal.getName());
        YouTubePlaylistItemResponse response = playlistService.addVideoToPlaylist(user.getId(), playlistId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
