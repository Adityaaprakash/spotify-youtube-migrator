Define the testing strategy once:
Unit
Integration
Repository
API
Contract
End-to-end

And later:
TitleNormalizerTest
ArtistNormalizerTest
MatchScorerTest
MigrationStateMachineTest
SpotifyClientIntegrationTest
YouTubeClientIntegrationTest
MigrationControllerTest
PlaylistControllerTest
UserControllerTest
MigrationServiceImplTest
PlaylistServiceImplTest
UserServiceImplTest
DomainTest

The roadmap already calls for this layered testing strategy later.

**Current Blockers:**
- PostgreSQL integration / Repository Tests (`PlaylistRepositoryTest`, `MigrationJobRepositoryTest`, `UserRepositoryTest`) rely on `Testcontainers` which currently fails to execute due to lacking a Docker engine environment (`docker info` fails with exit code 1).

**Execution results (Phase 3G-3H Freeze):**
- `mvnw clean test`: Core unit and `@RestClientTest` suites executed resolving Spotify mock structures cleanly. 
- Validation totals: `SpotifyApiClientTest` (100% boundary testing), `SpotifyMapperTest`, `SpotifyPlaylistServiceTest`, `OAuthTokenLifecycleServiceImplTest` executed functionally validating error propagations.
- Checkstyle: `mvnw checkstyle:check` PASSED 0 violations.
- Integration tests blocked by local Docker failure, marking `verify` phase incomplete in continuous CI pipeline until container daemon is restored.