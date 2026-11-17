import { useMutation, useQueryClient } from '@tanstack/react-query';
import { queryKeys } from '@/lib/query/queryKeys';
import { MigrationExecutionDetail } from '@/features/execution/types';
import { historyApi } from '../api';

export function useResumeMigration() {
  const queryClient = useQueryClient();

  return useMutation<MigrationExecutionDetail, Error, string>({
    mutationFn: (executionId: string) => historyApi.resumeMigration(executionId),
    onSuccess: (updatedExecution, executionId) => {
      // Optimistically update execution detail cache
      queryClient.setQueryData(
        queryKeys.migrations.detail(executionId),
        updatedExecution
      );

      // Invalidate relevant caches across history, progress, timeline, and tracks
      void queryClient.invalidateQueries({ queryKey: queryKeys.migrations.all });
      void queryClient.invalidateQueries({ queryKey: queryKeys.migrations.detail(executionId) });
      void queryClient.invalidateQueries({ queryKey: queryKeys.migrations.timeline(executionId) });
      void queryClient.invalidateQueries({ queryKey: queryKeys.migrations.tracks(executionId) });
      void queryClient.invalidateQueries({ queryKey: queryKeys.migrations.progress(executionId) });
    },
  });
}
