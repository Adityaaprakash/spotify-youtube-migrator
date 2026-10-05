# ADR-007: No Redis in Initial Architecture

## Context
A playlist migration tool intuitively implies asynchronous background work (e.g., migrating 1,000 tracks via rate-limited external APIs). It is tempting to immediately introduce Redis, RabbitMQ, or Kafka to handle these jobs.

## Decision
We will **NOT** utilize Redis or any separate message broker in Phase 0 or Phase 1. 

**Rationale:**
- We currently have no asynchronous workload implemented that requires it.
- There is no distributed caching requirement at this stage.
- Introducing a secondary data storage/broker creates unnecessary infrastructure complexity for a foundation phase.

## Consequences
- Initial async migration processing (slated for Phase 12) might initially utilize an in-memory queue or basic Spring `@Async` combined with PostgreSQL table polling for job statuses.
- We will ONLY introduce Redis when a concrete bottleneck or reliability issue dictates the need for distributed queuing, rate limiting, or advanced coordination mechanisms that PostgreSQL cannot handle elegantly.
- **Rule:** Do not introduce Redis just because the future architecture *may* use it.
