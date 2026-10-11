Keep security decisions here.
Especially:
Application authentication
Spotify OAuth
Google OAuth
OAuth state
Token storage
Token encryption
Scopes
Cookies
CSRF
CORS
Secrets
Logging

And a critical rule:
Tokens must never be returned to the frontend.

Tokens must never appear in logs.

Provider credentials must never be committed.

YouTube Security & Credential Rules (Phase 4A-4C):
- YouTube API Key (`YOUTUBE_API_KEY`) is stored external to git and injected via environment variables (`application.yml`).
- YouTube API endpoints (`/api/youtube/videos/search`) require active authenticated session (`Principal`), returning 401/403 for unauthenticated access.
- API Key is appended strictly server-side in outbound HTTP requests by `YouTubeClient` and is never returned in client responses or logged.
- Error boundaries (Phase 3G & 4B limits): Raw Provider HTTP responses (Spotify and YouTube upstream exception bodies, error reason payloads, quota messages) must never be returned to the frontend.
`GlobalExceptionHandler` securely sanitizes HTTP exceptions into `ApiError` standardized abstractions.