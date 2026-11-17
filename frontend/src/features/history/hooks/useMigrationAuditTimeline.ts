import { useQuery } from '@tanstack/react-query';
import { queryKeys } from '@/lib/query/queryKeys';
import { historyApi } from '../api';
import { PagedResponse, MigrationAuditEvent, AuditTimelineQueryParams } from '../types';

export function useMigrationAuditTimeline(
  executionId: string | undefined,
  params?: AuditTimelineQueryParams
) {
  return useQuery<PagedResponse<MigrationAuditEvent>, Error>({
    queryKey: queryKeys.migrations.timeline(executionId ?? '', params),
    queryFn: () => {
      if (!executionId) throw new Error('Execution ID is required');
      return historyApi.getAuditTimeline(executionId, params);
    },
    enabled: Boolean(executionId),
    staleTime: 30 * 1000,
    placeholderData: (previousData) => previousData,
  });
}
