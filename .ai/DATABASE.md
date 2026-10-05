# Database Rules

Database:
PostgreSQL

Migration:
Flyway

Primary keys:
UUID

Audit fields:
createdAt
updatedAt

Naming:
snake_case

Java:
camelCase

Database:
snake_case

Schema changes:
Always through Flyway.

Never modify an already-applied migration.

Relationships:
Prefer explicit foreign keys.

Fetch strategy:
Avoid unnecessary EAGER relationships.

Collections:
Avoid uncontrolled cascade operations.