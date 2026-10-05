# ADR-006: No Redis in Phase 0

## Context
We want to keep the architecture as simple as possible until we prove the need for caching or distributed message queues.

## Decision
Do not introduce Redis (or Kafka) in Phase 0.

## Consequences
- Lower infrastructure costs and complexity at the start.
- We rely on Postgres for durable queues if needed later, unless we hit limits.
