# ADR-022: Async Migration Architecture & Contracts (Phase 12A / 12B)

## Status
**ACCEPTED & IMPLEMENTED (PHASE 12A Architecture & Phase 12B Persistence)**  
*(Sequential Reference Note: Chronologically succeeds ADR-030; titled ADR-022 per Phase 12 roadmap contract specification).*

---

## Context

In Phase 11 (`ADR-024` through `ADR-030`), the `spotify-youtube-migrator` project implemented and audited the complete destination playlist creation, execution, and state management subsystem:
1. **Pre-mutation validation** (`ApprovedMigrationExecutionService`, `ApprovedPlanValidator`, Phase 11B).
2. **Destination playlist creation** on YouTube (`DestinationPlaylistCreationService`, Phase 11C).
3. **Canonical 0-indexed sequential track insertion** (`OrderedTrackInsertionService`, Phase 11D).
4. **Durable execution state, safe resume, and atomic claim locking** (`MigrationExecutionStateService`, `MigrationExecutionRepository`, Phase 11E).
5. **Controlled failure recovery and partial rollback** (`ExecutionRollbackRecordRepository`, Phase 11F).
6. **Remote destination inspection and ambiguous outcome reconciliation** (`ExecutionReconciliationRecordRepository`, Phase 11G).
7. **End-to-end orchestration facade** (`ApprovedMigrationOrchestrator`, `MigrationExecutionController`, Phase 11H).

### The Synchronous Limitation

In Phase 11H, the orchestration entry points on `MigrationExecutionController`:
- `POST /api/migrations/execution/run`
- `POST /api/migrations/execution/run/{sessionId}`

execute synchronously. The client HTTP connection remains open while:
1. Destination playlist creation is invoked on YouTube (`POST /playlists`, ~300ms–800ms).
2. Durable execution state is initialized in PostgreSQL.
3. Every approved track is sequentially inserted via `POST /playlistItems` (~250ms–600ms per track).

For a 50-track playlist, this takes 15–30 seconds. For a 100-track playlist, it takes 30–60 seconds. For a 500-track playlist, execution duration exceeds HTTP client/gateway timeout thresholds (e.g., 30s or 60s in Nginx, Cloudflare, or browser fetch). Furthermore, browser reloads, network disconnects, or client drops during an active HTTP request sever the client connection while server-side processing continues untracked by the frontend.

### Objective for Phase 12

Decouple migration submission and execution from HTTP request lifecycles:
1. The submission endpoint must accept valid migration requests, persist/queue the job, and return `HTTP 202 Accepted` immediately with a durable job identifier and status `QUEUED`.
2. A background worker pool must claim the queued migration and execute it using existing Phase 11 orchestration logic (`ApprovedMigrationOrchestrator.executeApprovedMigration`).
3. Migration-level and track-level progress must be persisted as execution progresses.
4. Clients must retrieve progress using a polling REST API (`GET /api/migrations/{id}/progress` and `GET /api/migrations/{id}`).
5. Multiple concurrent migrations must execute safely without database connection exhaustion, race conditions, or duplicate insertions.

---

## Architectural Problem & Repository Audit

### 1. Dual Existing Domain Model Entities

The repository contains two distinct execution models:
1. **Phase 1 Model (`MigrationJob` & `MigrationTask`, schema `V2__init_schema.sql`)**:
   - Rudimentary entity in `migration_jobs` table.
   - Contains fields: `id, user_id, source_playlist_id, source_platform, target_platform, status, total_tracks, processed_tracks, failed_tracks, started_at, completed_at, target_playlist_url`.
   - Used by the early Phase 8G `PlaylistMigrationOrchestrator.executeMigration(UUID jobId)` and `MigrationController` (`/api/migrations`).
   - **Severe Gaps**: Lacks `@Version` optimistic locking; lacks linkage to `ReviewSession` and `MigrationPlan`; lacks granular track states (`UNCERTAIN`, `SKIPPED`, `ROLLING_BACK`, `ROLLED_BACK`); lacks rollback/reconciliation audit tables; coupled to unreviewed, direct migration.
