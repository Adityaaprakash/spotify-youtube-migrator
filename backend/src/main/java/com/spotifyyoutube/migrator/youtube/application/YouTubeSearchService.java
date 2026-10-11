package com.spotifyyoutube.migrator.youtube.application;

import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.youtube.api.dto.YouTubeVideoDetailsResponse;
import com.spotifyyoutube.migrator.youtube.api.dto.YouTubeVideoSearchResponse;
import com.spotifyyoutube.migrator.youtube.application.mapper.YouTubeMapper;
import com.spotifyyoutube.migrator.youtube.infrastructure.YouTubeClient;
import com.spotifyyoutube.migrator.youtube.infrastructure.YouTubeProperties;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubeSearchListResponseDto;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubeVideoItemDto;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubeVideoListResponseDto;
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

    public YouTubeVideoDetailsResponse getVideoDetails(String videoId) {
        if (videoId == null || videoId.isBlank()) {
            throw new IllegalArgumentException("Video ID must not be blank");
        }

        String trimmedVideoId = videoId.trim();
        YouTubeVideoListResponseDto responseDto = youTubeClient.getVideoMetadata(trimmedVideoId);

        if (responseDto == null || responseDto.items() == null || responseDto.items().isEmpty()) {
            throw new ResourceNotFoundException("YouTube video not found: " + trimmedVideoId);
        }

        YouTubeVideoItemDto itemDto = responseDto.items().get(0);
        if (itemDto == null || itemDto.id() == null) {
            throw new ResourceNotFoundException("YouTube video not found: " + trimmedVideoId);
        }

        return youTubeMapper.toVideoDetailsResponse(itemDto);
    }
}
