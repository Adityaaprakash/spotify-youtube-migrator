# Security Architecture

## Principles
1. NEVER commit secrets.
2. NEVER leak provider tokens to the frontend.
3. OAuth state and tokens are handled completely server-side.
4. Uses environment variables for configuration.

---

## Anti-IDOR & Multi-Tenant Authorization (Phase 14C, 14D & 14E)

### 1. Multi-Tenant Scoping
All migration history, execution progress, track outcomes, resume requests, and audit logs are strictly isolated by `user_id`:
- Repository queries (`findByUserIdOrderByCreatedAtDesc`, `findByUserIdAndStatusOrderByCreatedAtDesc`) enforce tenant boundary at the database level.
- Multi-tenant execution records cannot be accessed across different user sessions.
- Endpoints `GET /api/migrations`, `GET /api/migrations/history`, `GET /api/migrations/{id}`, `GET /api/migrations/{id}/history`, `GET /api/migrations/{id}/tracks`, and `POST /api/migrations/{id}/resume` are completely bounded by user ownership.

### 2. Non-Disclosure & Anti-Enumeration Policy (HTTP 404)
To prevent malicious users from discovering or enumerating valid execution UUIDs across users:
- Accessing an execution that does not exist returns `HTTP 404 Not Found`.
- Accessing an execution belonging to another user ALSO returns `HTTP 404 Not Found` (never `HTTP 403 Forbidden`).
- This eliminates information leakage regarding whether a specific migration UUID exists on the platform.
- Applies identically to detail inspection (`GET /{id}`), audit timeline (`GET /{id}/history`), itemized track outcomes (`GET /{id}/tracks`), and resume attempts (`POST /{id}/resume`).

### 3. Layered Defense Verification
- **Presentation Layer**: Controllers obtain the authenticated user via `@AuthenticationPrincipal` or `SecurityContextHolder`.
- **Application Layer**: Services like `ExecutionAuditService` and `MigrationExecutionStateService` execute strict ownership validation against `execution.getUser().getId()`. If an ID mismatch occurs, `ResourceNotFoundException` is thrown.
- **Data Layer**: Direct foreign-key enforcement (`user_id` on `migration_executions`) guarantees relational partition.
