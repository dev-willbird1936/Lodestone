# Native entity and inventory query evidence

Date: 2026-07-11

## Profile

- Minecraft Java 1.20.1
- Fabric Loader 0.15.11
- Fabric API 0.92.2+1.20.1
- Fabric Loom 1.10.5
- Java 21 runtime/build toolchain

The entity-list and server-player inventory handlers compile in Fabric 1.20.1, Fabric 1.21.1,
and NeoForge 1.21.1. This live run used the separate Fabric 1.20.1 host distribution and a
headless dedicated server with no connected player.

## MCP observations

| Check | Observation |
| --- | --- |
| Entity capability | `minecraft.entity.list` became `available` after world startup |
| Bounded entity query | `limit=16`, `includePlayers=false` returned `OK`, 16 entities, `truncated=true` |
| Inventory capability | `minecraft.inventory.read` is implemented and available only when a matching server player exists |
| No-player behavior | Inventory invocation returned an adapter error explaining that no matching server player was available |
| Threading | Both handlers dispatch through the Minecraft server executor |
| Shutdown | Server saved all dimensions and the authenticated gateway closed cleanly |

## Scope

Entity results cover loaded overworld entities only and do not load chunks. Inventory results are
server-player inventory slots; client-only screens and open-container projections remain separate.
No player-connected manual acceptance was possible in this headless run.
