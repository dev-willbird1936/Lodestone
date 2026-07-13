# Capability model

Every feasible native operation is represented by a capability descriptor, including operations
that are currently unavailable or restricted. Capability IDs are stable, namespaced contracts;
MCP tool names are presentation details.

Kinds: `command`, `input`, `action`, `query`, `event`.

Availability: `available`, `unavailable`, `restricted`, `degraded`.

Required metadata includes adapter/game context, permissions, side effects, idempotency,
prerequisites, schemas, timeout, threading constraints, ordering guarantees, and documentation.

Commands are discovered from the live native command registry when supported. Inputs distinguish
logical key bindings, physical emulation, UI interaction, and server-authoritative operations.
