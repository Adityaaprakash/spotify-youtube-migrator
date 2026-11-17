import { describe, it, beforeEach, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { historyApi } from '../features/history/api.ts';
import { ApiException } from '../lib/api/types.ts';
import { PagedResponse, MigrationAuditEvent } from '../features/history/types.ts';
import { MigrationExecutionDetail, ExecutionTrackRecord } from '../features/execution/types.ts';

describe('Phase 15H — Audit Timeline & Safe Recovery Tests', () => {
  const originalFetch = globalThis.fetch;

  beforeEach(() => {
    (globalThis as unknown as { document?: { cookie: string } }).document = {
      cookie: 'XSRF-TOKEN=test-csrf-token',
    };
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  describe('1. Chronological Audit Timeline (GET /api/migrations/:id/history)', () => {
    it('retrieves chronological audit events for migration execution', async () => {
      let requestedUrl = '';

      const mockTimeline: PagedResponse<MigrationAuditEvent> = {
        content: [
          {
            id: 'evt-001',
            executionId: 'e82b7b3d-1111-4bad-9bdd-2b0d7b3dcb6d',
            eventType: 'STATE_TRANSITION',
            previousState: null,
            newState: 'INITIALIZED',
            reason: 'Execution aggregate created and tracks initialized',
            actor: 'USER',
            correlationId: 'op-001',
            details: null,
            rollbackRecordId: null,
            reconciliationRecordId: null,
            createdAt: '2026-03-01T10:00:00Z',
          },
          {
            id: 'evt-002',
            executionId: 'e82b7b3d-1111-4bad-9bdd-2b0d7b3dcb6d',
            eventType: 'STATE_TRANSITION',
            previousState: 'INITIALIZED',
            newState: 'QUEUED',
            reason: 'Queued for background worker processing',
            actor: 'SYSTEM',
            correlationId: 'op-002',
            details: null,
            rollbackRecordId: null,
            reconciliationRecordId: null,
            createdAt: '2026-03-01T10:00:05Z',
          },
          {
            id: 'evt-003',
            executionId: 'e82b7b3d-1111-4bad-9bdd-2b0d7b3dcb6d',
            eventType: 'RETRY_SCHEDULED',
            previousState: 'RUNNING',
            newState: 'RUNNING',
            reason: 'Rate limit encountered on track 4; backoff 2000ms',
            actor: 'WORKER',
            correlationId: 'lease-999',
            details: '{"retryBackoffMs": 2000, "attempt": 2}',
            rollbackRecordId: null,
            reconciliationRecordId: null,
            createdAt: '2026-03-01T10:01:10Z',
          },
        ],
        page: 0,
        size: 50,
        totalElements: 3,
        totalPages: 1,
        first: true,
        last: true,
      };

      globalThis.fetch = async (url) => {
        requestedUrl = String(url);
        return new Response(JSON.stringify(mockTimeline), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      };

      const result = await historyApi.getAuditTimeline('e82b7b3d-1111-4bad-9bdd-2b0d7b3dcb6d');

      const urlObj = new URL(requestedUrl, 'http://localhost');
      assert.strictEqual(urlObj.pathname, '/api/migrations/e82b7b3d-1111-4bad-9bdd-2b0d7b3dcb6d/history');
      assert.strictEqual(urlObj.searchParams.get('page'), '0');
      assert.strictEqual(urlObj.searchParams.get('size'), '50');

      assert.strictEqual(result.totalElements, 3);
      assert.strictEqual(result.content[0].eventType, 'STATE_TRANSITION');
      assert.strictEqual(result.content[0].newState, 'INITIALIZED');
      assert.strictEqual(result.content[1].previousState, 'INITIALIZED');
      assert.strictEqual(result.content[1].newState, 'QUEUED');
      assert.strictEqual(result.content[2].eventType, 'RETRY_SCHEDULED');
      assert.strictEqual(result.content[2].actor, 'WORKER');
    });

    it('handles 404 anti-IDOR non-disclosure on audit timeline', async () => {
      globalThis.fetch = async () => {
        return new Response(
          JSON.stringify({
            status: 404,
            code: 'NOT_FOUND',
            message: 'Migration execution not found: e82b7b3d-foreign',
            timestamp: '2026-03-01T10:00:00Z',
          }),
          { status: 404, headers: { 'Content-Type': 'application/json' } }
        );
      };

      await assert.rejects(
        async () => {
          await historyApi.getAuditTimeline('e82b7b3d-foreign');
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

  describe('2. Historical Track Outcomes (GET /api/migrations/:id/tracks)', () => {
    it('retrieves track outcomes ordered by target position with match details', async () => {
      let requestedUrl = '';

      const mockTracks: ExecutionTrackRecord[] = [
        {
          id: 'rec-001',
          targetPosition: 0,
          sourcePosition: 0,
          sourceTrackId: 'spotify:track:001',
          sourceTitle: 'Midnight City',
          sourceArtist: 'M83',
          destinationTrackId: 'yt-001',
          destinationPlaylistItemId: 'yt-item-001',
          status: 'ADDED',
          errorMessage: null,
          attemptCount: 1,
          destinationTitle: 'M83 - Midnight City (Official)',
          destinationChannel: 'M83',
          destinationDurationMs: 243000,
          decisionSource: 'AUTOMATIC',
          matchScore: 0.98,
          matchDecision: 'AUTO_ACCEPT',
        },
      ];

      globalThis.fetch = async (url) => {
        requestedUrl = String(url);
        return new Response(JSON.stringify(mockTracks), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      };

      const tracks = await historyApi.getTracks('e82b7b3d-1111-4bad-9bdd-2b0d7b3dcb6d');

      assert.ok(requestedUrl.endsWith('/api/migrations/e82b7b3d-1111-4bad-9bdd-2b0d7b3dcb6d/tracks'));
      assert.strictEqual(tracks.length, 1);
      assert.strictEqual(tracks[0].targetPosition, 0);
      assert.strictEqual(tracks[0].status, 'ADDED');
      assert.strictEqual(tracks[0].matchScore, 0.98);
    });
  });

  describe('3. Safe Resume and Recovery (POST /api/migrations/:id/resume)', () => {
    it('resumes eligible unfinished migration execution transmitting CSRF header', async () => {
      let requestedUrl = '';
      let requestedMethod = '';
      let requestedHeaders: Headers | undefined;

      const mockResumedDetail: MigrationExecutionDetail = {
        executionId: 'e82b7b3d-1111-4bad-9bdd-2b0d7b3dcb6d',
        reviewSessionId: 'r52b7b3d-2222-4bad-9bdd-2b0d7b3dcb6d',
        userId: 'u99b7b3d-4444-4bad-9bdd-2b0d7b3dcb6d',
        destinationPlatform: 'YOUTUBE_MUSIC',
        destinationPlaylistId: 'PLxyz123',
        destinationPlaylistUrl: 'https://music.youtube.com/playlist?list=PLxyz123',
        sourcePlaylistTitle: 'Summer Vibes',
        status: 'RUNNING',
        totalApprovedTracks: 20,
        successfulInsertions: 15,
        failedInsertions: 0,
        uncertainInsertions: 0,
        skippedInsertions: 0,
        fullySuccessful: false,
        canResume: false,
        canRollback: true,
        startedAt: '2026-03-01T10:00:00Z',
        completedAt: null,
        totalSourceTracks: 20,
        matchedTracks: 20,
        failureSummary: null,
      };

      globalThis.fetch = async (url, init) => {
        requestedUrl = String(url);
        requestedMethod = init?.method || '';
        requestedHeaders = init?.headers as Headers;
        return new Response(JSON.stringify(mockResumedDetail), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      };

      const result = await historyApi.resumeMigration('e82b7b3d-1111-4bad-9bdd-2b0d7b3dcb6d');

      assert.ok(requestedUrl.endsWith('/api/migrations/e82b7b3d-1111-4bad-9bdd-2b0d7b3dcb6d/resume'));
      assert.strictEqual(requestedMethod, 'POST');
      assert.ok(requestedHeaders instanceof Headers);
      assert.strictEqual(requestedHeaders.get('X-XSRF-TOKEN'), 'test-csrf-token');

      assert.strictEqual(result.executionId, 'e82b7b3d-1111-4bad-9bdd-2b0d7b3dcb6d');
      assert.strictEqual(result.status, 'RUNNING');
      assert.strictEqual(result.canResume, false);
    });

    it('rejects resume when execution is already RUNNING with 409 Conflict', async () => {
      globalThis.fetch = async () => {
        return new Response(
          JSON.stringify({
            status: 409,
            code: 'INVALID_STATE',
            message: 'Migration execution is already in progress',
            timestamp: '2026-03-01T10:05:00Z',
          }),
          { status: 409, headers: { 'Content-Type': 'application/json' } }
        );
      };

      await assert.rejects(
        async () => {
          await historyApi.resumeMigration('e82b7b3d-running');
        },
        (err: unknown) => {
          assert.ok(err instanceof ApiException);
          assert.strictEqual(err.status, 409);
          assert.strictEqual(err.code, 'INVALID_STATE');
          assert.ok(err.message.includes('already in progress'));
          return true;
        }
      );
    });

    it('rejects resume when execution is already COMPLETED with 409 Conflict', async () => {
      globalThis.fetch = async () => {
        return new Response(
          JSON.stringify({
            status: 409,
            code: 'INVALID_STATE',
            message: 'Cannot resume: execution is already COMPLETED',
            timestamp: '2026-03-01T10:05:00Z',
          }),
          { status: 409, headers: { 'Content-Type': 'application/json' } }
        );
      };

      await assert.rejects(
        async () => {
          await historyApi.resumeMigration('e82b7b3d-completed');
        },
        (err: unknown) => {
          assert.ok(err instanceof ApiException);
          assert.strictEqual(err.status, 409);
          assert.strictEqual(err.code, 'INVALID_STATE');
          assert.ok(err.message.includes('already COMPLETED'));
          return true;
        }
      );
    });

    it('rejects resume when execution has been ROLLED_BACK with 409 Conflict', async () => {
      globalThis.fetch = async () => {
        return new Response(
          JSON.stringify({
            status: 409,
            code: 'INVALID_STATE',
            message: 'Cannot resume: execution has been ROLLED_BACK',
            timestamp: '2026-03-01T10:05:00Z',
          }),
          { status: 409, headers: { 'Content-Type': 'application/json' } }
        );
      };

      await assert.rejects(
        async () => {
          await historyApi.resumeMigration('e82b7b3d-rolled-back');
        },
        (err: unknown) => {
          assert.ok(err instanceof ApiException);
          assert.strictEqual(err.status, 409);
          assert.strictEqual(err.code, 'INVALID_STATE');
          assert.ok(err.message.includes('ROLLED_BACK'));
          return true;
        }
      );
    });

    it('handles 404 anti-IDOR non-disclosure when resuming unowned execution', async () => {
      globalThis.fetch = async () => {
        return new Response(
          JSON.stringify({
            status: 404,
            code: 'NOT_FOUND',
            message: 'Migration execution not found: e82b7b3d-foreign',
            timestamp: '2026-03-01T10:05:00Z',
          }),
          { status: 404, headers: { 'Content-Type': 'application/json' } }
        );
      };

      await assert.rejects(
        async () => {
          await historyApi.resumeMigration('e82b7b3d-foreign');
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
});
