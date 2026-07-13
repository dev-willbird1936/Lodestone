# Lodestone implementation specification

## Objective

Build a standalone MIT-licensed Minecraft MCP and control platform. Cover every feasible native
command, input, action, query, and event through capability-negotiated adapters. Do not claim a
single universal Minecraft binary.

Primary milestone: NeoForge 1.21.1. Covered families now include Fabric, Quilt, Forge, Paper,
Spigot, Folia, and RCON; future families include a distinct Bedrock integration path and later
Folia version lines.

## Non-negotiable rules

- Implement from this specification and official platform documentation.
- Keep all native access behind version-specific adapters.
- Do not silently omit unsupported native operations; report declared capability state.
- Do not expose secrets in errors, logs, snapshots, fixtures, or MCP responses.
- Default-deny mutations and non-loopback networking.
- Add future adapter directories only when their work starts.

## Module contracts

### protocol-model

Immutable language-level models for handshakes, manifests, capabilities, requests, results,
events, errors, versions, permissions, and availability. No Minecraft, loader, transport, or MCP
dependency.

### adapter-api

Loader-neutral service-provider contracts. Required adapter services: lifecycle, discovery,
capability invocation, cancellation, state snapshots, event source, and health reporting.

### runtime-core

Session registry, adapter selection, manifest aggregation, authorization, dispatch, timeout,
cancellation, event backpressure, audit metadata, structured errors, and transport-neutral state.

### mcp-server

MCP initialization, tool/resource/prompt presentation, dynamic capability discovery, typed generic
invocation, subscriptions/polling, structured outputs, and disconnect recovery. No native imports.

### NeoForge 1.21.1 adapter

Use supported lifecycle APIs, marshal native work to correct threads, distinguish client,
integrated-server, and dedicated-server contexts, and publish dynamic availability. Degrade
cleanly when a world, player, connection, screen, or permission is absent.

## Protocol requirements

Use JSON Schema 2020-12 for handshake, capability, manifest, request, result, event, and error;
the current embedded validator deliberately implements the documented protocol subset and must
reject unsupported constraints rather than silently treating them as enforced.
Every request carries protocol version, request ID, session ID, capability ID, input, deadline,
and optional idempotency key/dry-run flag. Every result is `ok`, `error`, `cancelled`, or
`timed-out` and uses structured errors.

Use independent version axes: Lodestone protocol, capability contract, adapter artifact,
Minecraft edition/version, loader/platform, and negotiated MCP revision. Tolerate unknown optional
fields. Reject incompatible required capability versions without reinterpretation.

## Capability requirements

Each descriptor contains:

- stable namespaced ID and kind;
- version, stability, availability, and reason;
- adapter, game, loader, environment, and session context;
- input, output, and event schemas;
- permissions, side effects, idempotency, prerequisites, threading, rate limits, timeout, and
  ordering guarantees;
- documentation and feature flags.

Core namespaces: `lodestone.system.*`, `minecraft.command.*`, `minecraft.input.*`,
`minecraft.player.*`, `minecraft.world.*`, `minecraft.inventory.*`, `minecraft.entity.*`,
`minecraft.ui.*`, `minecraft.chat.*`, `minecraft.server.*`, and `minecraft.event.*`.

Live native command registries drive command discovery. Distinguish logical key-binding state,
physical input emulation, UI interactions, and server-authoritative actions. Queries document
snapshot consistency. Events document source, filtering, sequence, buffering, overflow, replay,
and delivery semantics.

## MCP requirements

Expose system status, capability list/get/search, generic typed invocation, event
subscribe/unsubscribe/poll, and resources for manifests and snapshots. Stable high-value
capabilities may add convenience tools, but generic invocation remains canonical.

Do not make MCP tool names the long-term capability identity. Large artifacts use resources or
referenced files instead of oversized inline payloads.

## Security requirements

Permission classes: `observe`, `communicate`, `control-player`, `modify-world`,
`administer-server`, `manage-files-or-processes`.

Require bounded payloads, rate limits, deadlines, cancellation, session isolation, explicit
non-loopback opt-in, replaceable authentication, and structured audit records. Mark destructive,
retryable, idempotent, and open-world side effects accurately.

## Test requirements

1. Schema validation, valid/invalid fixtures, version compatibility, and stable IDs.
2. Shared adapter contract suite against fake and real adapters.
3. Runtime dispatch, authorization, cancellation, concurrency, backpressure, and errors.
4. MCP initialization, discovery, structured outputs, notifications, cancellation, and reconnect.
5. NeoForge integration: no-world startup, join/leave, integrated server, command discovery,
   player loss, reload, shutdown, and reconnect.
6. Manual real-game acceptance with a recorded manifest and representative query/action/input/
   command/event evidence.

## Delivery order

1. Keep this scaffold green.
2. Implement protocol model and schema conformance tests.
3. Implement adapter API and contract harness.
4. Implement runtime core.
5. Implement MCP gateway.
6. Implement NeoForge 1.21.1 vertical capability slices.
7. Record real-game evidence and release protocol v1.
8. Add later adapters only after the shared abstraction has proved necessary.

## Initial release definition of done

- reproducible build and clean repository hygiene;
- validated schemas, catalog, and fixtures;
- passing adapter contract suite;
- working MCP gateway;
- real-game NeoForge 1.21.1 verification;
- every exposed native operation mapped to capability or explicit unavailable/restricted state;
- documented and tested security defaults;
- compatibility claims backed by recorded evidence.
