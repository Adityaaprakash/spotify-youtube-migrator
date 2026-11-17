import { useQuery } from '@tanstack/react-query';
import { queryKeys } from '@/lib/query/queryKeys';
import { executionApi } from '../api';
import { MigrationExecutionDetail } from '../types';

export function useMigrationExecutionDetail(executionId: string | undefined) {
  return useQuery<MigrationExecutionDetail, Error>({
    queryKey: queryKeys.migrations.detail(executionId ?? ''),
    queryFn: () => {
      if (!executionId) throw new Error('Execution ID is required');
      return executionApi.getExecution(executionId);
    },
    enabled: Boolean(executionId),
    staleTime: 5000,
  });
}
