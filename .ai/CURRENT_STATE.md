# Current State

Last Updated: 2026-10-05

Current Phase: Phase 1 — Domain Model & API Contracts
Current Subphase: Phase 1G — Persistence & Migration Verification (BLOCKED)

## Completed

### Phase 0 / 1A / 1B / 1C
- Domain entities, invariants, and Repositories built. ADRs written.

### Phase 1D / 1E / 1F
- Application Service Boundaries conceptualized and implemented using explicit Service/Implementation pairings mapping across `Identity`, `Playlist`, and `Migration` features.
- Dedicated DTO records (`UserResponse`, `PlaylistResponse`, `TrackResponse`, `CreateMigrationRequest`, `MigrationJobResponse`, `MigrationTaskResponse`) mapped to strictly divorce Controller models from Domain realities. 
- Validation bounds handled via Jakarta validation limits inside DTOs (`@NotNull`, `@NotBlank`) resolving explicitly to `400 Bad Request` wrapped seamlessly by an augmented `GlobalExceptionHandler`. State constraints (`ResourceNotFound`, `InvalidState`, `Conflict`) seamlessly structured to render `404` or `409` HTTP responses ensuring stable API conventions.
- Explicit Mappers manually configured (no MapStruct) tracking strict one-to-one Domain-to-DTO conventions.

## Current Issues
- Environment configuration: `Testcontainers` lacks internal availability on execution pipelines restricting complete Repository suite verification. Test execution fails repeatedly with `Failed to find a Docker environment`.

## Not Yet Implemented
- OAuth logic (Phase 2).
- Spotify/YouTube Providers matching and synchronization logic.
- Background asynchronous queue execution for migrations.

## Current Verification
- Backend Compilation: `mvn clean compile` — **PASS**
- Checkstyle: `mvn checkstyle:check` — **PASS**
- MockWeb API Testing: `MigrationControllerTest` — **PASS**
- Repository/Flyway Integration Tests (`PlaylistRepositoryTest`, `UserRepositoryTest`, `MigrationJobRepositoryTest`): **BLOCKED** — environment unavailable
- Transaction Boundary Verification: **BLOCKED** — environment unavailable
- Cascade Verification: **BLOCKED** — environment unavailable

To run verifying commands once Docker is available:
`mvn clean verify` or `docker-compose up -d && mvn test`

## Next Recommended Work
1. Resolve internal Testcontainers environment blockers, or run Phase 1G verification explicitly in an environment that supports Docker. 
2. Only after Phase 1 tests PASS, proceed to freeze Phase 1 and start Phase 2 (Authentication & Identity).