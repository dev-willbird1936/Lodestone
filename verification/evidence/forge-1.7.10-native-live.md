# Forge 1.7.10 native bridge live evidence

- Minecraft Java 1.7.10
- Forge 10.13.4.1614
- Server Java: JDK 8u492
- ForgeGradle 1.2-1.0.12 with Gradle 4.10.3
- Lodestone artifact: `hosts/forge/1.7.10/build/libs/lodestone-0.1.0-SNAPSHOT.jar`
- Lodestone artifact SHA-256: `80C9E1B3ABB539D41950AA7D6DE39730E35683286DE1979B650B4868BA97EBA2`

The Java 8-native Forge mod reached the server `Done` state and created a fresh world. A separate
Java 21 Lodestone launcher connected to the authenticated loopback bridge and negotiated MCP.

Verified capabilities:

- world block read, far unloaded read, bounded bulk read, and region scan;
- bounded block dry-run and mutation with read-after-write;
- loaded entity query, server chat, command execution, and clean server stop;
- Forge server-tick scheduling for HTTP-to-main-thread handoff;
- Java 8 server startup with no Lodestone crash report.

The legacy bridge uses the old Forge world API and may load a target chunk during a requested block
write; this is explicitly isolated to the legacy adapter and is not the policy of the modern native
adapters, which reject unloaded writes.
