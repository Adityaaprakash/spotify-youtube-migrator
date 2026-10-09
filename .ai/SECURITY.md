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

Error boundaries (Phase 3G limits): 
Raw Provider HTTP responses (Spotify exception bodies, payload traces) must never be returned to the frontend.
`GlobalExceptionHandler` securely sanitizes HTTP exceptions into `ApiError` standardized abstractions.