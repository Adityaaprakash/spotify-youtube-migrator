import { useQuery } from '@tanstack/react-query';
import { queryKeys } from '@/lib/query/queryKeys';
import { executionApi } from '../api';
import { ExecutionTrackRecord } from '../types';

export function useMigrationExecutionTracks(
  executionId: string | undefined,
  refetchInterval: number | false = false
) {
  return useQuery<ExecutionTrackRecord[], Error>({
    queryKey: queryKeys.migrations.tracks(executionId ?? ''),
    queryFn: () => {
      if (!executionId) throw new Error('Execution ID is required');
      return executionApi.getTracks(executionId);
    },
    enabled: Boolean(executionId),
    refetchInterval,
    staleTime: 2000,
  });
}
