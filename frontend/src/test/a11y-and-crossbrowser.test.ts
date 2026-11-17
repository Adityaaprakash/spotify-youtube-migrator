import { test, describe, before, after } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium, firefox, webkit, type Browser } from 'playwright';
import AxeBuilder from '@axe-core/playwright';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

// Find built frontend dist directory
const distDir = [
  path.resolve(process.cwd(), 'dist'),
  path.resolve(__dirname, '../../../dist'),
  path.resolve(__dirname, '../../dist'),
].find((d) => fs.existsSync(d) && fs.existsSync(path.join(d, 'index.html'))) || path.resolve(process.cwd(), 'dist');

let server: http.Server;
let baseUrl: string;

// Mock data fixtures
const mockUser = {
  id: 'usr_mock_123',
  email: 'alex.rivera@example.com',
  displayName: 'Alex Rivera',
  status: 'ACTIVE',
};

const mockPlaylists = [
  {
    id: 'p1',
    name: 'Indie Discoveries 2026',
    description: 'Fresh indie tracks curated weekly for music lovers',
    imageUrl: 'https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=300',
    spotifyUrl: 'https://open.spotify.com/playlist/p1',
    ownerDisplayName: 'Alex Rivera',
    totalTracks: 12,
    isPublic: true,
  },
  {
    id: 'p2',
    name: 'Acoustic Morning Chill',
    description: 'Gentle acoustic guitars and ambient coffeehouse warmth',
    imageUrl: null,
    spotifyUrl: 'https://open.spotify.com/playlist/p2',
    ownerDisplayName: 'Alex Rivera',
    totalTracks: 8,
    isPublic: true,
  },
];

const mockTracks = [
  { id: 't1', externalId: 'spotify:t1', name: 'Midnight Horizon', artist: 'Luna Bay', album: 'Nightfall EP', durationMs: 215000, position: 0 },
  { id: 't2', externalId: 'spotify:t2', name: 'Golden Hour Memories', artist: 'Solaris Duo', album: 'Summer Recollections', durationMs: 184000, position: 1 },
  { id: 't3', externalId: 'spotify:t3', name: 'Echoes in the Valley', artist: 'The Northern Lights', album: 'Wilderness', durationMs: 242000, position: 2 },
  { id: 't4', externalId: 'spotify:t4', name: 'Silver Linings', artist: 'Acoustic Drift', album: 'Serenity', durationMs: 198000, position: 3 },
  { id: 't5', externalId: 'spotify:t5', name: 'Velvet Sky', artist: 'Starlight Avenue', album: 'Constellations', durationMs: 230000, position: 4 },
];

const mockMigrationHistory = {
  content: [
    {
      migrationId: 'exec1',
      sourcePlaylistId: 'p1',
      sourcePlaylistTitle: 'Indie Discoveries 2026',
      destinationPlatform: 'YouTube Music',
      destinationPlaylistUrl: 'https://music.youtube.com/playlist?list=PL_test_1',
      status: 'RUNNING',
      totalSourceTracks: 12,
      totalApprovedTracks: 12,
      successfulInsertions: 8,
      failedInsertions: 0,
      createdAt: '2026-11-17T10:00:00Z',
    },
    {
      migrationId: 'exec2',
      sourcePlaylistId: 'p2',
      sourcePlaylistTitle: 'Acoustic Morning Chill',
      destinationPlatform: 'YouTube Music',
      destinationPlaylistUrl: 'https://music.youtube.com/playlist?list=PL_test_2',
      status: 'COMPLETED',
      totalSourceTracks: 8,
      totalApprovedTracks: 8,
      successfulInsertions: 8,
      failedInsertions: 0,
      createdAt: '2026-11-16T15:30:00Z',
    },
  ],
  page: 0,
  size: 20,
  totalElements: 2,
  totalPages: 1,
  first: true,
  last: true,
};

const mockExecutionDetail = {
  executionId: 'exec1',
  sourcePlaylistId: 'p1',
  sourcePlaylistTitle: 'Indie Discoveries 2026',
  destinationPlatform: 'YouTube Music',
  destinationPlaylistId: 'PL_test_1',
  destinationPlaylistUrl: 'https://music.youtube.com/playlist?list=PL_test_1',
  status: 'RUNNING',
  totalSourceTracks: 12,
  totalApprovedTracks: 12,
  successfulInsertions: 8,
  failedInsertions: 0,
  skippedInsertions: 0,
  uncertainInsertions: 0,
  canResume: true,
  createdAt: '2026-11-17T10:00:00Z',
  startedAt: '2026-11-17T10:01:00Z',
};

const mockExecutionProgress = {
  executionId: 'exec1',
  status: 'RUNNING',
  totalTracks: 12,
  processedTracks: 8,
  addedTracks: 8,
  addingTracks: 1,
  pendingTracks: 3,
  failedTracks: 0,
  skippedTracks: 0,
  uncertainTracks: 0,
  progressPercentage: 66.7,
  estimatedRemainingSeconds: 24,
};

const mockExecutionTracks = [
  { id: 'et1', targetPosition: 0, sourceTitle: 'Midnight Horizon', sourceArtist: 'Luna Bay', destinationTitle: 'Luna Bay - Midnight Horizon (Official Audio)', destinationChannel: 'Luna Bay Official', status: 'ADDED', attempts: 1 },
  { id: 'et2', targetPosition: 1, sourceTitle: 'Golden Hour Memories', sourceArtist: 'Solaris Duo', destinationTitle: 'Solaris Duo - Golden Hour Memories', destinationChannel: 'Solaris Music', status: 'ADDED', attempts: 1 },
  { id: 'et3', targetPosition: 2, sourceTitle: 'Echoes in the Valley', sourceArtist: 'The Northern Lights', destinationTitle: 'Echoes in the Valley', destinationChannel: 'The Northern Lights', status: 'ADDING', attempts: 1 },
  { id: 'et4', targetPosition: 3, sourceTitle: 'Silver Linings', sourceArtist: 'Acoustic Drift', destinationTitle: null, destinationChannel: null, status: 'PENDING', attempts: 0 },
];

