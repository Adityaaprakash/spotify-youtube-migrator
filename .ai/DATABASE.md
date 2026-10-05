# Database

## Decision
PostgreSQL used as the primary data store.

## Conventions
- **Migrations**: Flyway for migrations (starting at `V2__init_schema.sql` mapped accurately to JPA entities).
- **Primary Keys**: UUIDs for all internal identities to prevent enumeration.
- **Audit Fields**: `TIMESTAMP WITH TIME ZONE` for `created_at` and `updated_at`.
- **Enums**: Persisted strictly as `VARCHAR(50)` strings to prevent ordinal breakages.
- **Foreign Keys**: Explicit `ON DELETE CASCADE` enforced directly in the database from Users -> Playlists -> Tracks and Users -> MigrationJobs -> MigrationTasks.
- **Constraints**: Enforced uniqueness for Playlists on `(user_id, external_id, platform)`. Explicit text and URL column lengths (`2048`) aligned.
- **Indexes**: Indexes deployed surgically ONLY for active `findBy...` repository paths (`user_id`, `playlist_id`, `job_id`, `job_id + status`). No speculative indexing.