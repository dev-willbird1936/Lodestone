# Lodestone Fabric 1.21.1 adapter

Concrete Fabric server adapter for Minecraft 1.21.1.

Pinned toolchain:

- Fabric Loader 0.16.10
- Fabric API 0.116.9+1.21.1
- Fabric Loom 1.10.5
- Java 21

The pure adapter implements command discovery, command execution (permission restricted by the
runtime policy), player state reads, overworld block reads, bounded overworld writes, loaded entity
queries, and server-player inventory reads. Lifecycle events report server start/stop through the
shared Lodestone event hub. It does not depend on the MCP gateway. The runnable loader host is
`hosts/fabric/1.21.1`.

Run a dedicated development server with `:hosts:fabric:mc1_21_1:runServer`.