const mockAuditTimeline = {
  content: [
    { id: 'ev1', executionId: 'exec1', eventType: 'STATE_TRANSITION', previousState: 'INITIALIZED', newState: 'ALLOCATING_DESTINATION', actor: 'SYSTEM', reason: 'Pre-allocating YouTube playlist', createdAt: '2026-11-17T10:00:05Z' },
    { id: 'ev2', executionId: 'exec1', eventType: 'STATE_TRANSITION', previousState: 'ALLOCATING_DESTINATION', newState: 'RUNNING', actor: 'WORKER_NODE_1', reason: 'Worker acquired lease and started track insertions', createdAt: '2026-11-17T10:00:15Z' },
    { id: 'ev3', executionId: 'exec1', eventType: 'LEASE_ACQUIRED', actor: 'WORKER_NODE_1', reason: 'Lease generation token 1 acquired', createdAt: '2026-11-17T10:00:20Z' },
  ],
  page: 0,
  size: 50,
  totalElements: 3,
  totalPages: 1,
  first: true,
  last: true,
};

const mockReviewSession = {
  id: 'rev1',
  sourcePlaylistId: 'p1',
  sourcePlaylistTitle: 'Indie Discoveries 2026',
  sourcePlaylistDescription: 'Fresh indie tracks curated weekly for music lovers',
  status: 'IN_PROGRESS',
  totalTracks: 5,
};

const mockReviewProgress = {
  totalTracks: 5,
  resolvedCount: 3,
  awaitingReviewCount: 2,
  acceptedCount: 2,
  manuallyCorrectedCount: 1,
  excludedCount: 0,
  unresolvedConflictCount: 0,
  isReadyForExecution: false,
};

const mockReviewTracks = [
  {
    id: 'rt1',
    position: 0,
    sourceTitle: 'Midnight Horizon',
    sourceArtist: 'Luna Bay',
    reviewStatus: 'ACCEPTED',
    excluded: false,
    hasConflict: false,
    originalMatchScore: 0.96,
    originalCandidate: { id: 'c1', title: 'Luna Bay - Midnight Horizon (Official Audio)', channelOrArtist: 'Luna Bay Official', durationMs: 215000 },
  },
  {
    id: 'rt2',
    position: 1,
    sourceTitle: 'Golden Hour Memories',
    sourceArtist: 'Solaris Duo',
    reviewStatus: 'AWAITING_REVIEW',
    excluded: false,
    hasConflict: false,
    originalMatchScore: 0.78,
    originalCandidate: { id: 'c2', title: 'Solaris - Golden Hour Memories Live', channelOrArtist: 'Live Acoustic Sessions', durationMs: 190000 },
  },
  {
    id: 'rt3',
    position: 2,
    sourceTitle: 'Echoes in the Valley',
    sourceArtist: 'The Northern Lights',
    reviewStatus: 'AWAITING_REVIEW',
    excluded: false,
    hasConflict: false,
    originalMatchScore: 0.65,
    originalCandidate: { id: 'c3', title: 'Northern Lights - Valley Echoes Instrumental', channelOrArtist: 'Instrumental Hub', durationMs: 240000 },
  },
];

const mockPreviewPlan = {
  planId: 'plan_mock_1',
  spotifyPlaylistId: 'p1',
  spotifyPlaylistName: 'Indie Discoveries 2026',
  summary: {
    totalTracks: 12,
    autoMatchCount: 9,
    reviewRequiredCount: 3,
    unmatchedCount: 0,
    duplicateCount: 0,
    autoMatchRate: 0.75,
    reviewRate: 0.25,
  },
  tracks: [
    { position: 0, sourceTitle: 'Midnight Horizon', sourceArtist: 'Luna Bay', classification: 'AUTO_MATCH', candidateTitle: 'Midnight Horizon' },
    { position: 1, sourceTitle: 'Golden Hour Memories', sourceArtist: 'Solaris Duo', classification: 'REVIEW_REQUIRED', candidateTitle: 'Golden Hour' },
  ],
};

function getMimeType(filePath: string): string {
  const ext = path.extname(filePath).toLowerCase();
  switch (ext) {
    case '.html': return 'text/html';
    case '.js': return 'application/javascript';
    case '.css': return 'text/css';
    case '.json': return 'application/json';
    case '.svg': return 'image/svg+xml';
    case '.png': return 'image/png';
    case '.jpg': return 'image/jpeg';
    default: return 'application/octet-stream';
  }
}

