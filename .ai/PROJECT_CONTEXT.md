# Spotify → YouTube Music Migrator
## Project Context

### Purpose

A Java/Spring Boot application that allows users to migrate playlists
from Spotify to YouTube Music.

The system must:

1. Authenticate the application user.
2. Connect the user's Spotify account.
3. Read Spotify playlists and tracks.
4. Normalize track metadata.
5. Search YouTube for candidate matches.
6. Rank candidates using a matching engine.
7. Allow uncertain matches to be reviewed.
8. Create a YouTube Music playlist.
9. Add matched tracks.
10. Track migration progress and failures.

### Architecture

The application is a modular monolith.

Frontend:
React + TypeScript + Vite

Backend:
Java 22 + Spring Boot + Maven

Database:
PostgreSQL + Flyway

Frontend architecture:

React
→ TanStack Query
→ API Client
→ Spring Boot

Backend architecture:

API
→ Application
→ Domain
→ Infrastructure

External providers:

Spotify Adapter
→ Spotify API

YouTube Adapter
→ YouTube API

### Core architectural principle

External provider models MUST NOT become domain models.

Provider DTO
→ Provider Mapper
→ Internal Domain Model

Never:

Spotify DTO
→ Controller
→ Domain
→ YouTube API

### Current Phase

Phase 1 — Domain Model & API Contracts

Phase 0 foundation has already been established.

### Current priority

Complete and audit the internal domain model and API contracts
before implementing external integrations or OAuth.

### Important

Do not implement future phases unless explicitly instructed.

Do not redesign the architecture without documenting the reason.