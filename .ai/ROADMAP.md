# Migration Roadmap

## Phase Boundary Rule
Never implement functionality belonging to a future phase unless explicitly requested.
Current phase must be completed and verified before progressing.
When a future requirement affects current architecture, document the architectural requirement but do not implement the future feature prematurely.

## Current Progress

### **Phase 0 — Architecture & Project Foundation** [COMPLETE]
- Directory structures, build systems, framework boilerplate, and ADRs established.

### **Phase 1 — Domain Model & API Contracts** [IN PROGRESS - BLOCKED]
- **Phase 1A/B/C**: [COMPLETE] Core Domain Model, Invariants, and Repositories built.
- **Phase 1D**: [COMPLETE] Application Service Contracts
- **Phase 1E/1F**: [COMPLETE] API DTOs and Validation/Error Contracts
- **Phase 1G**: [BLOCKED] Persistence & Migration Verification. Domain and Flyway schemas manually aligned (UNIQUE constraints, Nullability), but runtime validation blocked by lack of Docker/Testcontainers environment.

## Future Phases
- Phase 2 — Authentication & Identity
- Phase 3 — Spotify Integration
- Phase 4 — YouTube Integration
- Phase 5 — Track Normalization
- Phase 6 — Candidate Discovery
- Phase 7 — Matching Engine
- Phase 8 — Matching Evaluation & Benchmark
- Phase 9 — Migration Engine
- Phase 10 — Review & Correction
- Phase 11 — Playlist Creation
- Phase 12 — Async Migration & Progress
- Phase 13 — Reliability Engineering
- Phase 14 — Migration History
- Phase 15 — Frontend Product Layer
- Phase 16 — Security Hardening
- Phase 17 — Testing
- Phase 18 — Observability
- Phase 19 — Deployment