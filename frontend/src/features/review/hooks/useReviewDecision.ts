import { useMutation, useQueryClient } from '@tanstack/react-query';
import { queryKeys } from '@/lib/query/queryKeys';
import { reviewApi } from '../api';
import { SelectCandidateRequest, ReviewTrack } from '../types';

export function useReviewDecision(sessionId: string) {
  const queryClient = useQueryClient();

  const invalidateSession = () => {
    void queryClient.invalidateQueries({ queryKey: queryKeys.reviewSessions.tracks(sessionId) });
    void queryClient.invalidateQueries({ queryKey: queryKeys.reviewSessions.progress(sessionId) });
    void queryClient.invalidateQueries({ queryKey: queryKeys.reviewSessions.detail(sessionId) });
  };

  const acceptMutation = useMutation<ReviewTrack, Error, number>({
    mutationFn: (position) => reviewApi.acceptMatch(sessionId, position),
    onSuccess: invalidateSession,
  });

  const selectMutation = useMutation<
    ReviewTrack,
    Error,
    { position: number; request: SelectCandidateRequest }
  >({
    mutationFn: ({ position, request }) => reviewApi.selectCandidate(sessionId, position, request),
    onSuccess: invalidateSession,
  });

  const excludeMutation = useMutation<ReviewTrack, Error, { position: number; reason?: string }>({
    mutationFn: ({ position, reason }) => reviewApi.excludeTrack(sessionId, position, reason),
    onSuccess: invalidateSession,
  });

  return {
    accept: acceptMutation.mutateAsync,
    select: selectMutation.mutateAsync,
    exclude: excludeMutation.mutateAsync,
    isAccepting: acceptMutation.isPending,
    isSelecting: selectMutation.isPending,
    isExcluding: excludeMutation.isPending,
    isSubmitting:
      acceptMutation.isPending || selectMutation.isPending || excludeMutation.isPending,
    error: acceptMutation.error || selectMutation.error || excludeMutation.error,
  };
}
