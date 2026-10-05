# ADR-002: Provider Isolation

## Context
The core business of this application is migrating playlists between different music platforms (initially Spotify and YouTube). Each API has a distinct schema, varying constraints, and unique authentication models. Tying our core domain logic directly to how Spotify or YouTube models their data would make the system rigid, hard to test, and difficult to extend.

## Decision
External provider models (DTOs) MUST NOT become internal domain models. We enforce strict provider isolation.

**Required Flow:**
1. Spotify API → Spotify DTO → Spotify Mapper → Internal Domain Model (e.g., `TrackCandidate`)
2. YouTube API → YouTube DTO → YouTube Mapper → Internal Domain Model

The `migration` and `matching` systems must depend exclusively on internal abstractions rather than provider-specific DTOs.

## Consequences

**Benefits:**
- **Decoupling:** Provider-specific changes (such as API deprecations or schema upgrades) are localized to the infrastructure/adapter layer and mapper.
- **Portability:** Adding new providers (e.g., Apple Music or Tidal) becomes a matter of implementing new adapters, rather than rewriting core migration logic.
- **Maintainability:** The domain model only encapsulates concepts indispensable to our business logic, ignoring extraneous platform-specific noise.

**Tradeoffs:**
- Overhead of creating separate DTO classes, domain classes, and mapping layers.
- Boilerplate mapping code must be maintained.
