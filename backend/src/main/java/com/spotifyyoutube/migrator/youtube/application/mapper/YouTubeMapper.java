package com.spotifyyoutube.migrator.youtube.application.mapper;

import com.spotifyyoutube.migrator.youtube.api.dto.YouTubePlaylistItemResponse;
import com.spotifyyoutube.migrator.youtube.api.dto.YouTubePlaylistResponse;
import com.spotifyyoutube.migrator.youtube.api.dto.YouTubeVideoDetailsResponse;
import com.spotifyyoutube.migrator.youtube.api.dto.YouTubeVideoSearchResponse;
import com.spotifyyoutube.migrator.youtube.api.dto.YouTubeVideoSummaryResponse;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubePlaylistItemDto;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubePlaylistItemSnippetResponseDto;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubePlaylistDto;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubePlaylistSnippetDto;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubeSearchListResponseDto;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubeSearchResultDto;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubeSearchSnippetDto;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubeThumbnailDto;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubeVideoItemDto;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubeVideoSnippetDto;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class YouTubeMapper {

    public YouTubeVideoSearchResponse toSearchResponse(YouTubeSearchListResponseDto providerDto) {
        if (providerDto == null) {
            return new YouTubeVideoSearchResponse(List.of(), null, null, 0, 0);
        }

        List<YouTubeVideoSummaryResponse> items = new ArrayList<>();
        if (providerDto.items() != null) {
            for (YouTubeSearchResultDto itemDto : providerDto.items()) {
                YouTubeVideoSummaryResponse summary = toSummaryResponse(itemDto);
                if (summary != null) {
                    items.add(summary);
                }
            }
        }

        int totalResults = 0;
        int resultsPerPage = 0;
        if (providerDto.pageInfo() != null) {
            if (providerDto.pageInfo().totalResults() != null) {
                totalResults = providerDto.pageInfo().totalResults();
            }
            if (providerDto.pageInfo().resultsPerPage() != null) {
                resultsPerPage = providerDto.pageInfo().resultsPerPage();
            }
        }

        return new YouTubeVideoSearchResponse(
                items,
                providerDto.nextPageToken(),
                providerDto.prevPageToken(),
                totalResults,
                resultsPerPage
        );
    }

    public YouTubeVideoSummaryResponse toSummaryResponse(YouTubeSearchResultDto itemDto) {
        if (itemDto == null || itemDto.id() == null || itemDto.id().videoId() == null || itemDto.id().videoId().isBlank()) {
            return null;
        }

        String videoId = itemDto.id().videoId().trim();
        YouTubeSearchSnippetDto snippet = itemDto.snippet();

        String title = null;
        String channelTitle = null;
        String description = null;
        String publishedAt = null;
        String thumbnailUrl = null;

        if (snippet != null) {
            title = snippet.title();
            channelTitle = snippet.channelTitle();
            description = snippet.description();
            publishedAt = snippet.publishedAt();

            thumbnailUrl = extractBestThumbnailUrl(snippet.thumbnails());
        }

        return new YouTubeVideoSummaryResponse(
                videoId,
                title,
                channelTitle,
                description,
                thumbnailUrl,
                publishedAt
        );
    }

    public YouTubeVideoDetailsResponse toVideoDetailsResponse(YouTubeVideoItemDto itemDto) {
        if (itemDto == null || itemDto.id() == null) {
            return null;
        }

        String videoId = itemDto.id();
        YouTubeVideoSnippetDto snippet = itemDto.snippet();

        String title = null;
        String channelTitle = null;
        String description = null;
        String publishedAt = null;
        String thumbnailUrl = null;

        if (snippet != null) {
            title = snippet.title();
            channelTitle = snippet.channelTitle();
            description = snippet.description();
            publishedAt = snippet.publishedAt();

            thumbnailUrl = extractBestThumbnailUrl(snippet.thumbnails());
        }

        String duration = null;
        if (itemDto.contentDetails() != null) {
            duration = itemDto.contentDetails().duration();
        }

        return new YouTubeVideoDetailsResponse(
                videoId,
                title,
                channelTitle,
                description,
                thumbnailUrl,
                publishedAt,
                duration
        );
    }

    public YouTubePlaylistResponse toPlaylistResponse(YouTubePlaylistDto playlistDto) {
        if (playlistDto == null) {
            return null;
        }

        String id = playlistDto.id();
        YouTubePlaylistSnippetDto snippet = playlistDto.snippet();

        String title = null;
        String description = null;
        String publishedAt = null;
        String channelTitle = null;

        if (snippet != null) {
            title = snippet.title();
            description = snippet.description();
            publishedAt = snippet.publishedAt();
            channelTitle = snippet.channelTitle();
        }

        String privacyStatus = null;
        if (playlistDto.status() != null) {
            privacyStatus = playlistDto.status().privacyStatus();
        }

        return new YouTubePlaylistResponse(
                id,
                title,
                description,
                privacyStatus,
                publishedAt,
                channelTitle
        );
    }

    public YouTubePlaylistItemResponse toPlaylistItemResponse(YouTubePlaylistItemDto itemDto) {
        if (itemDto == null) {
            return null;
        }

        String playlistItemId = itemDto.id();
        YouTubePlaylistItemSnippetResponseDto snippet = itemDto.snippet();

        String playlistId = null;
        Integer position = null;
        String videoId = null;

        if (snippet != null) {
            playlistId = snippet.playlistId();
            position = snippet.position();
            if (snippet.resourceId() != null) {
                videoId = snippet.resourceId().videoId();
            }
        }

        return new YouTubePlaylistItemResponse(
                playlistItemId,
                playlistId,
                videoId,
                position,
                true
        );
    }

    private String extractBestThumbnailUrl(Map<String, YouTubeThumbnailDto> thumbnails) {
        if (thumbnails == null || thumbnails.isEmpty()) {
            return null;
        }

        if (thumbnails.containsKey("high") && thumbnails.get("high") != null && thumbnails.get("high").url() != null) {
            return thumbnails.get("high").url();
        }
        if (thumbnails.containsKey("medium") && thumbnails.get("medium") != null && thumbnails.get("medium").url() != null) {
            return thumbnails.get("medium").url();
        }
        if (thumbnails.containsKey("default") && thumbnails.get("default") != null && thumbnails.get("default").url() != null) {
            return thumbnails.get("default").url();
        }

        for (YouTubeThumbnailDto thumb : thumbnails.values()) {
            if (thumb != null && thumb.url() != null) {
                return thumb.url();
            }
        }

        return null;
    }
}
