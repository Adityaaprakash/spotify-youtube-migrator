# Phase 2A-2C: Authentication & OAuth Architecture

## Motivation
This phase establishes the foundational security model for the application without coupling the system to a specific external provider. It establishes the separation between the Application Identity (`User`) and the Provider Identity (`OAuthConnection`), addressing security, persistence, and session management.

## 1. Identity Foundation (Phase 2A)
The identity layer relies on a clear Application User distinct from external platforms.
- `User` entity has been localized to hold application details: `email`, `passwordHash`, `displayName`, and `status`.
- Previously speculative provider fields (`spotify_id`, `youtube_id`) were explicitly dropped from the application User via `V3__phase2_auth_oauth_schema.sql`.
- Two isolation constructs were introduced:
  - `OAuthConnection`: Manages decoupled tokens and provider associations for an Application User.
  - `OAuthState`: Handles secure, verifiable, and short-lived state generation to protect the OAuth callback flow against CSRF and injection attacks.

## 2. Application Authentication (Phase 2B)
Since the architecture relies on an Application User, explicit robust Session-based Authentication was chosen.
- Endpoints located at `/api/auth/register`, `/api/auth/login`, `/api/auth/logout`, and `/api/auth/me`.
- Spring Security enforces CSRF (`CookieCsrfTokenRepository`) standardizing security for the SPA layer without leaking JWTs to LocalStorage.
- Authentication utilizes standard Spring `SecurityContextHolder` mapped to Tomcat Sessions using secure HTTP cookies.

## 3. Provider-Neutral OAuth Infrastructure (Phase 2C)
All OAuth mechanics have been strictly generalized:
- `OAuthProviderAdapter`: An application interface acting as a port. Future Spotify or YouTube clients will implement this Adapter, ensuring their DTOs never breach the core domain.
- `OAuthAuthorizationService`: An orchestrator that manages generation of the OAuth URLs and processes the resultant callback code.
- `OAuthStateService`: Ensures states are cryptographically sound, verified against the authenticated user, and consumed only once.
- `OAuthConnectionService`: Persists and retrieves encrypted connections.
- `OAuthTokenService`: Establishes the encryption boundary. `AES/GCM/NoPadding` encryption is employed transparently before storing `refreshToken` or `accessToken` at rest.

## Security Decisions
1. **At-Rest Token Encryption**: All stored access and refresh tokens are symmetrically encrypted at the application boundary `OAuthTokenService` via AES-GCM.
2. **Double-Submit CSRF**: Enabled out of the box for the frontend to parse and send `X-XSRF-TOKEN`.
3. **Session vs JWT**: Adopted standard server-side sessions over stateless JWTs on the client, minimizing XSS token-extraction risks and allowing easy immediate session invalidation on logout.
4. **State Tie-in**: `OAuthState` enforces that the application user who initiated the request is the exact same application user consuming the state token.
