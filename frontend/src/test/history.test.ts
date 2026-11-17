import { describe, it, beforeEach, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { historyApi } from '../features/history/api.ts';
import { ApiException } from '../lib/api/types.ts';
import { MigrationHistoryItem, PagedResponse } from '../features/history/types.ts';
import { MigrationExecutionDetail } from '../features/execution/types.ts';

describe('Phase 15G — Migration History API & Contract Tests', () => {
  const originalFetch = globalThis.fetch;

  beforeEach(() => {
    (globalThis as unknown as { document?: { cookie: string } }).document = {
      cookie: 'XSRF-TOKEN=test-csrf-token',
    };
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  describe('1. Migration History Listing (GET /api/migrations)', () => {
    it('queries migration history using default pagination (page=0, size=20)', async () => {
      let requestedUrl = '';
      let requestedMethod = '';

      const mockPagedHistory: PagedResponse<MigrationHistoryItem> = {
        content: [
          {
            migrationId: 'e82b7b3d-1111-4bad-9bdd-2b0d7b3dcb6d',
            sourcePlaylistId: 'sp-pl-001',
            sourcePlaylistTitle: 'Summer Vibes 2026',
            destinationPlatform: 'YOUTUBE_MUSIC',
            destinationPlaylistId: 'PLxyz123',
            destinationPlaylistUrl: 'https://music.youtube.com/playlist?list=PLxyz123',
            status: 'COMPLETED',
            totalSourceTracks: 15,
            matchedTracks: 15,
            totalApprovedTracks: 15,
            successfulInsertions: 15,
            failedInsertions: 0,
            uncertainInsertions: 0,
            skippedInsertions: 0,
            rolledBackInsertions: 0,
            createdAt: '2026-03-01T10:00:00Z',
            startedAt: '2026-03-01T10:00:10Z',
            completedAt: '2026-03-01T10:02:30Z',
            failureSummary: null,
          },
        ],
        page: 0,
        size: 20,
        totalElements: 1,
        totalPages: 1,
        first: true,
        last: true,
      };

      globalThis.fetch = async (url, init) => {
        requestedUrl = String(url);
        requestedMethod = init?.method || '';
        return new Response(JSON.stringify(mockPagedHistory), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      };

      const result = await historyApi.getHistory();

      assert.strictEqual(requestedMethod, 'GET');
      const urlObj = new URL(requestedUrl, 'http://localhost');
      assert.strictEqual(urlObj.pathname, '/api/migrations');
      assert.strictEqual(urlObj.searchParams.get('page'), '0');
      assert.strictEqual(urlObj.searchParams.get('size'), '20');
      assert.strictEqual(urlObj.searchParams.get('status'), null);

      assert.strictEqual(result.totalElements, 1);
      assert.strictEqual(result.content.length, 1);
      assert.strictEqual(result.content[0].migrationId, 'e82b7b3d-1111-4bad-9bdd-2b0d7b3dcb6d');
      assert.strictEqual(result.content[0].status, 'COMPLETED');
      assert.strictEqual(result.content[0].successfulInsertions, 15);
    });

    it('transmits custom pagination parameters and status filter', async () => {
      let requestedUrl = '';

      const mockPagedHistory: PagedResponse<MigrationHistoryItem> = {
        content: [
          {
            migrationId: 'e82b7b3d-2222-4bad-9bdd-2b0d7b3dcb6d',
            sourcePlaylistId: 'sp-pl-002',
            sourcePlaylistTitle: 'Road Trip Bangers',
            destinationPlatform: 'YOUTUBE_MUSIC',
            destinationPlaylistId: 'PLfailed123',
            destinationPlaylistUrl: null,
            status: 'FAILED',
            totalSourceTracks: 20,
            matchedTracks: 18,
            totalApprovedTracks: 18,
            successfulInsertions: 5,
            failedInsertions: 13,
            uncertainInsertions: 0,
            skippedInsertions: 0,
            rolledBackInsertions: 0,
            createdAt: '2026-03-02T12:00:00Z',
            startedAt: '2026-03-02T12:00:05Z',
            completedAt: '2026-03-02T12:01:15Z',
            failureSummary: 'Destination quota exceeded during track insertion',
          },
        ],
        page: 1,
        size: 10,
        totalElements: 11,
        totalPages: 2,
        first: false,
        last: true,
      };

      globalThis.fetch = async (url) => {
        requestedUrl = String(url);
        return new Response(JSON.stringify(mockPagedHistory), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      };

      const result = await historyApi.getHistory({
        page: 1,
        size: 10,
        status: 'FAILED',
      });

      const urlObj = new URL(requestedUrl, 'http://localhost');
      assert.strictEqual(urlObj.searchParams.get('page'), '1');
      assert.strictEqual(urlObj.searchParams.get('size'), '10');
      assert.strictEqual(urlObj.searchParams.get('status'), 'FAILED');

      assert.strictEqual(result.content[0].status, 'FAILED');
      assert.strictEqual(result.content[0].failedInsertions, 13);
      assert.ok(result.content[0].failureSummary?.includes('Destination quota exceeded'));
    });

    it('handles empty migration history response cleanly', async () => {
      const mockEmptyHistory: PagedResponse<MigrationHistoryItem> = {
        content: [],
        page: 0,
        size: 20,
        totalElements: 0,
        totalPages: 0,
        first: true,
        last: true,
      };

      globalThis.fetch = async () => {
        return new Response(JSON.stringify(mockEmptyHistory), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      };

      const result = await historyApi.getHistory();
      assert.strictEqual(result.totalElements, 0);
      assert.strictEqual(result.content.length, 0);
    });

    it('propagates 400 Bad Request on invalid status filter', async () => {
      globalThis.fetch = async () => {
        return new Response(
          JSON.stringify({
            status: 400,
            code: 'INVALID_REQUEST',
            message: 'Invalid status filter provided',
            timestamp: '2026-03-01T10:00:00Z',
          }),
          { status: 400, headers: { 'Content-Type': 'application/json' } }
        );
      };

      await assert.rejects(
        async () => {
          await historyApi.getHistory({ status: 'INVALID_STATUS' });
        },
        (err: unknown) => {
          assert.ok(err instanceof ApiException);
          assert.strictEqual(err.status, 400);
          assert.strictEqual(err.code, 'INVALID_REQUEST');
          return true;
        }
      );
    });
  });

  describe('2. Single Execution Detail (GET /api/migrations/:id)', () => {
    it('retrieves detailed execution record with canResume eligibility flag', async () => {
      let requestedUrl = '';

      const mockDetail: MigrationExecutionDetail = {
        executionId: 'e82b7b3d-1111-4bad-9bdd-2b0d7b3dcb6d',
        reviewSessionId: 'r52b7b3d-2222-4bad-9bdd-2b0d7b3dcb6d',
        planId: 'p12b7b3d-3333-4bad-9bdd-2b0d7b3dcb6d',
        userId: 'u99b7b3d-4444-4bad-9bdd-2b0d7b3dcb6d',
        destinationPlatform: 'YOUTUBE_MUSIC',
        destinationPlaylistId: 'PLxyz123',
        destinationPlaylistUrl: 'https://music.youtube.com/playlist?list=PLxyz123',
        sourcePlaylistTitle: 'Road Trip Bangers',
        status: 'PARTIALLY_COMPLETED',
        totalApprovedTracks: 20,
        successfulInsertions: 15,
        failedInsertions: 5,
        uncertainInsertions: 0,
        skippedInsertions: 0,
        rolledBackInsertions: 0,
        fullySuccessful: false,
        canResume: true,
        canRollback: true,
        startedAt: '2026-03-01T10:00:00Z',
        completedAt: '2026-03-01T10:03:00Z',
        totalSourceTracks: 22,
        matchedTracks: 20,
        failureSummary: '5 tracks failed due to destination rate limiting',
      };

      globalThis.fetch = async (url) => {
        requestedUrl = String(url);
        return new Response(JSON.stringify(mockDetail), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      };

      const result = await historyApi.getDetail('e82b7b3d-1111-4bad-9bdd-2b0d7b3dcb6d');

      assert.ok(requestedUrl.endsWith('/api/migrations/e82b7b3d-1111-4bad-9bdd-2b0d7b3dcb6d'));
      assert.strictEqual(result.executionId, 'e82b7b3d-1111-4bad-9bdd-2b0d7b3dcb6d');
      assert.strictEqual(result.status, 'PARTIALLY_COMPLETED');
      assert.strictEqual(result.canResume, true);
      assert.strictEqual(result.successfulInsertions, 15);
      assert.strictEqual(result.failedInsertions, 5);
      assert.strictEqual(result.destinationPlaylistUrl, 'https://music.youtube.com/playlist?list=PLxyz123');
    });

    it('handles 404 anti-IDOR non-disclosure when execution belongs to another user', async () => {
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
          await historyApi.getDetail('e82b7b3d-foreign');
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
