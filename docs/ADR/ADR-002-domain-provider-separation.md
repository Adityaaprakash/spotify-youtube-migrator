# ADR-002: Domain and Provider Separation

## Context
We must avoid leaking Spotify and YouTube DTOs into the core domain.

## Decision
Domain models must not depend on Provider SDKs/DTOs. Adapters will translate between domain models and provider models.

## Consequences
- The domain remains pure and agnostic of specific external vendors.
- Allows for easier changes if providers update their API contracts.