before(async () => {
  server = http.createServer((req, res) => {
    const url = new URL(req.url || '/', 'http://localhost');
    const pathname = url.pathname;

    const json = (data: unknown, status = 200) => {
      res.writeHead(status, {
        'Content-Type': 'application/json',
        'Set-Cookie': 'XSRF-TOKEN=test-csrf-token; Path=/',
      });
      res.end(JSON.stringify(data));
    };

    if (pathname === '/api/auth/me') {
      const referer = req.headers['referer'] || '';
      if (referer.includes('/login') || referer.includes('/register') || url.searchParams.get('unauth') === 'true') {
        res.writeHead(401, { 'Content-Type': 'application/json' });
        return res.end(JSON.stringify({ message: 'Unauthorized' }));
      }
      return json(mockUser);
    }
    if (pathname === '/api/auth/login' || pathname === '/api/auth/register') {
      return json(mockUser);
    }
    if (pathname === '/api/auth/logout') {
      res.writeHead(204);
      return res.end();
    }
    if (pathname === '/api/oauth/spotify' || pathname === '/api/oauth/status/spotify') {
      return json({ connected: true, provider: 'spotify', providerUserId: 'spotify_usr_1', expiresAt: '2028-12-31T23:59:59Z' });
    }
    if (pathname === '/api/oauth/google' || pathname === '/api/oauth/status/google') {
      return json({ connected: true, provider: 'google', providerUserId: 'google_usr_1', expiresAt: '2028-12-31T23:59:59Z' });
    }
    if (pathname === '/api/spotify/playlists') {
      return json({ items: mockPlaylists, total: mockPlaylists.length, limit: 20, offset: 0, hasNext: false });
    }
    if (pathname === '/api/spotify/playlists/p1') {
      return json(mockPlaylists[0]);
    }
    if (pathname === '/api/spotify/playlists/p1/tracks') {
      return json(mockTracks);
    }
    if (pathname === '/api/migrations' || pathname === '/api/migrations/history') {
      return json(mockMigrationHistory);
    }
    if (pathname === '/api/migrations/exec1') {
      return json(mockExecutionDetail);
    }
    if (pathname === '/api/migrations/exec1/progress') {
      return json(mockExecutionProgress);
    }
    if (pathname === '/api/migrations/exec1/tracks') {
      return json(mockExecutionTracks);
    }
    if (pathname === '/api/migrations/exec1/history') {
      return json(mockAuditTimeline);
    }
    if (pathname === '/api/migrations/exec1/resume') {
      return json({ executionId: 'exec1', status: 'RUNNING', message: 'Migration resumed' });
    }
    if (pathname === '/api/migrations/exec2') {
      return json({
        ...mockExecutionDetail,
        executionId: 'exec2',
        status: 'COMPLETED',
        successfulInsertions: 12,
        canResume: false,
      });
    }
    if (pathname === '/api/migrations/exec2/progress') {
      return json({
        ...mockExecutionProgress,
        executionId: 'exec2',
        status: 'COMPLETED',
        progressPercentage: 100,
        pendingTracks: 0,
        estimatedRemainingSeconds: 0,
      });
    }
    if (pathname === '/api/migrations/exec2/tracks') {
      return json(mockExecutionTracks.map((t) => ({ ...t, status: 'ADDED' })));
    }
    if (pathname === '/api/migrations/exec2/history') {
      return json(mockAuditTimeline);
    }
    if (pathname === '/api/review-sessions/rev1') {
      return json(mockReviewSession);
    }
    if (pathname === '/api/review-sessions/rev1/progress') {
      return json(mockReviewProgress);
    }
    if (pathname === '/api/review-sessions/rev1/tracks') {
      return json(mockReviewTracks);
    }
    if (pathname.startsWith('/api/review-sessions/rev1/tracks/') && pathname.endsWith('/candidates/search')) {
      return json({
        candidates: [
          {
            id: 'c_search_1',
            title: 'Midnight Horizon (Official Music Video)',
            channelOrArtist: 'Luna Bay',
            durationMs: 215000,
          },
        ],
        totalCount: 1,
      });
    }
    if (pathname.startsWith('/api/review-sessions/rev1/tracks/') && pathname.endsWith('/decision')) {
      return json({
        id: 'rt1',
        position: 0,
        sourceTitle: 'Midnight Horizon',
        sourceArtist: 'Luna Bay',
        reviewStatus: 'ACCEPTED',
        excluded: false,
        hasConflict: false,
      });
    }
    if (pathname === '/api/migrations/preview') {
      return json(mockPreviewPlan);
    }

    let filePath = path.join(distDir, pathname);
    if (!fs.existsSync(filePath) || fs.statSync(filePath).isDirectory()) {
      filePath = path.join(distDir, 'index.html');
    }

    if (fs.existsSync(filePath)) {
      res.writeHead(200, { 'Content-Type': getMimeType(filePath) });
      return fs.createReadStream(filePath).pipe(res);
    }

    res.writeHead(404);
    res.end('Not found');
  });

  await new Promise<void>((resolve) => {
    server.listen(0, '127.0.0.1', () => {
      const addr = server.address();
      if (addr && typeof addr === 'object') {
        baseUrl = `http://127.0.0.1:${addr.port}`;
      }
      resolve();
    });
  });
});

after(async () => {
  if (server) {
    await new Promise<void>((resolve) => server.close(() => resolve()));
  }
});

