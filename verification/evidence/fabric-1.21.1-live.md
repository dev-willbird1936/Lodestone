# Fabric 1.21.1 live evidence

Date: 2026-07-11

## Profile

- Minecraft Java 1.21.1
- Fabric Loader 0.16.10
- Fabric API 0.116.9+1.21.1
- Fabric Loom 1.10.5
- Java 21
- Gradle 8.14

## Build and launch

The following completed successfully:

```text
gradlew.bat :hosts:fabric:mc1_21_1:build --no-daemon
gradlew.bat --no-daemon :hosts:fabric:mc1_21_1:runServer
```

The packaged host jar contains `fabric.mod.json`, the `dev.lodestone.fabric` entrypoint and pure adapter,
the embedded Lodestone runtime/gateway, and Gson supplied by Minecraft. The development server loaded
Fabric API and Lodestone, created a dedicated 1.21.1 world, and reached the Minecraft `Done`
state.

## MCP observations

The authenticated loopback endpoint responded throughout the live test.

| Check | Observation |
| --- | --- |
| MCP initialize | HTTP 200; negotiated `2025-11-25` |
| Capability search | `minecraft.command.discover=available`; `minecraft.command.execute=restricted` |
| Native command discovery | Succeeded; 83 root command children returned |
| Native block read | `(0,64,0)` returned `minecraft:air` in the overworld |
| Default mutation policy | Command execution returned an error result under observe-only permissions |
| Explicit command authorization | With `LODESTONE_PERMISSIONS=administer-server`, `say lodestone-authorized-test` returned `OK` and `executed=true`; `communicate` alone is denied |
| Bad token | HTTP 401 |
| Clean shutdown | `stop` saved all dimensions; the Lodestone endpoint closed with the server |

## Scope

The clean packaged-artifact dedicated-server acceptance row also passes; see
`packaged-server-matrix-2026-07-11.md` for the exact external-install test.

This is representative integration evidence, not a full-product support claim. Player-present
tests, client input/UI, block-entity NBT, inventory/container operations, registry/schematic/vision
surfaces, mod-interoperability testing, and other loader/version rows remain open.
