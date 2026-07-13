# NeoForge 1.21.1 client live evidence

Date: 2026-07-11

Environment: Windows 11, Java 21.0.11, Minecraft 1.21.1, NeoForge 21.1.211,
NeoGradle 7.1.38, Gradle 8.14.

The real NeoForge client was launched with the development client target and opened the existing
`LodestoneClientWorld` save. The final integrated-server log reached `Dev joined the game`; after
the client lifecycle null-safety fix, no new crash report was produced.

| Check | Result |
| --- | --- |
| MCP initialize | negotiated `2025-11-25` |
| `minecraft.player.state.read` | `OK`; player identity, position, health, and food returned |
| `minecraft.ui.state.read` / `minecraft.ui.key` | `OK`; pause screen read and Escape handled |
| `minecraft.player.look` | `OK`; yaw `45`, pitch `10` applied |
| `minecraft.player.move` | `OK`; bounded movement state applied |
| `minecraft.input.key.set` / mouse | `OK`; logical key and left/right mouse mappings accepted |
| inventory slot selection | `OK`; slot `0` selected |
| `minecraft.player.interact` | `OK`; pick interaction queued |
| inventory/entity/world queries | `OK`; inventory, bounded entity list, block read, and top-level envelope dry-run returned |
| `minecraft.inventory.container.click` | Structured `CAPABILITY_UNAVAILABLE`; deliberately not advertised as safe until revision/slot validation exists |
| chat and event flow | `OK`; chat broadcast and `minecraft.chat.received` event observed |

An initial quick-play attempt exposed a real lifecycle crash when NeoForge emitted a logging-out
event with a null player during world setup. The controller now treats null lifecycle payloads as
state transitions and refreshes capability availability; the subsequent world-load and control
run completed successfully.

The dedicated-server matrix remains the release-boundary compatibility proof. This client file
proves the NeoForge 1.21.1 physical-client slice only.
