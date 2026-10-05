# ADR-005: Flyway Database Migrations

## Context
When collaborating across environments (local, CI, production) and making schema evolutionary changes, relying on implicit ORM generation (`hibernate.hbm2ddl.auto`) is incredibly dangerous. We require strict, reproducible control over our database schema.

## Decision
We will use **Flyway** for database schema migration.

**Rules:**
- Schema changes must be explicitly versioned (e.g., `V1__init.sql`).
- Migrations are strictly ordered.
- Already applied migrations MUST NOT be modified under any circumstances.
- Any modification requires a new migration file.
- Migrations must be completely reproducible enabling full teardown and rebuild of local environments.
- Application startup will validate Flyway migration state before serving traffic.

## Consequences

**Alternatives Considered:**
- **Liquibase:** Rejected due to XML/YAML verbosity; Flyway's pure SQL approach aligns perfectly with our need for simplicity and direct control over PostgreSQL schemas.
- **Hibernate DDL Auto:** Utterly rejected for production due to data-loss risks and lack of explicit versioning.
