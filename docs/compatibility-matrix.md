# Compatibility matrix

This matrix separates product intent from support evidence. A row is not supported until its own
adapter artifact, contract run, and integration evidence exist. A nearby version or loader never
inherits another row's status.

## Current rows

| Family | Version/profile | State | Evidence |
| --- | --- | --- | --- |
| NeoForge | Minecraft Java 1.21.1 / NeoForge 21.1.211 | ✅ integration-tested (representative slice) | `verification/evidence/neoforge-1.21.1-live.md` |
| Fabric | Minecraft Java 1.21.1 / Fabric Loader 0.16.10 / Fabric API 0.116.9+1.21.1 | ✅ integration-tested (representative slice) | `verification/evidence/fabric-1.21.1-live.md` |
| Fabric | Minecraft Java 1.20.1 / Fabric Loader 0.15.11 / Fabric API 0.92.2+1.20.1 | ✅ integration-tested (representative slice) | `verification/evidence/fabric-1.20.1-live.md`, `verification/evidence/world-mutation-live.md`, `verification/evidence/native-query-live.md` |
| Fabric | Minecraft Java 1.19.2 / Fabric Loader 0.14.25 / Fabric API 0.76.1+1.19.2 | ✅ integration-tested (representative slice) | `verification/evidence/compatibility-live-2026-07-12.md`, `verification/curseforge-profiles/lodestone-fabric-1.19.2-local.zip` |
| Fabric | Minecraft Java 1.18.2 / Fabric Loader 0.14.25 / Fabric API 0.77.0+1.18.2 | ✅ integration-tested (representative slice) | `verification/evidence/compatibility-live-2026-07-12.md`, `verification/curseforge-profiles/lodestone-fabric-1.18.2-local.zip` |
| Fabric | Minecraft Java 26.2 / Fabric Loader 0.19.3 / Fabric API 0.154.2+26.2 | ✅ integration-tested (representative slice; Java 25, non-remapping) | `verification/evidence/compatibility-live-2026-07-12.md` |
| Forge | Minecraft Java 1.21.1 / Forge 52.1.0 | ✅ integration-tested (representative slice) | `verification/evidence/compatibility-live-2026-07-12.md` |
| Forge | Minecraft Java 1.20.1 / Forge 47.4.10 | ✅ integration-tested (representative slice) | `verification/evidence/compatibility-live-2026-07-12.md` |
| Forge | Minecraft Java 1.19.2 / Forge 43.5.2 | ✅ integration-tested (representative slice) | `verification/evidence/compatibility-live-2026-07-12.md`, `verification/curseforge-profiles/lodestone-forge-1.19.2-local.zip` |
| Forge | Minecraft Java 1.18.2 / Forge 40.3.12 | ✅ integration-tested (representative slice) | `verification/evidence/compatibility-live-2026-07-12.md`, `verification/curseforge-profiles/lodestone-forge-1.18.2-local.zip` |
| Forge | Minecraft Java 1.16.5 / Forge 36.2.42 | ✅ integration-tested (representative slice; isolated Gradle 7.6.4 / Java 17 path) | `verification/evidence/compatibility-live-2026-07-12.md`, `verification/curseforge-profiles/lodestone-forge-1.16.5-local.zip` |
| Forge native bridge | Minecraft Java 1.12.2 / Forge 14.23.5.2859 | ✅ integration-tested (Java 8 native bridge slice) | `verification/evidence/forge-1.12.2-native-live.md`, `verification/legacy-forge-native-matrix.ps1` |
| Forge native bridge | Minecraft Java 1.7.10 / Forge 10.13.4.1614 | ✅ integration-tested (Java 8 native bridge slice) | `verification/evidence/forge-1.7.10-native-live.md`, `verification/legacy-forge-native-matrix.ps1` |
| Forge native bridge | Minecraft Java 1.8.9 / Forge 11.15.1.2318 | ✅ integration-tested (Java 8 native bridge slice) | `verification/evidence/forge-1.8.9-native-live.md`, `verification/legacy-forge-native-matrix.ps1` |
| Quilt compatibility | Minecraft Java 1.20.1 / Quilt Loader 0.29.2 via Fabric compatibility-host variant | ✅ integration-tested (narrow native slice) | `verification/evidence/compatibility-live-2026-07-12.md`, `verification/prepare-quilt-host.ps1` |
| Quilt compatibility | Minecraft Java 1.21.1 / Quilt Loader 0.29.2 via Fabric compatibility-host variant | ✅ integration-tested (narrow native slice) | `verification/evidence/compatibility-live-2026-07-12.md`, `verification/prepare-quilt-host.ps1`, `verification/curseforge-profiles/lodestone-quilt-1.21.1-local.zip` |
| Paper | Minecraft Java 1.21.1 / Paper 1.21.1-133 / Paper API 1.21.1-R0.1-SNAPSHOT | ✅ integration-tested (representative server-plugin slice) | `verification/evidence/compatibility-live-2026-07-12.md`, `verification/evidence/paper-1.21.1-live.md`, `verification/paper-server-matrix.ps1` |
| Spigot | Minecraft Java 1.21.1 / BuildTools `4344-Spigot-a759b62` | ✅ integration-tested (representative server-plugin slice) | `verification/evidence/compatibility-live-2026-07-12.md`, `verification/evidence/spigot-1.21.1-live.md`, `verification/spigot-server-matrix.ps1` |
| Folia | Minecraft Java 1.21.4 / Folia build 6 | ✅ integration-tested (scheduler-aware server-plugin slice); 1.21.1 pending | `verification/evidence/compatibility-live-2026-07-12.md`, `verification/evidence/folia-1.21.4-live.md`, `verification/folia-server-matrix.ps1` |
| RCON transport | Forge 1.12.2 / 14.23.5.2859, 1.8.9 / 11.15.1.2318, 1.7.10 / 10.13.4.1614 | ✅ integration-tested (narrow command profile) | `verification/evidence/compatibility-live-2026-07-12.md`, `verification/evidence/rcon-live.md` |

