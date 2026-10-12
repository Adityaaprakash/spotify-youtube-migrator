package com.spotifyyoutube.migrator.youtube.application.mapper;

import com.spotifyyoutube.migrator.youtube.api.dto.YouTubeVideoSearchResponse;
import com.spotifyyoutube.migrator.youtube.api.dto.YouTubeVideoSummaryResponse;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubePageInfoDto;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubeResourceIdDto;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubeSearchListResponseDto;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubeSearchResultDto;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubeSearchSnippetDto;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubeThumbnailDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class YouTubeMapperTest {

    private YouTubeMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new YouTubeMapper();
    }

    @Test
    void toSearchResponse_shouldReturnMappedResponse_whenValidProviderDto() {
        YouTubeThumbnailDto highThumb = new YouTubeThumbnailDto("https://img.youtube.com/high.jpg", 480, 360);
        YouTubeThumbnailDto medThumb = new YouTubeThumbnailDto("https://img.youtube.com/med.jpg", 320, 180);
        Map<String, YouTubeThumbnailDto> thumbnails = Map.of("high", highThumb, "medium", medThumb);

        YouTubeSearchSnippetDto snippet = new YouTubeSearchSnippetDto(
                "2023-01-01T12:00:00Z",
                "chan1",
                "Test Video",
                "Test Description",
                thumbnails,
                "Test Channel",
                "none",
                "2023-01-01T12:00:00Z"
        );
        YouTubeResourceIdDto id = new YouTubeResourceIdDto("youtube#video", "vid123", null, null);
        YouTubeSearchResultDto item = new YouTubeSearchResultDto("youtube#searchResult", "etag1", id, snippet);

        YouTubeSearchListResponseDto providerDto = new YouTubeSearchListResponseDto(
                "youtube#searchListResponse",
                "etagRoot",
                "tokenNext",
                "tokenPrev",
                "US",
                new YouTubePageInfoDto(100, 25),
                List.of(item)
        );

        YouTubeVideoSearchResponse response = mapper.toSearchResponse(providerDto);

        assertThat(response).isNotNull();
        assertThat(response.totalResults()).isEqualTo(100);
        assertThat(response.resultsPerPage()).isEqualTo(25);
        assertThat(response.nextPageToken()).isEqualTo("tokenNext");
        assertThat(response.prevPageToken()).isEqualTo("tokenPrev");
        assertThat(response.items()).hasSize(1);

        YouTubeVideoSummaryResponse summary = response.items().get(0);
        assertThat(summary.videoId()).isEqualTo("vid123");
        assertThat(summary.title()).isEqualTo("Test Video");
        assertThat(summary.channelTitle()).isEqualTo("Test Channel");
        assertThat(summary.description()).isEqualTo("Test Description");
        assertThat(summary.thumbnailUrl()).isEqualTo("https://img.youtube.com/high.jpg");
        assertThat(summary.publishedAt()).isEqualTo("2023-01-01T12:00:00Z");
    }

    @Test
    void toSearchResponse_shouldHandleNullProviderDto() {
        YouTubeVideoSearchResponse response = mapper.toSearchResponse(null);

        assertThat(response).isNotNull();
        assertThat(response.items()).isEmpty();
        assertThat(response.totalResults()).isEqualTo(0);
        assertThat(response.resultsPerPage()).isEqualTo(0);
        assertThat(response.nextPageToken()).isNull();
        assertThat(response.prevPageToken()).isNull();
    }

    @Test
    void toSearchResponse_shouldHandleNullItemsAndNullPageInfo() {
        YouTubeSearchListResponseDto providerDto = new YouTubeSearchListResponseDto(
                "youtube#searchListResponse", "etag", null, null, null, null, null
        );

        YouTubeVideoSearchResponse response = mapper.toSearchResponse(providerDto);

        assertThat(response).isNotNull();
        assertThat(response.items()).isEmpty();
        assertThat(response.totalResults()).isEqualTo(0);
        assertThat(response.resultsPerPage()).isEqualTo(0);
    }

    @Test
    void toSearchResponse_shouldFilterOutItemsWithNullIdOrVideoId() {
        YouTubeSearchResultDto nullItem = null;
        YouTubeSearchResultDto itemWithNullId = new YouTubeSearchResultDto("youtube#searchResult", "etag", null, null);
        YouTubeSearchResultDto itemWithNullVideoId = new YouTubeSearchResultDto(
                "youtube#searchResult", "etag", new YouTubeResourceIdDto("youtube#video", null, null, null), null
        );
        YouTubeSearchResultDto itemWithBlankVideoId = new YouTubeSearchResultDto(
                "youtube#searchResult", "etag", new YouTubeResourceIdDto("youtube#video", "   ", null, null), null
        );
        YouTubeSearchResultDto validItem = new YouTubeSearchResultDto(
                "youtube#searchResult", "etag", new YouTubeResourceIdDto("youtube#video", "  v1  ", null, null), null
        );

        YouTubeSearchListResponseDto providerDto = new YouTubeSearchListResponseDto(
                "youtube#searchListResponse", "etag", null, null, null,
                new YouTubePageInfoDto(1, 1),
                java.util.Arrays.asList(nullItem, itemWithNullId, itemWithNullVideoId, itemWithBlankVideoId, validItem)
        );

        YouTubeVideoSearchResponse response = mapper.toSearchResponse(providerDto);

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).videoId()).isEqualTo("v1");
    }

    @Test
    void toSummaryResponse_shouldFallbackToMediumThumbnail_whenHighNotAvailable() {
        YouTubeThumbnailDto medThumb = new YouTubeThumbnailDto("https://img.youtube.com/med.jpg", 320, 180);
        Map<String, YouTubeThumbnailDto> thumbnails = Map.of("medium", medThumb);

        YouTubeSearchSnippetDto snippet = new YouTubeSearchSnippetDto(
                "2023-01-01T00:00:00Z", "c1", "Title", "Desc", thumbnails, "Chan", "none", "2023-01-01"
        );
        YouTubeSearchResultDto item = new YouTubeSearchResultDto(
                "youtube#searchResult", "etag", new YouTubeResourceIdDto("youtube#video", "v1", null, null), snippet
        );

        YouTubeVideoSummaryResponse result = mapper.toSummaryResponse(item);

        assertThat(result.thumbnailUrl()).isEqualTo("https://img.youtube.com/med.jpg");
    }

    @Test
    void toSummaryResponse_shouldFallbackToDefaultThumbnail_whenHighAndMediumNotAvailable() {
        YouTubeThumbnailDto defThumb = new YouTubeThumbnailDto("https://img.youtube.com/default.jpg", 120, 90);
        Map<String, YouTubeThumbnailDto> thumbnails = Map.of("default", defThumb);

        YouTubeSearchSnippetDto snippet = new YouTubeSearchSnippetDto(
                "2023-01-01T00:00:00Z", "c1", "Title", "Desc", thumbnails, "Chan", "none", "2023-01-01"
        );
        YouTubeSearchResultDto item = new YouTubeSearchResultDto(
                "youtube#searchResult", "etag", new YouTubeResourceIdDto("youtube#video", "v1", null, null), snippet
        );

        YouTubeVideoSummaryResponse result = mapper.toSummaryResponse(item);

        assertThat(result.thumbnailUrl()).isEqualTo("https://img.youtube.com/default.jpg");
    }

    @Test
    void toSummaryResponse_shouldFallbackToAnyThumbnail_whenStandardKeysMissing() {
        YouTubeThumbnailDto customThumb = new YouTubeThumbnailDto("https://img.youtube.com/custom.jpg", 640, 480);
        Map<String, YouTubeThumbnailDto> thumbnails = Map.of("standard", customThumb);

        YouTubeSearchSnippetDto snippet = new YouTubeSearchSnippetDto(
                "2023-01-01T00:00:00Z", "c1", "Title", "Desc", thumbnails, "Chan", "none", "2023-01-01"
        );
        YouTubeSearchResultDto item = new YouTubeSearchResultDto(
                "youtube#searchResult", "etag", new YouTubeResourceIdDto("youtube#video", "v1", null, null), snippet
        );

        YouTubeVideoSummaryResponse result = mapper.toSummaryResponse(item);

        assertThat(result.thumbnailUrl()).isEqualTo("https://img.youtube.com/custom.jpg");
    }

    @Test
    void toSummaryResponse_shouldHandleNullSnippetGracefully() {
        YouTubeSearchResultDto item = new YouTubeSearchResultDto(
                "youtube#searchResult", "etag", new YouTubeResourceIdDto("youtube#video", "v1", null, null), null
        );

        YouTubeVideoSummaryResponse result = mapper.toSummaryResponse(item);

        assertThat(result).isNotNull();
        assertThat(result.videoId()).isEqualTo("v1");
        assertThat(result.title()).isNull();
        assertThat(result.thumbnailUrl()).isNull();
    }

    @Test
    void toSummaryResponse_shouldHandleNullAndEmptyThumbnailsGracefully() {
        YouTubeSearchSnippetDto snippetNullThumb = new YouTubeSearchSnippetDto(
                "2023-01-01", "c1", "Title", "Desc", null, "Chan", "none", "2023-01-01"
        );
        YouTubeSearchResultDto item1 = new YouTubeSearchResultDto(
                "youtube#searchResult", "etag", new YouTubeResourceIdDto("youtube#video", "v1", null, null), snippetNullThumb
        );
        assertThat(mapper.toSummaryResponse(item1).thumbnailUrl()).isNull();

        YouTubeSearchSnippetDto snippetEmptyThumb = new YouTubeSearchSnippetDto(
                "2023-01-01", "c1", "Title", "Desc", Collections.emptyMap(), "Chan", "none", "2023-01-01"
        );
        YouTubeSearchResultDto item2 = new YouTubeSearchResultDto(
                "youtube#searchResult", "etag", new YouTubeResourceIdDto("youtube#video", "v2", null, null), snippetEmptyThumb
        );
        assertThat(mapper.toSummaryResponse(item2).thumbnailUrl()).isNull();

        Map<String, YouTubeThumbnailDto> mapWithNullValue = new HashMap<>();
        mapWithNullValue.put("high", null);
        YouTubeSearchSnippetDto snippetNullValue = new YouTubeSearchSnippetDto(
                "2023-01-01", "c1", "Title", "Desc", mapWithNullValue, "Chan", "none", "2023-01-01"
        );
        YouTubeSearchResultDto item3 = new YouTubeSearchResultDto(
                "youtube#searchResult", "etag", new YouTubeResourceIdDto("youtube#video", "v3", null, null), snippetNullValue
        );
        assertThat(mapper.toSummaryResponse(item3).thumbnailUrl()).isNull();
    }

    @Test
    void toVideoDetailsResponse_shouldReturnMappedResponse_whenValidItemDto() {
        YouTubeThumbnailDto highThumb = new YouTubeThumbnailDto("https://img.youtube.com/high.jpg", 480, 360);
        Map<String, YouTubeThumbnailDto> thumbnails = Map.of("high", highThumb);
        com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubeVideoSnippetDto snippet =
                new com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubeVideoSnippetDto(
                        "2023-05-01T10:00:00Z", "chanId1", "Video Title", "Video Description",
                        thumbnails, "Channel One"
                );
        com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubeVideoContentDetailsDto contentDetails =
                new com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubeVideoContentDetailsDto("PT4M20S");
        com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubeVideoItemDto itemDto =
                new com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubeVideoItemDto(
                        "youtube#video", "etag1", "vid999", snippet, contentDetails
                );

        com.spotifyyoutube.migrator.youtube.api.dto.YouTubeVideoDetailsResponse response = mapper.toVideoDetailsResponse(itemDto);

        assertThat(response).isNotNull();
        assertThat(response.videoId()).isEqualTo("vid999");
        assertThat(response.title()).isEqualTo("Video Title");
        assertThat(response.channelTitle()).isEqualTo("Channel One");
        assertThat(response.description()).isEqualTo("Video Description");
        assertThat(response.thumbnailUrl()).isEqualTo("https://img.youtube.com/high.jpg");
        assertThat(response.publishedAt()).isEqualTo("2023-05-01T10:00:00Z");
        assertThat(response.duration()).isEqualTo("PT4M20S");
    }

    @Test
    void toVideoDetailsResponse_shouldHandleNullsAndMissingContentDetails() {
        assertThat(mapper.toVideoDetailsResponse(null)).isNull();

        com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubeVideoItemDto itemWithNullId =
                new com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubeVideoItemDto(
                        "youtube#video", "etag", null, null, null
                );
        assertThat(mapper.toVideoDetailsResponse(itemWithNullId)).isNull();

        com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubeVideoItemDto itemWithNoDetails =
                new com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubeVideoItemDto(
                        "youtube#video", "etag", "vidNoDetails", null, null
                );
        com.spotifyyoutube.migrator.youtube.api.dto.YouTubeVideoDetailsResponse res = mapper.toVideoDetailsResponse(itemWithNoDetails);
        assertThat(res).isNotNull();
        assertThat(res.videoId()).isEqualTo("vidNoDetails");
        assertThat(res.title()).isNull();
        assertThat(res.duration()).isNull();
    }

    @Test
    void toPlaylistResponse_shouldReturnMappedResponse_whenValidPlaylistDto() {
        com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubePlaylistSnippetDto snippet =
                new com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubePlaylistSnippetDto(
                        "Playlist Title", "Playlist Description", "2023-06-01T00:00:00Z",
                        "chanId", "Chan Name", null
                );
        com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubePlaylistStatusDto status =
                new com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubePlaylistStatusDto("private");
        com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubePlaylistDto playlistDto =
                new com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubePlaylistDto(
                        "youtube#playlist", "etagPL", "PL123", snippet, status
                );

        com.spotifyyoutube.migrator.youtube.api.dto.YouTubePlaylistResponse response = mapper.toPlaylistResponse(playlistDto);

        assertThat(response).isNotNull();
        assertThat(response.id()).isEqualTo("PL123");
        assertThat(response.title()).isEqualTo("Playlist Title");
        assertThat(response.description()).isEqualTo("Playlist Description");
        assertThat(response.privacyStatus()).isEqualTo("private");
        assertThat(response.publishedAt()).isEqualTo("2023-06-01T00:00:00Z");
        assertThat(response.channelTitle()).isEqualTo("Chan Name");
    }

    @Test
    void toPlaylistResponse_shouldHandleNullsGracefully() {
        assertThat(mapper.toPlaylistResponse(null)).isNull();

        com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubePlaylistDto minimalDto =
                new com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubePlaylistDto(
                        "youtube#playlist", "etag", "PLmin", null, null
                );
        com.spotifyyoutube.migrator.youtube.api.dto.YouTubePlaylistResponse response = mapper.toPlaylistResponse(minimalDto);
        assertThat(response).isNotNull();
        assertThat(response.id()).isEqualTo("PLmin");
        assertThat(response.title()).isNull();
        assertThat(response.privacyStatus()).isNull();
    }

    @Test
    void toPlaylistItemResponse_shouldReturnMappedResponse_whenValidPlaylistItemDto() {
        YouTubeResourceIdDto resourceId = new YouTubeResourceIdDto("youtube#video", "vid456", null, null);
        com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubePlaylistItemSnippetResponseDto snippet =
                new com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubePlaylistItemSnippetResponseDto(
                        "PL123", 2, resourceId, "Item Title", "Item Desc", null
                );
        com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubePlaylistItemDto itemDto =
                new com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubePlaylistItemDto(
                        "youtube#playlistItem", "etagItem", "pli789", snippet
                );

        com.spotifyyoutube.migrator.youtube.api.dto.YouTubePlaylistItemResponse response = mapper.toPlaylistItemResponse(itemDto);

        assertThat(response).isNotNull();
        assertThat(response.playlistItemId()).isEqualTo("pli789");
        assertThat(response.playlistId()).isEqualTo("PL123");
        assertThat(response.videoId()).isEqualTo("vid456");
        assertThat(response.position()).isEqualTo(2);
        assertThat(response.success()).isTrue();
    }

    @Test
    void toPlaylistItemResponse_shouldHandleNullsGracefully() {
        assertThat(mapper.toPlaylistItemResponse(null)).isNull();

        com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubePlaylistItemDto minimalDto =
                new com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubePlaylistItemDto(
                        "youtube#playlistItem", "etag", "pliMin", null
                );
        com.spotifyyoutube.migrator.youtube.api.dto.YouTubePlaylistItemResponse response = mapper.toPlaylistItemResponse(minimalDto);
        assertThat(response).isNotNull();
        assertThat(response.playlistItemId()).isEqualTo("pliMin");
        assertThat(response.playlistId()).isNull();
        assertThat(response.videoId()).isNull();
        assertThat(response.position()).isNull();
        assertThat(response.success()).isTrue();
    }
}
