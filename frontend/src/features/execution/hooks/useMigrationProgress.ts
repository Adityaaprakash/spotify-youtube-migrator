import { useQuery } from '@tanstack/react-query';
import { queryKeys } from '@/lib/query/queryKeys';
import { ApiException } from '@/lib/api/types';
import { executionApi } from '../api';
import { MigrationProgress, MigrationExecutionStatus } from '../types';

export function isTerminalExecutionStatus(status?: MigrationExecutionStatus): boolean {
  if (!status) return false;
  return (
    status === 'COMPLETED' ||
    status === 'FAILED' ||
    status === 'ROLLED_BACK' ||
    status === 'PARTIALLY_COMPLETED' ||
    status === 'RECOVERY_REQUIRED' ||
    status === 'PARTIALLY_ROLLED_BACK'
  );
}

export function useMigrationProgress(executionId: string | undefined) {
  return useQuery<MigrationProgress, Error>({
    queryKey: queryKeys.migrations.progress(executionId ?? ''),
    queryFn: () => {
      if (!executionId) throw new Error('Execution ID is required');
      return executionApi.getProgress(executionId);
    },
    enabled: Boolean(executionId),
    refetchInterval: (query) => {
      const error = query.state.error;
      if (error instanceof ApiException) {
        if (error.status === 404) {
          return false; // Stop polling immediately on 404 / non-existent execution / IDOR
        }
        if (error.status === 429) {
          return 5000; // Back off to 5 seconds on rate limiting
        }
        return 3000; // Back off on transient server errors
      }
      const data = query.state.data;
      if (!data) return 1500;
      if (isTerminalExecutionStatus(data.status)) {
        return false; // Stop polling upon terminal or stopped execution
      }
      return 1500; // Poll active execution every 1.5 seconds
    },
    retry: (failureCount, error) => {
      if (error instanceof ApiException && error.status === 404) {
        return false; // Do not retry on 404 anti-IDOR
      }
      return failureCount < 2;
    },
    staleTime: 1000,
  });
}
