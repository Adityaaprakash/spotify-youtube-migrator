import { useQuery } from '@tanstack/react-query';
import { queryKeys } from '@/lib/query/queryKeys';
import { MigrationExecutionDetail } from '@/features/execution/types';
import { historyApi } from '../api';

export function useMigrationHistoryDetail(executionId: string | undefined) {
  return useQuery<MigrationExecutionDetail, Error>({
    queryKey: queryKeys.migrations.detail(executionId ?? ''),
    queryFn: () => {
      if (!executionId) throw new Error('Execution ID is required');
      return historyApi.getDetail(executionId);
    },
    enabled: Boolean(executionId),
    staleTime: 30 * 1000,
  });
}
