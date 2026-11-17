import { useQuery } from '@tanstack/react-query';
import { queryKeys } from '@/lib/query/queryKeys';
import { useOAuthStatus } from '@/features/oauth';
import { playlistsApi } from '../api';
import { PlaylistDetail } from '../types';

export function usePlaylistDetail(playlistId: string | undefined) {
  const {
    isConnected: isSpotifyConnected,
    isConnecting: isConnectingSpotify,
    connect: connectSpotify,
  } = useOAuthStatus('spotify');

  const query = useQuery<PlaylistDetail, Error>({
    queryKey: queryKeys.playlists.detail(playlistId ?? ''),
    queryFn: () => {
      if (!playlistId) throw new Error('Playlist ID is required');
      return playlistsApi.getPlaylist(playlistId);
    },
    enabled: Boolean(playlistId) && isSpotifyConnected,
    staleTime: 60 * 1000,
  });

  return {
    ...query,
    playlist: query.data ?? null,
    isSpotifyConnected,
    isConnectingSpotify,
    connectSpotify,
  };
}