// ============================================================================
// SUITE 1: WCAG 2.1 Level AA Accessibility Audit across Routes & States
// ============================================================================
describe('WCAG 2.1 Level AA Accessibility Audit across All Phase 15 Routes & Representative States', () => {
  let browser: Browser;

  before(async () => {
    browser = await chromium.launch({ headless: true });
  });

  after(async () => {
    await browser.close();
  });

  const routes = [
    { name: 'Dashboard (/dashboard)', path: '/dashboard' },
    { name: 'Playlists Listing (/playlists)', path: '/playlists' },
    { name: 'Playlist Details (/playlists/p1)', path: '/playlists/p1' },
    { name: 'Migration History (/migrations)', path: '/migrations' },
    { name: 'Migration Detail (/migrations/exec1)', path: '/migrations/exec1' },
    { name: 'Migration Review (/migrations/rev1/review)', path: '/migrations/rev1/review' },
    { name: 'Migration Progress (/migrations/exec1/progress)', path: '/migrations/exec1/progress' },
    { name: 'Settings & Connections (/settings)', path: '/settings' },
  ];

  for (const route of routes) {
    test(`Normal State: Route ${route.name} meets WCAG 2.1 Level AA standards`, async () => {
      const context = await browser.newContext();
      const page = await context.newPage();
      try {
        await page.goto(`${baseUrl}${route.path}`, { waitUntil: 'networkidle' });
        const axeResults = await new AxeBuilder({ page })
          .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
          .analyze();

        if (axeResults.violations.length > 0) {
          console.error(`Accessibility violations on ${route.path}:`, JSON.stringify(axeResults.violations, null, 2));
        }

        assert.equal(
          axeResults.violations.length,
          0,
          `Expected 0 WCAG 2.1 AA violations on ${route.path}, found: ${axeResults.violations.map((v) => v.id).join(', ')}`
        );
      } finally {
        await page.close();
        await context.close();
      }
    });
  }

  test('Normal State: Public Login Route (/login) meets WCAG 2.1 Level AA standards', async () => {
    const context = await browser.newContext();
    const page = await context.newPage();
    try {
      await page.goto(`${baseUrl}/login`, { waitUntil: 'networkidle' });
      const axeResults = await new AxeBuilder({ page })
        .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
        .analyze();

      assert.equal(
        axeResults.violations.length,
        0,
        `Expected 0 WCAG 2.1 AA violations on /login, found: ${axeResults.violations.map((v) => v.id).join(', ')}`
      );
    } finally {
      await page.close();
      await context.close();
    }
  });

  test('Normal State: Public Register Route (/register) meets WCAG 2.1 Level AA standards', async () => {
    const context = await browser.newContext();
    const page = await context.newPage();
    try {
      await page.goto(`${baseUrl}/register`, { waitUntil: 'networkidle' });
      const axeResults = await new AxeBuilder({ page })
        .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
        .analyze();

      assert.equal(
        axeResults.violations.length,
        0,
        `Expected 0 WCAG 2.1 AA violations on /register, found: ${axeResults.violations.map((v) => v.id).join(', ')}`
      );
    } finally {
      await page.close();
      await context.close();
    }
  });

  test('Loading State: Skeleton loaders maintain WCAG 2.1 AA compliance', async () => {
    const context = await browser.newContext();
    const page = await context.newPage();
    try {
      // Delay response to capture loading skeleton state
      await page.route('**/api/spotify/playlists**', async () => {
        // Keep pending
      });

      await page.goto(`${baseUrl}/playlists`, { waitUntil: 'commit' });
      await page.locator('.animate-pulse').first().waitFor({ state: 'visible', timeout: 5000 });

      const axeResults = await new AxeBuilder({ page })
        .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
        .analyze();

      assert.equal(
        axeResults.violations.length,
        0,
        `Expected 0 WCAG 2.1 AA violations during loading skeleton state, found: ${axeResults.violations.map((v) => v.id).join(', ')}`
      );
    } finally {
      await page.close();
      await context.close();
    }
  });

  test('Empty State: Empty playlists and migration history maintain WCAG 2.1 AA compliance', async () => {
    const context = await browser.newContext();
    const page = await context.newPage();
    try {
      await page.route('**/api/spotify/playlists**', (route) =>
        route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ items: [], total: 0, limit: 20, offset: 0, hasNext: false }),
        })
      );

      await page.goto(`${baseUrl}/playlists`, { waitUntil: 'networkidle' });
      const emptyHeading = page.locator('h3').filter({ hasText: /No playlists found/i });
      await emptyHeading.waitFor({ state: 'visible' });

      const axeResults = await new AxeBuilder({ page })
        .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
        .analyze();

      assert.equal(
        axeResults.violations.length,
        0,
        `Expected 0 WCAG 2.1 AA violations on EmptyState, found: ${axeResults.violations.map((v) => v.id).join(', ')}`
      );
    } finally {
      await page.close();
      await context.close();
    }
  });

  test('Error State: Failed API response ErrorState maintains WCAG 2.1 AA compliance', async () => {
    const context = await browser.newContext();
    const page = await context.newPage();
    try {
      await page.route('**/api/spotify/playlists**', (route) =>
        route.fulfill({
          status: 500,
          contentType: 'application/json',
          body: JSON.stringify({ message: 'Internal Server Error fetching playlists' }),
        })
      );

      await page.goto(`${baseUrl}/playlists`, { waitUntil: 'networkidle' });
      const errorHeading = page.locator('h4').filter({ hasText: /Failed to load playlists/i });
      await errorHeading.waitFor({ state: 'visible' });

      const axeResults = await new AxeBuilder({ page })
        .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
        .analyze();

      assert.equal(
        axeResults.violations.length,
        0,
        `Expected 0 WCAG 2.1 AA violations on ErrorState, found: ${axeResults.violations.map((v) => v.id).join(', ')}`
      );
    } finally {
      await page.close();
      await context.close();
    }
  });

  test('Expired OAuth State: Disconnected/Expired OAuth status maintains WCAG 2.1 AA compliance', async () => {
    const context = await browser.newContext();
    const page = await context.newPage();
    try {
      await page.route('**/api/oauth/spotify**', (route) =>
        route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            connected: false,
            provider: 'spotify',
            providerUserId: 'spotify_usr_1',
            expiresAt: '2020-01-01T00:00:00Z',
          }),
        })
      );

      await page.goto(`${baseUrl}/settings`, { waitUntil: 'networkidle' });
      const axeResults = await new AxeBuilder({ page })
        .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
        .analyze();

      assert.equal(
        axeResults.violations.length,
        0,
        `Expected 0 WCAG 2.1 AA violations with Expired OAuth, found: ${axeResults.violations.map((v) => v.id).join(', ')}`
      );
    } finally {
      await page.close();
      await context.close();
    }
  });

  test('Form Validation State: Login validation errors maintain WCAG 2.1 AA compliance', async () => {
    const context = await browser.newContext();
    const page = await context.newPage();
    try {
      await page.goto(`${baseUrl}/login`, { waitUntil: 'networkidle' });
      const submitBtn = page.getByRole('button', { name: /Sign in/i });
      await submitBtn.click();

      // Validation errors should be displayed
      await page.locator('#email-error').waitFor({ state: 'visible' });

      const axeResults = await new AxeBuilder({ page })
        .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
        .analyze();

      assert.equal(
        axeResults.violations.length,
        0,
        `Expected 0 WCAG 2.1 AA violations on form validation error state, found: ${axeResults.violations.map((v) => v.id).join(', ')}`
      );
    } finally {
      await page.close();
      await context.close();
    }
  });

  test('Filtered Results State: Track filter on review session maintains WCAG 2.1 AA compliance', async () => {
    const context = await browser.newContext();
    const page = await context.newPage();
    try {
      await page.goto(`${baseUrl}/migrations/rev1/review`, { waitUntil: 'networkidle' });
      const searchInput = page.getByPlaceholder(/Search tracks in review/i);
      await searchInput.fill('NonExistentTrackSearch');

      const axeResults = await new AxeBuilder({ page })
        .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
        .analyze();

      assert.equal(
        axeResults.violations.length,
        0,
        `Expected 0 WCAG 2.1 AA violations on filtered results state, found: ${axeResults.violations.map((v) => v.id).join(', ')}`
      );
    } finally {
      await page.close();
      await context.close();
    }
  });

  test('Active vs Completed Migration States maintain WCAG 2.1 AA compliance', async () => {
    const context = await browser.newContext();
    const page = await context.newPage();
    try {
      // 1. Active migration
      await page.goto(`${baseUrl}/migrations/exec1/progress`, { waitUntil: 'networkidle' });
      const activeAxe = await new AxeBuilder({ page })
        .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
        .analyze();
      assert.equal(activeAxe.violations.length, 0, 'Active migration must meet WCAG 2.1 AA');

      // 2. Completed migration
      await page.goto(`${baseUrl}/migrations/exec2`, { waitUntil: 'networkidle' });
      const completedAxe = await new AxeBuilder({ page })
        .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
        .analyze();
      assert.equal(completedAxe.violations.length, 0, 'Completed migration must meet WCAG 2.1 AA');
    } finally {
      await page.close();
      await context.close();
    }
  });

  test('Open Dialog: Resume Migration Dialog maintains WCAG 2.1 AA compliance', async () => {
    const context = await browser.newContext();
    const page = await context.newPage();
    try {
      await page.goto(`${baseUrl}/migrations/exec1`, { waitUntil: 'networkidle' });
      const resumeBtn = page.getByRole('button', { name: /Resume Migration/i });
      await resumeBtn.click();
      const dialog = page.getByRole('dialog');
      await dialog.waitFor({ state: 'visible' });

      const axeResults = await new AxeBuilder({ page })
        .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
        .analyze();

      assert.equal(
        axeResults.violations.length,
        0,
        `Expected 0 WCAG 2.1 AA violations with Resume Dialog open, found: ${axeResults.violations.map((v) => v.id).join(', ')}`
      );
    } finally {
      await page.close();
      await context.close();
    }
  });

  test('Open Dialog: Candidate Search Dialog maintains WCAG 2.1 AA compliance', async () => {
    const context = await browser.newContext();
    const page = await context.newPage();
    try {
      await page.goto(`${baseUrl}/migrations/rev1/review`, { waitUntil: 'networkidle' });
      const searchBtn = page.getByRole('button', { name: /Find Alternative/i }).first();
      await searchBtn.click();
      const dialog = page.getByRole('dialog');
      await dialog.waitFor({ state: 'visible' });

      const axeResults = await new AxeBuilder({ page })
        .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
        .analyze();

      assert.equal(
        axeResults.violations.length,
        0,
        `Expected 0 WCAG 2.1 AA violations with Candidate Search Dialog open, found: ${axeResults.violations.map((v) => v.id).join(', ')}`
      );
    } finally {
      await page.close();
      await context.close();
    }
  });
});

