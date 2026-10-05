# AI Development Instructions

You are working on the Spotify → YouTube Music Migrator.

Before starting any task:

1. Read .ai/PROJECT_CONTEXT.md
2. Read .ai/CURRENT_STATE.md
3. Read the context file relevant to the requested task.
4. Read relevant ADRs if the task affects architecture.
5. Inspect only the source files necessary for implementation.

Do NOT repeatedly scan the entire repository.

## Context priority

When making decisions, use this priority:

1. Current task instructions
2. .ai/AI_INSTRUCTIONS.md
3. .ai/CURRENT_STATE.md
4. .ai/ARCHITECTURE.md
5. Relevant domain/API/database/security context
6. ADRs
7. Existing implementation
8. General assumptions

If source code conflicts with CURRENT_STATE.md:

- inspect the source code
- determine the actual current implementation
- update CURRENT_STATE.md if necessary
- do not blindly trust stale context.

## Phase discipline

Current phase is defined in CURRENT_STATE.md.

Do not implement future phases unless explicitly requested.

If future architecture affects the current task:

- document the requirement
- create/update an ADR if appropriate
- do not prematurely implement the future feature.

## Before coding

Determine:

- which module is affected
- which context files are relevant
- which source files are actually required
- whether the change affects architecture
- whether tests are required

## After coding

Always:

1. Run relevant tests.
2. Run relevant static analysis.
3. Verify compilation/build.
4. Inspect the resulting diff.
5. Update CURRENT_STATE.md if the project state changed.
6. Update relevant .ai documentation if an architectural decision changed.

## Never

- expose secrets
- commit credentials
- bypass tests
- suppress errors just to make CI pass
- create meaningless abstractions
- implement future phases without permission
- modify unrelated files
- rewrite existing architecture without justification