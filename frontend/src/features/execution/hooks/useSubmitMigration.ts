import { useMutation, useQueryClient } from '@tanstack/react-query';
import { queryKeys } from '@/lib/query/queryKeys';
import { executionApi } from '../api';
import { RunMigrationRequest, MigrationSubmissionResponse } from '../types';

export function useSubmitMigration() {
  const queryClient = useQueryClient();

  return useMutation<
    MigrationSubmissionResponse,
    Error,
    { request: RunMigrationRequest; idempotencyKey?: string }
  >({
    mutationFn: ({ request, idempotencyKey }) => {
      const key =
        idempotencyKey ||
        (typeof crypto !== 'undefined' && crypto.randomUUID
          ? crypto.randomUUID()
          : `idemp-${Date.now()}-${Math.random().toString(36).substring(2, 9)}`);
      return executionApi.submitMigration(request, key);
    },
    onSuccess: (response) => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.migrations.all });
      queryClient.setQueryData(
        queryKeys.migrations.progress(response.executionId),
        response
      );
    },
  });
}