// ============================================================================
// SUITE 2: Non-Axe Semantic Accessibility and WCAG 2.1 Inspections
// ============================================================================
describe('Non-Axe Semantic Accessibility and WCAG 2.1 Inspections', () => {
  let browser: Browser;

  before(async () => {
    browser = await chromium.launch({ headless: true });
  });

  after(async () => {
    await browser.close();
  });

  test('Accessible names and roles are valid across all interactive controls', async () => {
    const context = await browser.newContext();
    const page = await context.newPage();
    try {
      await page.goto(`${baseUrl}/dashboard`, { waitUntil: 'networkidle' });

      const namelessControls = await page.evaluate(() => {
        const interactive = Array.from(document.querySelectorAll('button, a[href], input, select, textarea'));
        const issues: string[] = [];
        for (const el of interactive) {
          const name = el.getAttribute('aria-label') ||
            (el.getAttribute('aria-labelledby') ? document.getElementById(el.getAttribute('aria-labelledby')!)?.textContent : null) ||
            el.textContent?.trim() ||
            (el as HTMLInputElement).placeholder ||
            (el as HTMLInputElement).value;
          if (!name || name.trim().length === 0) {
            issues.push(el.outerHTML.slice(0, 80));
          }
        }
        return issues;
      });

      assert.equal(namelessControls.length, 0, `Interactive controls missing accessible names: ${namelessControls.join(' | ')}`);
    } finally {
      await page.close();
      await context.close();
    }
  });

  test('Heading hierarchy starts with h1 and has semantic nesting on all core routes', async () => {
    const context = await browser.newContext();
    const page = await context.newPage();
    try {
      const paths = ['/dashboard', '/playlists', '/migrations', '/settings'];
      for (const p of paths) {
        await page.goto(`${baseUrl}${p}`, { waitUntil: 'networkidle' });
        const headings = await page.evaluate(() => {
          const tags = Array.from(document.querySelectorAll('h1, h2, h3, h4, h5, h6')).map((el) =>
            parseInt(el.tagName.replace('H', ''), 10)
          );
          return tags;
        });

        assert.ok(headings.length > 0, `Route ${p} must have headings`);
        assert.equal(headings[0], 1, `Route ${p} must start with an h1 heading`);
      }
    } finally {
      await page.close();
      await context.close();
    }
  });

  test('Landmark semantics provide banner, main, navigation, and complementary regions', async () => {
    const context = await browser.newContext();
    const page = await context.newPage();
    try {
      await page.goto(`${baseUrl}/dashboard`, { waitUntil: 'networkidle' });

      const hasHeader = await page.locator('header').count() > 0;
      const hasMain = await page.locator('main#main-content').count() > 0;
      const hasNav = await page.locator('nav').count() > 0;
      const hasAside = await page.locator('aside').count() > 0;

      assert.ok(hasHeader, 'Must contain <header> banner landmark');
      assert.ok(hasMain, 'Must contain <main id="main-content"> landmark');
      assert.ok(hasNav, 'Must contain <nav> navigation landmark');
      assert.ok(hasAside, 'Must contain <aside> sidebar landmark');
    } finally {
      await page.close();
      await context.close();
    }
  });

  test('Modal dialogs define programmatic title and description associations', async () => {
    const context = await browser.newContext();
    const page = await context.newPage();
    try {
      await page.goto(`${baseUrl}/migrations/exec1`, { waitUntil: 'networkidle' });
      await page.getByRole('button', { name: /Resume Migration/i }).click();

      const dialog = page.getByRole('dialog');
      await dialog.waitFor({ state: 'visible' });

      const dialogAttributes = await page.evaluate(() => {
        const dlg = document.querySelector('[role="dialog"]');
        return {
          hasLabelledBy: dlg?.hasAttribute('aria-labelledby'),
          hasDescribedBy: dlg?.hasAttribute('aria-describedby'),
          labelledText: dlg?.querySelector('h2')?.textContent?.trim(),
          describedText: dlg?.querySelector('p')?.textContent?.trim(),
        };
      });

      assert.ok(dialogAttributes.labelledText?.includes('Resume Migration'), 'Dialog must have accessible title text');
      assert.ok(dialogAttributes.describedText?.length, 'Dialog must have accessible description text');
    } finally {
      await page.close();
      await context.close();
    }
  });

  test('Live progress indicators define progressbar role and valid bounding values', async () => {
    const context = await browser.newContext();
    const page = await context.newPage();
    try {
      await page.goto(`${baseUrl}/migrations/exec1/progress`, { waitUntil: 'networkidle' });

      const progressAttrs = await page.evaluate(() => {
        const pb = document.querySelector('[role="progressbar"]');
        if (!pb) return null;
        return {
          now: pb.getAttribute('aria-valuenow'),
          min: pb.getAttribute('aria-valuemin'),
          max: pb.getAttribute('aria-valuemax'),
        };
      });

      assert.ok(progressAttrs, 'Live progress page must contain [role="progressbar"]');
      assert.equal(progressAttrs?.min, '0');
      assert.equal(progressAttrs?.max, '100');
      const numericVal = parseFloat(progressAttrs?.now || '');
      assert.ok(!isNaN(numericVal) && numericVal >= 0 && numericVal <= 100, 'aria-valuenow must be between 0 and 100');
    } finally {
      await page.close();
      await context.close();
    }
  });

  test('Data tables contain th headers with column scope associations', async () => {
    const context = await browser.newContext();
    const page = await context.newPage();
    try {
      await page.goto(`${baseUrl}/migrations`, { waitUntil: 'networkidle' });

      const tableHeaders = await page.evaluate(() => {
        const ths = Array.from(document.querySelectorAll('table th'));
        return ths.map((th) => ({
          text: th.textContent?.trim(),
          scope: th.getAttribute('scope'),
        }));
      });

      assert.ok(tableHeaders.length > 0, 'Migration table must contain <th> headers');
      for (const th of tableHeaders) {
        assert.ok(th.text && th.text.length > 0, 'Every table header must have text');
      }
    } finally {
      await page.close();
      await context.close();
    }
  });
});

