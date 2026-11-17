import { MigrationExecutionStatus } from '@/features/execution/types';

/**
 * Standardized presentation envelope for paginated collections matching backend PagedResponse<T>.
 */
export interface PagedResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}

/**
 * Summary presentation response DTO representing a historical migration matching backend MigrationHistoryItemResponse.
 */
export interface MigrationHistoryItem {
  migrationId: string;
  sourcePlaylistId: string;
  sourcePlaylistTitle: string;
  destinationPlatform: string;
  destinationPlaylistId?: string | null;
  destinationPlaylistUrl?: string | null;
  status: MigrationExecutionStatus;
  totalSourceTracks: number;
  matchedTracks: number;
  totalApprovedTracks: number;
  successfulInsertions: number;
  failedInsertions: number;
  uncertainInsertions: number;
  skippedInsertions: number;
  rolledBackInsertions: number;
  createdAt: string;
  startedAt?: string | null;
  completedAt?: string | null;
  failureSummary?: string | null;
}

/**
 * Query parameters for fetching paginated migration history.
 */
export interface MigrationHistoryQueryParams {
  page?: number;
  size?: number;
  status?: MigrationExecutionStatus | string;
}

/**
 * Presentation response DTO representing an execution lifecycle audit event matching backend MigrationAuditEventResponse.
 */
export interface MigrationAuditEvent {
  id: string;
  executionId: string;
  eventType: string;
  previousState?: string | null;
  newState?: string | null;
  reason?: string | null;
  actor?: string | null;
  correlationId?: string | null;
  details?: string | null;
  rollbackRecordId?: string | null;
  reconciliationRecordId?: string | null;
  createdAt: string;
}

/**
 * Query parameters for fetching chronological audit timeline events.
 */
export interface AuditTimelineQueryParams {
  page?: number;
  size?: number;
}
