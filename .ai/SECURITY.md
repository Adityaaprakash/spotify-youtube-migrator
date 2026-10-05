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