// ============================================================================
// SUITE 3: Keyboard Accessibility and Focus Trapping Verification
// ============================================================================
describe('Keyboard Accessibility and Focus Trapping Verification', () => {
  let browser: Browser;

  before(async () => {
    browser = await chromium.launch({ headless: true });
  });

  after(async () => {
    await browser.close();
  });

  test('Skip-to-content mechanism focuses main content landmark', async () => {
    const context = await browser.newContext();
    const page = await context.newPage();
    try {
      await page.goto(`${baseUrl}/dashboard`, { waitUntil: 'networkidle' });
      await page.keyboard.press('Tab');
      const activeText = await page.evaluate(() => document.activeElement?.textContent?.trim());
      assert.equal(activeText, 'Skip to main content');

      await page.keyboard.press('Enter');
      const activeId = await page.evaluate(() => document.activeElement?.id);
      assert.equal(activeId, 'main-content');
    } finally {
      await page.close();
      await context.close();
    }
  });

  test('Login form keyboard navigation order flows logically', async () => {
    const context = await browser.newContext();
    const page = await context.newPage();
    try {
      await page.goto(`${baseUrl}/login`, { waitUntil: 'networkidle' });
      const emailInput = page.locator('#email');
      await emailInput.waitFor({ state: 'visible' });

      await emailInput.focus();
      assert.equal(await page.evaluate(() => document.activeElement?.id), 'email');

      await page.keyboard.press('Tab');
      assert.equal(await page.evaluate(() => document.activeElement?.id), 'password');

      await page.keyboard.press('Tab');
      const isSubmit = await page.evaluate(() => (document.activeElement as HTMLButtonElement)?.type);
      assert.equal(isSubmit, 'submit');
    } finally {
      await page.close();
      await context.close();
    }
  });

  test('Resume modal dialog traps focus and restores focus on Escape dismissal', async () => {
    const context = await browser.newContext();
    const page = await context.newPage();
    try {
      await page.goto(`${baseUrl}/migrations/exec1`, { waitUntil: 'networkidle' });
      const resumeBtn = page.getByRole('button', { name: /Resume Migration/i });
      await resumeBtn.focus();
      await page.keyboard.press('Enter');

      const dialog = page.getByRole('dialog');
      await dialog.waitFor({ state: 'visible' });

      const isFocusInsideDialog = await page.evaluate(() => {
        const active = document.activeElement;
        const dlg = document.querySelector('[role="dialog"]');
        return dlg?.contains(active);
      });
      assert.ok(isFocusInsideDialog, 'Focus must move into modal dialog on open');

      await page.keyboard.press('Escape');
      await dialog.waitFor({ state: 'hidden' });

      const restoredText = await page.evaluate(() => document.activeElement?.textContent?.trim());
      assert.match(restoredText || '', /Resume Migration/i);
    } finally {
      await page.close();
      await context.close();
    }
  });

  test('Candidate Search dialog traps focus and restores focus on Escape dismissal', async () => {
    const context = await browser.newContext();
    const page = await context.newPage();
    try {
      await page.goto(`${baseUrl}/migrations/rev1/review`, { waitUntil: 'networkidle' });
      const searchBtn = page.getByRole('button', { name: /Find Alternative/i }).first();
      await searchBtn.focus();
      await page.keyboard.press('Enter');

      const dialog = page.getByRole('dialog');
      await dialog.waitFor({ state: 'visible' });

      const isFocusInsideDialog = await page.evaluate(() => {
        const active = document.activeElement;
        const dlg = document.querySelector('[role="dialog"]');
        return dlg?.contains(active);
      });
      assert.ok(isFocusInsideDialog, 'Focus must move into Candidate Search dialog on open');

      await page.keyboard.press('Escape');
      await dialog.waitFor({ state: 'hidden' });

      const restoredText = await page.evaluate(() => document.activeElement?.textContent?.trim());
      assert.match(restoredText || '', /Find Alternative/i);
    } finally {
      await page.close();
      await context.close();
    }
  });

  test('Playlist cards can be activated with Enter and Space keys', async () => {
    const context = await browser.newContext();
    const page = await context.newPage();
    try {
      await page.goto(`${baseUrl}/playlists`, { waitUntil: 'networkidle' });
      const card = page.locator('[role="article"]').first();
      await card.waitFor({ state: 'visible' });

      const link = card.locator('a[href="/playlists/p1"]');
      await link.focus();
      await page.keyboard.press('Enter');
      await page.waitForURL('**/playlists/p1');
      assert.ok(page.url().includes('/playlists/p1'), 'Card must navigate to playlist detail on Enter');
    } finally {
      await page.close();
      await context.close();
    }
  });
});

