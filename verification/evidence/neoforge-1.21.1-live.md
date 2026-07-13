# NeoForge 1.21.1 live evidence

Date: 2026-07-11

Environment: Windows 11, Java 21.0.11, Minecraft 1.21.1, NeoForge 21.1.211, NeoGradle 7.1.38,
Gradle 8.14.

## Launch evidence

Command:

```text
gradlew.bat :hosts:neoforge:mc1_21_1:runServer --no-daemon
```

Observed:

- NeoForge reported `Lodestone 0.1.0-SNAPSHOT (lodestone)` in the mod list.
- Lodestone loaded its authenticated loopback endpoint and generated a token file under
  the run configuration directory.
- The dedicated server generated a world and reached `Done` without a Lodestone loading error.

The development server was offline/insecure because the userdev test configuration is not a
production server profile. This is recorded as a test limitation, not a recommended deployment
setting.

## MCP checks

Authenticated requests used the token header without recording the token value.

| Check | Observed result |
| --- | --- |
| `initialize` | MCP `2025-11-25` negotiated successfully |
| manifest resource | 33 capability descriptors returned |
| `minecraft.command.discover` | `available`; native command tree returned 92 root children |
| `minecraft.world.block.read` at `(0,64,0)` | `ok`; block `minecraft:air` |
| `minecraft.command.execute` with default policy | `AUTHORIZATION_DENIED` |
| invalid token | HTTP 401 |
| lifecycle event subscription | bounded subscription created with buffer limit 8 |

This proves the startup, transport, authorization, resource, event-subscription, command-query,
and world-query slices. It does not prove player-present, client input/UI, world mutation,
container, or clean shutdown behavior; those remain open acceptance work.

## Host-boundary recheck

The loader entrypoint was re-run from the separate `hosts/neoforge/1.21.1` distribution after
splitting the pure adapter from the gateway composition. The host compiled the adapter source
into the single NeoForge mod module and staged dependency module descriptors safely for the Java
21 UserDev run.

| Check | Observed result |
| --- | --- |
| Host startup | Dedicated server reached `Done`; Lodestone announced `127.0.0.1:37821` |
| MCP initialize | HTTP 200; negotiated `2025-11-25` |
| Native entity query | `minecraft.entity.list` returned `OK` with bounded empty result (`limit=8`, `truncated=false`) |
| Native world write dry-run | `minecraft.world.blocks.write` returned `OK`, `validated=true`, `changedCount=0`, previous block `minecraft:air` |
| Invalid token | HTTP 401 |

The server process was terminated after the checks; a graceful command-driven shutdown remains
an open acceptance item for this host run.

The clean packaged-artifact dedicated-server acceptance row now passes, including command-driven
stop and port release; see `packaged-server-matrix-2026-07-11.md` for the exact external-install
test and final artifact hash.