2. **Phase 11 Model (`MigrationExecution` & `ExecutionTrackRecord`, schema `V6__phase11e_durable_execution_schema.sql`, `V7`, `V8`, `V9`)**:
   - Comprehensive domain aggregate in `migration_executions` and `execution_track_records` tables.
   - Binds to `ReviewSession` (Phase 10) and `MigrationPlan` (Phase 9).
   - Contains 8 execution statuses (`INITIALIZED`, `RUNNING`, `PARTIALLY_COMPLETED`, `COMPLETED`, `FAILED`, `RECOVERY_REQUIRED`, `ROLLED_BACK`, `PARTIALLY_ROLLED_BACK`).
   - Contains 8 track statuses (`PENDING`, `ADDING`, `ADDED`, `FAILED`, `UNCERTAIN`, `SKIPPED`, `ROLLING_BACK`, `ROLLED_BACK`).
   - Contains built-in atomic cross-instance claiming via PostgreSQL row-level conditional locking: `MigrationExecutionRepository.claimExecutionRunningWithVersion(...)`.
   - Built-in crash state reconciliation with 5-minute lease threshold (`DEFAULT_STALE_THRESHOLD`).
   - Linked to immutable audit records: `ExecutionRollbackRecord` (V8) and `ExecutionReconciliationRecord` (V9) with `ON DELETE RESTRICT`.

### 2. Actual Phase 11 Execution Contract

The actual Phase 11 execution contract is:
```java
ApprovedMigrationOrchestrator.executeApprovedMigration(ApprovedMigrationExecutionRequest request, UUID authenticatedUserId)
```
or by review session:
```java
ApprovedMigrationOrchestrator.executeApprovedMigration(UUID reviewSessionId, UUID authenticatedUserId)
```
and track insertion:
```java
MigrationExecutionStateService.executeTrackInsertion(UUID executionId, UUID authenticatedUserId)
```
The legacy method `PlaylistMigrationOrchestrator.executeMigration(UUID jobId)` belongs to Phase 8G and must NOT be used for Phase 11/12 approved migrations.

---

## Considered Alternatives

### Alternative 1: Redis / Celery / BullMQ External Queue
- **Description**: Introduce Redis as an external message broker with Celery/BullMQ or Redisson for task queuing and worker distribution.
- **Pros**: Distributed queue semantics; built-in job delay and rate limiting.
- **Cons**: Violates **ADR-001 (Modular Monolith)** and **ADR-007 (No Redis in Initial Architecture)**. Adds operational dependency, network serialization overhead, cross-system split-brain failure modes, and deployment complexity.
- **Verdict**: **REJECTED**.

### Alternative 2: RabbitMQ / Apache Kafka Message Broker
- **Description**: Introduce an AMQP message broker to handle asynchronous migration tasks.
- **Pros**: Enterprise-grade durability, dead-letter queues, horizontal scaling across independent microservices.
- **Cons**: Excessive operational complexity for the current modular monolith scale. Unnecessary infrastructure overhead when the PostgreSQL database already manages durable execution state and atomic row claiming.
- **Verdict**: **REJECTED**.

### Alternative 3: PostgreSQL-Backed Polling Table with Background Scheduler
- **Description**: Workers periodically poll PostgreSQL (`SELECT ... FOR UPDATE SKIP LOCKED`) every N seconds for unclaimed jobs.
- **Pros**: Durable job queue surviving application process crashes; decoupled from JVM memory.
- **Cons**: Introduces polling latency (1–5 seconds); constant DB query load even when idle; higher complexity than immediate in-memory dispatching.
- **Verdict**: **DEFERRED TO PHASE 13**. Valid future extension when multi-instance clustering or persistent restart recovery is required.

