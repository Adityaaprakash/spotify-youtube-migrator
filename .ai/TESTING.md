# Testing Strategy

## Layers of Verification

1. **Unit Testing**: Tests domain objects, mappers, and pure business logic independently. (e.g. `DomainTest`, `SpotifyMapperTest`).
2. **Service Layer Testing**: Tests orchestration logic with mocked dependencies. (e.g. `SpotifyPlaylistServiceTest`, `UserServiceImplTest`).
3. **API / WebMvc Testing**: Validates controller endpoints, serialization, validation, and security intercepts via `MockMvc`. (e.g. `OAuthControllerTest`, `GlobalExceptionHandlerTest`).
4. **Infrastructure Integration Tests**: Validates interactions with external APIs using MockRestServiceServer. (e.g. `SpotifyApiClientTest`, `GoogleProviderAdapterTest`).
5. **Repository Integration Tests**: Validates JPA projections and queries against an actual database using `Testcontainers` (PostgreSQL). (e.g., `UserRepositoryTest`, `PlaylistRepositoryTest`).

## Future Testing Scope (Phases 4+)
As the migrator logic scales, testing will expand to:
- `ArtistNormalizerTest` / `TitleNormalizerTest`
- `MatchScorerTest`
- `MigrationStateMachineTest`
- `YouTubeClientIntegrationTest`
- Full End-to-End Migration Flow Testing

## Current Blockers & Status
- **Phase 3G-3H Freeze**: Unit, Service, API, and MockRestServiceServer integration tests are passing beautifully across the entire Spotify feature set. 
- **Docker Dependency**: Repository and full context integration testing (e.g., `PlaylistRepositoryTest`) via `Testcontainers` is failing strictly due to the current execution environment lacking a functional Docker daemon binding. (Use `$ mvnw clean test` to avoid Docker tests, vs `$ mvnw clean verify` when Docker is available).
- **Format Verification**: `mvnw checkstyle:check` is completely passing with 0 violations.