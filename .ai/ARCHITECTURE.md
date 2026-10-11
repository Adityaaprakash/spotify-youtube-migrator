Architecture style
Module boundaries
Dependency direction
Domain isolation
Application layer
Infrastructure layer
API layer
External provider isolation
Transaction boundaries
Configuration architecture
Frontend architecture

┌───────────────────────────────┐
│            API                │
└───────────────┬───────────────┘
                ↓
┌───────────────────────────────┐
│        APPLICATION            │
└───────────────┬───────────────┘
                ↓
┌───────────────────────────────┐
│           DOMAIN              │
└───────────────┬───────────────┘
                ↑
┌───────────────┴───────────────┐
│       INFRASTRUCTURE           │
└───────────────────────────────┘


## Forbidden Dependencies

Domain MUST NOT depend on:

- Spotify SDK
- YouTube SDK
- Spring MVC
- HTTP clients
- Controller classes
- Provider DTOs
- Database implementations

Controllers MUST NOT:

- call external APIs directly
- contain business logic
- access JPA repositories directly
- expose provider DTOs

## Matching & Normalization Layer

- Package: `com.spotifyyoutube.migrator.matching.normalization`
- Purpose: Provider-neutral normalization of raw track metadata for deterministic similarity comparisons.
- Architecture Rules:
  - Normalizers (`TitleNormalizer`, `ArtistNormalizer`, `AlbumNormalizer`, `DurationNormalizer`) MUST NOT depend on provider DTOs (`spotify`, `youtube`).
  - Normalizers MUST NOT mutate incoming objects or domain models.
  - Normalizers MUST be pure, deterministic, and independently testable without database or network infrastructure.
  - Duration representation is standardized on canonical `java.time.Duration`, maintaining millisecond and nanosecond precision while strictly validating inputs.