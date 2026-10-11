# Spotify → YouTube Music Playlist Migrator

## Purpose
A backend and frontend architecture establishing the platform for migrating Spotify playlists to YouTube Music.

## Current Phase: PHASE 4 — YOUTUBE INTEGRATION (PHASES 4A-4C COMPLETE)
This project is currently on Phase 4: the domain foundation, identity management, OAuth flows, Spotify API integration (playlists, tracks, metadata), and YouTube Video Search integration (API client, DTO mapping, search service, controller) have been implemented. YouTube playlist management/writing and migration matching logic are next.

## Architecture
- **Language Stack**: Java 22 / TypeScript
- **Backend Framework**: Spring Boot
- **Frontend Framework**: React + Vite
- **Database**: PostgreSQL
- **Migrations**: Flyway

## Local Setup

### 1. Configuration
Create a `.env` file in the root based on `.env.example`. You will need to provide legitimate OAuth credentials and API keys for the external providers:
```ini
SPOTIFY_CLIENT_ID=your_spotify_client_id
SPOTIFY_CLIENT_SECRET=your_spotify_client_secret
GOOGLE_CLIENT_ID=your_google_client_id
GOOGLE_CLIENT_SECRET=your_google_client_secret
YOUTUBE_API_KEY=your_youtube_api_key
```

### 2. Database
Ensure your local Docker daemon is running, then start the PostgreSQL service:
```bash
docker compose up -d postgres
```

### 3. Backend
Navigate to the `backend` folder and run the backend server:
```bash
./mvnw clean spring-boot:run
```
Run unit Tests and checkstyle (does not require Docker):
```bash
./mvnw clean test checkstyle:check
```

Run full integration test suite (Testcontainers; requires Docker to be running natively):
```bash
./mvnw clean verify
```

### 3. Frontend
Navigate to the `frontend` folder.
Install dependencies:
```bash
npm install
```
Start development server:
```bash
npm run dev
```

Run frontend validations:
```bash
npm run typecheck
npm run lint
npm run build
```

## Security Rules
- NEVER commit secrets.
- Use `.env` file referencing `.env.example`.
- Frontend does not leak provider credentials.
