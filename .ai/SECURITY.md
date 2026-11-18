# Security Architecture and Invariants

## Core Principles
1. **Never return tokens to the frontend**: Access and refresh tokens are strictly maintained server-side.
2. **Never log raw tokens, secrets, or credentials**: Diagnostic logging must scrub sensitive parameters or use structured contextual summaries.
3. **No plaintext secrets at rest**: Database fields holding external tokens must be encrypted using authenticated encryption (AES-256-GCM).
4. **Never commit provider credentials**: Client IDs, secrets, and encryption keys must be injected via externalized environment variables.
5. **Enforce multi-tenant anti-IDOR isolation**: Every resource access check must bind to `SecurityContextHolder` / `Principal` with 404 non-disclosure on mismatch.

---

## 1. Application Authentication & Session Security (Phase 16C)
- **Session Architecture**: Cookie-based stateful authentication via Spring Security and Servlet container `JSESSIONID`.
- **Session Fixation Protection**: Configured `sessionFixation().changeSessionId()` on authentication. In `AuthController.establishSession`, pre-existing sessions are explicitly invalidated before creating the authenticated session.
- **Logout Invalidation**: `/api/auth/logout` completely invalidates the HTTP session and expires the `JSESSIONID` cookie with `Max-Age=0`, `HttpOnly`, and `SameSite=Lax`.
- **Cookie Security**:
  - `httpOnly: true` prevents access by client scripts (XSS mitigation).
  - `sameSite: lax` protects against cross-site request forgery in top-level navigations.
  - `secure: true` strictly enforced in production via HTTPS (`application-prod.yml`); fallback to `false` solely in local dev.
- **Anti-Impersonation & IDOR**:
  - Acting user ID is derived solely from the authenticated `Principal`. Client-supplied user ID parameters in URLs or bodies are never trusted.
  - Resource authorization validates that requested user profiles, playlists, migration executions, and review sessions belong to the authenticated user.
  - Unauthorized access attempts by other authenticated users receive HTTP 404 Not Found non-disclosure, eliminating enumeration and oracle leakage.

---

## 2. OAuth 2.0 Security & Provider Token Hardening (Phase 16A & 16B)
- **Cryptographic State Entropy**: OAuth `state` generated using `SecureRandom` producing 256 bits of entropy (32 bytes Base64 URL-safe).
- **State Lifecycle & Consumption**:
  - Bound to initiating user ID and provider.
  - Stored with 15-minute expiration timestamp (`expires_at`).
  - Consumed atomically via `DELETE FROM oauth_states WHERE state = :state` to prevent replay attacks and race conditions.
  - Unused states invalidated immediately upon cancellation or provider error callback.
