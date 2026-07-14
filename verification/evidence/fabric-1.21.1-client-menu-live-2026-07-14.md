# Fabric 1.21.1 client-menu live evidence

Date: 2026-07-14

Environment: Windows 11, Java 21.0.11, Minecraft 1.21.1, Fabric Loader 0.16.10,
Fabric API 0.116.9+1.21.1, Fabric Loom 1.10.5, Gradle 8.14. The development
client profile set `soundCategory_master:0.0` before launch.

This is a post-v1.0.0 source validation. It does not alter the immutable v1.0.0
release artifact or its conformance certificate. The targeted post-v1 artifact
was `hosts/fabric/1.21.1/build/libs/lodestone-1.0.0.jar`:

| Check | Result |
| --- | --- |
| Targeted artifact | Build passed; SHA-256 `4C06ECB47CF0F6B16C6C13803E3DCB3DAFFF27D8D1B2189917FC994051750D08` (435,413 bytes). |
| Bundled contract | The artifact advertises a bounded 120,000 ms default for `minecraft.ui.click`, `minecraft.ui.key`, and `lodestone.ui.navigate`. |
| Authenticated MCP | A token-authenticated loopback session negotiated MCP `2025-11-25` with `observe,control-player` process permissions supplied to the client JVM. |
| Main-menu discovery | `minecraft.ui.state.read` returned complete `TitleScreen` state with 8 typed widgets and exactly one enabled, clickable `Singleplayer` widget. |
| Semantic menu route | Guarded `lodestone.ui.navigate` moved title screen → `SelectWorldScreen` → `CreateWorldScreen`. Every UI mutation used a fresh typed state snapshot and revision guard; no mutation was retried. |
| Partial-screen boundary | The world-list projection was `partial`, but it exposed exactly one enabled, clickable `Create New World` target. The selected target was revision-guarded; the later `CreateWorldScreen` projection was complete. |
| Fresh world | Modern 1.21.1 has two separately guarded English-labelled `Create New World` buttons: world list → configuration screen, then configuration screen → integrated world. The final action returned `handled: true` and `LevelLoadingScreen`; `lodestone.ui.wait` then returned `inWorld: true`. |
| In-world readback | `minecraft.player.state.read` returned `Player63` in `minecraft:overworld` at `(-33.5, 68, 26.5)`. |
| Real control/readback | `minecraft.player.look` set yaw `30`, pitch `15`; a subsequent player-state read returned exactly yaw `30`, pitch `15`. |
| Lifecycle and shutdown | The log records integrated-server start, 4,466 ms spawn preparation, `Player63 joined the game`, normal server stop, and overworld/nether/end chunk saves. The tracked Fabric client window received a normal close request; Gradle ended `BUILD SUCCESSFUL`. |

No Lodestone error, exception, or crash line was observed during launch, main-menu
control, fresh-world creation, in-world control, or clean shutdown.

## Scope boundary

This certifies this exact English-language vanilla menu route and one bounded
in-world client mutation on Fabric 1.21.1. A partial world-list projection is
not a blanket UI-automation claim: only its single visible, enabled target was
used. UI labels/layouts may differ with locale, mods, resource packs, or future
game versions. This does not certify broad menu automation, containers/NBT,
every client capability, or change the public v1.0.0 release claim.