### Alternative 4: Selected Architecture — PostgreSQL Durable State + Spring Bounded TaskExecutor
- **Description**: Persist migration execution state in PostgreSQL (`migration_executions`), dispatch the job ID to a bounded Spring `ThreadPoolTaskExecutor`, claim atomically via PostgreSQL row locking, and execute Phase 11 orchestration logic in worker threads.
- **Pros**:
  - Zero external infrastructure dependencies (pure Spring Boot + PostgreSQL).
  - Immediate worker pickup (sub-millisecond latency).
  - PostgreSQL retains authoritative durable job state and atomic row-level claiming.
  - Bounded thread pool prevents CPU and database connection saturation.
  - Aligns cleanly with ADR-001, ADR-003, and ADR-030.
- **Verdict**: **ACCEPTED**.

---

## Architectural Decisions

```
+-----------------------------------------------------------------------------------------+
|                               PHASE 12 ASYNC ARCHITECTURE                               |
+-----------------------------------------------------------------------------------------+
                                                                                          
   Client Browser / API Consumer                                                          
         |                                                                                
         | 1. POST /api/migrations (or /execution/run-async)                              
         v                                                                                
+----------------------------------------------------+                                    
|            MigrationSubmissionController           |                                    
|  - Validates request & user authentication         |                                    
|  - Anti-IDOR: verifies review session ownership    |                                    
|  - Pre-mutation eligibility check (Phase 11B)      |                                    
|  - Idempotency: checks existing execution state    |                                    
|  - Persists MigrationExecution (status: QUEUED)    |                                    
+-------------------------+--------------------------+                                    
                          |                                                               
       Returns HTTP 202   | 2. Enqueues job execution                                     
       Accepted with      |    via MigrationJobDispatcher                                 
       migrationId &      v                                                               
       Location Header  +----------------------------------------------------+            
                        |            ThreadPoolTaskExecutor                  |            
                        |  - Core Pool: 2 | Max Pool: 4 | Queue: 50          |            
                        |  - Thread Prefix: "migration-worker-"              |            
                        +-------------------------+--------------------------+            
                                                  |                                       
                                                  | 3. Worker Thread claims               
                                                  v                                       
                        +----------------------------------------------------+            
                        |            MigrationBackgroundWorker               |            
                        |  - Invokes: claimExecutionRunningWithVersion()     |            
                        |  - Sets status: RUNNING                            |            
                        |  - Delegates to: ApprovedMigrationOrchestrator     |            
                        +-------------------------+--------------------------+            
                                                  |                                       
                     +----------------------------+----------------------------+          
                     | 4. Track Insertion Loop                                 |          
                     v                                                         v          
+---------------------------------------------+   +--------------------------------------+
|       Programmatic Short DB Transaction     |   |    External YouTube Provider Call    |
| - markAdding()                              |   | - POST /playlistItems                |
| - Out-of-transaction external call          |-->|   (OUTSIDE DB TRANSACTION)           |
| - markAdded() / markFailed() / markUncertain|   +--------------------------------------+
| - DB Connection returned immediately!       |                                           
+---------------------------------------------+                                           
                     |                                                                    
                     v 5. Final Release                                                   
+---------------------------------------------+                                           
| MigrationExecution.releaseExecution()       |                                           
| - COMPLETED / PARTIALLY_COMPLETED / FAILED  |                                           
+---------------------------------------------+                                           
                                                                                          
   Client Polling (every 1-2s):                                                           
   GET /api/migrations/{id}/progress                                                      
         |                                                                                
         v                                                                                
+---------------------------------------------+                                           
| Fast Lightweight DTO (Zero track record list)|                                           
| { "status": "RUNNING", "progress": 72.4% }  |                                           
+---------------------------------------------+                                           
```

### 1. Job Representation: Unification on `MigrationExecution`

