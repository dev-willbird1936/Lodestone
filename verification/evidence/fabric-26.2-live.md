# Fabric 26.2 live evidence

Date: 2026-07-11

## Environment

- Minecraft Java 26.2
- Java 25.0.3
- Fabric Loader 0.19.3
- Fabric API 0.154.2+26.2
- Fabric Loom 1.17.1
- Historical server run: Gradle 9.5.1 (current 26.2 client/release toolchain uses Gradle 9.6.1)
- Windows 11
- External server directory under the disposable temp matrix root

26.2 is non-remapping in Fabric's current toolchain. The server log explicitly reports
`Mappings not present!`, then loads Lodestone and Fabric API without a mapping failure.

## Acceptance

`verification/stage-fabric-262-server.ps1` prepared the official Fabric server launcher and
pinned Fabric API. `verification/packaged-server-matrix.ps1` passed the target
`Fabric 26.2 / Loader 0.19.3 (Vulkan client setting; dedicated server)`.

The row proved:

- Fabric and Lodestone loaded on Java 25.
- Lodestone bound the authenticated MCP loopback endpoint.
- A fresh 26.2 world reached Minecraft `Done` and wrote `world/level.dat`.
- The native block read, bulk block read, region scan, entity list, dry-run write, and server chat
  checks returned `OK`.
- Invalid MCP tokens returned HTTP 401.
- The authenticated stop command shut down the server cleanly.

26.2 stores overworld regions at
`world/dimensions/minecraft/overworld/region`; the matrix runner accepts that layout.

## Vulkan boundary

Minecraft 26.2 adds an experimental client graphics backend selection. This headless dedicated
server test intentionally proves the server/MCP compatibility path only; it does not claim to
exercise Vulkan rendering. A future desktop-client acceptance test must launch the client with
the Vulkan preference and verify world join separately.
