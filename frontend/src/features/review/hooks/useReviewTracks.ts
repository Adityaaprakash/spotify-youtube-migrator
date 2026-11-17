import { useQuery } from '@tanstack/react-query';
import { queryKeys } from '@/lib/query/queryKeys';
import { reviewApi } from '../api';
import { ReviewTrack } from '../types';

export function useReviewTracks(sessionId: string | undefined) {
  return useQuery<ReviewTrack[], Error>({
    queryKey: queryKeys.reviewSessions.tracks(sessionId ?? ''),
    queryFn: () => {
      if (!sessionId) throw new Error('Session ID is required');
      return reviewApi.getTracks(sessionId);
    },
    enabled: Boolean(sessionId),
    staleTime: 30 * 1000,
  });
}