We explicitly decide **NOT** to introduce a duplicate `MigrationJob` or `AsyncMigrationJob` entity.
- `MigrationExecution` is the authoritative domain entity. It represents durable execution, binds to `ReviewSession`, contains per-track execution records (`ExecutionTrackRecord`), and already possesses optimistic locking (`@Version`) and atomic claim methods (`claimExecutionRunningWithVersion`).
- The conceptual roadmap endpoint `POST /api/migrations` will map internally to an approved review session migration, returning the `executionId` as the primary identifier.
- The `MigrationExecutionStatus` enum will be updated to include `QUEUED` as an explicit initial state for asynchronous submission:
  ```text
  QUEUED -> RUNNING -> COMPLETED / PARTIALLY_COMPLETED / FAILED / RECOVERY_REQUIRED
  ```

### 2. Bounded Background Worker Engine (`ThreadPoolTaskExecutor`)

Spring Boot's `ThreadPoolTaskExecutor` will be configured specifically for migration workloads:

| Configuration Property | Proposed Value | Architectural Rationale |
|---|---|---|
| `corePoolSize` | `2` | Baseline worker threads ready to process concurrent migrations immediately. |
| `maxPoolSize` | `4` | Caps concurrent migrations to prevent YouTube API quota burst depletion and DB connection saturation. |
| `queueCapacity` | `50` | Bounded memory queue preventing OutOfMemoryError under burst submissions. |
| `threadNamePrefix` | `migration-worker-` | Identifiable thread naming for observability, logs, and stack traces. |
| `rejectedExecutionHandler` | `AbortPolicy` | Rejects submissions when queue is saturated, throwing `TaskRejectedException` mapped to HTTP 429 Too Many Requests. |
| `waitForTasksToCompleteOnShutdown` | `true` | Graceful shutdown: allows in-flight track insertions to finish cleanly. |
| `awaitTerminationSeconds` | `30` | Maximum wait time during JVM termination before forcing shutdown. |

### 3. Database Connection Protection & Short Transactions

A critical invariant established in Phase 11E and preserved in Phase 12:
- **NO database transaction is held during external YouTube API calls.**
- Each track insertion follows a discrete 3-step cycle:
  1. *Short DB Transaction (2–5ms)*: Claim track `status = ADDING`. Transaction commits; connection released to HikariCP pool.
  2. *External HTTP Call (200–500ms)*: WebClient/RestTemplate dispatches `POST /playlistItems` to YouTube. **Zero DB connections held.**
  3. *Short DB Transaction (2–5ms)*: Mark track `ADDED`, `FAILED`, or `UNCERTAIN`. Transaction commits; connection released.
- This ensures a 4-worker pool consumes database connections for less than 2% of total execution wall-clock time, completely avoiding connection starvation in HikariCP (default pool size: 10).

### 4. Progress Tracking Model & Mathematical Invariants

Progress reporting is derived directly from persisted track records rather than arbitrary elapsed-time estimates.

#### Semantics:
- `totalTracks`: Total approved tracks in the migration execution (`totalApprovedTracks`).
- `addedTracks`: Tracks in `ADDED` status (`successfulInsertions`).
- `failedTracks`: Tracks in `FAILED` status (`failedInsertions`).
- `uncertainTracks`: Tracks in `UNCERTAIN` status (`uncertainInsertions`).
- `skippedTracks`: Tracks in `SKIPPED` status (`skippedInsertions`).
- `processedTracks`: Sum of all settled/evaluated tracks:
  $$\text{processedTracks} = \text{addedTracks} + \text{failedTracks} + \text{uncertainTracks} + \text{skippedTracks}$$
- `progressPercentage`:
  $$\text{progressPercentage} = \begin{cases} 100.0 & \text{if } \text{status} = \text{COMPLETED} \\ 0.0 & \text{if } \text{totalTracks} = 0 \\ \min\left(100.0, \frac{\text{processedTracks} \times 100.0}{\text{totalTracks}}\right) & \text{otherwise} \end{cases}$$

#### Current In-Flight Track:
- `currentTrackPosition`: `targetPosition` of the track currently in `ADDING` status, or null if no track is in flight.
- `currentTrackTitle`: `sourceTitle` of the track currently in `ADDING` status.

