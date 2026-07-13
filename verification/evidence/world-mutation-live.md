# Native world mutation live evidence

Date: 2026-07-11

## Profile

- Minecraft Java 1.20.1
- Fabric Loader 0.15.11
- Fabric API 0.92.2+1.20.1
- Fabric Loom 1.10.5
- Java 21 runtime/build toolchain

The same bounded write implementation compiles in the Fabric 1.21.1 and NeoForge 1.21.1
adapter projects; this document records the live mutation run against the 1.20.1 server.

## MCP observations

The authenticated loopback gateway negotiated MCP `2025-11-25`.

| Check | Observation |
| --- | --- |
| Capability state | `minecraft.world.blocks.write=restricted`; descriptor requires `modify-world` |
| Default deny | Observe-only invocation returned `AUTHORIZATION_DENIED`; no write was dispatched |
| Dry-run | With `modify-world`, one `minecraft:air` → `minecraft:stone` change validated with `changedCount=0` |
| Mutation | With `modify-world`, the same change returned `OK`, `changedCount=1`, previous block `minecraft:air` |
| Read-back | `minecraft.world.block.read` returned `minecraft:stone` at the target position |
| Bounds/shape | Implementation validates 1–64 unique changes, block IDs, world bounds, and overworld-only scope before mutation |
| Shutdown | Server saved all dimensions and the authenticated gateway closed cleanly |

## Scope

This is a bounded block-state write slice. It intentionally does not support block entity NBT,
non-overworld dimensions, bulk artifacts, inventory/entity mutation, client input/UI, or arbitrary
process/file control. Those remain separate capability milestones.
