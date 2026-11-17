# Phase 15A: Frontend Audit & Integration Contract Report
## Spotify-to-YouTube Music Playlist Migrator

**Date**: 2026-11-17
**Status**: COMPLETE
**Repository**: `C:\Projects\spotify-youtube-migrator`
**Branch**: `main`
**Latest Backend Commit**: `1bb3ecf1d650521f5644999caee2bb9762a166d8` (`feat: complete phase 14 migration history and recovery`)

---

## 1. Current Repository State

- **Branch**: `main` (clean working tree, tracking `origin/main`).
- **Backend**: Spring Boot 3.3.4, Java 22, Maven, PostgreSQL 16, Flyway migrations V1–V16, fully tested and frozen through Phase 14 (2,139 tests passed, 0 failures, 0 errors, 0 skipped, 0 Checkstyle violations).
- **Frontend Directory**: `C:\Projects\spotify-youtube-migrator\frontend`.
- **Frontend Status**: Initial Phase 0 boilerplate exists with Vite, React 18, TypeScript, Tailwind CSS, TanStack Query, React Hook Form, and Zod installed.

---

## 2. Current Frontend Architecture

### 2.1 File & Directory Inventory
```text
frontend/
├── dist/                     # Production build artifacts
├── node_modules/             # Installed dependencies
├── public/                   # Static public assets
├── src/
│   ├── app/
│   │   ├── App.tsx           # Initial placeholder rendering "Phase 0 Architecture initialized."
│   │   └── providers.tsx     # TanStack Query QueryClientProvider
│   ├── lib/
│   │   └── api/
│   │       └── client.ts     # Initial fetchClient with ApiError / ApiException
│   ├── index.css             # Tailwind base, components, utilities directives
│   ├── main.tsx              # React createRoot entry point
│   └── vite-env.d.ts         # Vite client type definitions
├── .gitignore
├── eslint.config.js          # ESLint 9 flat config
├── index.html                # HTML entry point (title: "frontend")
├── package-lock.json
├── package.json              # Project scripts and dependencies
├── postcss.config.js         # PostCSS config (Tailwind, Autoprefixer)
├── tailwind.config.js        # Tailwind 3 configuration
├── tsconfig.app.json         # TypeScript config for client application
├── tsconfig.json             # Root TypeScript project references
├── tsconfig.node.json        # TypeScript config for Vite configuration
└── vite.config.ts            # Vite 5 configuration with @vitejs/plugin-react
```

### 2.2 Frontend Dependency Inventory

| Package | Version | Type | Purpose |
| :--- | :--- | :--- | :--- |
| `react` | `^18.3.1` | dependency | Core UI library |
| `react-dom` | `^18.3.1` | dependency | React DOM rendering |
| `react-router-dom` | `^7.18.4` | dependency | Client-side routing |
| `@tanstack/react-query` | `^5.104.1` | dependency | Server state management, caching & polling |
| `react-hook-form` | `^7.89.0` | dependency | Performant form state & validation |
| `@hookform/resolvers` | `^5.9.1` | dependency | Schema validation resolver for React Hook Form |
| `zod` | `^3.25.76` | dependency | TypeScript-first schema declaration & validation |
| `clsx` | `^2.1.1` | dependency | Utility for conditional CSS class names |
| `tailwind-merge` | `^3.7.0` | dependency | Utility to safely merge Tailwind CSS classes |
| `lucide-react` | `^1.52.0` | dependency | Minimal modern icon set |
| `vite` | `^5.4.8` | devDependency | Build tool and local development server |
| `@vitejs/plugin-react` | `^4.3.2` | devDependency | React Fast Refresh & JSX transformation |
| `typescript` | `^5.5.3` | devDependency | Static type checking |
| `tailwindcss` | `^3.4.19` | devDependency | Utility-first CSS framework |
| `postcss` | `^8.5.29` | devDependency | CSS post-processing |
| `autoprefixer` | `^10.6.1` | devDependency | CSS vendor prefixing |
| `eslint` | `^9.11.1` | devDependency | Code quality and linting |
| `typescript-eslint` | `^8.7.0` | devDependency | TypeScript ESLint rules |

All required foundational packages are already present in `package.json` and lockfile; no major package replacements or breaking upgrades are needed.

---

## 3. Existing Frontend Features & Status

- **Build & Compilation**: Verified (`typecheck`, `lint`, and `build` succeed out-of-the-box).
- **Routing**: `react-router-dom` is installed, but no router or route tree is implemented yet.
- **Components**: No UI component library, design system tokens, or shared UX primitives exist yet.
- **Layouts**: No shared layout, header, navigation sidebar, or page container exists.
- **API Client**: Basic `fetchClient` exists in `src/lib/api/client.ts`, but lacks credentials support (`credentials: 'include'`), CSRF header injection (`X-XSRF-TOKEN`), normalized error handling, and environment-based proxy integration.
- **TanStack Query**: Basic `QueryClientProvider` instantiated with default settings; no cache policies, retry limits, or query key conventions defined.

