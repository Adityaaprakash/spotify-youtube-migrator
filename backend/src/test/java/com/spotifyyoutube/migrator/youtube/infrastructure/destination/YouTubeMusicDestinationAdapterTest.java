package com.spotifyyoutube.migrator.youtube.infrastructure.destination;

import com.spotifyyoutube.migrator.migration.application.destination.model.AddTrackToDestinationPlaylistRequest;
import com.spotifyyoutube.migrator.migration.application.destination.model.CreateDestinationPlaylistRequest;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationPlaylist;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationPlaylistItem;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationPlaylistVerification;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationSearchResult;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationTrackCandidate;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationTrackDetails;
import com.spotifyyoutube.migrator.migration.application.destination.model.PlaylistVerificationStatus;
import com.spotifyyoutube.migrator.playlist.domain.Platform;
import com.spotifyyoutube.migrator.youtube.api.dto.AddVideoToPlaylistRequest;
import com.spotifyyoutube.migrator.youtube.api.dto.CreateYouTubePlaylistRequest;
import com.spotifyyoutube.migrator.youtube.api.dto.YouTubePlaylistItemResponse;
import com.spotifyyoutube.migrator.youtube.api.dto.YouTubePlaylistResponse;
import com.spotifyyoutube.migrator.youtube.api.dto.YouTubePrivacyStatus;
import com.spotifyyoutube.migrator.youtube.api.dto.YouTubeVideoDetailsResponse;
import com.spotifyyoutube.migrator.youtube.api.dto.YouTubeVideoSearchResponse;
import com.spotifyyoutube.migrator.youtube.api.dto.YouTubeVideoSummaryResponse;
import com.spotifyyoutube.migrator.youtube.application.YouTubePlaylistService;
import com.spotifyyoutube.migrator.youtube.application.YouTubeSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class YouTubeMusicDestinationAdapterTest {

    @Mock
    private YouTubeSearchService searchService;

    @Mock
    private YouTubePlaylistService playlistService;

    private YouTubeMusicDestinationAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new YouTubeMusicDestinationAdapter(searchService, playlistService);
    }

    @Test
    void getPlatform_shouldReturnYouTube() {
        assertThat(adapter.getPlatform()).isEqualTo(Platform.YOUTUBE);
    }

    @Test
    void searchTracks_shouldCallSearchServiceAndMapResults() {
        YouTubeVideoSummaryResponse item = new YouTubeVideoSummaryResponse(
                "vid123", "Song Title", "Artist Channel", "Song description",
                "https://thumbnail.url", "2026-10-10T00:00:00Z"
        );
        YouTubeVideoSearchResponse searchResponse = new YouTubeVideoSearchResponse(
                List.of(item), "token456", null, 1, 25
        );

        when(searchService.searchVideos("Song Title", null, 10)).thenReturn(searchResponse);

        DestinationSearchResult result = adapter.searchTracks("Song Title", null, 10);

        assertThat(result).isNotNull();
        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().get(0).id()).isEqualTo("vid123");
        assertThat(result.candidates().get(0).title()).isEqualTo("Song Title");
        assertThat(result.candidates().get(0).channelOrArtist()).isEqualTo("Artist Channel");
        assertThat(result.candidates().get(0).description()).isEqualTo("Song description");
        assertThat(result.candidates().get(0).thumbnailUrl()).isEqualTo("https://thumbnail.url");
        assertThat(result.candidates().get(0).publishedAt()).isEqualTo("2026-10-10T00:00:00Z");
        assertThat(result.candidates().get(0).duration()).isNull();
        assertThat(result.nextPageToken()).isEqualTo("token456");
        assertThat(result.totalResults()).isEqualTo(1);
    }

    @Test
    void searchTracks_shouldFilterOutNullAndBlankVideoIdsAndPreserveValidCandidates() {
        YouTubeVideoSummaryResponse valid1 = new YouTubeVideoSummaryResponse(
                "vid1", "Title 1", "Artist 1", "Desc 1", "https://thumb1.jpg", "2023-01-01"
        );
        YouTubeVideoSummaryResponse nullId = new YouTubeVideoSummaryResponse(
                null, "Title 2", "Artist 2", "Desc 2", "https://thumb2.jpg", "2023-01-01"
        );
        YouTubeVideoSummaryResponse emptyId = new YouTubeVideoSummaryResponse(
                "", "Title 3", "Artist 3", "Desc 3", "https://thumb3.jpg", "2023-01-01"
        );
        YouTubeVideoSummaryResponse blankId = new YouTubeVideoSummaryResponse(
                "   ", "Title 4", "Artist 4", "Desc 4", "https://thumb4.jpg", "2023-01-01"
        );
        YouTubeVideoSummaryResponse valid2WithWhitespace = new YouTubeVideoSummaryResponse(
                "  vid2  ", "Title 5", "Artist 5", "Desc 5", "https://thumb5.jpg", "2023-01-01"
        );

        List<YouTubeVideoSummaryResponse> items = new java.util.ArrayList<>();
        items.add(valid1);
        items.add(null);
        items.add(nullId);
        items.add(emptyId);
        items.add(blankId);
        items.add(valid2WithWhitespace);

        YouTubeVideoSearchResponse searchResponse = new YouTubeVideoSearchResponse(
                items, null, null, 2, 25
        );

        when(searchService.searchVideos("query", null, 10)).thenReturn(searchResponse);

        DestinationSearchResult result = adapter.searchTracks("query", null, 10);

        assertThat(result.candidates()).hasSize(2);
        assertThat(result.candidates().get(0).id()).isEqualTo("vid1");
        assertThat(result.candidates().get(1).id()).isEqualTo("vid2");
    }

    @Test
    void searchTracks_shouldPreserveNullOptionalMetadataSafely() {
        YouTubeVideoSummaryResponse minimalItem = new YouTubeVideoSummaryResponse(
                "vidMinimal", null, null, null, null, null
        );
        YouTubeVideoSearchResponse searchResponse = new YouTubeVideoSearchResponse(
                List.of(minimalItem), null, null, 1, 25
        );

        when(searchService.searchVideos("query", null, 5)).thenReturn(searchResponse);

        DestinationSearchResult result = adapter.searchTracks("query", null, 5);

        assertThat(result.candidates()).hasSize(1);
        DestinationTrackCandidate candidate = result.candidates().get(0);
        assertThat(candidate.id()).isEqualTo("vidMinimal");
        assertThat(candidate.title()).isNull();
        assertThat(candidate.channelOrArtist()).isNull();
        assertThat(candidate.description()).isNull();
        assertThat(candidate.thumbnailUrl()).isNull();
        assertThat(candidate.publishedAt()).isNull();
        assertThat(candidate.duration()).isNull();
    }

    @Test
    void searchTracks_shouldHandleNullResponseAndNullItemsGracefully() {
        when(searchService.searchVideos("nullResponse", null, 10)).thenReturn(null);
        DestinationSearchResult resultNull = adapter.searchTracks("nullResponse", null, 10);
        assertThat(resultNull.candidates()).isEmpty();
        assertThat(resultNull.totalResults()).isEqualTo(0);
        assertThat(resultNull.nextPageToken()).isNull();

        YouTubeVideoSearchResponse responseNullItems = new YouTubeVideoSearchResponse(
                null, null, null, 0, 0
        );
        when(searchService.searchVideos("nullItems", null, 10)).thenReturn(responseNullItems);
        DestinationSearchResult resultNullItems = adapter.searchTracks("nullItems", null, 10);
        assertThat(resultNullItems.candidates()).isEmpty();
        assertThat(resultNullItems.totalResults()).isEqualTo(0);
    }

    @Test
    void searchTracks_shouldNeverCallGetVideoDetails_verifyingZeroAdditionalApiQuota() {
        YouTubeVideoSummaryResponse item = new YouTubeVideoSummaryResponse(
                "v1", "T1", "A1", "D1", "thumb", "2023"
        );
        YouTubeVideoSearchResponse searchResponse = new YouTubeVideoSearchResponse(
                List.of(item), null, null, 1, 25
        );
        when(searchService.searchVideos("test", null, 10)).thenReturn(searchResponse);

        DestinationSearchResult result = adapter.searchTracks("test", null, 10);

        assertThat(result.candidates()).hasSize(1);
        verify(searchService).searchVideos("test", null, 10);
        org.mockito.Mockito.verifyNoMoreInteractions(searchService);
    }

    @Test
    void getTrackDetails_shouldCallSearchServiceAndMapDetails() {
        YouTubeVideoDetailsResponse detailsResponse = new YouTubeVideoDetailsResponse(
                "vid123", "Song Title", "Artist Channel", "Description",
                "https://thumbnail.url", "2026-10-10T00:00:00Z", "PT3M45S"
        );

        when(searchService.getVideoDetails("vid123")).thenReturn(detailsResponse);

        DestinationTrackDetails result = adapter.getTrackDetails("vid123");

        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo("vid123");
        assertThat(result.title()).isEqualTo("Song Title");
        assertThat(result.channelOrArtist()).isEqualTo("Artist Channel");
        assertThat(result.duration()).isEqualTo("PT3M45S");
    }

    @Test
    void createPlaylist_shouldMapPrivateRequestAndReturnDestinationPlaylist() {
        UUID userId = UUID.randomUUID();
        CreateDestinationPlaylistRequest request = new CreateDestinationPlaylistRequest(
                "My Playlist", "My Description", true
        );

        YouTubePlaylistResponse playlistResponse = new YouTubePlaylistResponse(
                "PL123", "My Playlist", "My Description", "private",
                "2026-10-10T00:00:00Z", "My Channel"
        );

        ArgumentCaptor<CreateYouTubePlaylistRequest> captor = ArgumentCaptor.forClass(CreateYouTubePlaylistRequest.class);
        when(playlistService.createPlaylist(eq(userId), captor.capture())).thenReturn(playlistResponse);

        DestinationPlaylist result = adapter.createPlaylist(userId, request);

        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo("PL123");
        assertThat(result.title()).isEqualTo("My Playlist");
        assertThat(result.privacyStatus()).isEqualTo("private");
        assertThat(result.url()).isEqualTo("https://www.youtube.com/playlist?list=PL123");
        assertThat(result.platform()).isEqualTo(Platform.YOUTUBE);

        assertThat(captor.getValue().privacyStatus()).isEqualTo(YouTubePrivacyStatus.PRIVATE);
    }

    @Test
    void createPlaylist_shouldMapPublicRequestWhenNotPrivate() {
        UUID userId = UUID.randomUUID();
        CreateDestinationPlaylistRequest request = new CreateDestinationPlaylistRequest(
                "My Public Playlist", null, false
        );

        YouTubePlaylistResponse playlistResponse = new YouTubePlaylistResponse(
                "PL456", "My Public Playlist", null, "public",
                "2026-10-10T00:00:00Z", "My Channel"
        );

        ArgumentCaptor<CreateYouTubePlaylistRequest> captor = ArgumentCaptor.forClass(CreateYouTubePlaylistRequest.class);
        when(playlistService.createPlaylist(eq(userId), captor.capture())).thenReturn(playlistResponse);

        DestinationPlaylist result = adapter.createPlaylist(userId, request);

        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo("PL456");
        assertThat(captor.getValue().privacyStatus()).isEqualTo(YouTubePrivacyStatus.PUBLIC);
    }

    @Test
    void addTrackToPlaylist_shouldMapRequestAndReturnDestinationPlaylistItem() {
        UUID userId = UUID.randomUUID();
        AddTrackToDestinationPlaylistRequest request = new AddTrackToDestinationPlaylistRequest("vid123", 0);

        YouTubePlaylistItemResponse itemResponse = new YouTubePlaylistItemResponse(
                "pli789", "PL123", "vid123", 0, true
        );

        ArgumentCaptor<AddVideoToPlaylistRequest> captor = ArgumentCaptor.forClass(AddVideoToPlaylistRequest.class);
        when(playlistService.addVideoToPlaylist(eq(userId), eq("PL123"), captor.capture()))
                .thenReturn(itemResponse);

        DestinationPlaylistItem result = adapter.addTrackToPlaylist(userId, "PL123", request);

        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo("pli789");
        assertThat(result.playlistId()).isEqualTo("PL123");
        assertThat(result.trackId()).isEqualTo("vid123");
        assertThat(result.position()).isEqualTo(0);

        assertThat(captor.getValue().videoId()).isEqualTo("vid123");
        assertThat(captor.getValue().position()).isEqualTo(0);
    }

    @Test
    void getPlaylist_shouldCallPlaylistServiceAndReturnDestinationPlaylist() {
        UUID userId = UUID.randomUUID();
        YouTubePlaylistResponse playlistResponse = new YouTubePlaylistResponse(
                "PL123", "My Playlist", "Desc", "private",
                "2026-10-10T00:00:00Z", "Channel"
        );

        when(playlistService.getPlaylist(userId, "PL123")).thenReturn(playlistResponse);

        DestinationPlaylist result = adapter.getPlaylist(userId, "PL123");

        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo("PL123");
        assertThat(result.title()).isEqualTo("My Playlist");
        assertThat(result.url()).isEqualTo("https://www.youtube.com/playlist?list=PL123");
        assertThat(result.platform()).isEqualTo(Platform.YOUTUBE);
    }

    @Test
    void verifyPlaylist_shouldDelegateToPlaylistService() {
        UUID userId = UUID.randomUUID();
        DestinationPlaylistVerification verification = new DestinationPlaylistVerification(
                "PL123", PlaylistVerificationStatus.ACCESSIBLE, "Accessible", null
        );

        when(playlistService.verifyPlaylist(userId, "PL123")).thenReturn(verification);

        DestinationPlaylistVerification result = adapter.verifyPlaylist(userId, "PL123");

        assertThat(result).isEqualTo(verification);
        verify(playlistService).verifyPlaylist(userId, "PL123");
    }
}
