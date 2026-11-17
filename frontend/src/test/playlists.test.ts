import { describe, it, beforeEach, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { playlistsApi } from '../features/playlists/api.ts';
import { formatDuration, sanitizeDescription } from '../features/playlists/utils.ts';
import { ApiException } from '../lib/api/types.ts';
import { TrackItem } from '../features/playlists/types.ts';

describe('Phase 15D — Spotify Playlist Discovery & Detail Tests', () => {
  const originalFetch = globalThis.fetch;

  beforeEach(() => {
    (globalThis as unknown as { document?: { cookie: string } }).document = {
      cookie: 'XSRF-TOKEN=test-csrf-token',
    };
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  describe('1. Playlist Discovery API (GET /api/spotify/playlists)', () => {
    it('fetches playlists using default limit and offset', async () => {
      let requestedUrl = '';
      const mockPageResponse = {
        items: [
          {
            id: 'spotify-pl-1',
            name: 'Road Trip Bangers',
            description: 'Hits for long drives',
            ownerDisplayName: 'Spotify User',
            imageUrl: 'https://images.spotify.com/pl1.jpg',
            totalTracks: 42,
            spotifyUrl: 'https://open.spotify.com/playlist/spotify-pl-1',
          },
        ],
        total: 1,
        limit: 20,
        offset: 0,
        hasNext: false,
      };

      globalThis.fetch = async (url) => {
        requestedUrl = String(url);
        return new Response(JSON.stringify(mockPageResponse), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      };

      const result = await playlistsApi.getPlaylists();

      assert.ok(requestedUrl.includes('/api/spotify/playlists'));
      assert.ok(requestedUrl.includes('limit=20'));
      assert.ok(requestedUrl.includes('offset=0'));
      assert.strictEqual(result.items.length, 1);
      assert.strictEqual(result.items[0].id, 'spotify-pl-1');
      assert.strictEqual(result.items[0].name, 'Road Trip Bangers');
      assert.strictEqual(result.items[0].totalTracks, 42);
      assert.strictEqual(result.hasNext, false);
    });

    it('transmits custom limit and offset parameters for pagination', async () => {
      let requestedUrl = '';
      const mockPageResponse = {
        items: [],
        total: 100,
        limit: 50,
        offset: 50,
        hasNext: false,
      };

      globalThis.fetch = async (url) => {
        requestedUrl = String(url);
        return new Response(JSON.stringify(mockPageResponse), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      };

      const result = await playlistsApi.getPlaylists({ limit: 50, offset: 50 });

      assert.ok(requestedUrl.includes('limit=50'));
      assert.ok(requestedUrl.includes('offset=50'));
      assert.strictEqual(result.total, 100);
      assert.strictEqual(result.offset, 50);
      assert.strictEqual(result.limit, 50);
    });

    it('handles empty playlist items response', async () => {
      globalThis.fetch = async () => {
        return new Response(
          JSON.stringify({
            items: [],
            total: 0,
            limit: 20,
            offset: 0,
            hasNext: false,
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      };

      const result = await playlistsApi.getPlaylists();
      assert.strictEqual(result.items.length, 0);
      assert.strictEqual(result.total, 0);
      assert.strictEqual(result.hasNext, false);
    });
  });

  describe('2. Playlist Detail API (GET /api/spotify/playlists/{id})', () => {
    it('retrieves single playlist metadata with url encoded id', async () => {
      let requestedUrl = '';
      const mockDetail = {
        id: '9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d',
        name: 'Chill Vibes 2026',
        description: 'Relaxing ambient & lo-fi beats',
        platform: 'SPOTIFY',
        externalId: 'spotify:playlist:abc 123',
        url: 'https://open.spotify.com/playlist/abc%20123',
        totalTracks: 25,
      };

      globalThis.fetch = async (url) => {
        requestedUrl = String(url);
        return new Response(JSON.stringify(mockDetail), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      };

      const result = await playlistsApi.getPlaylist('spotify:playlist:abc 123');

      assert.ok(requestedUrl.includes('/api/spotify/playlists/spotify%3Aplaylist%3Aabc%20123'));
      assert.strictEqual(result.name, 'Chill Vibes 2026');
      assert.strictEqual(result.platform, 'SPOTIFY');
      assert.strictEqual(result.totalTracks, 25);
    });

    it('throws ApiException with 404 when playlist does not exist', async () => {
      globalThis.fetch = async () => {
        return new Response(
          JSON.stringify({
            code: 'NOT_FOUND',
            message: 'Playlist not found on Spotify',
            timestamp: '2026-11-17T11:00:00Z',
            path: '/api/spotify/playlists/missing',
          }),
          { status: 404, headers: { 'Content-Type': 'application/json' } }
        );
      };

      await assert.rejects(
        async () => {
          await playlistsApi.getPlaylist('missing');
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

  describe('3. Playlist Tracks API (GET /api/spotify/playlists/{id}/tracks)', () => {
    it('retrieves playlist tracks preserving exact source ordering', async () => {
      let requestedUrl = '';
      const mockTracks = [
        {
          id: 'track-1',
          name: 'Track Alpha',
          artist: 'Artist A',
          album: 'Album One',
          durationMs: 210000,
          externalId: 'spotify:track:alpha',
        },
        {
          id: 'track-2',
          name: 'Track Beta',
          artist: 'Artist B & Artist C',
          album: 'Album Two',
          durationMs: 185000,
          externalId: 'spotify:track:beta',
        },
        {
          id: 'track-3',
          name: 'Track Gamma',
          artist: null,
          album: null,
          durationMs: 0,
          externalId: 'spotify:track:gamma',
        },
      ];

      globalThis.fetch = async (url) => {
        requestedUrl = String(url);
        return new Response(JSON.stringify(mockTracks), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      };

      const result = await playlistsApi.getPlaylistTracks('pl-test-123');

      assert.ok(requestedUrl.endsWith('/api/spotify/playlists/pl-test-123/tracks'));
      assert.strictEqual(result.length, 3);
      // Verify ordered index preservation
      assert.strictEqual(result[0].name, 'Track Alpha');
      assert.strictEqual(result[1].name, 'Track Beta');
      assert.strictEqual(result[2].name, 'Track Gamma');
      assert.strictEqual(result[2].artist, null);
      assert.strictEqual(result[2].album, null);
    });

    it('handles external provider errors (502 Bad Gateway) gracefully', async () => {
      globalThis.fetch = async () => {
        return new Response(
          JSON.stringify({
            code: 'EXTERNAL_PROVIDER_ERROR',
            message: 'Spotify API rate limit reached',
            timestamp: '2026-11-17T11:00:00Z',
            path: '/api/spotify/playlists/pl-test-123/tracks',
          }),
          { status: 502, headers: { 'Content-Type': 'application/json' } }
        );
      };

      await assert.rejects(
        async () => {
          await playlistsApi.getPlaylistTracks('pl-test-123');
        },
        (err: unknown) => {
          assert.ok(err instanceof ApiException);
          assert.strictEqual(err.status, 502);
          assert.strictEqual(err.code, 'EXTERNAL_PROVIDER_ERROR');
          return true;
        }
      );
    });
  });

  describe('4. Formatting & Sanitization Utilities', () => {
    describe('formatDuration', () => {
      it('formats standard milliseconds into mm:ss', () => {
        assert.strictEqual(formatDuration(214000), '3:34');
        assert.strictEqual(formatDuration(180000), '3:00');
        assert.strictEqual(formatDuration(65000), '1:05');
      });

      it('formats short tracks (< 1 minute) with zero minute padding', () => {
        assert.strictEqual(formatDuration(45000), '0:45');
        assert.strictEqual(formatDuration(9000), '0:09');
        assert.strictEqual(formatDuration(0), '0:00');
      });

      it('formats long tracks (> 1 hour) with hours', () => {
        assert.strictEqual(formatDuration(3661000), '1:01:01');
        assert.strictEqual(formatDuration(7200000), '2:00:00');
      });

      it('returns fallback string for invalid, negative, or missing values', () => {
        assert.strictEqual(formatDuration(null), '-:--');
        assert.strictEqual(formatDuration(undefined), '-:--');
        assert.strictEqual(formatDuration(NaN), '-:--');
        assert.strictEqual(formatDuration(-5000), '-:--');
      });
    });

    describe('sanitizeDescription', () => {
      it('strips html tags from description', () => {
        const input = 'Check out this playlist <a href="https://example.com">here</a>!';
        assert.strictEqual(sanitizeDescription(input), 'Check out this playlist here!');
      });

      it('decodes common html entities', () => {
        const input = 'Rock &amp; Roll &quot;Classics&#39; Edition&quot;';
        assert.strictEqual(sanitizeDescription(input), "Rock & Roll \"Classics' Edition\"");
      });

      it('handles null, undefined, or empty descriptions safely', () => {
        assert.strictEqual(sanitizeDescription(null), '');
        assert.strictEqual(sanitizeDescription(undefined), '');
        assert.strictEqual(sanitizeDescription(''), '');
      });
    });
  });

  describe('5. Track Filtering & Pagination Contract Logic', () => {
    const mockTracks: TrackItem[] = [
      {
        id: 't1',
        name: 'Bohemian Rhapsody',
        artist: 'Queen',
        album: 'A Night at the Opera',
        durationMs: 354000,
        externalId: 'ext-1',
      },
      {
        id: 't2',
        name: 'Hotel California',
        artist: 'Eagles',
        album: 'Hotel California',
        durationMs: 391000,
        externalId: 'ext-2',
      },
      {
        id: 't3',
        name: 'Radio Ga Ga',
        artist: 'Queen',
        album: 'The Works',
        durationMs: 344000,
        externalId: 'ext-3',
      },
      {
        id: 't4',
        name: 'Unknown Sound',
        artist: null,
        album: null,
        durationMs: null,
        externalId: null,
      },
    ];

    it('filters tracks by title case-insensitively', () => {
      const query = 'bohemian';
      const results = mockTracks.filter(
        (t) =>
          t.name.toLowerCase().includes(query) ||
          (t.artist && t.artist.toLowerCase().includes(query)) ||
          (t.album && t.album.toLowerCase().includes(query))
      );
      assert.strictEqual(results.length, 1);
      assert.strictEqual(results[0].name, 'Bohemian Rhapsody');
    });

    it('filters tracks by artist across multiple matches', () => {
      const query = 'queen';
      const results = mockTracks.filter(
        (t) =>
          t.name.toLowerCase().includes(query) ||
          (t.artist && t.artist.toLowerCase().includes(query)) ||
          (t.album && t.album.toLowerCase().includes(query))
      );
      assert.strictEqual(results.length, 2);
      assert.strictEqual(results[0].name, 'Bohemian Rhapsody');
      assert.strictEqual(results[1].name, 'Radio Ga Ga');
    });

    it('handles tracks with null artist or album during filtering without error', () => {
      const query = 'unknown';
      const results = mockTracks.filter(
        (t) =>
          t.name.toLowerCase().includes(query) ||
          (t.artist && t.artist.toLowerCase().includes(query)) ||
          (t.album && t.album.toLowerCase().includes(query))
      );
      assert.strictEqual(results.length, 1);
      assert.strictEqual(results[0].name, 'Unknown Sound');
    });

    it('calculates pagination boundary conditions correctly', () => {
      const pageSize = 20;

      // First page of 45 items
      const total1 = 45;
      const offset1 = 0;
      const hasNext1 = true;
      assert.strictEqual(offset1 === 0, true); // prev disabled
      assert.strictEqual(!hasNext1 && offset1 + pageSize >= total1, false); // next enabled
      assert.strictEqual(Math.ceil(total1 / pageSize), 3); // 3 pages

      // Last page of 45 items
      const offset3 = 40;
      const hasNext3 = false;
      assert.strictEqual(offset3 === 0, false); // prev enabled
      assert.strictEqual(!hasNext3 && offset3 + pageSize >= total1, true); // next disabled (40+20=60 >= 45)
    });
  });
});
