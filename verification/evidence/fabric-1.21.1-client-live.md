# Fabric 1.21.1 client live evidence

Date: 2026-07-11

Environment: Windows 11, Java 21.0.11, Minecraft 1.21.1, Fabric Loader 0.16.10,
Fabric API 0.116.9+1.21.1, Gradle 8.14.

The real Fabric client was launched with Loom `runClient` and quick-played the
`LodestoneClientWorld` save. The log reached:

```text
Player404 joined the game
```

No Lodestone crash occurred during startup, integrated-server creation, world join,
or the MCP control calls below. The test endpoint was loopback-authenticated; the
token value is intentionally not recorded.

| Check | Result |
| --- | --- |
| MCP initialize | negotiated `2025-11-25` |
| `minecraft.player.state.read` | `OK`; player identity and position returned |
| `minecraft.player.look` | `OK`; yaw `45`, pitch `10` applied |
| `minecraft.player.move` | `OK`; bounded movement state applied |
| `minecraft.input.key.set` | `OK`; logical forward key accepted; invalid `key.keyboard.w` was rejected without a crash |
| `minecraft.input.mouse.set` | `OK`; left mouse mapping accepted |
| `minecraft.inventory.slot.select` | `OK`; hotbar slot `0` selected |
| `minecraft.player.interact` | `OK`; pick interaction queued |
| `minecraft.ui.state.read` | `OK`; no screen open in the joined world |

The client bridge is isolated in the Fabric client source set. The host now refreshes
the negotiated manifest when the player/world state changes; without that refresh, client
capabilities discovered before world join remained unavailable.

This is a Fabric 1.21.1 client contract slice. Fabric 1.20.1 and 26.2 remain dedicated-server
compatibility rows, and full semantic container/UI automation and chat capture are not claimed
for those targets.