---

## 4. Authentication Architecture Audit

1. **Authentication Paradigm**:
   - The application uses **Spring Security session-based authentication**.
   - Session identifier is transmitted via the standard `JSESSIONID` cookie (`HttpOnly`, `SameSite=Lax`).
   - Authentication is established via `POST /api/auth/login` or `POST /api/auth/register`, which populates `SecurityContextHolder` and saves the context into `HttpSession` (`SPRING_SECURITY_CONTEXT`).
   - Session termination is executed via `POST /api/auth/logout`, which invalidates the `HttpSession` and clears security context.
   - Authentication check endpoint: `GET /api/auth/me` returns `UserResponse` (`id`, `email`, `displayName`, `createdAt`) when authenticated; returns `HTTP 401 Unauthorized` when unauthenticated.

2. **CSRF Protection**:
   - `SecurityConfig.java` enforces `CookieCsrfTokenRepository.withHttpOnlyFalse()` with `CsrfTokenRequestAttributeHandler()`.
   - On response, Spring Security issues an `XSRF-TOKEN` cookie (readable by JavaScript).
   - For all mutating HTTP requests (`POST`, `PUT`, `DELETE`, `PATCH`), the frontend must read `XSRF-TOKEN` from `document.cookie` and attach it as the `X-XSRF-TOKEN` HTTP header.
   - Idempotent / safe requests (`GET`, `HEAD`, `OPTIONS`) do not require the CSRF token header.

3. **OAuth Integration Architecture**:
   - Provider OAuth (`spotify`, `google`) is **strictly separated** from application authentication.
   - OAuth flows connect a user's Spotify and Google accounts to their authenticated application user account.
   - The user must already be authenticated in the application before initiating an OAuth connection.
   - `GET /api/oauth/{provider}/authorize` returns a JSON object `{"url": "<authorization_url>"}`. The frontend redirects or opens this URL for OAuth consent.
   - Provider redirects back to `GET /api/oauth/{provider}/callback`, where the backend exchanges the authorization code for tokens, encrypts tokens at rest, and redirects to `/`.
   - Provider connection status is verified via `GET /api/oauth/{provider}`, which returns `OAuthConnectionStatusDto` (`provider`, `connected`, `providerUserId`, `expiresAt`, `status`).
   - Disconnection is triggered via `DELETE /api/oauth/{provider}`.
   - Crucial Security Rule: Access tokens and refresh tokens are **never** exposed to frontend JavaScript.

4. **CORS & Proxying in Local Development**:
   - In local development, the frontend runs on Vite at `http://localhost:5173`, and the backend runs at `http://localhost:8080`.
   - To guarantee seamless cookie handling (`JSESSIONID`, `XSRF-TOKEN`) without complex cross-origin credential issues, Vite must configure a reverse proxy forwarding `/api` to `http://localhost:8080`.

---

## 5. Backend API Integration Matrix