Each green native/plugin row reached a fresh world using its final packaged artifact. Write-capable
rows applied and read back a bounded mutation, rejected replacement of an existing block entity,
and stopped without a crash report or retained test listener. CurseForge ZIPs are format-validated
test profiles; they have not been claimed as manually imported GUI/client acceptance. The final
source snapshot, release binaries, launchers, and profile ZIPs are locked by
`verification/evidence/release-artifacts-2026-07-12.json` and checked with
`verification/release-artifact-manifest.ps1 -Mode Verify` around the live matrix.

## Candidate coverage backlog

These are planned compatibility targets, not support claims. Each requires a dedicated adapter
directory only when implementation begins.

| Family | Candidate targets | Required native test focus |
| --- | --- | --- |
| NeoForge | 1.21.x release lines after 1.21.1 | lifecycle/API drift, client input/UI, server commands, world and registry access |
| Fabric | later 1.21.x and 1.20.1 patch variants | Fabric Loader/API lifecycle, client hooks, server thread model |
| Quilt | later 1.21.x lines where Quilt/Fabric compatibility is available | Quilt lifecycle and compatibility-layer behavior |
| Forge | supported 1.21.x lines | Forge event/lifecycle and mappings differences |
| Paper | later 1.21.x lines | Bukkit/Paper API drift, permissions, entity/world APIs |
| Spigot | later supported 1.21.x lines | Bukkit-compatible subset and server-side limitations |
| Folia | 1.21.1 when an official server build is available; later 1.21.x lines | region-thread scheduling and cross-region safety |
| Bedrock | separate integration profile | edition-specific protocol/authentication; never represented as Java-native capability |

The current native coverage includes NeoForge 1.21.1, Fabric 1.20.1/1.21.1/1.19.2/1.18.2/26.2, Forge 1.21.1/1.20.1/1.19.2/1.18.2/1.16.5,
Paper 1.21.1, Spigot 1.21.1, Folia 1.21.4, and Quilt 1.20.1/1.21.1 compatibility rows. CurseForge import profiles are created per concrete Java
loader/version pair; current profiles are the checked-in directories under
`verification/curseforge-profiles/` for Fabric 1.18.2/1.19.2/1.20.1/1.21.1/26.2,
Forge 1.16.5/1.18.2/1.19.2/1.20.1/1.21.1, NeoForge 1.21.1, and Quilt 1.20.1/1.21.1.
