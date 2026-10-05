# ADR-001: Modular Monolith

## Context
The application is a playlist migrator that integrates:
- Spotify
- YouTube
- PostgreSQL
- authentication
- playlist management
- track matching
- migration execution

While this involves multiple distinct domains (e.g., identity, third-party adapters, matching engine, migration logic), introducing microservices immediately would lead to unnecessary infrastructure overhead, deployment complexity, and distributed system challenges without a proven scale requirement.

## Decision
We will use a **Modular Monolith** architecture.

Expected module boundaries include:
- `common`: Cross-cutting concerns (base entities, global exception handling).
- `identity`: User authentication and authorization.
- `playlist`: Domain models and logic for Playlists and Tracks.
- `migration`: Orchestrating the overall migration workflow and tracking task progress.
- `matching`: The engine that matches a generic track against a specific provider's catalog.
- `spotify`: The Spotify API client and provider-specific mappers.
- `youtube`: The YouTube API client and provider-specific mappers.

## Consequences

**Benefits:**
- Simplifies deployment and local testing (a single Spring Boot artifact).
- Eliminates network boundaries and RPC overhead between internal domains.
- Facilitates refactoring and type safety across modules.

**Tradeoffs:**
- Requires strict package discipline to prevent "big ball of mud" coupling between domains (e.g., identity should not depend on spotify).
- A single failure in one module can potentially crash the entire monolith.

**Future Considerations:**
- If the application reaches significant scale (particularly in the async migration processing workload), individual modules (such as `migration` or `matching`) can be extracted into dedicated microservices because the modular boundaries have been strictly enforced from the beginning.
