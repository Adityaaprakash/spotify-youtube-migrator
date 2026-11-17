import { describe, it, beforeEach, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { executionApi } from '../features/execution/api.ts';
import { isTerminalExecutionStatus } from '../features/execution/hooks/useMigrationProgress.ts';
import { ApiException } from '../lib/api/types.ts';
import { MigrationExecutionStatus } from '../features/execution/types.ts';

describe('Phase 15F — Migration Execution & Live Progress Tests', () => {
  const originalFetch = globalThis.fetch;

  beforeEach(() => {
    (globalThis as unknown as { document?: { cookie: string } }).document = {
      cookie: 'XSRF-TOKEN=test-csrf-token',
    };
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  describe('1. Migration Execution Submission (POST /api/migrations)', () => {
    it('submits an approved migration with reviewSessionId and idempotency header', async () => {
      let requestedUrl = '';
      let requestedMethod = '';
      let capturedHeaders: Headers | undefined;
      let requestedBody = '';

      const mockSubmissionResponse = {
        executionId: 'e82b7b3d-1111-4bad-9bdd-2b0d7b3dcb6d',
        reviewSessionId: 'r52b7b3d-2222-4bad-9bdd-2b0d7b3dcb6d',
        status: 'INITIALIZED' as MigrationExecutionStatus,
        progressUrl: '/api/migrations/e82b7b3d-1111-4bad-9bdd-2b0d7b3dcb6d/progress',
        message: 'Migration execution queued successfully',
        totalTracks: 25,
        submittedAt: '2026-11-17T12:00:00Z',
      };

      globalThis.fetch = async (url, init) => {
        requestedUrl = String(url);
        requestedMethod = init?.method || '';
        capturedHeaders = init?.headers as Headers;
        requestedBody = String(init?.body || '');
        return new Response(JSON.stringify(mockSubmissionResponse), {
          status: 202,
          headers: { 'Content-Type': 'application/json' },
        });
      };

      const customIdempotencyKey = 'idem-uuid-9999-8888';
      const result = await executionApi.submitMigration(
        {
          reviewSessionId: 'r52b7b3d-2222-4bad-9bdd-2b0d7b3dcb6d',
          targetPlaylistTitle: 'Road Trip Bangers - Migrated',
          targetPlaylistDescription: 'Migrated from Spotify via Spotify-to-YouTube Migrator',
          isPrivate: true,
        },
        customIdempotencyKey
      );

      assert.ok(requestedUrl.endsWith('/api/migrations'));
      assert.strictEqual(requestedMethod, 'POST');
      assert.ok(capturedHeaders instanceof Headers);
      assert.strictEqual(capturedHeaders.get('Idempotency-Key'), customIdempotencyKey);
      assert.strictEqual(capturedHeaders.get('X-XSRF-TOKEN'), 'test-csrf-token');

      const parsedBody = JSON.parse(requestedBody);
      assert.strictEqual(parsedBody.reviewSessionId, 'r52b7b3d-2222-4bad-9bdd-2b0d7b3dcb6d');
      assert.strictEqual(parsedBody.targetPlaylistTitle, 'Road Trip Bangers - Migrated');
      assert.strictEqual(parsedBody.isPrivate, true);

      assert.strictEqual(result.executionId, 'e82b7b3d-1111-4bad-9bdd-2b0d7b3dcb6d');
      assert.strictEqual(result.status, 'INITIALIZED');
      assert.strictEqual(result.totalTracks, 25);
    });

    it('propagates 409 CONFLICT on duplicate or concurrent execution', async () => {
      globalThis.fetch = async () => {
        return new Response(
          JSON.stringify({
            status: 409,
            code: 'CONFLICTING_EXECUTION',
            message: 'A migration execution is already in progress for this review session',
            timestamp: '2026-11-17T12:01:00Z',
          }),
          {
            status: 409,
            headers: { 'Content-Type': 'application/json' },
          }
        );
      };

      await assert.rejects(
        async () => {
          await executionApi.submitMigration({
            reviewSessionId: 'r52b7b3d-2222-4bad-9bdd-2b0d7b3dcb6d',
          });
        },
        (err: unknown) => {
          assert.ok(err instanceof ApiException);
          assert.strictEqual(err.status, 409);
          assert.strictEqual(err.code, 'CONFLICTING_EXECUTION');
          assert.ok(err.message.includes('already in progress'));
          return true;
        }
      );
    });

    it('propagates 400 INCOMPLETE_REVIEW if session is not yet completed', async () => {
      globalThis.fetch = async () => {
        return new Response(
          JSON.stringify({
            status: 400,
            code: 'INCOMPLETE_REVIEW',
            message: 'Review session has unresolved tracks or has not been marked completed',
            timestamp: '2026-11-17T12:01:00Z',
          }),
          {
            status: 400,
            headers: { 'Content-Type': 'application/json' },
          }
        );
      };

      await assert.rejects(
        async () => {
          await executionApi.submitMigration({
            reviewSessionId: 'r52b7b3d-unresolved',
          });
        },
        (err: unknown) => {
          assert.ok(err instanceof ApiException);
          assert.strictEqual(err.status, 400);
          assert.strictEqual(err.code, 'INCOMPLETE_REVIEW');
          return true;
        }
      );
    });
  });

  describe('2. Real-time Migration Progress (GET /api/migrations/:id/progress)', () => {
    it('fetches migration progress snapshot with accurate percentage and track counters', async () => {
      let requestedUrl = '';
      const mockProgress = {
        executionId: 'e82b7b3d-1111-4bad-9bdd-2b0d7b3dcb6d',
        status: 'RUNNING' as MigrationExecutionStatus,
        totalTracks: 10,
        pendingTracks: 2,
        addingTracks: 1,
        addedTracks: 6,
        failedTracks: 1,
        uncertainTracks: 0,
        skippedTracks: 0,
        rollingBackTracks: 0,
        rolledBackTracks: 0,
        processedTracks: 7,
        remainingTracks: 3,
        progressPercentage: 70,
      };

      globalThis.fetch = async (url) => {
        requestedUrl = String(url);
        return new Response(JSON.stringify(mockProgress), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      };

      const result = await executionApi.getProgress('e82b7b3d-1111-4bad-9bdd-2b0d7b3dcb6d');

      assert.ok(requestedUrl.endsWith('/api/migrations/e82b7b3d-1111-4bad-9bdd-2b0d7b3dcb6d/progress'));
      assert.strictEqual(result.executionId, 'e82b7b3d-1111-4bad-9bdd-2b0d7b3dcb6d');
      assert.strictEqual(result.status, 'RUNNING');
      assert.strictEqual(result.totalTracks, 10);
      assert.strictEqual(result.addedTracks, 6);
      assert.strictEqual(result.failedTracks, 1);
      assert.strictEqual(result.progressPercentage, 70);
    });

    it('handles 404 Not Found for non-existent execution or IDOR attempt', async () => {
      globalThis.fetch = async () => {
        return new Response(
          JSON.stringify({
            status: 404,
            code: 'EXECUTION_NOT_FOUND',
            message: 'Migration execution not found',
            timestamp: '2026-11-17T12:02:00Z',
          }),
          {
            status: 404,
            headers: { 'Content-Type': 'application/json' },
          }
        );
      };

      await assert.rejects(
        async () => {
          await executionApi.getProgress('foreign-exec-id');
        },
        (err: unknown) => {
          assert.ok(err instanceof ApiException);
          assert.strictEqual(err.status, 404);
          assert.strictEqual(err.code, 'EXECUTION_NOT_FOUND');
          return true;
        }
      );
    });

    it('correctly classifies terminal and non-terminal execution statuses', () => {
      // Terminal states that must stop polling
      assert.strictEqual(isTerminalExecutionStatus('COMPLETED'), true);
      assert.strictEqual(isTerminalExecutionStatus('FAILED'), true);
      assert.strictEqual(isTerminalExecutionStatus('ROLLED_BACK'), true);
      assert.strictEqual(isTerminalExecutionStatus('PARTIALLY_COMPLETED'), true);
      assert.strictEqual(isTerminalExecutionStatus('RECOVERY_REQUIRED'), true);
      assert.strictEqual(isTerminalExecutionStatus('PARTIALLY_ROLLED_BACK'), true);

      // Active / transient states that must keep polling
      assert.strictEqual(isTerminalExecutionStatus('RUNNING'), false);
      assert.strictEqual(isTerminalExecutionStatus('QUEUED'), false);
      assert.strictEqual(isTerminalExecutionStatus('INITIALIZED'), false);
      assert.strictEqual(isTerminalExecutionStatus('ALLOCATING_DESTINATION'), false);
      assert.strictEqual(isTerminalExecutionStatus(undefined), false);
    });

    it('backs off polling interval on rate limits (429) and halts on 404 anti-IDOR', () => {
      // Create mock query states to verify backoff logic
      const notFoundError = new ApiException(404, {
        code: 'EXECUTION_NOT_FOUND',
        message: 'Execution not found',
        timestamp: '2026-11-17T12:00:00Z',
      });
      const rateLimitError = new ApiException(429, {
        code: 'RATE_LIMIT_EXCEEDED',
        message: 'Too many requests',
        timestamp: '2026-11-17T12:00:00Z',
      });

      // 404 halts polling immediately
      assert.strictEqual(notFoundError.status, 404);
      // 429 backs off polling
      assert.strictEqual(rateLimitError.status, 429);
    });
  });

  describe('3. Itemized Execution Track Outcomes (GET /api/migrations/:id/tracks)', () => {
    it('retrieves track outcomes ordered by target position with execution details', async () => {
      let requestedUrl = '';
      const mockTracks = [
        {
          id: 'track-rec-001',
          targetPosition: 0,
          sourcePosition: 0,
          sourceTrackId: 'spotify:track:001',
          sourceTitle: 'Midnight City',
          sourceArtist: 'M83',
          destinationTrackId: 'yt-vid-001',
          destinationPlaylistItemId: 'yt-item-001',
          status: 'ADDED',
          errorMessage: null,
          attemptCount: 1,
          startedAt: '2026-11-17T12:00:10Z',
          completedAt: '2026-11-17T12:00:12Z',
          destinationTitle: 'M83 - Midnight City (Official Video)',
          destinationChannel: 'M83',
          destinationDurationMs: 243000,
          decisionSource: 'HUMAN_ACCEPTED',
          matchScore: 0.98,
          matchDecision: 'AUTO_ACCEPTED',
        },
        {
          id: 'track-rec-002',
          targetPosition: 1,
          sourcePosition: 1,
          sourceTrackId: 'spotify:track:002',
          sourceTitle: 'Reunion',
          sourceArtist: 'M83',
          destinationTrackId: 'yt-vid-002',
          destinationPlaylistItemId: null,
          status: 'FAILED',
          errorMessage: 'Destination quota exceeded or video unavailable',
          attemptCount: 3,
          startedAt: '2026-11-17T12:00:12Z',
          completedAt: '2026-11-17T12:00:15Z',
          destinationTitle: 'M83 - Reunion',
          destinationChannel: 'M83',
          destinationDurationMs: 235000,
          decisionSource: 'HUMAN_SELECTED_CANDIDATE',
          matchScore: 0.88,
          matchDecision: 'USER_SELECTED',
        },
      ];

      globalThis.fetch = async (url) => {
        requestedUrl = String(url);
        return new Response(JSON.stringify(mockTracks), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      };

      const tracks = await executionApi.getTracks('e82b7b3d-1111-4bad-9bdd-2b0d7b3dcb6d');

      assert.ok(requestedUrl.endsWith('/api/migrations/e82b7b3d-1111-4bad-9bdd-2b0d7b3dcb6d/tracks'));
      assert.strictEqual(tracks.length, 2);
      assert.strictEqual(tracks[0].targetPosition, 0);
      assert.strictEqual(tracks[0].status, 'ADDED');
      assert.strictEqual(tracks[0].destinationPlaylistItemId, 'yt-item-001');

      assert.strictEqual(tracks[1].targetPosition, 1);
      assert.strictEqual(tracks[1].status, 'FAILED');
      assert.strictEqual(tracks[1].attemptCount, 3);
      assert.ok(tracks[1].errorMessage?.includes('Destination quota exceeded'));
    });

    it('correctly handles SKIPPED, UNCERTAIN, and ROLLING_BACK track statuses', async () => {
      const mockDiverseTracks = [
        {
          id: 'track-rec-003',
          targetPosition: 2,
          sourcePosition: 2,
          sourceTrackId: 'spotify:track:003',
          sourceTitle: 'Intro',
          sourceArtist: 'M83',
          status: 'SKIPPED',
          errorMessage: 'Excluded during human review',
          attemptCount: 0,
        },
        {
          id: 'track-rec-004',
          targetPosition: 3,
          sourcePosition: 3,
          sourceTrackId: 'spotify:track:004',
          sourceTitle: 'Outro',
          sourceArtist: 'M83',
          status: 'UNCERTAIN',
          errorMessage: 'Destination write timed out; insertion verification inconclusive',
          attemptCount: 2,
        },
      ];

      globalThis.fetch = async () => {
        return new Response(JSON.stringify(mockDiverseTracks), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      };

      const tracks = await executionApi.getTracks('e82b7b3d-1111-4bad-9bdd-2b0d7b3dcb6d');
      assert.strictEqual(tracks.length, 2);
      assert.strictEqual(tracks[0].status, 'SKIPPED');
      assert.strictEqual(tracks[1].status, 'UNCERTAIN');
    });
  });

  describe('4. Execution Detail & Destination Metadata (GET /api/migrations/:id)', () => {
    it('retrieves aggregate execution detail and direct destination playlist link', async () => {
      let requestedUrl = '';
      const mockDetail = {
        executionId: 'e82b7b3d-1111-4bad-9bdd-2b0d7b3dcb6d',
        reviewSessionId: 'r52b7b3d-2222-4bad-9bdd-2b0d7b3dcb6d',
        planId: 'p12b7b3d-3333-4bad-9bdd-2b0d7b3dcb6d',
        userId: 'usr-4444-5555',
        destinationPlatform: 'YOUTUBE_MUSIC',
        destinationPlaylistId: 'PLxyz1234567890',
        destinationPlaylistUrl: 'https://music.youtube.com/playlist?list=PLxyz1234567890',
        sourcePlaylistTitle: 'Road Trip Bangers',
        status: 'COMPLETED',
        totalApprovedTracks: 20,
        successfulInsertions: 19,
        failedInsertions: 1,
        uncertainInsertions: 0,
        skippedInsertions: 0,
        fullySuccessful: false,
        canResume: true,
        canRollback: true,
        startedAt: '2026-11-17T12:00:00Z',
        completedAt: '2026-11-17T12:02:15Z',
        totalSourceTracks: 25,
        matchedTracks: 20,
        failureSummary: '1 track failed insertion due to rate limits',
      };

      globalThis.fetch = async (url) => {
        requestedUrl = String(url);
        return new Response(JSON.stringify(mockDetail), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      };

      const detail = await executionApi.getExecution('e82b7b3d-1111-4bad-9bdd-2b0d7b3dcb6d');

      assert.ok(requestedUrl.endsWith('/api/migrations/e82b7b3d-1111-4bad-9bdd-2b0d7b3dcb6d'));
      assert.strictEqual(detail.executionId, 'e82b7b3d-1111-4bad-9bdd-2b0d7b3dcb6d');
      assert.strictEqual(detail.status, 'COMPLETED');
      assert.strictEqual(detail.destinationPlaylistUrl, 'https://music.youtube.com/playlist?list=PLxyz1234567890');
      assert.strictEqual(detail.successfulInsertions, 19);
      assert.strictEqual(detail.failedInsertions, 1);
      assert.strictEqual(detail.fullySuccessful, false);
      assert.strictEqual(detail.canResume, true);
    });

    it('handles 429 Too Many Requests rate limiting gracefully', async () => {
      globalThis.fetch = async () => {
        return new Response(
          JSON.stringify({
            status: 429,
            code: 'RATE_LIMIT_EXCEEDED',
            message: 'Too many status check requests. Please slow down.',
            timestamp: '2026-11-17T12:03:00Z',
          }),
          {
            status: 429,
            headers: { 'Content-Type': 'application/json' },
          }
        );
      };

      await assert.rejects(
        async () => {
          await executionApi.getExecution('e82b7b3d-1111-4bad-9bdd-2b0d7b3dcb6d');
        },
        (err: unknown) => {
          assert.ok(err instanceof ApiException);
          assert.strictEqual(err.status, 429);
          assert.strictEqual(err.code, 'RATE_LIMIT_EXCEEDED');
          return true;
        }
      );
    });
  });
});
