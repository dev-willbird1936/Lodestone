# Forge 1.12.2 native bridge live evidence

- Minecraft Java 1.12.2
- Forge 14.23.5.2859
- Server Java: JDK 8u492
- ForgeGradle 3.x with Gradle 4.10.3
- Lodestone artifact: `hosts/forge/1.12.2/build/reobfJar/output.jar`
- Lodestone artifact SHA-256: `F1E1FBC9B0368672302C647F2E2FEB364AAB89CD0926D1860B6A00D029757E6F`

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
