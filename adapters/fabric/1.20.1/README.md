# Lodestone Fabric 1.20.1 adapter

Concrete Fabric server adapter for the long-lived Minecraft 1.20.1 pack line.

Pinned toolchain:

- Fabric Loader 0.15.11
- Fabric API 0.92.2+1.20.1
- Fabric Loom 1.10.5
- Java 21 build and runtime requirement; the shared Lodestone runtime uses Java 21

The pure adapter implements command discovery, command execution (permission restricted by the
runtime policy), player state reads, overworld block reads, bounded overworld writes, loaded entity
queries, and server-player inventory reads. It does not depend on the MCP gateway. The runnable
loader host is `hosts/fabric/1.20.1`.

Run a dedicated development server with `:hosts:fabric:mc1_20_1:runServer`.
