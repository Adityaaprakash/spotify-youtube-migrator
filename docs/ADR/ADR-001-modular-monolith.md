# ADR-001: Modular Monolith Architecture

## Context
The system requires integrating Spotify and YouTube. We need to avoid prematurely scaling into microservices while maintaining clean boundaries.

## Decision
We will use a Modular Monolith Architecture.

## Consequences
- Easier deployment (single artifact).
- Strongly bounded contexts enforced by package rules and future ArchUnit tests.
- Simplified operational overhead.
