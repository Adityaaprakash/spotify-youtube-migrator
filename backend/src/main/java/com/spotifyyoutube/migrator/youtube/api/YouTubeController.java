package com.spotifyyoutube.migrator.youtube.api;

import com.spotifyyoutube.migrator.youtube.api.dto.YouTubeVideoSearchResponse;
import com.spotifyyoutube.migrator.youtube.application.YouTubeSearchService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

@RestController
@RequestMapping("/api/youtube")
public class YouTubeController {

    private final YouTubeSearchService searchService;

    public YouTubeController(YouTubeSearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping("/videos/search")
    public ResponseEntity<YouTubeVideoSearchResponse> searchVideos(
            Principal principal,
            @RequestParam("q") String query,
            @RequestParam(value = "pageToken", required = false) String pageToken,
            @RequestParam(value = "maxResults", required = false) Integer maxResults) {

        if (principal == null) {
            return ResponseEntity.status(401).build();
        }

        YouTubeVideoSearchResponse response = searchService.searchVideos(query, pageToken, maxResults);
        return ResponseEntity.ok(response);
    }
}