| Subsystem | HTTP Method | Path | Request Body | Response DTO | Auth Required | Known Error Statuses | Frontend Roadmap Usage |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Auth** | `POST` | `/api/auth/register` | `RegisterRequest(email, password, displayName)` | `UserResponse` | Public | 400 (Validation), 409 (Email Conflict) | `/login` (Register tab) |
| **Auth** | `POST` | `/api/auth/login` | `LoginRequest(email, password)` | `UserResponse` | Public | 400, 401 (Bad Credentials) | `/login` |
| **Auth** | `POST` | `/api/auth/logout` | None | None (`204 No Content`) | Required | 401, 403 | Global Header / User Menu |
| **Auth** | `GET` | `/api/auth/me` | None | `UserResponse` | Required | 401 (Unauthenticated) | Global Auth Context & Route Guards |
| **OAuth** | `GET` | `/api/oauth/{provider}` | None | `OAuthConnectionStatusDto` | Required | 401, 404 | `/dashboard`, `/settings` |
| **OAuth** | `GET` | `/api/oauth/{provider}/authorize` | None | `Map<String, String>` (`url`) | Required | 401, 400 | Connect Spotify/Google buttons |
| **OAuth** | `DELETE` | `/api/oauth/{provider}` | None | None (`204 No Content`) | Required | 401, 404 | `/settings` Disconnect button |
| **Playlists** | `GET` | `/api/spotify/playlists` | Query: `limit`, `offset` | `SpotifyPageResponse<SpotifyPlaylistSummaryResponse>` | Required | 401, 502 (Spotify Error) | `/playlists`, `/dashboard` |
| **Playlists** | `GET` | `/api/spotify/playlists/{id}` | None | `PlaylistResponse` | Required | 401, 404 | `/playlists/:id` |
| **Playlists** | `GET` | `/api/spotify/playlists/{id}/tracks` | None | `List<TrackResponse>` | Required | 401, 404 | `/playlists/:id` |
| **Planning** | `POST` | `/api/migrations/preview` | `MigrationPlanPreviewRequest` | `MigrationPlanResponse` | Required | 400, 401, 404 | `/playlists/:id` (Preview modal) |
| **Review** | `POST` | `/api/review-sessions` | `CreateReviewSessionRequest` | `ReviewSessionResponse` | Required | 400, 401, 404 | Start Review action |
| **Review** | `GET` | `/api/review-sessions` | None | `List<ReviewSessionResponse>` | Required | 401 | `/dashboard` (Active reviews) |
| **Review** | `GET` | `/api/review-sessions/{id}` | None | `ReviewSessionResponse` | Required | 401, 404 (Anti-IDOR) | `/migrations/:id/review` |
| **Review** | `GET` | `/api/review-sessions/{id}/progress` | None | `ReviewProgressSummaryResponse` | Required | 401, 404 | `/migrations/:id/review` summary |
| **Review** | `GET` | `/api/review-sessions/{id}/tracks` | None | `List<ReviewTrackResponse>` | Required | 401, 404 | `/migrations/:id/review` track list |
| **Review** | `POST` | `/api/review-sessions/{id}/tracks/{pos}/decision` | `SubmitReviewDecisionRequest` | `ReviewTrackResponse` | Required | 400, 401, 404, 409 | Accept / Exclude / Match action |
| **Review** | `POST` | `/api/review-sessions/{id}/tracks/{pos}/candidates/search` | `ReviewCandidateSearchApiRequest` | `ReviewCandidateSearchResultResponse` | Required | 400, 401, 404 | Candidate Search modal |
| **Review** | `POST` | `/api/review-sessions/{id}/complete` | None | `ReviewSessionResponse` | Required | 401, 404, 409 (Unresolved) | Review completion trigger |
| **Execution**| `POST` | `/api/migrations` | `CreateMigrationRequest` | `MigrationSubmissionResponse` (`202 Accepted`) | Required | 400, 401, 404, 409, 429 | Run Migration button |
| **Progress** | `GET` | `/api/migrations/{id}/progress` | None | `MigrationProgressResponse` | Required | 401, 404, 429 | `/migrations/:id/progress` polling |
| **History** | `GET` | `/api/migrations` | Query: `page`, `size`, `status` | `PagedResponse<MigrationHistoryItemResponse>` | Required | 401 | `/migrations`, `/dashboard` |
| **History** | `GET` | `/api/migrations/{id}` | None | `MigrationExecutionResponse` | Required | 401, 404 (Anti-IDOR) | `/migrations/:id` |
| **History** | `GET` | `/api/migrations/{id}/tracks` | None | `List<ExecutionTrackRecordResponse>` | Required | 401, 404 (Anti-IDOR) | `/migrations/:id` track details |
| **History** | `GET` | `/api/migrations/{id}/history` | Query: `page`, `size` | `PagedResponse<MigrationAuditEventResponse>` | Required | 401, 404 (Anti-IDOR) | `/migrations/:id` audit timeline |
| **Resume** | `POST` | `/api/migrations/{id}/resume` | None | `MigrationExecutionResponse` | Required | 401, 404, 409 (Conflict) | Resume/Retry migration button |

### Backend Error Response Contract
All error responses from the backend adhere to the `ApiError` record:
```json
{
  "code": "INVALID_STATE",
  "message": "Migration execution is already in progress",
  "timestamp": "2026-11-17T10:13:20Z",
  "path": "/api/migrations/667e7823-a21c-485f-afa3-9f40bec32995/resume"
}
```
Standard `ErrorCode` enum values:
- `INVALID_REQUEST` (400)
- `UNAUTHORIZED` (401)
- `FORBIDDEN` (403)
- `NOT_FOUND` (404)
- `CONFLICT` (409)
- `INVALID_STATE` (409)
- `EXTERNAL_PROVIDER_ERROR` (502 / provider status)
- `TOO_MANY_REQUESTS` (429 - includes `Retry-After` header)
- `INTERNAL_SERVER_ERROR` (500)
- `IDEMPOTENCY_FINGERPRINT_MISMATCH` (409)

---

## 6. Recommended Frontend Architecture (Phase 15B)

