package com.spotifyyoutube.migrator.youtube.application;

import com.spotifyyoutube.migrator.common.exception.ExternalProviderException;
import com.spotifyyoutube.migrator.youtube.api.dto.YouTubeVideoSearchResponse;
import com.spotifyyoutube.migrator.youtube.api.dto.YouTubeVideoSummaryResponse;
import com.spotifyyoutube.migrator.youtube.application.mapper.YouTubeMapper;
import com.spotifyyoutube.migrator.youtube.infrastructure.YouTubeClient;
import com.spotifyyoutube.migrator.youtube.infrastructure.YouTubeProperties;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubeSearchListResponseDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class YouTubeSearchServiceTest {

    @Mock
    private YouTubeClient youTubeClient;

    @Mock
    private YouTubeMapper youTubeMapper;

    private YouTubeProperties properties;
    private YouTubeSearchService searchService;

    @BeforeEach
    void setUp() {
        properties = new YouTubeProperties();
        properties.setDefaultMaxResults(25);
        properties.setMaxResultsLimit(50);
        searchService = new YouTubeSearchService(youTubeClient, youTubeMapper, properties);
    }

    @Test
    void searchVideos_shouldReturnMappedResults_whenValidInput() {
        YouTubeSearchListResponseDto mockDto = new YouTubeSearchListResponseDto(
                "youtube#searchListResponse", "etag", "nextToken", null, "US", null, List.of()
        );
        YouTubeVideoSearchResponse expectedResponse = new YouTubeVideoSearchResponse(
                List.of(new YouTubeVideoSummaryResponse("v1", "Title", "Chan", "Desc", "thumb", "2023-01-01")),
                "nextToken", null, 1, 25
        );

        when(youTubeClient.searchVideos("Queen", 25, null)).thenReturn(mockDto);
        when(youTubeMapper.toSearchResponse(mockDto)).thenReturn(expectedResponse);

        YouTubeVideoSearchResponse result = searchService.searchVideos("Queen", null, null);

        assertThat(result).isNotNull();
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).videoId()).isEqualTo("v1");
        verify(youTubeClient).searchVideos("Queen", 25, null);
        verify(youTubeMapper).toSearchResponse(mockDto);
    }

    @Test
    void searchVideos_shouldTrimQueryAndPageToken() {
        YouTubeSearchListResponseDto mockDto = new YouTubeSearchListResponseDto(
                "youtube#searchListResponse", "etag", null, null, null, null, List.of()
        );
        YouTubeVideoSearchResponse expectedResponse = new YouTubeVideoSearchResponse(
                List.of(), null, null, 0, 10
        );

        when(youTubeClient.searchVideos("Bohemian Rhapsody", 10, "TOKEN123")).thenReturn(mockDto);
        when(youTubeMapper.toSearchResponse(mockDto)).thenReturn(expectedResponse);

        YouTubeVideoSearchResponse result = searchService.searchVideos("  Bohemian Rhapsody  ", "  TOKEN123  ", 10);

        assertThat(result).isNotNull();
        verify(youTubeClient).searchVideos("Bohemian Rhapsody", 10, "TOKEN123");
    }

    @Test
    void searchVideos_shouldTreatBlankPageTokenAsNull() {
        YouTubeSearchListResponseDto mockDto = new YouTubeSearchListResponseDto(
                "youtube#searchListResponse", "etag", null, null, null, null, List.of()
        );
        YouTubeVideoSearchResponse expectedResponse = new YouTubeVideoSearchResponse(
                List.of(), null, null, 0, 25
        );

        when(youTubeClient.searchVideos("Queen", 25, null)).thenReturn(mockDto);
        when(youTubeMapper.toSearchResponse(mockDto)).thenReturn(expectedResponse);

        searchService.searchVideos("Queen", "   ", null);

        verify(youTubeClient).searchVideos("Queen", 25, null);
    }

    @Test
    void searchVideos_shouldThrowIllegalArgumentException_whenQueryIsNull() {
        assertThatThrownBy(() -> searchService.searchVideos(null, null, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Search query must not be blank");
    }

    @Test
    void searchVideos_shouldThrowIllegalArgumentException_whenQueryIsBlank() {
        assertThatThrownBy(() -> searchService.searchVideos("   ", null, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Search query must not be blank");
    }

    @Test
    void searchVideos_shouldThrowIllegalArgumentException_whenQueryExceedsMaxLength() {
        String longQuery = "a".repeat(501);

        assertThatThrownBy(() -> searchService.searchVideos(longQuery, null, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds maximum limit of 500 characters");
    }

    @Test
    void searchVideos_shouldThrowIllegalArgumentException_whenMaxResultsIsLessThanOne() {
        assertThatThrownBy(() -> searchService.searchVideos("Queen", null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxResults must be between 1 and 50");
    }

    @Test
    void searchVideos_shouldThrowIllegalArgumentException_whenMaxResultsExceedsLimit() {
        assertThatThrownBy(() -> searchService.searchVideos("Queen", null, 51))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxResults must be between 1 and 50");
    }

    @Test
    void searchVideos_shouldPropagateExternalProviderException_whenClientThrows() {
        when(youTubeClient.searchVideos(eq("Queen"), eq(25), isNull()))
                .thenThrow(new ExternalProviderException("YouTube quota exceeded", 403, null));

        assertThatThrownBy(() -> searchService.searchVideos("Queen", null, null))
                .isInstanceOf(ExternalProviderException.class)
                .satisfies(e -> {
                    ExternalProviderException ex = (ExternalProviderException) e;
                    assertThat(ex.getProviderStatusCode()).isEqualTo(403);
                    assertThat(ex.getMessage()).contains("quota exceeded");
                });
    }

    @Test
    void getVideoDetails_shouldReturnMappedResponse_whenVideoFound() {
        com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubeVideoItemDto item =
                new com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubeVideoItemDto("youtube#video", "etag", "vid123", null, null);
        com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubeVideoListResponseDto responseDto =
                new com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubeVideoListResponseDto(
                        "youtube#videoListResponse", "etagRoot", null, List.of(item)
                );
        com.spotifyyoutube.migrator.youtube.api.dto.YouTubeVideoDetailsResponse expectedResponse =
                new com.spotifyyoutube.migrator.youtube.api.dto.YouTubeVideoDetailsResponse(
                        "vid123", "Title", "Channel", "Desc", "thumb", "2023-01-01", "PT3M30S"
                );

        when(youTubeClient.getVideoMetadata("vid123")).thenReturn(responseDto);
        when(youTubeMapper.toVideoDetailsResponse(item)).thenReturn(expectedResponse);

        com.spotifyyoutube.migrator.youtube.api.dto.YouTubeVideoDetailsResponse result = searchService.getVideoDetails("vid123");

        assertThat(result).isNotNull();
        assertThat(result.videoId()).isEqualTo("vid123");
        assertThat(result.duration()).isEqualTo("PT3M30S");
        verify(youTubeClient).getVideoMetadata("vid123");
        verify(youTubeMapper).toVideoDetailsResponse(item);
    }

    @Test
    void getVideoDetails_shouldThrowIllegalArgumentException_whenVideoIdIsBlank() {
        assertThatThrownBy(() -> searchService.getVideoDetails(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Video ID must not be blank");

        assertThatThrownBy(() -> searchService.getVideoDetails("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Video ID must not be blank");
    }

    @Test
    void getVideoDetails_shouldThrowResourceNotFoundException_whenItemsEmptyOrNull() {
        com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubeVideoListResponseDto emptyResponse =
                new com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubeVideoListResponseDto(
                        "youtube#videoListResponse", "etagRoot", null, List.of()
                );
        when(youTubeClient.getVideoMetadata("missing")).thenReturn(emptyResponse);

        assertThatThrownBy(() -> searchService.getVideoDetails("missing"))
                .isInstanceOf(com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException.class)
                .hasMessageContaining("YouTube video not found: missing");

        when(youTubeClient.getVideoMetadata("nullItems")).thenReturn(
                new com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubeVideoListResponseDto(
                        "youtube#videoListResponse", "etagRoot", null, null
                )
        );
        assertThatThrownBy(() -> searchService.getVideoDetails("nullItems"))
                .isInstanceOf(com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException.class)
                .hasMessageContaining("YouTube video not found: nullItems");
    }

    @Test
    void getVideoDetails_shouldThrowResourceNotFoundException_whenItemHasNullId() {
        com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubeVideoItemDto itemWithNullId =
                new com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubeVideoItemDto("youtube#video", "etag", null, null, null);
        com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubeVideoListResponseDto responseDto =
                new com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubeVideoListResponseDto(
                        "youtube#videoListResponse", "etagRoot", null, List.of(itemWithNullId)
                );
        when(youTubeClient.getVideoMetadata("vNullId")).thenReturn(responseDto);

        assertThatThrownBy(() -> searchService.getVideoDetails("vNullId"))
                .isInstanceOf(com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException.class)
                .hasMessageContaining("YouTube video not found: vNullId");
    }

    @Test
    void getVideoDetails_shouldPropagateExternalProviderException_whenClientThrows() {
        when(youTubeClient.getVideoMetadata("vid123"))
                .thenThrow(new ExternalProviderException("YouTube rate limited", 429, "60"));

        assertThatThrownBy(() -> searchService.getVideoDetails("vid123"))
                .isInstanceOf(ExternalProviderException.class)
                .satisfies(e -> {
                    ExternalProviderException ex = (ExternalProviderException) e;
                    assertThat(ex.getProviderStatusCode()).isEqualTo(429);
                    assertThat(ex.getRetryAfter()).isEqualTo("60");
                });
    }
}
