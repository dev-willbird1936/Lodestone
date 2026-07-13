# RCON live evidence

Date: 2026-07-11

## Profile

This is a loader-neutral Java-edition RCON profile. The Java transport and external MCP launcher
were tested against five different dedicated-server environments:

- Fabric Loader 0.15.11 / Minecraft Java 1.20.1
- NeoForge 21.1.211 / Minecraft Java 1.21.1
- Forge 14.23.5.2859 / Minecraft Java 1.12.2
- Forge 11.15.1.2318 / Minecraft Java 1.8.9
- Forge 10.13.4.1614 / Minecraft Java 1.7.10
- Java 21 runtime/build toolchain
- Gradle 8.14

The server passwords and MCP tokens were supplied through environment variables and are not part
of the evidence or logs.

## Build and transport checks

The following completed successfully:

```text
gradlew.bat :adapters:rcon:java:test --no-daemon
gradlew.bat :gateway:rcon-launcher:build --no-daemon
verification/legacy-forge-rcon-matrix.ps1
```

The RCON client authenticates with bounded deadlines, cancellation checks, packet-size limits,
output truncation, fresh connections per command, and no automatic retry after command dispatch.
The output is never parsed into native world/player state.

## MCP observations

Each target was exposed through a separate authenticated loopback launcher endpoint.

| Check | Fabric 1.20.1 | NeoForge 1.21.1 | Forge 1.12.2 / 1.8.9 / 1.7.10 |
| --- | --- | --- | --- |
| MCP initialize | HTTP 200; negotiated `2025-11-25` | HTTP 200; negotiated `2025-11-25` | HTTP 200; negotiated `2025-11-25` on all three |
| Capability discovery | `minecraft.command.rcon.execute=available` | `minecraft.command.rcon.execute=available` | Available on all three |
| Capability invoke | `list` returned `OK`, `transport=rcon`, `truncated=false` with `administer-server` | `list` returned `OK`, `transport=rcon`, `truncated=false` with `administer-server` | `list`, `time query day`, and `say` returned `OK` on all three with `administer-server` |
| Output contract | Unstructured server text | Unstructured server text | Unstructured server text |
| Bad token | HTTP 401 | HTTP 401 | HTTP 401 on all three |
| Shutdown | Launcher and server stopped; RCON/MCP ports closed | Launcher and server stopped; RCON/MCP ports closed | Launcher and server stopped; RCON/MCP ports closed on all three |

## Scope

This proves the RCON fallback transport and launcher across modern and Java 8-era Java loader
families. It does not
claim native world, player, inventory, entity, UI, client-input, event, or structured mutation
semantics through RCON. Those capabilities remain unavailable until a native or protocol-specific
adapter implements them.
