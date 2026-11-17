import { apiClient } from '@/lib/api/client';
import {
  MigrationPlanPreviewRequest,
  MigrationPlanResponse,
  CreateReviewSessionRequest,
  ReviewSession,
  ReviewProgressSummary,
  ReviewTrack,
  ReviewCandidateSearchRequest,
  ReviewCandidateSearchResult,
  SelectCandidateRequest,
} from './types';

export const reviewApi = {
  /**
   * Generates a transient point-in-time migration plan preview without destination mutation.
   */
  generatePreview: async (
    request: MigrationPlanPreviewRequest
  ): Promise<MigrationPlanResponse> => {
    return apiClient.post<MigrationPlanResponse>('/migrations/preview', request);
  },

  /**
   * Creates a durable review session from a source playlist migration plan.
   */
  createSession: async (
    request: CreateReviewSessionRequest
  ): Promise<ReviewSession> => {
    return apiClient.post<ReviewSession>('/review-sessions', request);
  },

  /**
   * Retrieves all review sessions owned by the authenticated user.
   */
  getUserSessions: async (): Promise<ReviewSession[]> => {
    return apiClient.get<ReviewSession[]>('/review-sessions');
  },

  /**
   * Retrieves a single review session by ID.
   */
  getSession: async (sessionId: string): Promise<ReviewSession> => {
    return apiClient.get<ReviewSession>(`/review-sessions/${encodeURIComponent(sessionId)}`);
  },

  /**
   * Retrieves the real-time reconciled review progress summary for a session.
   */
  getProgress: async (sessionId: string): Promise<ReviewProgressSummary> => {
    return apiClient.get<ReviewProgressSummary>(
      `/review-sessions/${encodeURIComponent(sessionId)}/progress`
    );
  },

  /**
   * Retrieves all review tracks for a session in original source position order.
   */
  getTracks: async (sessionId: string): Promise<ReviewTrack[]> => {
    return apiClient.get<ReviewTrack[]>(
      `/review-sessions/${encodeURIComponent(sessionId)}/tracks`
    );
  },

  /**
   * Retrieves a single review track by session ID and 0-indexed position.
   */
  getTrack: async (sessionId: string, position: number): Promise<ReviewTrack> => {
    return apiClient.get<ReviewTrack>(
      `/review-sessions/${encodeURIComponent(sessionId)}/tracks/${position}`
    );
  },

  /**
   * Searches for alternative destination candidates for a review track.
   */
  searchCandidates: async (
    sessionId: string,
    position: number,
    request?: ReviewCandidateSearchRequest
  ): Promise<ReviewCandidateSearchResult> => {
    return apiClient.post<ReviewCandidateSearchResult>(
      `/review-sessions/${encodeURIComponent(sessionId)}/tracks/${position}/candidates/search`,
      request ?? {}
    );
  },

  /**
   * Accepts the original automatic matching candidate for a review track.
   */
  acceptMatch: async (sessionId: string, position: number): Promise<ReviewTrack> => {
    return apiClient.post<ReviewTrack>(
      `/review-sessions/${encodeURIComponent(sessionId)}/tracks/${position}/accept`
    );
  },

  /**
   * Manually selects a replacement candidate for a review track.
   */
  selectCandidate: async (
    sessionId: string,
    position: number,
    request: SelectCandidateRequest
  ): Promise<ReviewTrack> => {
    return apiClient.post<ReviewTrack>(
      `/review-sessions/${encodeURIComponent(sessionId)}/tracks/${position}/select`,
      request
    );
  },

  /**
   * Explicitly excludes a track from migration.
   */
  excludeTrack: async (
    sessionId: string,
    position: number,
    reason?: string | null
  ): Promise<ReviewTrack> => {
    return apiClient.post<ReviewTrack>(
      `/review-sessions/${encodeURIComponent(sessionId)}/tracks/${position}/exclude`,
      reason ? { reason } : {}
    );
  },

  /**
   * Finalizes and completes a review session when all decisions and conflicts are resolved.
   */
  completeSession: async (sessionId: string): Promise<ReviewSession> => {
    return apiClient.post<ReviewSession>(
      `/review-sessions/${encodeURIComponent(sessionId)}/complete`
    );
  },

  /**
   * Cancels or abandons an in-progress review session.
   */
  cancelSession: async (sessionId: string): Promise<ReviewSession> => {
    return apiClient.post<ReviewSession>(
      `/review-sessions/${encodeURIComponent(sessionId)}/cancel`
    );
  },
};
