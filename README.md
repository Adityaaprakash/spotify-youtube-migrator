# Spotify → YouTube Music Playlist Migrator

## Purpose
A backend and frontend architecture establishing the platform for migrating Spotify playlists to YouTube Music.

## Current Phase: PHASE 0 — ARCHITECTURE & PROJECT FOUNDATION
This project is currently on Phase 0. No business logic algorithms or provider matching engines are implemented yet.

## Architecture
- **Language Stack**: Java 22 / TypeScript
- **Backend Framework**: Spring Boot
- **Frontend Framework**: React + Vite
- **Database**: PostgreSQL
- **Migrations**: Flyway

## Local Setup

### 1. Database
```bash
docker compose up -d postgres
```

### 2. Backend
Navigate to the `backend` folder and run:
```bash
./mvnw clean spring-boot:run
```
Run tests and checkstyle:
```bash
./mvnw clean test checkstyle:check
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
