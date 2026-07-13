# ADR 0002: Capabilities are negotiated at runtime

## Decision

Adapters publish a capability manifest with availability and restrictions at session start.

## Consequences

Missing worlds, player state, permissions, loader limitations, and version differences are visible
without false capability claims.
