# ADR-008: Repository Architecture

## Context
There is debate in modular monoliths and domain-driven design communities on whether to use Spring Data JPA repositories directly via application services (Option A), or to define domain-level Repository Interfaces and implement them via Adapters (Option B - Ports and Adapters). 

Option B insulates the domain completely from Spring and JPA dependencies, but drastically increases boilerplate.

## Decision
We will use **Option A: Direct Spring Data JPA Repositories**.

## Consequences
- The domain models will contain JPA annotations (`@Entity`, `@Column`, etc.), and the application layer will depend directly on interfaces extending `JpaRepository`.
- **Benefits**:
  - Significant reduction in boilerplate (no duplicated domain vs. entity classes mapping back and forth for simple CRUD).
  - Out-of-the-box support for Spring Data features like Pagination and custom queries.
- **Tradeoffs**:
  - The domain is technically coupled to JPA. If we ever switch entirely from relational persistence to a NoSQL datastore, we must rewrite the domain entities. 
  - Given the decision in ADR-003 to commit firmly to PostgreSQL, this tradeoff is acceptable and preferred for velocity.
