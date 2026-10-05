# ADR-006: API / Domain Separation

## Context
It is a common anti-pattern in Spring applications to use JPA Entities as `@RequestBody` or `@ResponseBody` in web controllers. This breaks the encapsulation of the domain, inevitably leaks infrastructure-specific logic (e.g., lazy loading exceptions, hidden database IDs), and ties the frontend API contract to the relational database schema.

## Decision
We enforce strict separation between HTTP/API DTOs, Application services, Domain models, and Persistence models.

**Expected Flow Inbound:**
HTTP Request → API Request DTO → Application Service → Domain Model → Infrastructure

**Expected Flow Outbound:**
Domain Model → API Response Mapper → API Response DTO → HTTP Response

## Consequences

**Rules:**
- Controllers MUST NOT contain business logic.
- Controllers MUST NOT directly invoke repositories.
- API request/response objects MUST NOT automatically become domain entities.
- Any modifications to the database schema (such as adding an internal column) will not affect the API payload unless consciously mapped in the UI tier DTO.
- Added boilerplate for mapping will be mitigated using controlled conversion functions (or MapStruct).
