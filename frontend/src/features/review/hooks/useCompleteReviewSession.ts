import { useMutation, useQueryClient } from '@tanstack/react-query';
import { queryKeys } from '@/lib/query/queryKeys';
import { reviewApi } from '../api';
import { ReviewSession } from '../types';

export function useCompleteReviewSession(sessionId: string) {
  const queryClient = useQueryClient();

  return useMutation<ReviewSession, Error, void>({
    mutationFn: () => reviewApi.completeSession(sessionId),
    onSuccess: (session) => {
      queryClient.setQueryData(queryKeys.reviewSessions.detail(sessionId), session);
      void queryClient.invalidateQueries({ queryKey: queryKeys.reviewSessions.lists() });
      void queryClient.invalidateQueries({ queryKey: queryKeys.reviewSessions.progress(sessionId) });
    },
  });
}
