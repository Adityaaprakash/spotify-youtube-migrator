# ADR-004: Flyway for Migrations

## Context
We need predictable schema evolution.

## Decision
Flyway is chosen as the database migration tool.

## Consequences
- Migrations are version-controlled alongside application code.
- Automatic execution on startup.
