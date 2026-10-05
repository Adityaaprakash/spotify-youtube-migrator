# External APIs

## Spotify

Purpose:
Source playlist provider.

Integration location:
backend/.../spotify/

Rules:
Spotify DTOs remain inside Spotify infrastructure.

Mapping:
Spotify DTO
→ SpotifyMapper
→ Domain

## YouTube

Purpose:
Destination provider.

Integration location:
backend/.../youtube/

Rules:
YouTube DTOs remain inside YouTube infrastructure.

Mapping:
YouTube DTO
→ YouTubeMapper
→ Domain

## Provider Abstraction

Migration engine must depend on internal abstractions,
not Spotify/YouTube implementations.