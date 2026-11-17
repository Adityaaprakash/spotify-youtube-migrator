/**
 * Lifecycle status of a durable migration execution matching backend MigrationExecutionStatus.
 */
export type MigrationExecutionStatus =
  | 'ALLOCATING_DESTINATION'
  | 'INITIALIZED'
  | 'QUEUED'
  | 'RUNNING'
  | 'PARTIALLY_COMPLETED'
  | 'COMPLETED'
  | 'FAILED'
  | 'RECOVERY_REQUIRED'
  | 'ROLLED_BACK'
  | 'PARTIALLY_ROLLED_BACK';

/**
 * Request payload for dispatching an approved playlist migration matching backend RunMigrationApiRequest.
 */
export interface RunMigrationRequest {
  reviewSessionId: string;
  userId?: string | null;
  sourcePlaylistId?: string | null;
  targetPlaylistTitle?: string | null;
  targetPlaylistDescription?: string | null;
  isPrivate?: boolean | null;
}

/**
 * Response payload returned upon submitting an approved migration matching backend MigrationSubmissionResponse.
 */
export interface MigrationSubmissionResponse {
  executionId: string;
  reviewSessionId: string;
  status: MigrationExecutionStatus;
  progressUrl: string;
  message: string;
  totalTracks: number;
  submittedAt: string;
}

/**
 * Migration progress snapshot matching backend MigrationProgressResponse.
 */
export interface MigrationProgress {
  executionId: string;
  status: MigrationExecutionStatus;
  totalTracks: number;
  pendingTracks: number;
  addingTracks: number;
  addedTracks: number;
  failedTracks: number;
  uncertainTracks: number;
  skippedTracks: number;
  rollingBackTracks: number;
  rolledBackTracks: number;
  processedTracks: number;
  remainingTracks: number;
  progressPercentage: number;
}

/**
 * Individual track execution outcome record matching backend ExecutionTrackRecordResponse.
 */
export interface ExecutionTrackRecord {
  id: string;
  targetPosition: number;
  sourcePosition: number;
  sourceTrackId: string;
  sourceTitle: string;
  sourceArtist: string;
  destinationTrackId?: string | null;
  destinationPlaylistItemId?: string | null;
  status: string; // PENDING, ADDING, ADDED, FAILED, UNCERTAIN, SKIPPED, ROLLING_BACK, ROLLED_BACK
  errorMessage?: string | null;
  attemptCount: number;
  startedAt?: string | null;
  completedAt?: string | null;
  destinationTitle?: string | null;
  destinationChannel?: string | null;
  destinationDurationMs?: number | null;
  decisionSource?: string | null;
  matchScore?: number | null;
  matchDecision?: string | null;
}

/**
 * Execution summary details matching backend MigrationExecutionResponse.
 */
export interface MigrationExecutionDetail {
  executionId: string;
  reviewSessionId: string;
  planId?: string | null;
  userId: string;
  destinationPlatform: string;
  destinationPlaylistId?: string | null;
  destinationPlaylistUrl?: string | null;
  sourcePlaylistTitle?: string | null;
  status: string;
  totalApprovedTracks: number;
  successfulInsertions: number;
  failedInsertions: number;
  uncertainInsertions: number;
  skippedInsertions: number;
  fullySuccessful: boolean;
  canResume: boolean;
  canRollback: boolean;
  startedAt?: string | null;
  completedAt?: string | null;
  trackRecords?: ExecutionTrackRecord[];
  totalSourceTracks: number;
  matchedTracks: number;
  failureSummary?: string | null;
}