- **Redirect URI Security**:
  - Provider redirect URIs are static, server-side configuration values.
  - Post-authorization destinations validated via `RedirectUriValidator`: whitelisted scheme (`https` in production), rejected open redirects (`//`, `/\`, CRLF, data/javascript schemes), path normalization.
- **Minimal OAuth Scopes**:
  - Spotify: `user-read-private`, `user-read-email`, `playlist-read-private`, `playlist-read-collaborative`.
  - Google/YouTube: `openid`, `email`, `profile`, `https://www.googleapis.com/auth/youtube.force-ssl`.

---

## 3. Database Security & Token Persistence (Phase 16E)
- **Token Encryption at Rest**:
  - Algorithm: AES-256-GCM (`AES/GCM/NoPadding`) authenticated encryption.
  - Key management: Base64-encoded 256-bit (32-byte) key injected via `OAUTH_ENCRYPTION_KEY`. Fails fast on startup if key is absent or incorrect length in production.
  - IV/Nonce generation: 12-byte cryptographically secure random IV generated per encryption operation. Nonces are never reused.
  - Authentication tag: 128-bit GCM authentication tag appended to ciphertext. Any bit manipulation causes `AEADBadTagException`, caught and wrapped as `InvalidStateException` without leaking internal payloads.
- **Persistence Decoupling**:
  - Plaintext tokens exist exclusively in transient JVM memory during active provider API dispatch.
  - `OAuthConnectionService.saveConnection` encrypts tokens before persisting entity to PostgreSQL.
  - `OAuthConnectionService.getConnection` returns a detached entity with decrypted tokens for consumer use; the stored database entity remains encrypted.
- **Schema Constraints & Audit Integrity**:
  - Immutable audit history: `execution_rollback_records` and `execution_reconciliation_records` enforce `ON DELETE RESTRICT` to `migration_executions`, preventing deletion of compensating mutations.
  - Query-justified indexes (Flyway `V17__phase16e_database_security_and_indexes.sql`):
    - `idx_oauth_states_expires_at`: Optimizes expired state sweep (`OAuthStateRepository.deleteExpiredStates`).
    - `idx_reviewsessions_user_created`: Optimizes user review session discovery in reverse chronological order (`findByUserIdOrderByCreatedAtDesc`).
    - `idx_rollback_execution_target_position`: Optimizes ordered rollback record retrieval (`findByExecutionIdOrderByTargetPositionAsc`).
    - `idx_reconciliation_execution_created`: Optimizes ordered reconciliation record retrieval (`findByExecutionIdOrderByCreatedAtAsc`).

---

## 4. Logging & Sensitive Data Protection (Phase 16F)
- **Zero Sensitive Data in Logs**:
  - Access tokens, refresh tokens, client secrets, passwords, API keys, OAuth state tokens, authorization codes, session cookies, and CSRF tokens are strictly forbidden from log output.
- **Centralized Sanitizer (`SensitiveDataSanitizer`)**:
  - Redacts Bearer tokens: `Bearer [A-Za-z0-9._~+/=-]+` -> `Bearer [REDACTED]`
  - Redacts Basic authentication: `Basic [A-Za-z0-9+/=]+` -> `Basic [REDACTED]`
  - Redacts Authorization headers: `Authorization: Bearer ...` -> `Authorization: Bearer [REDACTED]`
  - Redacts Query/Form parameters: `(?i)(access_token|refresh_token|id_token|token|client_secret|secret|password|api_key|apikey|key|code|state|jsessionid|xsrf)=...` -> `$1=[REDACTED]`
  - Redacts JSON payload properties: `"access_token": "..."` -> `"access_token": "[REDACTED]"`
  - Redacts Cookie values: `JSESSIONID=...`, `XSRF-TOKEN=...`, `SESSION=...` -> `[REDACTED]`
  - Idempotent and deterministic: `sanitize(sanitize(text)) == sanitize(text)`.
  - CRLF Log Injection Protection: `sanitizeSingleLine` strips `\r` and `\n` to prevent log splitting and forging.
  - Throwable Sanitization: `sanitize(Throwable)` traverses cause chains up to 20 levels with cycle detection, sanitizing all diagnostic messages without leaking tokens.
- **Safe Contextual Log Messages**:
  - Success events log safe IDs: `OAuth token refreshed successfully userId=<id> provider=spotify`.
  - Upstream errors log sanitized summaries: `Failed to refresh OAuth token for user {} and provider {}. Flagging REAUTH_REQUIRED: {}`.

---

## 5. API Security Controls (Phase 16D)
- **Rate Limiting**: Sliding window in-memory limiter (`InMemoryApiRateLimiter`) with bounded capacity (10,000 keys) and eviction. Tiered limits on sensitive endpoints (`/api/auth/login`, `/api/auth/register`, `/api/oauth/{provider}/authorize`, `/api/youtube/videos/search`, `/api/migrations`). Returns HTTP 429 with RFC 6585 `Retry-After`.
- **CORS**: Explicit origin whitelist (`http://localhost:5173,http://localhost:3000`), credentials allowed, explicit methods and exposed headers (`Retry-After`, `Location`, `Idempotency-Key`, `Idempotency-Replayed`).
- **CSRF**: Double-submit cookie with `CookieCsrfTokenRepository.withHttpOnlyFalse()` and eager generation via `CsrfCookieFilter`. Safe GET requests receive `XSRF-TOKEN`; state mutations require matching `X-XSRF-TOKEN`. Public auth endpoints exempt.
- **Input Validation**: JSR-380 bean validation with length constraints on all DTO strings, collections, and pagination parameters. Multipart uploads disabled. Tomcat form size capped at 1MB/2MB.
- **HTTP Method Restrictions**: Unsupported methods return HTTP 405 Method Not Allowed via `GlobalExceptionHandler`.

---

## 6. Actuator & Operational Endpoint Hardening
- **Public Surface Minimization**:
  - `/actuator/health` (including readiness/liveness group probes) and `/actuator/info` are permitted without authentication for Kubernetes/container orchestration checks.
  - All other actuator endpoints (`/actuator/**`) require authentication in Spring Security (`SecurityConfig.java`).
  - Web exposure is explicitly restricted via `management.endpoints.web.exposure.include: ${MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE:health,info}`. Sensitive endpoints such as `/actuator/env`, `/actuator/beans`, `/actuator/heapdump`, and `/actuator/threaddump` are completely unexposed over HTTP.
- **Health Information Disclosure Prevention**:
  - `management.endpoint.health.show-details: ${MANAGEMENT_HEALTH_SHOW_DETAILS:never}` in base and production configurations.
  - Public `/actuator/health` calls return strictly `{"status":"UP"}` without revealing database version, schema status, disk space, or subsystem internals.
  - In `application-prod.yml`, `management.info.env.enabled: false` ensures environment details are not leaked in `/actuator/info`.
  - Development profiles (`application-local.yml`) may selectively expose metrics or details for local diagnostic use.

---

## 7. Integrated Security Architecture & Phase 16 Freeze (Phase 16G & 16H)
- **Multi-Layer Defense Posture**:
  - The security posture combines perimeter controls (CORS whitelist, CSRF double-submit cookies, sliding-window rate limiting), edge application controls (Spring Security session fixation prevention, cookie expiry, JSR-380 input validation, bounded payload limits), authorization controls (authenticated Principal derivation, multi-tenant IDOR 404 non-disclosure), provider controls (256-bit entropy OAuth states, atomic single-use deletion, static redirect URIs, striped concurrency locking), database controls (AES-256-GCM authenticated encryption at rest, `ON DELETE RESTRICT` audit retention, Flyway V1–V17 migrations), diagnostic controls (centralized `SensitiveDataSanitizer`, CRLF log injection protection, circular-safe exception traversal), and operational controls (restricted Actuator exposure, `show-details: never`).
- **Production Secret & Key Management**:
  - Production deployments require external injection of `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`, `OAUTH_ENCRYPTION_KEY`, `SPOTIFY_CLIENT_ID`, `SPOTIFY_CLIENT_SECRET`, `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, and `YOUTUBE_API_KEY`.
  - In `application-prod.yml`, missing required secrets fail fast on container startup without falling back to insecure defaults.
- **Accepted Limitations & Architectural Boundaries**:
  - In-memory rate limiting and in-memory OAuth refresh lock striping operate node-locally within a single JVM process. Multi-node clustered deployments will require distributed locking or API gateway rate limiting.
  - Testcontainers runtime verification requires a functional local Docker daemon; mock-based regression test suites provide 100% test isolation when Docker is unavailable.
- **Phase 16 Formal Freeze**:
  - All security requirements from Phase 16A through 16H are verified and sealed. The security subsystem is formally frozen. Phase 17 (Testing) may proceed without re-opening Phase 16 architectural contracts.
