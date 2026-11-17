import { useMutation } from '@tanstack/react-query';
import { reviewApi } from '../api';
import { ReviewCandidateSearchRequest, ReviewCandidateSearchResult } from '../types';

export function useCandidateSearch(sessionId: string) {
  return useMutation<
    ReviewCandidateSearchResult,
    Error,
    { position: number; request?: ReviewCandidateSearchRequest }
  >({
    mutationFn: ({ position, request }) =>
      reviewApi.searchCandidates(sessionId, position, request),
  });
}
