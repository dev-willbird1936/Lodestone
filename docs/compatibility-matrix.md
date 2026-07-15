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
| ✅ | Fabric | 26.2 / Loader 0.19.3 / Java 25 | Mod + CurseForge profile | Ordered client flow plus bounded server/MCP slice; Vulkan client rendering is not tested |
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
| ✅ | NeoForge 1.21.1 client flow (post-v1 source) | Exact artifact passed the ordered fresh-client flow with KeepFocus: 106/106 main-menu assertions, 27/27 world assertions, focus-loss readback, and 6/6 clean-shutdown assertions. This evidence does not alter v1.0.0. |
| ✅ | Fabric 1.21.1 client flow (post-v1 source) | Exact artifact passed the ordered fresh-client flow: 97/97 main-menu assertions, 27/27 world assertions, and 6/6 clean-shutdown assertions. The fresh no-save route uses the direct `CreateWorldScreen` path; chat-read remains explicitly unavailable. This evidence does not alter v1.0.0. |
| ✅ | Fabric 1.20.1 client flow (post-v1 source) | Exact artifact passed the ordered fresh-client flow: 97/97 main-menu assertions, 27/27 world assertions, and 6/6 clean-shutdown assertions. The exact direct `singleplayer` → `CreateWorldScreen` route and unavailable chat-read state are asserted. This evidence does not alter v1.0.0. |
| ✅ | Fabric 1.19.2 client flow (post-v1 source) | Exact artifact passed the ordered fresh-client flow: 104/104 main-menu assertions, 27/27 world assertions including reversible mutation/readback, and 6/6 clean-shutdown assertions. The adapter uses Java 17, Loader 0.14.25, and detects whether vanilla opens `SelectWorldScreen` or `CreateWorldScreen`; chat-read remains explicitly unavailable. This evidence does not alter v1.0.0. |
| ✅ | Fabric 1.18.2 client flow (post-v1 source) | Exact artifact passed the ordered fresh-client flow: 104/104 main-menu assertions, 27/27 world assertions including reversible mutation/readback, and 6/6 clean-shutdown assertions. The adapter uses Java 17, Loader 0.14.25, and explicit legacy UI routing; chat-read remains explicitly unavailable. This evidence does not alter v1.0.0. |
| ✅ | Forge 1.21.1 client flow (post-v1 source) | Exact artifact passed the ordered fresh-client flow: 100/100 main-menu assertions, 15/15 minimal-world assertions including reversible block mutation/readback, and 6/6 clean-shutdown assertions. The first-run Forge loading-warning screen and exact unavailable registry/screenshot/input states are explicit evidence. This evidence does not alter v1.0.0. |
| ✅ | Forge 1.20.1 client flow (post-v1 source) | Exact artifact passed the ordered fresh-client flow: 100/100 main-menu assertions, 15/15 minimal-world assertions including reversible block mutation/readback, and 6/6 clean-shutdown assertions. The exact Forge 1.20.1 UI press/release path and unsupported text/legacy input states are explicit evidence. This evidence does not alter v1.0.0. |
| ✅ | Forge 1.21.1 Vibecraft furniture slice (post-v1 source) | Fresh exact-version client passed 102/102 main-menu assertions, 21/21 world assertions, bundled `simple_chair` dry-run, state-aware placement/readback/restoration, and 6/6 clean-shutdown assertions. Tested/final artifact SHA-256: `929a6a770d3da33ceb73c65e6536af07028dbf2a1fabca45e075779a7643e2f9`. |
| ✅ | Forge 1.20.1 Vibecraft furniture slice (post-v1 source) | Fresh exact-version client passed 102/102 main-menu assertions, 21/21 world assertions, bundled `simple_chair` dry-run, state-aware placement/readback/restoration, and 6/6 clean-shutdown assertions. The ForgeGradle userdev run used mapped test bytes (`73d0dce0859c94676445876e2e250efad398d19206d45fd58a94abaee5bef55e`); the separately built production reobf jar is `E8A27036EC9DE0DF58130C13BDAD3DD0EB94D01B487B1ECF8839BC7C2039178B`. |
| ✅ | Fabric 26.2 client flow (post-v1 source) | Exact Java 25 artifact passed the ordered fresh-client flow: 99 main-menu assertions, 27 world assertions including reversible block mutation/readback and look readback, and clean MCP shutdown with no fatal marker and zero Minecraft client processes. Heightmap/light/move/slot/chat-read remain explicit unsupported states. This evidence does not alter v1.0.0. |
| 🔄 | Full cross-version client control and menu automation | Bounded client bridges exist on selected Fabric and NeoForge lines, but broad manual client acceptance is not certified. |
| 🔄 | Container/NBT, inventory/entity mutation, and broad event parity | Typed catalog entries remain unavailable or restricted until each adapter has a safe implementation and evidence. |
| 🔄 | More Minecraft versions and loaders | Each needs a separate adapter, final artifact, and its own row. |
| ❌ | Folia 1.21.1 | No official server build was available for the certified path. |
| ❌ | Bedrock | Java-edition architecture does not represent a Bedrock adapter. |

