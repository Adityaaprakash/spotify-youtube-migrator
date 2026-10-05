# Development Rules

## General

1. Inspect relevant .ai context before modifying code.
2. Do not scan the entire repository unnecessarily.
3. Read the smallest relevant set of files required for the task.
4. Do not modify unrelated files.
5. Do not implement future phases.
6. Do not introduce dependencies without justification.
7. Prefer simple architecture over premature abstraction.

## Backend

- Java 22
- Spring Boot
- Maven
- Constructor injection
- No field injection
- DTOs at API boundaries
- Domain/provider separation
- Explicit transaction boundaries

## Database

- PostgreSQL
- Flyway migrations
- Never modify an already-applied migration
- Create a new migration for schema changes
- Foreign keys must be explicit
- Indexes must have justification

## External APIs

Never expose provider DTOs outside provider infrastructure.

Provider flow:

External API
→ Provider DTO
→ Mapper
→ Domain

## Errors

Never expose:

- access tokens
- refresh tokens
- provider secrets
- internal stack traces

## Testing

Every meaningful business behavior requires tests.

Do not create meaningless tests simply for coverage.

## Git

Keep commits focused.

Do not mix unrelated refactors with feature work.