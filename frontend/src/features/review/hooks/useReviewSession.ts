import { useQuery } from '@tanstack/react-query';
import { queryKeys } from '@/lib/query/queryKeys';
import { reviewApi } from '../api';
import { ReviewSession } from '../types';

export function useReviewSession(sessionId: string | undefined) {
  return useQuery<ReviewSession, Error>({
    queryKey: queryKeys.reviewSessions.detail(sessionId ?? ''),
    queryFn: () => {
      if (!sessionId) throw new Error('Session ID is required');
      return reviewApi.getSession(sessionId);
    },
    enabled: Boolean(sessionId),
    staleTime: 30 * 1000,
  });
}
