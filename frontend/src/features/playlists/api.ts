import { apiClient } from '@/lib/api/client';
import {
  SpotifyPageResponse,
  SpotifyPlaylistSummary,
  PlaylistDetail,
  TrackItem,
  PlaylistQueryParams,
} from './types';

export const playlistsApi = {
  /**
   * Fetches the authenticated user's Spotify playlists with offset-based pagination.
   */
  getPlaylists: async (
    params?: PlaylistQueryParams
  ): Promise<SpotifyPageResponse<SpotifyPlaylistSummary>> => {
    return apiClient.get<SpotifyPageResponse<SpotifyPlaylistSummary>>('/spotify/playlists', {
      params: {
        limit: params?.limit ?? 20,
        offset: params?.offset ?? 0,
      },
    });
  },

  /**
   * Fetches metadata for a single Spotify playlist.
   */
  getPlaylist: async (playlistId: string): Promise<PlaylistDetail> => {
    return apiClient.get<PlaylistDetail>(`/spotify/playlists/${encodeURIComponent(playlistId)}`);
  },

  /**
   * Fetches the complete ordered track listing of a Spotify playlist.
   */
  getPlaylistTracks: async (playlistId: string): Promise<TrackItem[]> => {
    return apiClient.get<TrackItem[]>(`/spotify/playlists/${encodeURIComponent(playlistId)}/tracks`);
  },
};
