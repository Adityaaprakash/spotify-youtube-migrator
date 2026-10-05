# Phase 0 Architecture
This document details the architectural foundation for the Spotify to YouTube Music Playlist Migrator.

## Architecture Overview
The system uses a Modular Monolith approach.
We separated the application into common, identity, playlist, migration, matching, spotify, and youtube packages.

## Dependency Rules
- **Domain**: Independent of Spring, external DTOs, controllers.
- **Application**: Coordinates use cases, relies on domain models.
- **Infrastructure**: PostgeSQL, external APIs (Spotify, YouTube).
- **API**: HTTP endpoints, request validation, isolation from provider DTOs.

## Data Flow
Spotify API -> Adapter -> Application -> Matching -> YouTube Adapter -> YouTube API.

## Frontend
React, Vite, TypeScript, Tailwind, shadcn/ui.
Data fetching via TanStack Query.
