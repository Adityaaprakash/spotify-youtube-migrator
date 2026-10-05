# Database

## Decision
PostgreSQL used as the primary data store.

## Conventions
- Flyway for migrations.
- UUIDs for external IDs.
- Audit fields (created_at, updated_at).
- Transactional boundaries placed at the application service level.
