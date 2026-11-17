import { apiClient } from '@/lib/api/client';
import {
  RunMigrationRequest,
  MigrationSubmissionResponse,
  MigrationProgress,
  ExecutionTrackRecord,
  MigrationExecutionDetail,
} from './types';

export const executionApi = {
  /**
   * Submits an approved migration for asynchronous background processing.
   * Transmits Idempotency-Key header to prevent duplicate job creation.
   */
  submitMigration: async (
    request: RunMigrationRequest,
    idempotencyKey?: string
  ): Promise<MigrationSubmissionResponse> => {
    return apiClient.post<MigrationSubmissionResponse>('/migrations', request, {
      idempotencyKey,
    });
  },

  /**
   * Retrieves real-time migration progress snapshot for an execution.
   */
  getProgress: async (executionId: string): Promise<MigrationProgress> => {
    return apiClient.get<MigrationProgress>(
      `/migrations/${encodeURIComponent(executionId)}/progress`
    );
  },

  /**
   * Retrieves itemized track execution records in target position order.
   */
  getTracks: async (executionId: string): Promise<ExecutionTrackRecord[]> => {
    return apiClient.get<ExecutionTrackRecord[]>(
      `/migrations/${encodeURIComponent(executionId)}/tracks`
    );
  },

  /**
   * Retrieves full execution details and destination playlist metadata.
   */
  getExecution: async (executionId: string): Promise<MigrationExecutionDetail> => {
    return apiClient.get<MigrationExecutionDetail>(
      `/migrations/${encodeURIComponent(executionId)}`
    );
  },
};