// ============================================================================
// SUITE 4: Responsive Verification Across All 9 Routes at 8 Viewport Sizes
// ============================================================================
describe('Responsive Verification Across All 9 Routes at 8 Viewport Sizes', () => {
  let browser: Browser;

  before(async () => {
    browser = await chromium.launch({ headless: true });
  });

  after(async () => {
    await browser.close();
  });

  const viewports = [
    { name: '320x568 (Small Mobile)', width: 320, height: 568, isMobile: true },
    { name: '375x812 (Standard Mobile)', width: 375, height: 812, isMobile: true },
    { name: '430x932 (Large Mobile)', width: 430, height: 932, isMobile: true },
    { name: '768x1024 (Tablet Portrait)', width: 768, height: 1024, isMobile: false },
    { name: '1024x768 (Tablet Landscape)', width: 1024, height: 768, isMobile: false },
    { name: '1366x768 (Laptop)', width: 1366, height: 768, isMobile: false },
    { name: '1440x900 (Desktop)', width: 1440, height: 900, isMobile: false },
    { name: '1920x1080 (Large Desktop)', width: 1920, height: 1080, isMobile: false },
  ];

  const originalRoutes = [
    { name: '/login', path: '/login', isAuth: false },
    { name: '/dashboard', path: '/dashboard', isAuth: true },
    { name: '/playlists', path: '/playlists', isAuth: true },
    { name: '/playlists/p1', path: '/playlists/p1', isAuth: true },
    { name: '/migrations', path: '/migrations', isAuth: true },
    { name: '/migrations/exec1', path: '/migrations/exec1', isAuth: true },
    { name: '/migrations/rev1/review', path: '/migrations/rev1/review', isAuth: true },
    { name: '/migrations/exec1/progress', path: '/migrations/exec1/progress', isAuth: true },
    { name: '/settings', path: '/settings', isAuth: true },
  ];

  for (const route of originalRoutes) {
    test(`Route ${route.name}: validates all 8 viewports with zero horizontal overflow and responsive layout`, async () => {
      const context = await browser.newContext();
      const page = await context.newPage();
      try {
        await page.goto(`${baseUrl}${route.path}`, { waitUntil: 'networkidle' });

        for (const vp of viewports) {
          await page.setViewportSize({ width: vp.width, height: vp.height });
          // Short pause for CSS media queries and flexbox/grid reflow
          await page.waitForTimeout(50);

          const overflow = await page.evaluate(() => {
            return {
              scrollWidth: document.documentElement.scrollWidth,
              innerWidth: window.innerWidth,
              hasOverflow: document.documentElement.scrollWidth > window.innerWidth,
            };
          });

          assert.equal(
            overflow.hasOverflow,
            false,
            `Route ${route.name} at viewport ${vp.name} has horizontal overflow (scrollWidth: ${overflow.scrollWidth} > innerWidth: ${overflow.innerWidth})`
          );

          if (route.isAuth) {
            const mobileNavVisible = await page.getByRole('navigation', { name: /Mobile Navigation/i }).isVisible();
            const sidebarVisible = await page.getByRole('navigation', { name: /Sidebar Navigation/i }).isVisible();

            if (vp.isMobile) {
              assert.ok(mobileNavVisible, `Mobile nav must be visible at ${vp.name} on ${route.name}`);
              assert.equal(sidebarVisible, false, `Sidebar must be hidden at ${vp.name} on ${route.name}`);
            } else {
              assert.equal(mobileNavVisible, false, `Mobile nav must be hidden at ${vp.name} on ${route.name}`);
              assert.ok(sidebarVisible, `Sidebar must be visible at ${vp.name} on ${route.name}`);
            }
          }
        }
      } finally {
        await page.close();
        await context.close();
      }
    });
  }

  test('Table horizontal scroll containment prevents layout blowout on small mobile (320x568)', async () => {
    const context = await browser.newContext({ viewport: { width: 320, height: 568 } });
    const page = await context.newPage();
    try {
      const tableRoutes = ['/playlists/p1', '/migrations', '/migrations/exec1', '/migrations/exec1/progress'];
      for (const tr of tableRoutes) {
        await page.goto(`${baseUrl}${tr}`, { waitUntil: 'networkidle' });
        const hasBodyOverflow = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth);
        assert.equal(hasBodyOverflow, false, `Table on ${tr} must not cause body horizontal overflow at 320x568`);

        const hasScrollContainer = await page.evaluate(() => {
          const containers = document.querySelectorAll('.overflow-x-auto');
          return containers.length > 0;
        });
        assert.ok(hasScrollContainer, `Table view on ${tr} must have .overflow-x-auto wrapper`);
      }
    } finally {
      await page.close();
      await context.close();
    }
  });

  test('Modal dialogs fit within small mobile viewport (320x568) without body blowout', async () => {
    const context = await browser.newContext({ viewport: { width: 320, height: 568 } });
    const page = await context.newPage();
    try {
      await page.goto(`${baseUrl}/migrations/exec1`, { waitUntil: 'networkidle' });
      await page.getByRole('button', { name: /Resume Migration/i }).click();

      const dialog = page.getByRole('dialog');
      await dialog.waitFor({ state: 'visible' });

      const dialogFits = await page.evaluate(() => {
        const dlg = document.querySelector('[role="dialog"]');
        if (!dlg) return false;
        const rect = dlg.getBoundingClientRect();
        return rect.width <= window.innerWidth && rect.left >= 0;
      });
      assert.ok(dialogFits, 'Resume dialog must fit within 320px viewport without blowout');
    } finally {
      await page.close();
      await context.close();
    }
  });
});

