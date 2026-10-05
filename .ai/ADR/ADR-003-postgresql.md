# ADR-003: PostgreSQL

## Context
The application needs to store relational data, specifically mapping users to their migration jobs, playlists, and individual track migration tasks. The relationships require transactional boundaries and consistency to ensure partial migration recovery works reliably.

## Decision
We will use **PostgreSQL** as the primary relational persistence system.

**Requirements addresesed:**
- **Relational Data:** Explicit foreign key relationships between Users, Playlists, Tracks, MigrationJobs, and MigrationTasks.
- **Transactional Consistency:** ACID properties are critical to make sure that a track is only marked as "COMPLETED" exactly when the corresponding update to the playlist track count is successful.
- **Identifiers:** We will use UUIDs for external and internal identifiers to prevent ID enumeration.
- **Audit Trails:** Migration history requires robust indexing and audit fields (`created_at`, `updated_at`).

## Consequences

**Alternatives Considered:**
- **MongoDB / NoSQL:** Rejected because the core data strictly defines relationships and benefits immensely from SQL joins, constraints, and ACID transactions.
- **MySQL:** While functionally similar, PostgreSQL provides better handling of UUIDs natively and extended JSONB functionality should we require flexible metadata storage later.

**Note:**
We only utilize established PostgreSQL features directly required by the current phases (no partitioned tables or advanced queuing unless later warranted).
