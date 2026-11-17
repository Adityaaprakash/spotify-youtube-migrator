import { useMutation, useQueryClient } from '@tanstack/react-query';
import { queryKeys } from '@/lib/query/queryKeys';
import { reviewApi } from '../api';
import { CreateReviewSessionRequest, ReviewSession } from '../types';

export function useCreateReviewSession() {
  const queryClient = useQueryClient();

  return useMutation<ReviewSession, Error, CreateReviewSessionRequest>({
    mutationFn: (request) => reviewApi.createSession(request),
    onSuccess: (session) => {
      // Invalidate review sessions list and seed detail cache
      void queryClient.invalidateQueries({ queryKey: queryKeys.reviewSessions.lists() });
      queryClient.setQueryData(queryKeys.reviewSessions.detail(session.id), session);
      if (session.progress) {
        queryClient.setQueryData(queryKeys.reviewSessions.progress(session.id), session.progress);
      }
    },
  });
}
