import { describe, it, beforeEach, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { fetchClient, apiClient } from '../lib/api/client.ts';
import { ApiException } from '../lib/api/types.ts';

describe('API Client & Security Contract Tests', () => {
  const originalFetch = globalThis.fetch;
  let lastFetchUrl = '';
  let lastFetchOptions: RequestInit | undefined;

  beforeEach(() => {
    // Setup document mock for Node.js test environment
    (globalThis as unknown as { document?: { cookie: string } }).document = {
      cookie: 'XSRF-TOKEN=mock-csrf-token-12345; other-cookie=abc',
    };

    globalThis.fetch = async (input: RequestInfo | URL, init?: RequestInit) => {
      lastFetchUrl = String(input);
      lastFetchOptions = init;
      return new Response(JSON.stringify({ success: true }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      });
    };
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  it('enforces credentials: include on all requests', async () => {
    await apiClient.get('/test-endpoint');
    assert.strictEqual(lastFetchOptions?.credentials, 'include');
  });

  it('automatically extracts XSRF-TOKEN cookie and injects X-XSRF-TOKEN header on mutating POST', async () => {
    await apiClient.post('/api/auth/login', { email: 'user@example.com', password: 'secretPassword' });

    assert.ok(lastFetchOptions?.headers instanceof Headers);
    const headers = lastFetchOptions.headers as Headers;
    assert.strictEqual(headers.get('X-XSRF-TOKEN'), 'mock-csrf-token-12345');
    assert.strictEqual(headers.get('Content-Type'), 'application/json');
  });

  it('does not inject X-XSRF-TOKEN header on safe GET requests', async () => {
    await apiClient.get('/api/auth/me');

    assert.ok(lastFetchOptions?.headers instanceof Headers);
    const headers = lastFetchOptions.headers as Headers;
    assert.strictEqual(headers.get('X-XSRF-TOKEN'), null);
  });

  it('injects Idempotency-Key header when idempotencyKey option is provided', async () => {
    await fetchClient('/api/migrations', {
      method: 'POST',
      body: JSON.stringify({ reviewSessionId: 'test-uuid' }),
      idempotencyKey: 'idemp-key-xyz-789',
    });

    assert.ok(lastFetchOptions?.headers instanceof Headers);
    const headers = lastFetchOptions.headers as Headers;
    assert.strictEqual(headers.get('Idempotency-Key'), 'idemp-key-xyz-789');
  });

  it('parses backend ApiError and throws strongly typed ApiException on non-2xx', async () => {
    globalThis.fetch = async () => {
      const errorPayload = {
        code: 'NOT_FOUND',
        message: 'Invalid email or password.',
        timestamp: '2026-11-17T10:00:00Z',
        path: '/api/auth/login',
      };
      return new Response(JSON.stringify(errorPayload), {
        status: 404,
        statusText: 'Not Found',
        headers: { 'Content-Type': 'application/json' },
      });
    };

    await assert.rejects(
      async () => {
        await apiClient.post('/api/auth/login', { email: 'bad@example.com', password: 'wrong' });
      },
      (err: unknown) => {
        assert.ok(err instanceof ApiException);
        assert.strictEqual(err.status, 404);
        assert.strictEqual(err.code, 'NOT_FOUND');
        assert.strictEqual(err.message, 'Invalid email or password.');
        assert.strictEqual(err.path, '/api/auth/login');
        return true;
      }
    );
  });

  it('handles 204 No Content without attempting JSON parsing', async () => {
    globalThis.fetch = async () => {
      return new Response(null, { status: 204, statusText: 'No Content' });
    };

    const result = await apiClient.delete<void>('/api/oauth/spotify');
    assert.strictEqual(result, undefined);
  });

  it('appends query parameters correctly to the URL', async () => {
    await apiClient.get('/api/migrations', {
      params: { page: 0, size: 20, status: 'COMPLETED' },
    });
    assert.ok(lastFetchUrl.includes('page=0'));
    assert.ok(lastFetchUrl.includes('size=20'));
    assert.ok(lastFetchUrl.includes('status=COMPLETED'));
  });
});
