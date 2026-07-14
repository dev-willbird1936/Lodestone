# Compatibility

Compatibility is measured per adapter and capability, never inferred from a nearby version.

| Tier | Meaning |
| --- | --- |
| planned | documented target only |
| builds | artifact compiles |
| contract-tested | shared capability suite passes |
| integration-tested | automated game integration evidence exists |
| manually verified | reproducible human acceptance evidence exists |

Initial target: NeoForge 1.21.1. Future adapter families are introduced only with a milestone,
native test plan, and capability evidence. The release-certified source of truth is
`verification/evidence/release-conformance-v1.0.0.json`; the historical C0 files below are not
used to certify v1.0.0 bytes.

## Current evidence

| Adapter | Game | Loader/toolchain | Support state | Evidence | Scope/caveat |
| --- | --- | --- | --- | --- | --- |
| NeoForge host + adapter | Minecraft Java 1.21.1 | NeoForge 21.1.211, NeoGradle 7.1.38, Gradle 8.14 | integration-tested (packaged matrix); ordered fresh-client flow verified post-v1 source | `verification/evidence/compatibility-live-2026-07-12.md`, `verification/evidence/neoforge-1.21.1-client-live.md`, and `verification/evidence/neoforge-keepfocus-flow-main-menu-20260714T183212Z.json` | Authenticated ordered flow passed with KeepFocus: 106 main-menu assertions, 27/27 world assertions, focus-loss tick readback, and clean MCP shutdown. Containers/NBT and broader mutation/event coverage remain open. This does not change immutable v1.0.0 bytes. |
| Fabric adapter | Minecraft Java 1.21.1 | Fabric Loader 0.16.10, Fabric API 0.116.9+1.21.1, Fabric Loom 1.10.5, Gradle 8.14 | integration-tested (packaged matrix); ordered fresh-client flow verified post-v1 source | `verification/evidence/compatibility-live-2026-07-12.md`, `verification/evidence/fabric-1.21.1-client-menu-live-2026-07-14.md`, and `verification/evidence/fabric-1.21.1-flow-main-menu-20260714T192759Z.json` | Fresh client flow passed 97/97 main-menu assertions, 27/27 world assertions, and 6/6 clean-shutdown assertions against Lodestone SHA-256 `1ad86d7d6b63f2f039eed75942873b9fe6df9dba7fd0dc52ed11c496282a5899`; chat-read remains an explicit unavailable capability. Containers/NBT and broader mod interoperability remain open. This does not change immutable v1.0.0 bytes. |
| Fabric adapter | Minecraft Java 1.20.1 | Fabric Loader 0.15.11, Fabric API 0.92.2+1.20.1, Fabric Loom 1.10.5, Gradle 8.14 | integration-tested (packaged matrix); ordered fresh-client flow verified post-v1 source | `verification/evidence/compatibility-live-2026-07-12.md`, `verification/evidence/fabric-1.20.1-client-menu-live-2026-07-14.md`, and `verification/evidence/fabric-1.20.1-flow-main-menu-20260714T191717Z.json` | Fresh client flow passed 97/97 main-menu assertions, 27/27 world assertions, and 6/6 clean-shutdown assertions against Lodestone SHA-256 `2e6c6b29aa95b33ada97e3f68064708ca54d9cf4f881d332738a87548cb7dd45`; chat-read remains an explicit unavailable capability. Containers/NBT and broader mod interoperability remain open. This does not change immutable v1.0.0 bytes. |
| Fabric adapter | Minecraft Java 1.19.2 | Fabric Loader 0.14.25, Fabric API 0.76.1+1.19.2, Fabric Loom 1.10.5, Gradle 8.14 | integration-tested (packaged matrix) | `verification/evidence/compatibility-live-2026-07-12.md`, `verification/curseforge-profiles/lodestone-fabric-1.19.2-local.zip` | Bounded server/world/MCP slice is live-tested; production JSON parsing avoids newer Gson-only static APIs; client input/UI/container snapshot/click, entity interaction, and text insertion compile/package |
| Fabric adapter | Minecraft Java 1.18.2 | Fabric Loader 0.14.25, Fabric API 0.77.0+1.18.2, Fabric Loom 1.10.5, Gradle 8.14, Java 17 | integration-tested (packaged matrix) | `verification/evidence/compatibility-live-2026-07-12.md`, `verification/curseforge-profiles/lodestone-fabric-1.18.2-local.zip` | Bounded server/world/MCP slice is live-tested; client input/UI/container snapshot/click, entity interaction, and text insertion compile/package; profile staging includes the exact Fabric API module dependency graph |
| Fabric adapter | Minecraft Java 26.2 | Fabric Loader 0.19.3, Fabric API 0.154.2+26.2, Fabric Loom 1.17.1, Gradle 9.6.1, Java 25 | integration-tested (packaged matrix); ordered fresh-client flow verified post-v1 source | `verification/evidence/fabric-26.2-live.md`, `verification/evidence/compatibility-live-2026-07-12.md`, `verification/evidence/fabric-26.2-flow-main-menu-20260714T205901Z.json`, `verification/evidence/fabric-26.2-flow-world-20260714T205928Z.json`, and `verification/evidence/fabric-26.2-flow-shutdown-20260714T210238Z.json` | Exact final artifact SHA-256 `8872c45706e5d019d8eec4ed62a66bf1d26e8dce5fcbdef320c88d46bdea9127`; client flow passes typed MCP/UI/input, fresh-world mutation/readback, look readback, screenshot, and clean shutdown. Heightmap/light/move/slot/chat-read remain honest unsupported states; Vulkan is not tested |
| Forge host + adapter | Minecraft Java 1.21.1 | Forge 52.1.0, ForgeGradle 6.0.24, Gradle 8.14, Java 21 | integration-tested (packaged matrix); ordered fresh-client flow verified post-v1 source | `verification/evidence/compatibility-live-2026-07-12.md`, `verification/curseforge-profiles/lodestone-forge-1.21.1-local.zip`, and `verification/evidence/forge-1.21.1-flow-main-menu-20260714T195824Z.json` | Fresh artifact flow passed 100/100 main-menu, 15/15 minimal-world mutation/readback, and 6/6 clean-shutdown assertions against Lodestone SHA-256 `86bf8ec7c5c2d414af10288bbc1d4205656eb0949294168f64b249095764d2b9`; first-run Forge loading warning and unsupported registry/screenshot/input states are explicit. |
| Forge host + adapter | Minecraft Java 1.20.1 | Forge 47.4.10, ForgeGradle 6.0.25, Gradle 8.14 | integration-tested (packaged matrix) | `verification/evidence/compatibility-live-2026-07-12.md` | Same bounded native slice; legacy Forge rows retain RCON fallback plus the documented Java 8 native bridges |
| Forge host + adapter | Minecraft Java 1.19.2 | Forge 43.5.2, ForgeGradle 6.0.25, Gradle 8.14, Java 17 | integration-tested (packaged matrix) | `verification/evidence/compatibility-live-2026-07-12.md`, `verification/curseforge-profiles/lodestone-forge-1.19.2-local.zip` | Same bounded native slice; older Gson/runtime compatibility is handled by explicit catalog construction and the long-standing Gson instance parser API |
| Forge host + adapter | Minecraft Java 1.18.2 | Forge 40.3.12, ForgeGradle 6.0.25, Gradle 8.14, Java 17 | integration-tested (packaged matrix) | `verification/evidence/compatibility-live-2026-07-12.md`, `verification/curseforge-profiles/lodestone-forge-1.18.2-local.zip` | Same bounded native slice; 1.18.2 command and chat APIs use their version-specific signatures |
| Forge host + adapter | Minecraft Java 1.16.5 | Forge 36.2.42, ForgeGradle 5.1, Gradle 7.6.4, Java 17 | integration-tested (packaged matrix) | `verification/evidence/compatibility-live-2026-07-12.md`, `verification/curseforge-profiles/lodestone-forge-1.16.5-local.zip`, `verification/stage-forge-165-server.ps1` | Same bounded native slice with 1.16.5 MCP mappings and rollback-safe writes; launch requires one `--add-exports` and one `--add-opens` flag encoded by the matrix |
| Forge native legacy bridge | Minecraft Java 1.12.2 | Forge 14.23.5.2859, ForgeGradle 3.x, Gradle 4.10.3, Java 8 | integration-tested (native bridge matrix) | `verification/evidence/forge-1.12.2-native-live.md` and `verification/legacy-forge-native-matrix.ps1` | Java 8-native mod plus Java 21 MCP launcher; legacy block writes may explicitly load their target chunk |
| Forge native legacy bridge | Minecraft Java 1.7.10 | Forge 10.13.4.1614, ForgeGradle 1.2-1.0.12, Gradle 4.10.3, Java 8 | integration-tested (native bridge matrix) | `verification/evidence/forge-1.7.10-native-live.md` and `verification/legacy-forge-native-matrix.ps1` | Java 8-native mod plus Java 21 MCP launcher; HTTP operations use the Forge server-tick bus; legacy block writes may explicitly load their target chunk |
| Forge native legacy bridge | Minecraft Java 1.8.9 | Forge 11.15.1.2318, ForgeGradle 2.1.3, Gradle 2.7, Java 8 | integration-tested (native bridge matrix) | `verification/evidence/forge-1.8.9-native-live.md` and `verification/legacy-forge-native-matrix.ps1` | Java 8-native mod plus Java 21 MCP launcher; legacy block writes may explicitly load their target chunk |
| Quilt compatibility | Minecraft Java 1.20.1 | Quilt Loader 0.29.2 with Fabric compatibility API 0.92.2+1.20.1 and generated compatibility-host metadata | integration-tested (packaged matrix) | `verification/evidence/compatibility-live-2026-07-12.md`, `verification/prepare-quilt-host.ps1`, and `verification/curseforge-profiles/lodestone-quilt-1.20.1-local.zip` | Reuses the Fabric 1.20.1 native adapter through Quilt's Fabric compatibility path; no separate Quilt-native adapter is claimed |
| Quilt compatibility | Minecraft Java 1.21.1 | Quilt Loader 0.29.2 with Fabric compatibility API 0.116.9+1.21.1 and generated compatibility-host metadata | integration-tested (packaged matrix) | `verification/evidence/compatibility-live-2026-07-12.md`, `verification/prepare-quilt-host.ps1`, and `verification/curseforge-profiles/lodestone-quilt-1.21.1-local.zip` | Reuses the Fabric 1.21.1 native adapter through Quilt's Fabric compatibility path; no separate Quilt-native adapter is claimed |
| Paper host + adapter | Minecraft Java 1.21.1 | Paper 1.21.1-133, Paper API 1.21.1-R0.1-SNAPSHOT, Java 21 | integration-tested (server-plugin matrix) | `verification/evidence/compatibility-live-2026-07-12.md`, `verification/evidence/paper-1.21.1-live.md`, and `verification/paper-server-matrix.ps1` | Bukkit/Paper server slice is live-tested; command discovery, connected-player semantics, and container/NBT automation remain open |
| Spigot host + adapter | Minecraft Java 1.21.1 | Spigot BuildTools `4344-Spigot-a759b62`, Spigot API 1.21.1-R0.1-SNAPSHOT, Java 21 | integration-tested (server-plugin matrix) | `verification/evidence/compatibility-live-2026-07-12.md`, `verification/evidence/spigot-1.21.1-live.md`, and `verification/spigot-server-matrix.ps1` | Bukkit-compatible server slice is live-tested; command discovery, connected-player semantics, container/NBT automation, and broader events/mutations remain open |
| Folia host + adapter | Minecraft Java 1.21.4 | Folia build 6, Folia API 1.21.4-R0.1-SNAPSHOT, Java 21 | integration-tested (server-plugin matrix) | `verification/evidence/compatibility-live-2026-07-12.md`, `verification/evidence/folia-1.21.4-live.md`, and `verification/folia-server-matrix.ps1` | Global/region/entity scheduler slice is live-tested; entity listing is explicitly unavailable; Folia 1.21.1 is not currently published by the official build service |
| RCON transport profile | Forge 1.7.10 / 1.8.9 / 1.12.2 | Java 21 standard-library transport; launcher module | integration-tested on the exact v1.0.0 launcher ZIP | `verification/evidence/release-conformance-v1.0.0.json` and `verification/legacy-forge-rcon-matrix.ps1` | Only authenticated command execution; the certified rows mutate and query `doDaylightCycle`; native player/UI semantics remain unavailable |
| Protocol/runtime/gateway | Loader-neutral | Java 21 | contract-tested | focused protocol, runtime, gateway, and contract tests passed on 2026-07-12 | Does not prove any native game behavior |