### 6.1 Target Directory Layout
```text
frontend/src/
├── app/
│   ├── App.tsx               # Main application component with router and global providers
│   ├── providers.tsx         # Centralized QueryClientProvider, AuthProvider, RouterProvider
│   └── routes.tsx            # Centralized route definitions for all roadmap paths
├── components/
│   ├── layout/
│   │   ├── AppHeader.tsx     # Top navigation bar with branding, connection status, user menu
│   │   ├── AppLayout.tsx     # Application shell (header + main container + responsive padding)
│   │   └── AppSidebar.tsx    # Navigation links (Dashboard, Playlists, Migrations, Settings)
│   ├── shared/
│   │   ├── ConfirmationDialog.tsx # Reusable modal dialog
│   │   ├── EmptyState.tsx    # Clean state when lists are empty
│   │   ├── ErrorState.tsx    # Reusable error presentation with retry action
│   │   ├── LoadingSkeleton.tsx # Reusable skeleton placeholders
│   │   └── PageHeader.tsx    # Standardized page title, description, and action button container
│   └── ui/                   # Reusable minimal design system primitives (shadcn-compatible)
│       ├── alert.tsx
│       ├── badge.tsx
│       ├── button.tsx
│       ├── card.tsx
│       ├── dialog.tsx
│       ├── input.tsx
│       ├── label.tsx
│       ├── separator.tsx
│       ├── skeleton.tsx
│       └── tooltip.tsx
├── features/                 # Lightweight route shells/placeholders (no business logic in 15B)
│   ├── auth/                 # LoginPage shell
│   ├── dashboard/            # DashboardPage shell
│   ├── migrations/           # MigrationsPage, MigrationDetailPage, MigrationProgressPage, ReviewPage shells
│   ├── playlists/            # PlaylistsPage, PlaylistDetailPage shells
│   └── settings/             # SettingsPage shell
├── lib/
│   ├── api/
│   │   ├── client.ts         # Robust fetchClient with CSRF (X-XSRF-TOKEN), credentials, and ApiError normalization
│   │   └── types.ts          # Strongly-typed API error contracts
│   ├── query/
│   │   ├── queryClient.ts    # Configured QueryClient with retry, cache, and error boundaries
│   │   └── queryKeys.ts      # Hierarchical query key factory
│   └── utils/
│       └── cn.ts             # clsx + tailwind-merge helper
├── styles/
│   └── index.css             # Tailwind layers + semantic design tokens (CSS variables)
└── types/
    └── index.ts              # Core shared frontend domain and navigation types
```

### 6.2 Design System Token Conventions
- **Theme**: Clean, focused, dark-mode-first aesthetic inspired by modern audio/music software (Spotify / YouTube Music / Linear). Dark slate surface (`#0f172a`, `#1e293b`), crisp typography, emerald/green accent for Spotify alignment and primary actions, red accent for YouTube alignment.
- **Spacing**: Strict 4px grid (rem units: 0.5, 0.75, 1, 1.5, 2, etc.).
- **Typography**: Inter / system-ui sans-serif with clear optical hierarchies (text-xs through text-3xl).
- **Semantics**:
  - `status-success`: Emerald (#10b981)
  - `status-warning`: Amber (#f59e0b)
  - `status-error`: Rose (#f43f5e)
  - `status-info`: Sky (#0ea5e9)
  - `status-neutral`: Slate (#64748b)

---

## 7. Strict Phase Boundaries

### What Phase 15B Owns:
- Complete directory reorganization into modular structure.
- Configuration of path aliases (`@/*`) in Vite and TypeScript.
- Vite proxy setup for seamless `/api` requests to `localhost:8080`.
- Centralized `fetchClient` with automatic CSRF token parsing, `credentials: 'include'`, and `ApiError` normalization.
- Reusable `QueryClient` setup with structured `queryKeys` factory.
- Minimal, accessible UI primitives (`button`, `card`, `badge`, `input`, `label`, `alert`, `skeleton`, `dialog`, `separator`).
- Reusable UX states (`EmptyState`, `ErrorState`, `LoadingSkeleton`, `PageHeader`, `NotFoundPage`).
- Application layout shell (`AppLayout`, `AppHeader`, `AppSidebar`).
- Route definitions and placeholder shells for all 9 roadmap paths.
- Design tokens and theme styling in `index.css` and `tailwind.config.js`.

### What Phase 15B Strictly Prohibits (Deferred to 15C–15H):
- No login/registration form submission or token management.
- No OAuth popup or redirect triggers.
- No live playlist fetching or track rendering.
- No migration preview generation or track matching UI.
- No review decision interactions (accept/select/exclude).
- No migration execution or progress polling.
- No migration history tables or resume actions.
- No settings mutations.
- No modifications to backend controllers, services, entities, or database migrations.
