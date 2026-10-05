# ADR-003: PostgreSQL as Primary Database

## Context
We need a robust, relational store for mapping migration progress and system users.

## Decision
We will use PostgreSQL.

## Consequences
- ACID compliance.
- Supported universally by managed providers (AWS RDS, Google Cloud SQL, etc.).