---

## API Contracts

### 1. Asynchronous Migration Submission Layer

#### Endpoint:
`POST /api/migrations`  
*(Convenience alias: `POST /api/migrations/execution/run-async`)*

#### Request Body:
```json
{
  "reviewSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "targetPlaylistTitle": "My Migrated Rock Playlist",
  "targetPlaylistDescription": "Migrated from Spotify via App",
  "privacy": "PRIVATE"
}
```

#### Response:
`HTTP/1.1 202 Accepted`  
`Location: /api/migrations/d9f1e4a2-8b3c-4e5f-9a1d-7c2e5b8a0f3d/progress`

```json
{
  "migrationId": "d9f1e4a2-8b3c-4e5f-9a1d-7c2e5b8a0f3d",
  "reviewSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "status": "QUEUED",
  "message": "Migration request accepted and queued for background processing",
  "totalTracks": 87,
  "submittedAt": "2026-11-02T22:30:00Z"
}
```

### 2. Lightweight Progress Polling API

#### Endpoint:
`GET /api/migrations/{id}/progress`  
*(Convenience alias: `GET /api/migrations/execution/{id}/progress`)*

#### Response (While In Progress):
`HTTP/1.1 200 OK`

```json
{
  "migrationId": "d9f1e4a2-8b3c-4e5f-9a1d-7c2e5b8a0f3d",
  "reviewSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "status": "RUNNING",
  "destinationPlaylistId": "PL_target_youtube_playlist_id",
  "destinationPlaylistUrl": "https://music.youtube.com/playlist?list=PL_target_youtube_playlist_id",
  "totalTracks": 87,
  "processedTracks": 63,
  "addedTracks": 60,
  "failedTracks": 3,
  "uncertainTracks": 0,
  "skippedTracks": 0,
  "progressPercentage": 72.4,
  "currentTrack": {
    "targetPosition": 63,
    "sourceTitle": "Bohemian Rhapsody",
    "sourceArtist": "Queen",
    "status": "ADDING"
  },
  "startedAt": "2026-11-02T22:30:05Z",
  "completedAt": null,
  "lastUpdatedAt": "2026-11-02T22:30:45Z"
}
```

#### Response (When Completed):
`HTTP/1.1 200 OK`

```json
{
  "migrationId": "d9f1e4a2-8b3c-4e5f-9a1d-7c2e5b8a0f3d",
  "reviewSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "status": "COMPLETED",
  "destinationPlaylistId": "PL_target_youtube_playlist_id",
  "destinationPlaylistUrl": "https://music.youtube.com/playlist?list=PL_target_youtube_playlist_id",
  "totalTracks": 87,
  "processedTracks": 87,
  "addedTracks": 84,
  "failedTracks": 3,
  "uncertainTracks": 0,
  "skippedTracks": 0,
  "progressPercentage": 100.0,
  "currentTrack": null,
  "startedAt": "2026-11-02T22:30:05Z",
  "completedAt": "2026-11-02T22:31:12Z",
  "lastUpdatedAt": "2026-11-02T22:31:12Z"
}
```

---

## Concurrency, Claims, and Restart Limitations

### 1. Concurrency Controls
- **Cross-instance Worker Claiming**: When a background worker starts, it calls:
  ```sql
  UPDATE migration_executions 
  SET status = 'RUNNING', version = version + 1, updated_at = :now 
  WHERE id = :id AND status = 'QUEUED' AND version = :version;
  ```
  If another worker already claimed the row or the status changed, `rowsUpdated == 0`, and the worker terminates immediately.
- **Deduplication**: Submitting an execution for a review session that is already `QUEUED` or `RUNNING` returns `HTTP 409 Conflict`.

### 2. Restart and Crash Limitations (Phase 12 Scope)
- **In-Memory Queue Volatility**:
  - `ThreadPoolTaskExecutor` keeps queued tasks in JVM memory. If the server is restarted or crashes:
    - In-memory tasks in the queue are lost.
    - PostgreSQL entity remains in `QUEUED` status (or `RUNNING` if interrupted mid-track).
