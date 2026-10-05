# Error Handling

Global exception handling standardizes all client-facing errors:
1. `validate` -> 400 Bad Request
2. `auth` -> 401/403
3. `not found` -> 404
4. `server` -> 500

Structure:
```json
{
  "code": "INVALID_REQUEST",
  "message": "...",
  "timestamp": "...",
  "path": "..."
}
```
