import { useQuery } from '@tanstack/react-query';
import { queryKeys } from '@/lib/query/queryKeys';
import { useOAuthStatus } from '@/features/oauth';
import { playlistsApi } from '../api';
import { TrackItem } from '../types';

export function usePlaylistTracks(playlistId: string | undefined) {
  const {
    isConnected: isSpotifyConnected,
    isConnecting: isConnectingSpotify,
    connect: connectSpotify,
  } = useOAuthStatus('spotify');

  const query = useQuery<TrackItem[], Error>({
    queryKey: queryKeys.playlists.tracks(playlistId ?? ''),
    queryFn: () => {
      if (!playlistId) throw new Error('Playlist ID is required');
      return playlistsApi.getPlaylistTracks(playlistId);
    },
    enabled: Boolean(playlistId) && isSpotifyConnected,
    staleTime: 60 * 1000,
  });

  return {
    ...query,
    tracks: query.data ?? [],
    isSpotifyConnected,
    isConnectingSpotify,
    connectSpotify,
  };
}
