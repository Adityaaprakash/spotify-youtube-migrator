package com.spotifyyoutube.migrator.youtube.application.mapper;

import com.spotifyyoutube.migrator.youtube.api.dto.YouTubeVideoSearchResponse;
import com.spotifyyoutube.migrator.youtube.api.dto.YouTubeVideoSummaryResponse;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubeSearchListResponseDto;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubeSearchResultDto;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubeSearchSnippetDto;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubeThumbnailDto;
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
        if (itemDto == null || itemDto.id() == null || itemDto.id().videoId() == null) {
            return null;
        }

        String videoId = itemDto.id().videoId();
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
