/**
 * Spotify playlist summary DTO matching backend SpotifyPlaylistSummaryResponse.
 */
export interface SpotifyPlaylistSummary {
  id: string;
  name: string;
  description: string | null;
  ownerDisplayName: string | null;
  imageUrl: string | null;
  totalTracks: number | null;
  spotifyUrl: string | null;
}

/**
 * Paged response structure matching backend SpotifyPageResponse<T>.
 */
export interface SpotifyPageResponse<T> {
  items: T[];
  total: number;
  limit: number;
  offset: number;
  hasNext: boolean;
}

/**
 * Single playlist detail DTO matching backend PlaylistResponse.
 */
export interface PlaylistDetail {
  id: string | null;
  name: string;
  description: string | null;
  platform: 'SPOTIFY' | 'YOUTUBE' | string;
  externalId: string;
  url: string | null;
  totalTracks: number | null;
}

/**
 * Single track item DTO matching backend TrackResponse.
 */
export interface TrackItem {
  id: string | null;
  name: string;
  artist: string | null;
  album: string | null;
  durationMs: number | null;
  externalId: string | null;
}

/**
 * Query parameters for fetching user playlists with offset-based pagination.
 */
export interface PlaylistQueryParams {
  limit?: number;
  offset?: number;
}