The NeoForge, Fabric, and Forge adapters' current implemented native slice is covered by Fabric 1.18.2/1.19.2/1.20.1/1.21.1/26.2 and Forge 1.16.5/1.18.2/1.19.2/1.20.1/1.21.1. The slice is
`minecraft.command.discover`, `minecraft.command.execute`, `minecraft.player.state.read`,
`minecraft.world.block.read`, bounded `minecraft.world.blocks.read`, bounded
`minecraft.world.region.scan`, the permission-gated bounded `minecraft.world.blocks.write`,
`minecraft.entity.list`, server-player `minecraft.inventory.read`, and permission-gated
`minecraft.chat.send`. Bulk reads, region scans, writes, chat, and loaded-entity queries are
live-tested on the native rows that advertise them; Folia and RCON explicitly omit entity listing.
The inventory path is compile-tested and reports
a structured adapter failure when no player is present. The native implementations compile across
the tested native rows, including Fabric 26.2's non-remapping Java 25 toolchain. Each live manifest still includes every catalog capability, marking
unimplemented operations as `unavailable` with a reason and permission-gated operations as
`restricted`. The Paper 1.21.1 and Spigot 1.21.1 adapters implement the server-side subset
exercised by their live matrices: command execution, player state/inventory queries, block reads,
bounded bulk reads and region scans, bounded writes, entity listing, and chat broadcast; command
discovery remains unavailable there. Spigot delegates to the shared Bukkit-compatible operation
slice while retaining a distinct loader descriptor and host artifact.
The Folia 1.21.4 adapter implements the same bounded server slice where safe,
using global-region scheduling for server-wide command/chat work, region scheduling
for world/block work, and entity scheduling for player work. Cross-region entity
listing is intentionally unavailable until it has a safe implementation.

