import { useQuery } from '@tanstack/react-query';
import { queryKeys } from '@/lib/query/queryKeys';
import { useOAuthStatus } from '@/features/oauth';
import { playlistsApi } from '../api';
import { PlaylistQueryParams, SpotifyPageResponse, SpotifyPlaylistSummary } from '../types';

export function usePlaylists(params: PlaylistQueryParams = { limit: 20, offset: 0 }) {
  const {
    isConnected: isSpotifyConnected,
    isConnecting: isConnectingSpotify,
    connect: connectSpotify,
  } = useOAuthStatus('spotify');

  const limit = params.limit ?? 20;
  const offset = params.offset ?? 0;

  const query = useQuery<SpotifyPageResponse<SpotifyPlaylistSummary>, Error>({
    queryKey: queryKeys.playlists.list({ limit, offset }),
    queryFn: () => playlistsApi.getPlaylists({ limit, offset }),
    enabled: isSpotifyConnected,
    staleTime: 60 * 1000,
  });

  return {
    ...query,
    playlists: query.data?.items ?? [],
    total: query.data?.total ?? 0,
    hasNext: query.data?.hasNext ?? false,
    limit,
    offset,
    isSpotifyConnected,
    isConnectingSpotify,
    connectSpotify,
  };
}