RCON deliberately exposes only `minecraft.command.rcon.execute`; it is not a native player,
inventory, UI, event, or structured-world adapter. CurseForge ZIPs are local, hash-validated
profiles. They are not claims of a manual CurseForge GUI import or public CurseForge distribution.

## Periodic client-flow benchmark snapshot — 2026-07-15

These rows record post-v1 source evidence for exact local artifacts. The NeoForge profile has
KeepFocus installed alongside Lodestone; the Fabric profile is Lodestone-only. They do not rewrite
the immutable v1.0.0 release certificate; each report binds its exact artifact hash and source state.

| Status | Flow | Evidence |
| --- | --- | --- |
| ✅ | NeoForge 1.21.1 main menu → fresh world → authenticated MCP | 106 asserted records (56 OK plus expected unavailable/dry-run/route-guard states); Credits absence is asserted; dedicated event subscribe/poll/unsubscribe lifecycle |
| ✅ | NeoForge 1.21.1 real world control | 27/27 successful records; bounded gold-block write, readback, restore, readback; chat, screenshot, entity/player/world reads, key/mouse cleanup |
| ✅ | NeoForge 1.21.1 Vibecraft furniture slice | Fresh artifact benchmark passed 108 main-menu precondition records and 33/33 world records: bundled `simple_chair` dry-run, state-aware placement (`oak_stairs[facing=north,half=bottom]`), readback, restoration, and clean shutdown. Artifact SHA-256: `7caf8200c6d5a22cec7bcc702a7cbdbc1f31674101a07c8c8d59a7634e1d0fbd`. |
| ✅ | NeoForge 1.21.1 KeepFocus focus-loss readback | Tick advanced while Minecraft lost focus; remained in-world and did not pause |
| ✅ | NeoForge 1.21.1 MCP clean shutdown | MCP Escape opened PauseScreen, UI click saved/quitted to title, MCP quit game; Java count 0; no crash markers |
| ✅ | NeoForge 1.21.1 MCP typed coverage sweep | 32 tools, 49 capabilities, 315 typed/schema cases; schema-generated inputs are classified and unsupported states remained explicit |
| ✅ | NeoForge 1.21.1 container/menu control slice | Fresh exact artifact passed 108 main-menu records and 23 world records: raw inventory key dispatch opened `InventoryScreen`, revision/46-slot container read, revision-guarded empty-slot click, readback with the slot unchanged, close dispatch, and clean shutdown. Artifact SHA-256: `b5a23cd9bc1127f8679fb10fa3f186a565e525aed4767bed59595db630688bcc`. |
| ✅ | Fabric 1.20.1 main menu → fresh world → authenticated MCP | 97 asserted records; typed discovery, UI selectors, key/mouse cleanup, direct `CreateWorldScreen` navigation, text insertion, and fresh-world creation |
| ✅ | Fabric 1.20.1 real world control | 27/27 successful records; bounded gold-block write/readback/restore, player/entity/world reads, chat send, screenshot, and explicit unavailable chat-read state |
| ✅ | Fabric 1.20.1 Vibecraft furniture slice | Fresh artifact benchmark passed 100 main-menu precondition records and 33/33 world records: bundled `simple_chair` dry-run, state-aware placement (`oak_stairs[facing=north,half=bottom]`), readback, restoration, and clean shutdown. Artifact SHA-256: `66bc987a58744d3fd9bb322fa80656a615cd745242f08ae66a700ae3a7ae97f4`. |
| ✅ | Fabric 1.20.1 MCP clean shutdown | 6 asserted records; MCP Escape opened PauseScreen from the active world, Save & Quit returned to title, MCP quit game; Java count 0 and no crash markers |
| ✅ | Fabric 1.21.1 main menu → fresh world → authenticated MCP | 97 asserted records; typed discovery, UI selectors, key/mouse cleanup, direct fresh `CreateWorldScreen` navigation, text insertion, and fresh-world creation |
| ✅ | Fabric 1.21.1 real world control | 27/27 successful records; bounded gold-block write/readback/restore, player/entity/world reads, chat send, screenshot, and explicit unavailable chat-read state |
| ✅ | Fabric 1.21.1 Vibecraft furniture slice | Fresh artifact benchmark passed 100 main-menu precondition records and 33/33 world records: bundled `simple_chair` dry-run, state-aware placement (`oak_stairs[facing=north,half=bottom]`), readback, restoration, and clean shutdown. Artifact SHA-256: `dbe3b7e8c76c910349e339473fa950c26cc9d21fe6061a87e1ba4286e2b0cace`. |
| ✅ | Fabric 1.21.1 MCP clean shutdown | 6 asserted records; MCP Escape opened PauseScreen from the active world, Save & Quit returned to title, MCP quit game; Java count 0 and no crash markers |
| ✅ | Forge 1.21.1 main menu → fresh world → authenticated MCP | 100/100 asserted records; first-run Forge loading-warning dismissal, typed discovery, menu navigation, guarded selectors, and honest unavailable states |
| ✅ | Forge 1.21.1 real world control | 15/15 asserted records; bounded gold-block write/readback/restore plus player/entity/inventory reads and player look readback |
| ✅ | Forge 1.21.1 MCP clean shutdown | 6/6 asserted records; MCP Escape opened PauseScreen, Save & Quit returned to title, MCP quit game; Java count 0 and no fatal/crash markers |
| ✅ | Forge 1.21.1 Vibecraft furniture mutation/readback | 21/21 world records; known-air guard, preview-only validation, real `oak_stairs[facing=north,half=bottom]` placement, readback, restoration, and clean shutdown. Evidence: `forge-1.21.1-flow-{main-menu-20260715T030224Z,world-20260715T030304Z,shutdown-20260715T030345Z}.json` |
| ✅ | Forge 1.20.1 main menu → fresh world → authenticated MCP | 100 asserted records; exact Forge 47.4.10 artifact, typed discovery, menu selectors, fresh-world creation, and expected unavailable states |
| ✅ | Forge 1.20.1 real world control | 15/15 asserted records; bounded gold-block write/readback/restore plus player/entity/inventory reads and player look readback |
| ✅ | Forge 1.20.1 MCP clean shutdown | 6/6 asserted records; Escape/Pause, Save & Quit, MCP quit; Java 0 and no fatal/crash markers |
| ✅ | Forge 1.20.1 Vibecraft furniture mutation/readback | 21/21 world records; known-air guard, preview-only validation, real `oak_stairs[facing=north,half=bottom]` placement, readback, restoration, and clean shutdown. Evidence: `forge-1.20.1-flow-{main-menu-20260715T025229Z,world-20260715T025317Z,shutdown-20260715T025343Z}.json` |
| ✅ | Fabric 26.2 main menu → fresh world → authenticated MCP | 99 asserted records; Java 25/Loom 1.17.1 exact client, typed discovery, UI selectors, key/mouse cleanup, explicit world creation, and expected unsupported states |
| ✅ | Fabric 26.2 real world control | 27/27 asserted records; reversible gold-block mutation/readback, player look readback/restore, screenshot, and explicit heightmap/light/move/slot/chat-read unsupported states |
| ✅ | Fabric 26.2 MCP clean shutdown | 6/6 asserted records; MCP quit-game path, `Stopping!`, no fatal/crash marker, and zero Minecraft client processes |
| ✅ | Fabric 1.19.2 main menu → fresh world → authenticated MCP | 104/104 asserted records; Java 17 client runtime, screen-aware world creation, typed discovery, UI selectors, reversible mutation/readback, and expected unavailable states |
| ✅ | Fabric 1.19.2 MCP clean shutdown | 6/6 asserted records; MCP Escape/Pause, Save & Quit, MCP quit; Java 0 and no fatal/crash markers |
| ✅ | Fabric 1.18.2 main menu → fresh world → authenticated MCP | 104/104 asserted records; Java 17 client runtime, legacy SelectWorld/CreateWorld routing, typed discovery, UI selectors, reversible mutation/readback, and expected unavailable states |
| ✅ | Fabric 1.18.2 MCP clean shutdown | 6/6 asserted records; MCP Escape/Pause, Save & Quit, MCP quit; Java 0 and no fatal/crash markers |
| 🔄 | Other client loaders/versions | Requires the same ordered fresh-install flow and exact-version artifact evidence per row |
