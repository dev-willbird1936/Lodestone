# NeoForge 1.21.1 client-menu live evidence

Date: 2026-07-14

Environment: Windows 11, Java 21.0.11, Minecraft 1.21.1, NeoForge 21.1.211,
NeoGradle 7.1.38, Gradle 8.14. The development-client profile set
`soundCategory_master:0.0` before launch.

This is a post-v1.0.0 source validation. It does not alter the immutable v1.0.0
release artifact or its conformance certificate. The targeted post-v1 artifact
was `hosts/neoforge/1.21.1/build/libs/lodestone-1.0.0.jar`:

| Check | Result |
| --- | --- |
| Targeted artifact | Build passed; SHA-256 `5540D14073718EF3B011B45D87F82A04B130A65663EB09FE5F585E45A78D3FF0` (431,272 bytes). |
| Bundled contract | The artifact advertises a bounded 120,000 ms default for `minecraft.ui.click`, `minecraft.ui.key`, and `lodestone.ui.navigate`. |
| Authenticated MCP | A token-authenticated loopback session negotiated MCP `2025-11-25` with only `observe,control-player` process permissions. |
| Main-menu discovery | `minecraft.ui.state.read` returned complete `TitleScreen` state with 9 typed widgets and exactly one enabled, clickable `Singleplayer` widget. |
| Semantic menu route | Guarded `lodestone.ui.navigate` moved through title screen → world list → `CreateWorldScreen`. Every UI mutation used a fresh typed state snapshot and revision guard; no mutation was retried. |
| Fresh world | Modern 1.21.1 has two separately guarded English-labelled `Create New World` buttons: world list → configuration screen, then configuration screen → integrated world. The final action returned `handled: true` and `LevelLoadingScreen`; `lodestone.ui.wait` then returned `inWorld: true`. |
| In-world readback | `minecraft.player.state.read` returned `Dev` in `minecraft:overworld` at `(-9.5, 72, 10.5)`. |
| Real control/readback | `minecraft.player.look` set yaw `30`, pitch `15`; a subsequent player-state read returned exactly yaw `30`, pitch `15`. |
| Lifecycle and shutdown | The log records integrated-server start, 3,459 ms spawn preparation, `Dev joined the game`, normal server stop, and overworld/nether/end chunk saves. The tracked `forgeclientdev` window received a normal close request; Gradle ended `BUILD SUCCESSFUL`. |

No Lodestone error, exception, or crash line was observed during launch, main-menu
control, fresh-world creation, in-world control, or clean shutdown.

## Scope boundary

This certifies this exact English-language vanilla menu route and one bounded
in-world client mutation on NeoForge 1.21.1. UI labels are locale-dependent; menu
layouts may differ with mods, resource packs, or future game versions. It does not
certify broad menu automation, containers/NBT, every client capability, or change
the public v1.0.0 release claim.
