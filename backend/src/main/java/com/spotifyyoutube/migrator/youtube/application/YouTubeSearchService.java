package com.spotifyyoutube.migrator.youtube.application;

import com.spotifyyoutube.migrator.youtube.api.dto.YouTubeVideoSearchResponse;
import com.spotifyyoutube.migrator.youtube.application.mapper.YouTubeMapper;
import com.spotifyyoutube.migrator.youtube.infrastructure.YouTubeClient;
import com.spotifyyoutube.migrator.youtube.infrastructure.YouTubeProperties;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubeSearchListResponseDto;
import org.springframework.stereotype.Service;

@Service
public class YouTubeSearchService {

    public static final int MAX_QUERY_LENGTH = 500;

    private final YouTubeClient youTubeClient;
    private final YouTubeMapper youTubeMapper;
    private final YouTubeProperties properties;

    public YouTubeSearchService(YouTubeClient youTubeClient,
                                YouTubeMapper youTubeMapper,
                                YouTubeProperties properties) {
        this.youTubeClient = youTubeClient;
        this.youTubeMapper = youTubeMapper;
        this.properties = properties;
    }

    public YouTubeVideoSearchResponse searchVideos(String query, String pageToken, Integer maxResults) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Search query must not be blank");
        }

        String trimmedQuery = query.trim();
        if (trimmedQuery.length() > MAX_QUERY_LENGTH) {
            throw new IllegalArgumentException("Search query exceeds maximum limit of " + MAX_QUERY_LENGTH + " characters");
        }

        int limit = (maxResults != null) ? maxResults : properties.getDefaultMaxResults();
        if (limit < 1 || limit > properties.getMaxResultsLimit()) {
            throw new IllegalArgumentException("maxResults must be between 1 and " + properties.getMaxResultsLimit());
        }

        String cleanPageToken = (pageToken != null && !pageToken.isBlank()) ? pageToken.trim() : null;

        YouTubeSearchListResponseDto responseDto = youTubeClient.searchVideos(trimmedQuery, limit, cleanPageToken);
        return youTubeMapper.toSearchResponse(responseDto);
    }
}
