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
| Main-menu read | `TitleScreen` returned a typed complete widget list. The endpoint briefly appeared before a title screen existed; only idempotent state reads were retried until the screen was ready. |
| Semantic menu control | Guarded `lodestone.ui.navigate` advanced title screen → world list → `CreateWorldScreen`; each selection used fresh typed state and a snapshot revision. |
| Fresh world | The final guarded `Create New World` action loaded an integrated 1.20.1 world in 16,522 ms. The log proves server start, spawn preparation, `Player654 joined the game`, and saved `New World (1)`. |
| Long-action outcome | `minecraft.ui.click`, `minecraft.ui.key`, and `lodestone.ui.navigate` advertise a bounded 120,000 ms default. The 16.5-second world creation completed without `OUTCOME_INDETERMINATE` or mutation quarantine; an explicit earlier caller deadline still takes precedence. |
| In-world control/readback | `minecraft.player.look` set yaw `30` and pitch `15`; a subsequent `minecraft.player.state.read` returned exactly yaw `30`, pitch `15`. |
| Lifecycle honesty | A player capability may be temporarily unavailable during the world/player manifest refresh. No mutation was retried during that transition; after refresh, typed player state and the bounded look mutation were available. |
| Shutdown | The tracked Minecraft window received a normal close request. The log contains `Saving chunks for level`, `Stopping server`, `Stopping!`, and Gradle `BUILD SUCCESSFUL`; the tracked process tree exited. |

No Lodestone crash was observed during client start, menu control, integrated-world
creation, or clean shutdown.

## Fix validated

`minecraft.ui.click` declares `button` as optional. The Fabric 1.18.2 and 1.20.1
client bridges use `numberOrDefault(..., 0)`, preserving the documented primary
click default. The shared UI-transition contracts now use a bounded 120-second
default so normal integrated-world startup does not become an indeterminate
post-commit result. The final targeted Fabric 1.20.1 artifact is:

| Host | Artifact SHA-256 |
| --- | --- |
| Fabric 1.20.1 | `45AE98C99A6D6001D3D6E01C7F6575F54C8817285048D109A7A53EC18446DFB8` |

`ProtocolContractTest` covers all three advertised timeout values. Fabric 1.18.2
retains the optional-button correction but is not newly client-live-tested here.