All supported block-write adapters use reverse rollback plus an atomic commit boundary. They
reject an existing block entity before mutation because restoring only block state would discard
NBT; the matrices verify this fail-closed behavior, and Paper additionally verifies populated NBT
survives. Java 8 native bridges use the same transactional rule through their shared legacy module.
Wire-level contract tests enforce lowercase result statuses and required nullable capability fields.

## Reproducible profile

The manifests under `verification/curseforge-profiles/` are importable CurseForge modpack
manifests for the tested game/loader pairs. Stage the built JAR into each `overrides/mods/`
directory with the adjacent staging script before importing; the Fabric staging scripts also stage
the matching pinned Fabric API jar. Standalone Fabric manifests are exact-pinned; the Quilt profiles
use generated compatibility-host variants from `verification/prepare-quilt-host.ps1`. The 26.2
profile is a non-remapping Java 25 profile. Forge 1.16.5 has its own isolated Gradle 7.6.4 build,
official server staging script, Java 17 launch flags, and CurseForge profile. These
profiles are test infrastructure, not claims that CurseForge distribution or a real-world modpack
has been manually verified. Paper and Spigot use server-plugin matrix scripts because they are
server-plugin platforms rather than CurseForge mod-loader profiles.

Release evidence is hash-bound. `verification/assemble-v1-release.ps1` validates the exact
32-artifact source set against `verification/evidence/release-conformance-v1.0.0.json`, then
creates the upload manifest, checksums, provenance, and SPDX inventory. Assembly and verification
fail if any source artifact or profile ZIP differs from the certified byte binding.

Folia uses `verification/folia-server-matrix.ps1` for the same reason. Its adapter has a separate
scheduler implementation: global-region calls are used for server-wide command/chat work, region
scheduling for world/block work, and entity scheduling for player work.

Bedrock adapters are not claimed yet. Folia 1.21.1 remains pending because no official server build
is currently published; Folia 1.21.4 is integration-tested. Legacy Forge 1.8.9, 1.7.10, and
1.12.2 have native Java 8 bridge slices above plus RCON fallback. Quilt 1.20.1 and 1.21.1 are
tested Fabric compatibility rows rather than separate native adapters. RCON is a transport profile
rather than a native loader adapter, and its narrow scope is documented above.
