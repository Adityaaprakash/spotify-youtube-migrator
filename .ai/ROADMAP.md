# Migration Roadmap

## Phase Boundary Rule
Never implement functionality belonging to a future phase unless explicitly requested.
Current phase must be completed and verified before progressing.
When a future requirement affects current architecture, document the architectural requirement but do not implement the future feature prematurely.

## Current Progress

### **Phase 0 — Architecture & Project Foundation** [COMPLETE]
- Directory structures, build systems, framework boilerplate, and ADRs established.

### **Phase 1 — Domain Model & API Contracts** [IMPLEMENTATION COMPLETE BUT RUNTIME VERIFICATION BLOCKED]
- **Phase 1A/B/C**: [COMPLETE] Core Domain Model, Invariants, and Repositories built.
- **Phase 1D**: [COMPLETE] Application Service Contracts
- **Phase 1E/1F**: [COMPLETE] API DTOs and Validation/Error Contracts
- **Phase 1G**: [IMPLEMENTATION COMPLETE / BLOCKED] Persistence & Migration Verification statically verified; runtime testing blocked by Docker environment limits.
- **Phase 1H**: [COMPLETE] Domain / API Testing via Mockito and WebMvcTest.
- **Phase 1I/1J**: [COMPLETE / FROZEN] Architecture Audited and Code/Functional Contracts Frozen.

### **Phase 2 — Authentication & Identity** [IMPLEMENTATION COMPLETE BUT RUNTIME VERIFICATION BLOCKED]
- **Phase 2A-2C**: [COMPLETE] Application Identity, Authentication, and OAuth Infrastructure.
- **Phase 2D-2F**: [COMPLETE] Provider Adapters (Spotify/Google) and encrypted Token Lifecycle logic.
- **Phase 2G-2H**: [COMPLETE / FROZEN] Security hardening for sessions, state consumption race conditions, IDOR, and full unit test execution.

### **Phase 3 — Spotify Integration** [IN PROGRESS]
- **Phase 3A-3C**: [COMPLETE] Spotify API Foundation, Identity retrieval, and Paged Playlist Discovery implementation.
- **Phase 3D**: [NOT STARTED] Spotify Track Retrieval.

## Future Phases
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