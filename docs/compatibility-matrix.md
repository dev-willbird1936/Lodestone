# Lodestone v1.0.0 compatibility matrix

> [!IMPORTANT]
> A green row is an exact release artifact, not a nearby build. It loaded a fresh world,
> accepted an authenticated MCP session, exercised its supported control slice, performed a real
> mutation with readback where that slice permits it, and shut down cleanly.

`verification/evidence/release-conformance-v1.0.0.json` binds every matrix row below to the
exact byte length and SHA-256 of its release upload, a source input snapshot, and checksummed
retained acceptance logs. The release assembler rejects an artifact whose source bytes no longer
match that evidence.

| Status | Meaning |
| --- | --- |
| ✅ | Release-certified exact artifact and fresh-world evidence |
| 🔄 | Planned or partial capability; not a release compatibility claim |
| ❌ | Intentionally unsupported for this release |

## Release-certified rows

| Status | Platform | Exact version / loader | Deployment | Scope |
| --- | --- | --- | --- | --- |
| ✅ | Fabric | 1.18.2 / Loader 0.14.25 | Mod + CurseForge profile | Native server MCP slice |
| ✅ | Fabric | 1.19.2 / Loader 0.14.25 | Mod + CurseForge profile | Native server MCP slice |
| ✅ | Fabric | 1.20.1 / Loader 0.15.11 | Mod + CurseForge profile | Native server MCP slice |
| ✅ | Fabric | 1.21.1 / Loader 0.16.10 | Mod + CurseForge profile | Native server MCP slice |
| ✅ | Fabric | 26.2 / Loader 0.19.3 / Java 25 | Mod + CurseForge profile | Dedicated-server MCP slice; Vulkan client rendering is not tested |
| ✅ | Quilt | 1.20.1 / Loader 0.29.2 | Fabric-compatibility profile | Fabric compatibility host, not a Quilt-native adapter |
| ✅ | Quilt | 1.21.1 / Loader 0.29.2 | Fabric-compatibility profile | Fabric compatibility host, not a Quilt-native adapter |
| ✅ | NeoForge | 1.21.1 / 21.1.211 | Mod + CurseForge profile | Native server MCP slice |
| ✅ | Forge | 1.16.5 / 36.2.42 | Mod + CurseForge profile | Native server MCP slice; isolated Java 17 build path |
| ✅ | Forge | 1.18.2 / 40.3.12 | Mod + CurseForge profile | Native server MCP slice |
| ✅ | Forge | 1.19.2 / 43.5.2 | Mod + CurseForge profile | Native server MCP slice |
| ✅ | Forge | 1.20.1 / 47.4.10 | Mod + CurseForge profile | Native server MCP slice |
| ✅ | Forge | 1.21.1 / 52.1.0 | Mod + CurseForge profile | Native server MCP slice |
| ✅ | Forge native bridge | 1.12.2 / 14.23.5.2859 / Java 8 | Mod + legacy bridge launcher | Bounded native legacy control slice |
| ✅ | Forge native bridge | 1.8.9 / 11.15.1.2318 / Java 8 | Mod + legacy bridge launcher | Bounded native legacy control slice |
| ✅ | Forge native bridge | 1.7.10 / 10.13.4.1614 / Java 8 | Mod + legacy bridge launcher | Bounded native legacy control slice |
| ✅ | RCON fallback | Forge 1.12.2 / 14.23.5.2859 | External launcher | Authenticated command-only bridge; gamerule mutation/query readback |
| ✅ | RCON fallback | Forge 1.8.9 / 11.15.1.2318 | External launcher | Authenticated command-only bridge; gamerule mutation/query readback |
| ✅ | RCON fallback | Forge 1.7.10 / 10.13.4.1614 | External launcher | Authenticated command-only bridge; gamerule mutation/query readback |
| ✅ | Paper | 1.21.1 / build 133 | Server plugin | Bukkit/Paper server slice |
| ✅ | Spigot | 1.21.1 / BuildTools `4344-Spigot-a759b62` | Server plugin | Bukkit-compatible server slice |
| ✅ | Folia | 1.21.4 / build 6 | Server plugin | Scheduler-aware global/region slice |

## Honest limits and next rows

| Status | Target | Reason |
| --- | --- | --- |
| 🔄 | Fabric 1.20.1 client menu control (post-v1 source) | Authenticated typed navigation reached a fresh world, and player look has exact readback. Bounded 120-second UI transition defaults prevent the observed 16.5-second world load from becoming an indeterminate mutation. Evidence supports the next Fabric patch artifact, not an alteration of v1.0.0. |
| 🔄 | Full cross-version client control and menu automation | Bounded client bridges exist on selected Fabric lines, but broad manual client acceptance is not certified. |
| 🔄 | Container/NBT, inventory/entity mutation, and broad event parity | Typed catalog entries remain unavailable or restricted until each adapter has a safe implementation and evidence. |
| 🔄 | More Minecraft versions and loaders | Each needs a separate adapter, final artifact, and its own row. |
| ❌ | Folia 1.21.1 | No official server build was available for the certified path. |
| ❌ | Bedrock | Java-edition architecture does not represent a Bedrock adapter. |

RCON deliberately exposes only `minecraft.command.rcon.execute`; it is not a native player,
inventory, UI, event, or structured-world adapter. CurseForge ZIPs are local, hash-validated
profiles. They are not claims of a manual CurseForge GUI import or public CurseForge distribution.
