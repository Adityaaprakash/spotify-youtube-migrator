# Architecture Specification

## Overview
The Spotify-to-YouTube Playlist Migrator is structured as a Modular Monolith with clear hexagonal boundaries, domain isolation, and strict persistence contracts.

---

## Architecture Boundaries & Modules

- **`identity`**: User accounts, authentication, provider connection state, and token lifecycle.
- **`spotify`**: Spotify API integration, playlist querying, and track metadata extraction.
- **`matching`**: Matching engine, similarity scoring, calibrated probability, and match decision classification (`MATCH`, `REVIEW_REQUIRED`, `NO_MATCH`).
- **`migration`**:
  - `application.plan`: Pre-flight playlist inspection and classification (`PlanTrackClassification`).
  - `review`: Human review sessions (`ReviewSession`, `ReviewTrack`) preserving matching evidence and user overrides.
  - `execution`: Durable migration execution engine (`MigrationExecution`, `ExecutionTrackRecord`).
  - `reliability`: Durable lease-based scheduler, retry policy, API idempotency keys, crash recovery, and fencing.
- **`youtube`**: YouTube Data API v3 adapter, playlist creation, track addition, and two-read eventual consistency reconciliation.
- **`api`**: REST controllers and JSON DTOs strictly decoupled from internal JPA domain entities.

---

## Dependency & Architectural Invariants

1. **Domain Isolation**: Domain entities (`MigrationExecution`, `ExecutionTrackRecord`, `ReviewSession`, `ReviewTrack`) have zero dependencies on external Spotify/YouTube SDK DTOs or web presentation layers.
2. **Aggregate Integrity**: `MigrationExecution` is the authoritative aggregate root across Phases 11–14 (ADR-022, ADR-030).
3. **No Duplicate State Machines**: Phase 14 history and execution share the same underlying state machine (`MigrationExecutionStatus`).
4. **Idempotency & Lease Fencing**: Mutations are guarded by generational lease tokens (`lease_generation`) and database wall-clock expiration (`lease_until > clock_timestamp()`).
5. **Non-Overwriting Provenance**: Manual user match selections (`USER_SELECTED`) and matching evidence (`matchScore`, `matchDecision`) are durably preserved and never overwritten by subsequent automated match discoveries.
6. **Zero-Loss Resume**: Completed tracks (`ADDED` with `destinationPlaylistItemId`) are immutable and never re-evaluated or reverted to pending on retry or resume.

---

## Phase 14 Architecture: Persistent Migration History

### 1. Unified Aggregate Root
Phase 14B leverages `MigrationExecution` as the persistent migration history record. Every migration preserves:
- **Ownership**: `user_id` foreign key ensuring multi-tenant isolation.
- **Source Context**: `source_playlist_id`, `source_playlist_title`, `total_source_tracks`, `matched_tracks`.
- **Destination Context**: `destination_platform` (YouTube), `destination_playlist_id` (nullable during allocation), `destination_playlist_url`.
- **Execution Statistics**: `total_approved_tracks`, `successful_insertions`, `failed_insertions`, `uncertain_insertions`, `skipped_insertions`, `rolled_back_insertions`.
- **Lifecycle & Failure Audit**: `status`, `failure_summary`, `started_at`, `completed_at`, `created_at`, `updated_at`.

### 2. Per-Track Execution History & Provenance
`ExecutionTrackRecord` stores track-level outcomes:
- **Source Track**: `source_track_id`, `source_position`, `source_title`, `source_artist`.
- **Destination Track**: `destination_track_id`, `destination_title`, `destination_channel`, `destination_duration_ms`, `destination_playlist_item_id`.
- **Matching Provenance**: `decision_source` (`AUTOMATIC` vs `USER_SELECTED`), `match_score`, `match_decision`.
- **Status & Failure**: `status` (`PENDING`, `ADDING`, `ADDED`, `FAILED`, `UNCERTAIN`, `SKIPPED`, `ROLLED_BACK`), `error_message`, `attempt_count`, `max_attempts`.
- **Timestamps**: `started_at`, `completed_at`, `created_at`, `updated_at`.

### 3. Querying & Pagination
History retrieval queries `MigrationExecutionRepository.findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable)` utilizing the composite index `(user_id, created_at DESC)` for efficient, bounded pagination without memory overhead.
