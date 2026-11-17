import { describe, it, beforeEach, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { reviewApi } from '../features/review/api.ts';
import { ApiException } from '../lib/api/types.ts';

describe('Phase 15E — Migration Preview & Human Review Tests', () => {
  const originalFetch = globalThis.fetch;

  beforeEach(() => {
    (globalThis as unknown as { document?: { cookie: string } }).document = {
      cookie: 'XSRF-TOKEN=test-csrf-token',
    };
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  describe('1. Migration Preview Generation (POST /api/migrations/preview)', () => {
    it('submits preview request with spotifyPlaylistId and deduplicate flag', async () => {
      let requestedUrl = '';
      let requestedMethod = '';
      let requestedBody = '';

      const mockPreviewResponse = {
        planId: '4b3d7b3d-1111-4bad-9bdd-2b0d7b3dcb6d',
        sourcePlaylistId: 'spotify-pl-123',
        sourcePlaylistTitle: 'Road Trip Bangers',
        createdAt: '2026-11-17T12:00:00Z',
        totalTracks: 2,
        tracks: [
          {
            position: 0,
            sourceTrackId: 'spotify:track:001',
            sourceTitle: 'Song One',
            sourceArtist: 'Artist A',
            classification: 'AUTO_MATCH',
            selectedCandidate: {
              id: 'yt-001',
              title: 'Song One (Official Video)',
              channelOrArtist: 'Artist A',
              durationMs: 210000,
            },
            matchScore: 0.95,
            calibratedProbability: 0.98,
            matchReasons: ['Exact title match', 'Artist match'],
            errorMessage: null,
          },
          {
            position: 1,
            sourceTrackId: 'spotify:track:002',
            sourceTitle: 'Song Two',
            sourceArtist: 'Artist B',
            classification: 'REVIEW_REQUIRED',
            selectedCandidate: {
              id: 'yt-002',
              title: 'Song Two - Live 2024',
              channelOrArtist: 'Artist B',
              durationMs: 185000,
            },
            matchScore: 0.72,
            calibratedProbability: 0.65,
            matchReasons: ['Ambiguous live version'],
            errorMessage: null,
          },
        ],
        summary: {
          totalTracks: 2,
          autoMatchCount: 1,
          reviewRequiredCount: 1,
          unmatchedCount: 0,
          duplicateCount: 0,
          failedCount: 0,
          excludedCount: 0,
          autoMatchRate: 0.5,
          reviewRate: 0.5,
          unmatchedRate: 0.0,
          duplicateRate: 0.0,
          failureRate: 0.0,
          excludedRate: 0.0,
        },
      };

      globalThis.fetch = async (url, init) => {
        requestedUrl = String(url);
        requestedMethod = init?.method || '';
        requestedBody = String(init?.body || '');
        return new Response(JSON.stringify(mockPreviewResponse), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      };

      const result = await reviewApi.generatePreview({
        spotifyPlaylistId: 'spotify-pl-123',
        deduplicate: true,
      });

      assert.ok(requestedUrl.endsWith('/api/migrations/preview'));
      assert.strictEqual(requestedMethod, 'POST');
      assert.ok(requestedBody.includes('"spotifyPlaylistId":"spotify-pl-123"'));
      assert.strictEqual(result.planId, '4b3d7b3d-1111-4bad-9bdd-2b0d7b3dcb6d');
      assert.strictEqual(result.summary.totalTracks, 2);
      assert.strictEqual(result.summary.autoMatchCount, 1);
      assert.strictEqual(result.summary.reviewRequiredCount, 1);
      assert.strictEqual(result.tracks.length, 2);
      assert.strictEqual(result.tracks[0].classification, 'AUTO_MATCH');
      assert.strictEqual(result.tracks[1].classification, 'REVIEW_REQUIRED');
    });

    it('handles preview generation failure (e.g. invalid playlist id or Spotify error)', async () => {
      globalThis.fetch = async () => {
        return new Response(
          JSON.stringify({
            code: 'NOT_FOUND',
            message: 'Spotify playlist not found',
            timestamp: '2026-11-17T12:00:00Z',
            path: '/api/migrations/preview',
          }),
          { status: 404, headers: { 'Content-Type': 'application/json' } }
        );
      };

      await assert.rejects(
        async () => {
          await reviewApi.generatePreview({ spotifyPlaylistId: 'non-existent' });
        },
        (err: unknown) => {
          assert.ok(err instanceof ApiException);
          assert.strictEqual(err.status, 404);
          assert.strictEqual(err.code, 'NOT_FOUND');
          return true;
        }
      );
    });
  });

  describe('2. Review Session Lifecycle (POST & GET /api/review-sessions)', () => {
    it('creates a review session from source playlist and plan ID', async () => {
      let requestedUrl = '';
      let requestedMethod = '';
      let requestedBody = '';

      const mockSession = {
        id: 'session-uuid-123',
        userId: 'user-uuid-456',
        sourcePlaylistId: 'spotify-pl-123',
        sourcePlaylistTitle: 'Road Trip Bangers',
        planId: 'plan-uuid-789',
        status: 'IN_PROGRESS',
        totalTracks: 2,
        createdAt: '2026-11-17T12:00:00Z',
        updatedAt: '2026-11-17T12:00:00Z',
        completedAt: null,
        progress: {
          totalTracks: 2,
          awaitingReviewCount: 1,
          acceptedCount: 1,
          manuallyCorrectedCount: 0,
          excludedCount: 0,
          unresolvedConflictCount: 0,
          remainingRequiredDecisions: 1,
          eligibleForCompletion: false,
        },
      };

      globalThis.fetch = async (url, init) => {
        requestedUrl = String(url);
        requestedMethod = init?.method || '';
        requestedBody = String(init?.body || '');
        return new Response(JSON.stringify(mockSession), {
          status: 201,
          headers: { 'Content-Type': 'application/json' },
        });
      };

      const result = await reviewApi.createSession({
        sourcePlaylistId: 'spotify-pl-123',
        planId: 'plan-uuid-789',
        deduplicate: true,
      });

      assert.ok(requestedUrl.endsWith('/api/review-sessions'));
      assert.strictEqual(requestedMethod, 'POST');
      assert.ok(requestedBody.includes('"sourcePlaylistId":"spotify-pl-123"'));
      assert.strictEqual(result.id, 'session-uuid-123');
      assert.strictEqual(result.status, 'IN_PROGRESS');
      assert.strictEqual(result.progress.eligibleForCompletion, false);
      assert.strictEqual(result.progress.remainingRequiredDecisions, 1);
    });

    it('fetches review session details and progress summary', async () => {
      const mockSession = {
        id: 'session-uuid-123',
        userId: 'user-uuid-456',
        sourcePlaylistId: 'spotify-pl-123',
        sourcePlaylistTitle: 'Road Trip Bangers',
        status: 'IN_PROGRESS',
        totalTracks: 2,
        createdAt: '2026-11-17T12:00:00Z',
        updatedAt: '2026-11-17T12:00:00Z',
        completedAt: null,
        progress: {
          totalTracks: 2,
          awaitingReviewCount: 0,
          acceptedCount: 2,
          manuallyCorrectedCount: 0,
          excludedCount: 0,
          unresolvedConflictCount: 0,
          remainingRequiredDecisions: 0,
          eligibleForCompletion: true,
        },
      };

      globalThis.fetch = async (url) => {
        if (String(url).endsWith('/progress')) {
          return new Response(JSON.stringify(mockSession.progress), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          });
        }
        return new Response(JSON.stringify(mockSession), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      };

      const session = await reviewApi.getSession('session-uuid-123');
      const progress = await reviewApi.getProgress('session-uuid-123');

      assert.strictEqual(session.id, 'session-uuid-123');
      assert.strictEqual(progress.eligibleForCompletion, true);
      assert.strictEqual(progress.remainingRequiredDecisions, 0);
    });

    it('retrieves itemized tracks preserving 0-indexed positions', async () => {
      const mockTracks = [
        {
          id: 'track-0',
          sessionId: 'session-uuid-123',
          position: 0,
          sourceTrackId: 'spotify:track:001',
          sourceTitle: 'Song One',
          sourceArtist: 'Artist A',
          originalClassification: 'AUTO_MATCH',
          reviewStatus: 'ACCEPTED',
          approvedForMigration: true,
          unresolved: false,
          excluded: false,
          hasConflict: false,
        },
        {
          id: 'track-1',
          sessionId: 'session-uuid-123',
          position: 1,
          sourceTrackId: 'spotify:track:002',
          sourceTitle: 'Song Two',
          sourceArtist: 'Artist B',
          originalClassification: 'REVIEW_REQUIRED',
          reviewStatus: 'AWAITING_REVIEW',
          approvedForMigration: false,
          unresolved: true,
          excluded: false,
          hasConflict: false,
        },
      ];

      globalThis.fetch = async () => {
        return new Response(JSON.stringify(mockTracks), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      };

      const tracks = await reviewApi.getTracks('session-uuid-123');
      assert.strictEqual(tracks.length, 2);
      assert.strictEqual(tracks[0].position, 0);
      assert.strictEqual(tracks[0].reviewStatus, 'ACCEPTED');
      assert.strictEqual(tracks[1].position, 1);
      assert.strictEqual(tracks[1].reviewStatus, 'AWAITING_REVIEW');
    });
  });

  describe('3. Track Review Decisions (Accept, Select Candidate, Exclude)', () => {
    it('accepts automatic match on track', async () => {
      let requestedUrl = '';
      let requestedMethod = '';

      const updatedTrack = {
        id: 'track-1',
        sessionId: 'session-uuid-123',
        position: 1,
        sourceTrackId: 'spotify:track:002',
        sourceTitle: 'Song Two',
        sourceArtist: 'Artist B',
        reviewStatus: 'ACCEPTED',
        approvedForMigration: true,
        unresolved: false,
        excluded: false,
        hasConflict: false,
      };

      globalThis.fetch = async (url, init) => {
        requestedUrl = String(url);
        requestedMethod = init?.method || '';
        return new Response(JSON.stringify(updatedTrack), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      };

      const result = await reviewApi.acceptMatch('session-uuid-123', 1);

      assert.ok(requestedUrl.endsWith('/api/review-sessions/session-uuid-123/tracks/1/accept'));
      assert.strictEqual(requestedMethod, 'POST');
      assert.strictEqual(result.reviewStatus, 'ACCEPTED');
      assert.strictEqual(result.approvedForMigration, true);
    });

    it('selects replacement candidate with candidate payload', async () => {
      let requestedUrl = '';
      let requestedBody = '';

      const updatedTrack = {
        id: 'track-1',
        sessionId: 'session-uuid-123',
        position: 1,
        sourceTrackId: 'spotify:track:002',
        sourceTitle: 'Song Two',
        sourceArtist: 'Artist B',
        reviewStatus: 'USER_SELECTED',
        selectedCandidate: {
          id: 'yt-replacement-999',
          title: 'Song Two (Studio Version)',
          channelOrArtist: 'Artist B Official',
          durationMs: 205000,
        },
        approvedForMigration: true,
        unresolved: false,
        excluded: false,
        hasConflict: false,
      };

      globalThis.fetch = async (url, init) => {
        requestedUrl = String(url);
        requestedBody = String(init?.body || '');
        return new Response(JSON.stringify(updatedTrack), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      };

      const result = await reviewApi.selectCandidate('session-uuid-123', 1, {
        candidate: {
          id: 'yt-replacement-999',
          title: 'Song Two (Studio Version)',
          channelOrArtist: 'Artist B Official',
          durationMs: 205000,
        },
        notes: 'User picked official studio track',
      });

      assert.ok(requestedUrl.endsWith('/api/review-sessions/session-uuid-123/tracks/1/select'));
      assert.ok(requestedBody.includes('yt-replacement-999'));
      assert.strictEqual(result.reviewStatus, 'USER_SELECTED');
      assert.strictEqual(result.selectedCandidate?.id, 'yt-replacement-999');
    });

    it('excludes track from migration with optional reason', async () => {
      let requestedUrl = '';
      let requestedBody = '';

      const updatedTrack = {
        id: 'track-1',
        sessionId: 'session-uuid-123',
        position: 1,
        sourceTrackId: 'spotify:track:002',
        sourceTitle: 'Song Two',
        sourceArtist: 'Artist B',
        reviewStatus: 'EXCLUDED',
        excluded: true,
        approvedForMigration: false,
        unresolved: false,
        hasConflict: false,
      };

      globalThis.fetch = async (url, init) => {
        requestedUrl = String(url);
        requestedBody = String(init?.body || '');
        return new Response(JSON.stringify(updatedTrack), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      };

      const result = await reviewApi.excludeTrack('session-uuid-123', 1, 'Do not want this track');

      assert.ok(requestedUrl.endsWith('/api/review-sessions/session-uuid-123/tracks/1/exclude'));
      assert.ok(requestedBody.includes('Do not want this track'));
      assert.strictEqual(result.excluded, true);
      assert.strictEqual(result.approvedForMigration, false);
    });
  });

  describe('4. Candidate Search & Session Completion', () => {
    it('searches destination candidates for a track', async () => {
      let requestedUrl = '';
      let requestedBody = '';

      const mockSearchResult = {
        sessionId: 'session-uuid-123',
        trackPosition: 1,
        sourceTrackId: 'spotify:track:002',
        sourceTitle: 'Song Two',
        sourceArtist: 'Artist B',
        queryUsed: 'Song Two Artist B',
        candidates: [
          {
            id: 'yt-cand-1',
            title: 'Song Two - Official Video',
            channelOrArtist: 'Artist B',
            durationMs: 205000,
          },
        ],
        isCustomQuery: false,
        totalResults: 1,
      };

      globalThis.fetch = async (url, init) => {
        requestedUrl = String(url);
        requestedBody = String(init?.body || '');
        return new Response(JSON.stringify(mockSearchResult), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      };

      const result = await reviewApi.searchCandidates('session-uuid-123', 1, {
        query: 'Song Two Artist B',
        limit: 10,
      });

      assert.ok(requestedUrl.endsWith('/api/review-sessions/session-uuid-123/tracks/1/candidates/search'));
      assert.ok(requestedBody.includes('Song Two Artist B'));
      assert.strictEqual(result.candidates.length, 1);
      assert.strictEqual(result.candidates[0].id, 'yt-cand-1');
    });

    it('completes review session when all decisions are resolved', async () => {
      let requestedUrl = '';
      const completedSession = {
        id: 'session-uuid-123',
        status: 'COMPLETED',
        totalTracks: 2,
        progress: {
          totalTracks: 2,
          remainingRequiredDecisions: 0,
          eligibleForCompletion: true,
        },
      };

      globalThis.fetch = async (url) => {
        requestedUrl = String(url);
        return new Response(JSON.stringify(completedSession), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      };

      const result = await reviewApi.completeSession('session-uuid-123');
      assert.ok(requestedUrl.endsWith('/api/review-sessions/session-uuid-123/complete'));
      assert.strictEqual(result.status, 'COMPLETED');
    });
  });
});
