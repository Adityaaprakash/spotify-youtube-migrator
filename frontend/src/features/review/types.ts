/**
 * Mutually exclusive classification for a source track within a migration plan.
 * Matches backend PlanTrackClassification.
 */
export type PlanTrackClassification =
  | 'AUTO_MATCH'
  | 'REVIEW_REQUIRED'
  | 'UNMATCHED'
  | 'DUPLICATE'
  | 'FAILED'
  | 'EXCLUDED';

/**
 * Review session lifecycle status matching backend ReviewSessionStatus.
 */
export type ReviewSessionStatus = 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED';

/**
 * Review track lifecycle and decision status matching backend ReviewTrackStatus.
 */
export type ReviewTrackStatus =
  | 'AWAITING_REVIEW'
  | 'ACCEPTED'
  | 'USER_SELECTED'
  | 'EXCLUDED';

/**
 * Match decision type for submitting review decisions matching backend ReviewDecisionType.
 */
export type ReviewDecisionType = 'ACCEPT' | 'SELECT_CANDIDATE' | 'EXCLUDE';

/**
 * Destination candidate track or video matching backend DestinationCandidateResponse.
 */
export interface DestinationCandidate {
  id: string;
  title: string;
  channelOrArtist?: string | null;
  description?: string | null;
  thumbnailUrl?: string | null;
  publishedAt?: string | null;
  duration?: string | null;
  durationMs?: number | null;
}

/**
 * Single planned track entry in a migration plan preview matching backend MigrationPlanTrackResponse.
 */
export interface MigrationPlanTrack {
  position: number;
  sourceTrackId: string;
  sourceTitle: string;
  sourceArtist: string;
  classification: PlanTrackClassification;
  selectedCandidate?: DestinationCandidate | null;
  matchDecision?: string | null;
  matchScore?: number | null;
  calibratedProbability?: number | null;
  matchReasons?: string[] | null;
  errorMessage?: string | null;
}

/**
 * Aggregate summary metrics of a migration plan matching backend MigrationPlanSummaryResponse.
 */
export interface MigrationPlanSummary {
  totalTracks: number;
  autoMatchCount: number;
  reviewRequiredCount: number;
  unmatchedCount: number;
  duplicateCount: number;
  failedCount: number;
  excludedCount: number;
  autoMatchRate: number;
  reviewRate: number;
  unmatchedRate: number;
  duplicateRate: number;
  failureRate: number;
  excludedRate: number;
}

/**
 * Migration plan preview response matching backend MigrationPlanResponse.
 */
export interface MigrationPlanResponse {
  planId: string;
  sourcePlaylistId: string;
  sourcePlaylistTitle: string;
  createdAt: string;
  totalTracks: number;
  tracks: MigrationPlanTrack[];
  summary: MigrationPlanSummary;
}

/**
 * Request payload for generating a migration plan preview matching backend MigrationPlanPreviewRequest.
 */
export interface MigrationPlanPreviewRequest {
  spotifyPlaylistId: string;
  userId?: string | null;
  deduplicate?: boolean;
}

/**
 * Request payload for creating a review session matching backend CreateReviewSessionRequest.
 */
export interface CreateReviewSessionRequest {
  sourcePlaylistId: string;
  userId?: string | null;
  deduplicate?: boolean;
  planId?: string | null;
}

/**
 * Real-time review progress summary matching backend ReviewProgressSummaryResponse.
 */
export interface ReviewProgressSummary {
  totalTracks: number;
  awaitingReviewCount: number;
  acceptedCount: number;
  manuallyCorrectedCount: number;
  excludedCount: number;
  unresolvedConflictCount: number;
  remainingRequiredDecisions: number;
  eligibleForCompletion: boolean;
}

/**
 * Review session presentation response matching backend ReviewSessionResponse.
 */
export interface ReviewSession {
  id: string;
  userId: string;
  sourcePlaylistId: string;
  sourcePlaylistTitle: string;
  planId?: string | null;
  status: ReviewSessionStatus;
  totalTracks: number;
  createdAt: string;
  updatedAt: string;
  completedAt?: string | null;
  progress: ReviewProgressSummary;
}

/**
 * Review candidate track DTO matching backend ReviewCandidateResponse.
 */
export interface ReviewCandidate {
  id: string;
  title: string;
  channelOrArtist?: string | null;
  durationMs?: number | null;
  duration?: string | null;
}

/**
 * Itemized review track representation matching backend ReviewTrackResponse.
 */
export interface ReviewTrack {
  id: string;
  sessionId: string;
  position: number;
  sourceTrackId: string;
  sourceTitle: string;
  sourceArtist: string;
  originalClassification: PlanTrackClassification;
  originalCandidate?: ReviewCandidate | null;
  originalMatchScore?: number | null;
  originalCalibratedProbability?: number | null;
  originalMatchReasons?: string | null;
  reviewStatus: ReviewTrackStatus;
  decisionSource?: string | null;
  selectedCandidate?: ReviewCandidate | null;
  excluded: boolean;
  hasConflict: boolean;
  conflictReason?: string | null;
  decisionNotes?: string | null;
  approvedForMigration: boolean;
  unresolved: boolean;
}

/**
 * Search candidate request payload matching backend ReviewCandidateSearchApiRequest.
 */
export interface ReviewCandidateSearchRequest {
  query?: string | null;
  limit?: number | null;
}

/**
 * Search candidate result response matching backend ReviewCandidateSearchResultResponse.
 */
export interface ReviewCandidateSearchResult {
  sessionId: string;
  trackPosition: number;
  sourceTrackId: string;
  sourceTitle: string;
  sourceArtist: string;
  queryUsed: string;
  candidates: DestinationCandidate[];
  isCustomQuery: boolean;
  totalResults: number;
}

/**
 * Candidate payload supplied when selecting a replacement candidate matching backend CandidateSelectionDto.
 */
export interface CandidateSelection {
  id: string;
  title: string;
  channelOrArtist?: string | null;
  durationMs?: number | null;
  description?: string | null;
  thumbnailUrl?: string | null;
  publishedAt?: string | null;
}

/**
 * Request payload for manually selecting a replacement candidate matching backend SelectCandidateRequest.
 */
export interface SelectCandidateRequest {
  candidate: CandidateSelection;
  notes?: string | null;
}

/**
 * Request payload for excluding a track matching backend ExcludeTrackRequest.
 */
export interface ExcludeTrackRequest {
  reason?: string | null;
}
