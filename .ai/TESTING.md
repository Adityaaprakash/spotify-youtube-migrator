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
- PostgreSQL integration / Repository Tests rely on `Testcontainers` which currently fails to execute due to lacking a Docker engine environment.