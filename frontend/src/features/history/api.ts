import { apiClient } from '@/lib/api/client';
import { MigrationExecutionDetail, ExecutionTrackRecord } from '@/features/execution/types';
import {
  PagedResponse,
  MigrationHistoryItem,
  MigrationHistoryQueryParams,
  MigrationAuditEvent,
  AuditTimelineQueryParams,
} from './types';

export const historyApi = {
  /**
   * Retrieves paginated persistent migration history for the authenticated user.
   */
  getHistory: async (
    params?: MigrationHistoryQueryParams
  ): Promise<PagedResponse<MigrationHistoryItem>> => {
    return apiClient.get<PagedResponse<MigrationHistoryItem>>('/migrations', {
      params: {
        page: params?.page ?? 0,
        size: params?.size ?? 20,
        status: params?.status || undefined,
      },
    });
  },

  /**
   * Retrieves authoritative execution details for a migration by ID.
   */
  getDetail: async (executionId: string): Promise<MigrationExecutionDetail> => {
    return apiClient.get<MigrationExecutionDetail>(
      `/migrations/${encodeURIComponent(executionId)}`
    );
  },

  /**
   * Retrieves itemized track execution records in canonical target position order.
   */
  getTracks: async (executionId: string): Promise<ExecutionTrackRecord[]> => {
    return apiClient.get<ExecutionTrackRecord[]>(
      `/migrations/${encodeURIComponent(executionId)}/tracks`
    );
  },

  /**
   * Retrieves chronological audit event timeline for a migration execution.
   */
  getAuditTimeline: async (
    executionId: string,
    params?: AuditTimelineQueryParams
  ): Promise<PagedResponse<MigrationAuditEvent>> => {
    return apiClient.get<PagedResponse<MigrationAuditEvent>>(
      `/migrations/${encodeURIComponent(executionId)}/history`,
      {
        params: {
          page: params?.page ?? 0,
          size: params?.size ?? 50,
        },
      }
    );
  },

  /**
   * Resumes or recovers an eligible unfinished migration execution.
   */
  resumeMigration: async (executionId: string): Promise<MigrationExecutionDetail> => {
    return apiClient.post<MigrationExecutionDetail>(
      `/migrations/${encodeURIComponent(executionId)}/resume`
    );
  },
};
