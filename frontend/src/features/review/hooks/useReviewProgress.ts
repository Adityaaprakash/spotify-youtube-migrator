import { useQuery } from '@tanstack/react-query';
import { queryKeys } from '@/lib/query/queryKeys';
import { reviewApi } from '../api';
import { ReviewProgressSummary } from '../types';

export function useReviewProgress(sessionId: string | undefined) {
  return useQuery<ReviewProgressSummary, Error>({
    queryKey: queryKeys.reviewSessions.progress(sessionId ?? ''),
    queryFn: () => {
      if (!sessionId) throw new Error('Session ID is required');
      return reviewApi.getProgress(sessionId);
    },
    enabled: Boolean(sessionId),
    staleTime: 15 * 1000,
  });
}
