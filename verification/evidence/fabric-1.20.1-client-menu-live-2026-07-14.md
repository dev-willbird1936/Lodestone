# Fabric 1.20.1 client menu live evidence

Date: 2026-07-14

Environment: Windows 11, Minecraft 1.20.1, Fabric Loader 0.15.11, Fabric API
0.92.2+1.20.1, Fabric Loom 1.10.5, Gradle 8.14, Java 21 Gradle daemon, and Java
17 for the Loom `runClient` process. The development profile's master volume was
set to `0.0` before launch.

This is a post-v1.0.0 source validation. It does not alter the immutable v1.0.0
release artifact claim.

## Client and MCP results

The real Loom client started its loopback MCP endpoint using Java 17 and reached
the title screen. The token was read locally for the test and is intentionally
not recorded here. An authenticated MCP session negotiated protocol
`2025-11-25`.

| Check | Result |
| --- | --- |
| Capability discovery | `minecraft.ui.state.read` was `available`; `minecraft.ui.click`, `minecraft.ui.key`, `minecraft.player.look`, and hotbar selection were correctly `restricted` pending `control-player` authorization. |
| Main-menu read | `TitleScreen` snapshot returned a typed, complete widget list. |
| Semantic menu mutation | `Singleplayer` was selected from the guarded snapshot. The request intentionally omitted `button`; the adapter defaulted it to left-click (`0`), returned `handled: true`, and readback reached `CreateWorldScreen`. |
| Fresh world | `Create New World` was selected from its guarded snapshot with `button` omitted. The mutation committed, exceeded the operation deadline while the integrated server initialized, and returned `OUTCOME_INDETERMINATE`; it was not retried. The retained client log proves `Starting integrated minecraft server version 1.20.1`, spawn preparation, `Player303 joined the game`, and a saved `New World`. |
| In-world read | A complete `PauseScreen` snapshot and `minecraft.player.state.read` returned the joined player, overworld position, full health/food, rotation, and selected hotbar slot. |
| Safety boundary | Subsequent mutations returned `CAPABILITY_QUARANTINED` because the world-opening operation had an indeterminate post-commit outcome. This default-deny guard prevented a duplicate world or unchecked follow-up action. |
| Shutdown | The tracked Minecraft window received its normal close request. The log contains `Saving chunks for level`, `Stopping server`, `Stopping!`, and Gradle `BUILD SUCCESSFUL`; the tracked process tree exited. |

No Lodestone crash was observed during client start, menu control, integrated-world
creation, or clean shutdown.

## Fix validated

`minecraft.ui.click` declares `button` as optional. The Fabric 1.18.2 and 1.20.1
client bridges now use `numberOrDefault(..., 0)` instead of requiring that field,
preserving the protocol's documented left-click default. The final targeted builds
produced:

| Host | Artifact SHA-256 |
| --- | --- |
| Fabric 1.18.2 | `2420401DA73BEAFC06E67CF6EE776258D897660A7C897081053C6771379D457D` |
| Fabric 1.20.1 | `5BE69F71FC44365B1171BAA85B695759022D8B8667755FD4B04CE814B413C69A` |

The Fabric 1.18.2 correction is packaging-verified here; this evidence does not
claim a new manual 1.18.2 client run.
