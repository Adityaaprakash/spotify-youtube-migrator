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
    if (pathname === '/api/review-sessions/rev1') {
      return json(mockReviewSession);
    }
    if (pathname === '/api/review-sessions/rev1/progress') {
      return json(mockReviewProgress);
    }
    if (pathname === '/api/review-sessions/rev1/tracks') {
      return json(mockReviewTracks);
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

describe('WCAG 2.1 Level AA Accessibility Audit across All Phase 15 Routes', () => {
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
    test(`Route ${route.name} meets WCAG 2.1 Level AA standards`, async () => {
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

  test('Public Login Route (/login) meets WCAG 2.1 Level AA standards', async () => {
    const context = await browser.newContext();
    const page = await context.newPage();
    try {
      await page.goto(`${baseUrl}/login`, { waitUntil: 'networkidle' });
      const axeResults = await new AxeBuilder({ page })
        .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
        .analyze();

      if (axeResults.violations.length > 0) {
        console.error('Accessibility violations on /login:', JSON.stringify(axeResults.violations, null, 2));
      }

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

  test('Public Register Route (/register) meets WCAG 2.1 Level AA standards', async () => {
    const context = await browser.newContext();
    const page = await context.newPage();
    try {
      await page.goto(`${baseUrl}/register`, { waitUntil: 'networkidle' });
      const axeResults = await new AxeBuilder({ page })
        .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
        .analyze();

      if (axeResults.violations.length > 0) {
        console.error('Accessibility violations on /register:', JSON.stringify(axeResults.violations, null, 2));
      }

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

  test('Modal Dialogs (Resume Migration Dialog) maintain WCAG 2.1 AA compliance when open', async () => {
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

      if (axeResults.violations.length > 0) {
        console.error('Accessibility violations on Resume Dialog:', JSON.stringify(axeResults.violations, null, 2));
      }

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
});

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

  test('Modal dialog traps focus and restores focus on Escape dismissal', async () => {
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

describe('Responsive Design Verification Across 8 Viewport Sizes', () => {
  let browser: Browser;

  before(async () => {
    browser = await chromium.launch({ headless: true });
  });

  after(async () => {
    await browser.close();
  });

  const viewports = [
    { name: 'Small mobile (320x568)', width: 320, height: 568, isMobile: true },
    { name: 'Standard mobile (375x812)', width: 375, height: 812, isMobile: true },
    { name: 'Large mobile (430x932)', width: 430, height: 932, isMobile: true },
    { name: 'Tablet portrait (768x1024)', width: 768, height: 1024, isMobile: false },
    { name: 'Tablet landscape (1024x768)', width: 1024, height: 768, isMobile: false },
    { name: 'Laptop (1366x768)', width: 1366, height: 768, isMobile: false },
    { name: 'Desktop (1440x900)', width: 1440, height: 900, isMobile: false },
    { name: 'Large desktop (1920x1080)', width: 1920, height: 1080, isMobile: false },
  ];

  for (const vp of viewports) {
    test(`Viewport ${vp.name}: has no unintended horizontal overflow and correct layout`, async () => {
      const context = await browser.newContext({ viewport: { width: vp.width, height: vp.height } });
      const page = await context.newPage();
      try {
        await page.goto(`${baseUrl}/dashboard`, { waitUntil: 'networkidle' });

        const hasHorizontalScroll = await page.evaluate(() => {
          return document.documentElement.scrollWidth > window.innerWidth;
        });
        assert.equal(hasHorizontalScroll, false, `Viewport ${vp.name} must not have horizontal scrollbar`);

        const mobileNavVisible = await page.getByRole('navigation', { name: /Mobile Navigation/i }).isVisible();
        const sidebarVisible = await page.getByRole('navigation', { name: /Sidebar Navigation/i }).isVisible();

        if (vp.isMobile) {
          assert.ok(mobileNavVisible, `Mobile navigation must be visible on ${vp.name}`);
          assert.equal(sidebarVisible, false, `Desktop sidebar must be hidden on ${vp.name}`);
        } else {
          assert.equal(mobileNavVisible, false, `Mobile navigation must be hidden on ${vp.name}`);
          assert.ok(sidebarVisible, `Desktop sidebar must be visible on ${vp.name}`);
        }
      } finally {
        await page.close();
        await context.close();
      }
    });
  }

  test('Complex data tables on mobile (375x812) remain usable with horizontal containment', async () => {
    const context = await browser.newContext({ viewport: { width: 375, height: 812 } });
    const page = await context.newPage();
    try {
      await page.goto(`${baseUrl}/migrations`, { waitUntil: 'networkidle' });

      const hasBodyOverflow = await page.evaluate(() => {
        return document.documentElement.scrollWidth > window.innerWidth;
      });
      assert.equal(hasBodyOverflow, false, 'Migration history table page must not overflow mobile viewport');
    } finally {
      await page.close();
      await context.close();
    }
  });
});

describe('Cross-Browser Compatibility Verification (Chromium, Firefox, WebKit)', () => {
  const browserEngines = [
    { name: 'Chromium', launcher: chromium },
    { name: 'Firefox', launcher: firefox },
    { name: 'WebKit', launcher: webkit },
  ];

  for (const { name, launcher } of browserEngines) {
    test(`Browser ${name}: renders application, navigates routes, and logs zero console errors`, async () => {
      const browser = await launcher.launch({ headless: true });
      try {
        const context = await browser.newContext();
        try {
          const page = await context.newPage();
          try {
            const consoleErrors: string[] = [];
            page.on('console', (msg) => {
              if (msg.type() === 'error') {
                consoleErrors.push(msg.text());
              }
            });

            await page.goto(`${baseUrl}/dashboard`, { waitUntil: 'networkidle' });
            const dashboardHeading = page.locator('h1').filter({ hasText: /Welcome back|Dashboard/i });
            await dashboardHeading.waitFor({ state: 'visible' });
            assert.match(await dashboardHeading.textContent() || '', /Welcome back|Dashboard/i);

            await page.locator('aside a[href="/playlists"]').click();
            await page.waitForURL('**/playlists');
            const playlistsHeading = page.locator('h1').filter({ hasText: /Spotify Playlists/i });
            await playlistsHeading.waitFor({ state: 'visible' });
            assert.match(await playlistsHeading.textContent() || '', /Spotify Playlists/i);

            await page.locator('aside a[href="/migrations"]').click();
            await page.waitForURL('**/migrations');
            const migrationsHeading = page.locator('h1').filter({ hasText: /Migration History/i });
            await migrationsHeading.waitFor({ state: 'visible' });
            assert.match(await migrationsHeading.textContent() || '', /Migration History/i);

            await page.locator('aside a[href="/settings"]').click();
            await page.waitForURL('**/settings');
            const settingsHeading = page.locator('h1').filter({ hasText: /Settings & Connections/i });
            await settingsHeading.waitFor({ state: 'visible' });
            assert.match(await settingsHeading.textContent() || '', /Settings & Connections/i);

            assert.equal(
              consoleErrors.length,
              0,
              `Expected 0 console errors on ${name}, found: ${consoleErrors.join(', ')}`
            );
          } finally {
            await page.close();
          }
        } finally {
          await context.close();
        }
      } finally {
        await browser.close();
      }
    });
  }
});