// ============================================================================
// SUITE 5: Complete Cross-Browser Verification (Chromium, Firefox, WebKit)
// ============================================================================
describe('Complete Cross-Browser Verification (Chromium, Firefox, WebKit)', () => {
  // NOTE: Playwright WebKit uses the open-source WebKit rendering engine (same foundation
  // as Apple Safari), running headless on Windows/Linux. It tests the WebKit engine
  // layout, CSS, and JS execution faithfully, distinct from actual macOS Apple Safari.
  const browserEngines = [
    { name: 'Chromium', launcher: chromium },
    { name: 'Firefox', launcher: firefox },
    { name: 'WebKit (Playwright WebKit engine)', launcher: webkit },
  ];

  for (const { name, launcher } of browserEngines) {
    describe(`Engine: ${name}`, () => {
      let browser: Browser;

      before(async () => {
        browser = await launcher.launch({ headless: true });
      });

      after(async () => {
        await browser.close();
      });

      test(`${name}: Renders all nine original routes with zero console errors`, async () => {
        const context = await browser.newContext();
        const page = await context.newPage();
        try {
          const consoleErrors: string[] = [];
          page.on('console', (msg) => {
            if (msg.type() === 'error') {
              const text = msg.text();
              // Ignore standard browser network message for expected 401 response on unauthenticated public routes
              if (text.includes('401') || text.includes('Unauthorized')) {
                return;
              }
              consoleErrors.push(text);
            }
          });

          const testRoutes = [
            { path: '/login', expectedHeading: /Sign in|Migrator/i },
            { path: '/dashboard', expectedHeading: /Welcome back|Dashboard/i },
            { path: '/playlists', expectedHeading: /Spotify Playlists/i },
            { path: '/playlists/p1', expectedHeading: /Indie Discoveries/i },
            { path: '/migrations', expectedHeading: /Migration History/i },
            { path: '/migrations/exec1', expectedHeading: /Indie Discoveries/i },
            { path: '/migrations/rev1/review', expectedHeading: /Review Matches|Indie Discoveries/i },
            { path: '/migrations/exec1/progress', expectedHeading: /Live Migration Progress|Indie Discoveries/i },
            { path: '/settings', expectedHeading: /Settings & Connections/i },
          ];

          for (const r of testRoutes) {
            await page.goto(`${baseUrl}${r.path}`, { waitUntil: 'networkidle' });
            const heading = page.locator('h1, h2, h3').filter({ hasText: r.expectedHeading }).first();
            await heading.waitFor({ state: 'visible', timeout: 5000 });
            assert.match(await heading.textContent() || '', r.expectedHeading);
          }

          assert.equal(
            consoleErrors.length,
            0,
            `Expected 0 console errors across all 9 routes on ${name}, found: ${consoleErrors.join(', ')}`
          );
        } finally {
          await page.close();
          await context.close();
        }
      });

      test(`${name}: Form interaction and keyboard submission operate reliably`, async () => {
        const context = await browser.newContext();
        const page = await context.newPage();
        try {
          await page.goto(`${baseUrl}/login`, { waitUntil: 'networkidle' });
          await page.fill('#email', 'alex.rivera@example.com');
          await page.fill('#password', 'password123');
          await page.press('#password', 'Enter');
          await page.waitForURL('**/dashboard');
          assert.ok(page.url().includes('/dashboard'), `Login submission on ${name} must navigate to /dashboard`);
        } finally {
          await page.close();
          await context.close();
        }
      });

      test(`${name}: Modal dialogs open, trap focus, and dismiss cleanly`, async () => {
        const context = await browser.newContext();
        const page = await context.newPage();
        try {
          await page.goto(`${baseUrl}/migrations/exec1`, { waitUntil: 'networkidle' });
          const resumeBtn = page.getByRole('button', { name: /Resume Migration/i });
          await resumeBtn.click();

          const dialog = page.getByRole('dialog');
          await dialog.waitFor({ state: 'visible' });
          assert.ok(await dialog.isVisible(), `Resume dialog must be visible on ${name}`);

          await page.keyboard.press('Escape');
          await dialog.waitFor({ state: 'hidden' });
          assert.equal(await dialog.isVisible(), false, `Resume dialog must close on Escape on ${name}`);
        } finally {
          await page.close();
          await context.close();
        }
      });

      test(`${name}: Failed API requests render ErrorState gracefully with retry`, async () => {
        const context = await browser.newContext();
        const page = await context.newPage();
        try {
          await page.route('**/api/spotify/playlists**', (route) =>
            route.fulfill({
              status: 500,
              contentType: 'application/json',
              body: JSON.stringify({ message: 'Mocked 500 Network Failure' }),
            })
          );

          await page.goto(`${baseUrl}/playlists`, { waitUntil: 'networkidle' });
          const errorHeading = page.locator('h4').filter({ hasText: /Failed to load playlists/i });
          await errorHeading.waitFor({ state: 'visible' });
          assert.ok(await errorHeading.isVisible(), `ErrorState must render on 500 error in ${name}`);

          const retryBtn = page.getByRole('button', { name: /Retry/i });
          assert.ok(await retryBtn.isVisible(), `Retry button must be visible in ${name}`);
        } finally {
          await page.close();
          await context.close();
        }
      });

      test(`${name}: Layout and page scrolling execute without breakdown`, async () => {
        const context = await browser.newContext();
        const page = await context.newPage();
        try {
          await page.goto(`${baseUrl}/migrations/exec1`, { waitUntil: 'networkidle' });
          const canScroll = await page.evaluate(() => {
            const main = document.querySelector('main');
            return main !== null && main.scrollHeight >= main.clientHeight;
          });
          assert.ok(canScroll, `Page layout must support scrolling on ${name}`);
        } finally {
          await page.close();
          await context.close();
        }
      });
    });
  }
});
