import { useQuery } from '@tanstack/react-query';
import { queryKeys } from '@/lib/query/queryKeys';
import { historyApi } from '../api';
import { MigrationHistoryQueryParams, PagedResponse, MigrationHistoryItem } from '../types';

export function useMigrationHistory(params?: MigrationHistoryQueryParams) {
  return useQuery<PagedResponse<MigrationHistoryItem>, Error>({
    queryKey: queryKeys.migrations.history(params),
    queryFn: () => historyApi.getHistory(params),
    staleTime: 30 * 1000,
    placeholderData: (previousData) => previousData,
  });
}
