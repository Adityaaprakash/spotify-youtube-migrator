import { describe, it, beforeEach, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { oauthApi } from '../features/oauth/api.ts';
import { ApiException } from '../lib/api/types.ts';

describe('OAuth Provider Connection Tests', () => {
  const originalFetch = globalThis.fetch;

  beforeEach(() => {
    (globalThis as unknown as { document?: { cookie: string } }).document = {
      cookie: 'XSRF-TOKEN=oauth-csrf-token',
    };
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  describe('Connection Status Inspection (GET /api/oauth/{provider})', () => {
    it('returns connected status for Spotify with provider user id and expiry', async () => {
      const mockStatus = {
        provider: 'SPOTIFY',
        connected: true,
        providerUserId: 'spotify_user_99',
        expiresAt: '2026-11-17T12:00:00Z',
        status: 'CONNECTED',
      };

      let requestedUrl = '';
      globalThis.fetch = async (url) => {
        requestedUrl = String(url);
        return new Response(JSON.stringify(mockStatus), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      };

      const result = await oauthApi.getStatus('spotify');
      assert.ok(requestedUrl.endsWith('/api/oauth/spotify'));
      assert.strictEqual(result.connected, true);
      assert.strictEqual(result.provider, 'SPOTIFY');
      assert.strictEqual(result.providerUserId, 'spotify_user_99');
      assert.strictEqual(result.status, 'CONNECTED');
    });

    it('returns disconnected status for Google when not connected', async () => {
      const mockDisconnected = {
        provider: 'GOOGLE',
        connected: false,
        providerUserId: null,
        expiresAt: null,
        status: 'DISCONNECTED',
      };

      globalThis.fetch = async () => {
        return new Response(JSON.stringify(mockDisconnected), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      };

      const result = await oauthApi.getStatus('google');
      assert.strictEqual(result.connected, false);
      assert.strictEqual(result.status, 'DISCONNECTED');
      assert.strictEqual(result.providerUserId, null);
    });
  });

  describe('Authorization URL Retrieval (GET /api/oauth/{provider}/authorize)', () => {
    it('retrieves Spotify authorization URL for browser redirect', async () => {
      const authUrl = 'https://accounts.spotify.com/authorize?client_id=123&response_type=code';
      let requestedUrl = '';

      globalThis.fetch = async (url) => {
        requestedUrl = String(url);
        return new Response(JSON.stringify({ url: authUrl }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      };

      const result = await oauthApi.getAuthorizeUrl('spotify');
      assert.ok(requestedUrl.endsWith('/api/oauth/spotify/authorize'));
      assert.strictEqual(result.url, authUrl);
    });

    it('retrieves Google authorization URL for browser redirect', async () => {
      const authUrl = 'https://accounts.google.com/o/oauth2/v2/auth?client_id=456&response_type=code';
      let requestedUrl = '';

      globalThis.fetch = async (url) => {
        requestedUrl = String(url);
        return new Response(JSON.stringify({ url: authUrl }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      };

      const result = await oauthApi.getAuthorizeUrl('google');
      assert.ok(requestedUrl.endsWith('/api/oauth/google/authorize'));
      assert.strictEqual(result.url, authUrl);
    });
  });

  describe('Provider Disconnect (DELETE /api/oauth/{provider})', () => {
    it('calls DELETE on provider endpoint with CSRF protection and returns 204', async () => {
      let deleteMethod = '';
      let deleteUrl = '';
      let csrfHeader = '';

      globalThis.fetch = async (url, init) => {
        deleteMethod = init?.method || '';
        deleteUrl = String(url);
        const headers = init?.headers as Headers;
        csrfHeader = headers?.get('X-XSRF-TOKEN') || '';
        return new Response(null, { status: 204, statusText: 'No Content' });
      };

      await oauthApi.disconnect('spotify');
      assert.strictEqual(deleteMethod, 'DELETE');
      assert.ok(deleteUrl.endsWith('/api/oauth/spotify'));
      assert.strictEqual(csrfHeader, 'oauth-csrf-token');
    });

    it('handles provider disconnect failure gracefully', async () => {
      globalThis.fetch = async () => {
        return new Response(
          JSON.stringify({
            code: 'INTERNAL_SERVER_ERROR',
            message: 'Failed to revoke token on provider',
            timestamp: '2026-11-17T10:00:00Z',
            path: '/api/oauth/google',
          }),
          { status: 500, headers: { 'Content-Type': 'application/json' } }
        );
      };

      await assert.rejects(
        async () => {
          await oauthApi.disconnect('google');
        },
        (err: unknown) => {
          assert.ok(err instanceof ApiException);
          assert.strictEqual(err.status, 500);
          assert.strictEqual(err.message, 'Failed to revoke token on provider');
          return true;
        }
      );
    });
  });
});
