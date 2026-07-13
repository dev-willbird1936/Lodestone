# Forge 1.8.9 native bridge live evidence

- Minecraft Java 1.8.9
- Forge 11.15.1.2318
- Server Java: JDK 8u492
- ForgeGradle 2.1.3 with Gradle 2.7
- Lodestone artifact: `hosts/forge/1.8.9/build/libs/lodestone-0.1.0-SNAPSHOT.jar`
- Lodestone artifact SHA-256: `8677C3205588C4CDB0BF9D90FF38720048A7EE47D5DBF9DA9EF792E3FD429660`

The Java 8-native Forge mod reached the server `Done` state and created a fresh world. A separate
Java 21 Lodestone launcher connected to the authenticated loopback bridge and negotiated MCP.

Verified capabilities:

- world block read, far unloaded read, bounded bulk read, and region scan;
- bounded block dry-run and mutation with read-after-write;
- loaded entity query, server chat, command execution, and clean server stop;
- Java 8 server startup with no Lodestone crash report.

The legacy bridge uses the old Forge world API and may load a target chunk during a requested block
write; this is explicitly isolated to the legacy adapter and is not the policy of the modern native
adapters, which reject unloaded writes.
