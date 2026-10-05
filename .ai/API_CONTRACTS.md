# API Contracts

Base path:

/api

## Conventions

Success:
HTTP 2xx

Client error:
HTTP 4xx

Server error:
HTTP 5xx

Errors use:

{
  "code": "...",
  "message": "...",
  "timestamp": "...",
  "path": "..."
}