- **Stale State Reconciliation**:
  - If a job was `RUNNING` during a crash, `MigrationExecution.reconcileStaleCrashState()` uses the existing 5-minute lease threshold (`DEFAULT_STALE_THRESHOLD`) to classify abandoned `ADDING` tracks as `UNCERTAIN` and set execution status to `RECOVERY_REQUIRED`.
- **Phase 13 Roadmap Handoff**:
  - Full automatic recovery of queued jobs across server restarts (via database job polling or scheduled startup recovery scanner) will be implemented in **Phase 13 (Reliability Engineering)**.

---

## Phase 12B Persistence & Atomic Claiming Implementation

In Phase 12B, the persistence foundation for asynchronous execution was implemented and audited:

1. **`MigrationExecutionStatus.QUEUED`**:
   - Added as an explicit lifecycle state representing a persisted migration waiting for asynchronous dispatch.
   - **Resume Protection**: `canResume()` was explicitly kept `false` for `QUEUED`. In Phase 11, `canResume()` governs executions halted mid-stream (`PARTIALLY_COMPLETED` or `PARTIALLY_ROLLED_BACK`) or newly initialized for immediate synchronous insertion (`INITIALIZED`). Treating `QUEUED` as resumable would erroneously trigger synchronous track insertion on the HTTP thread if resume were invoked.
   - **Rollback Protection**: `canRollback()` was updated to return `false` for `QUEUED` executions since no destination mutations have been performed.
2. **Queue Initialization**:
   - `MigrationExecutionStateService.queueExecution(executionId, authenticatedUserId)`: Transitions existing `INITIALIZED` executions to `QUEUED`.
   - `MigrationExecutionStateService.initializeQueuedExecution(request, authenticatedUserId)`: Idempotently creates or transitions an execution directly into `QUEUED` status with approved track records.
   - Both operations enforce anti-IDOR user ownership via `ResourceNotFoundException`.
3. **Atomic Conditional Worker Claiming**:
   - `DefaultMigrationExecutionStateService.processInsertion` was updated so `eligibleStatuses` includes `MigrationExecutionStatus.QUEUED`.
   - The atomic update query in `MigrationExecutionRepository.claimExecutionRunningWithVersion`:
     ```sql
     UPDATE MigrationExecution m 
     SET m.status = RUNNING, m.updatedAt = :now, m.version = m.version + 1 
     WHERE m.id = :id AND m.status IN :eligibleStatuses AND m.version = :expectedVersion
     ```
     ensures cross-worker database-level atomicity. Concurrent claims by competing workers cleanly return 0 updated rows and throw `InvalidStateException` without dirtying state or executing remote mutations.
4. **Database Compatibility (Zero Flyway DDL Required)**:
   - Inspection of Flyway migration `V6__phase11e_durable_execution_schema.sql` confirmed that `migration_executions.status` is defined as `VARCHAR(50) NOT NULL DEFAULT 'INITIALIZED'`.
   - No database CHECK constraints exist on this column.
   - Therefore, adding the `QUEUED` enum literal in Java is fully backward- and forward-compatible, requiring zero Flyway DDL migrations.

---

## Phase 12C Background Worker Engine & TaskExecutor Implementation

In Phase 12C, the background processing engine and executor infrastructure were implemented and audited:

1. **`AsyncMigrationConfig`**:
   - Configures Spring `ThreadPoolTaskExecutor` bean `migrationTaskExecutor`.
   - Settings: `corePoolSize = 2`, `maxPoolSize = 4`, `queueCapacity = 50`, `threadNamePrefix = "migration-worker-"`, `rejectedExecutionHandler = new ThreadPoolExecutor.AbortPolicy()`.
   - Graceful shutdown: `waitForTasksToCompleteOnShutdown = true`, `awaitTerminationSeconds = 30`.
   - Thread pool behavior: Worker threads are started up to `corePoolSize` (2). Subsequent tasks are buffered in the bounded queue (capacity 50). Only when the queue is completely full does the pool expand up to `maxPoolSize` (4). Once maximum pool size and queue capacity are exhausted (54 concurrent in-flight and pending tasks), subsequent submissions trigger `AbortPolicy` throwing `TaskRejectedException`.

2. **Stage 1 Synchronous Preparation vs. Stage 2 Background Dispatch**:
   - `MigrationJobDispatcher` coordinates submission in two clean stages.
   - Stage 1 calls `ApprovedMigrationOrchestrator.prepareQueuedMigration(...)`: validates review session, creates YouTube destination playlist (fulfilling Flyway V6 `destination_playlist_id NOT NULL`), and persists `MigrationExecution` in `QUEUED` status.
   - Stage 2 submits a lightweight runnable `() -> migrationBackgroundWorker.processMigration(executionId, authenticatedUserId)` to `migrationTaskExecutor`.
   - Ensures the worker receives an already-persisted, valid `QUEUED` execution with destination playlist ID.

3. **QUEUED-Only Conditional Claiming**:
   - Added `executeQueuedTrackInsertion(executionId, authenticatedUserId)` on `MigrationExecutionStateService`.
   - Enforces `eligibleStatuses = Set.of(MigrationExecutionStatus.QUEUED)` when invoking `MigrationExecutionRepository.claimExecutionRunningWithVersion(...)`.
   - Guarantees that asynchronous workers can only claim `QUEUED` executions, preventing any accidental claim or state corruption of `INITIALIZED`, `PARTIALLY_COMPLETED`, or `RECOVERY_REQUIRED` executions.

4. **Executor Saturation & Orphan Prevention**:
   - When executor queue saturation causes `TaskRejectedException`, leaving an execution in `QUEUED` status would create an orphaned job with no scheduled worker.
   - Handled via `MigrationExecution.resetToInitialized()` invoked by `MigrationExecutionStateService.handleDispatchRejection(...)`.
   - Resets state from `QUEUED` back to `INITIALIZED`, preserving the created playlist ID and prepared track records for subsequent retry or Phase 13 durable dispatch.
   - Re-throws `TaskRejectedException` so Phase 12E REST controller can map directly to HTTP 429 Too Many Requests with appropriate `Retry-After`.

5. **Worker Delegation & Fault Isolation**:
   - `MigrationBackgroundWorker` delegates to `ApprovedMigrationOrchestrator.executeQueuedMigration(...)`.
   - Worker catches unexpected runtime exceptions and logs sanitized diagnostics without swallowing.
   - Does not overwrite execution state to `FAILED`, preserving Phase 11 recovery classifications (`RECOVERY_REQUIRED`, `PARTIALLY_COMPLETED`, `UNCERTAIN`).
   - Zero `@Transactional` on the worker level, ensuring Phase 11's discrete transaction strategy holds no DB connections during remote YouTube HTTP calls.

---

## Consequences

### Positive
1. **Zero HTTP Timeouts**: Long-running playlist migrations never block client HTTP threads.
2. **Deterministic Polling Progress**: REST polling retrieves lightweight, mathematically consistent progress.
3. **Database Protection**: Short transactions ensure database connections are released during external API network I/O.
4. **Architectural Continuity**: 100% reuse of Phase 11 validation, creation, insertion, recovery, rollback, and reconciliation logic. Zero code duplication.
5. **No External Infrastructure Overhead**: Eliminates Redis and message queue operational complexity while remaining production-grade.

### Negative / Trade-offs
1. **In-Memory Queue Volatility**: Process restarts drop in-memory pending tasks, requiring manual resume or Phase 13 startup recovery.
2. **Polling Overhead**: Frequent REST polling (e.g. 1 req/sec) generates light read traffic on PostgreSQL (mitigated by indexing on `migration_executions.id`).
