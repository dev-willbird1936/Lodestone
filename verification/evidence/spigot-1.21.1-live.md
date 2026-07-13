# Spigot 1.21.1 live evidence

Date: 2026-07-12

## Artifacts

- Spigot server: BuildTools-produced `spigot-1.21.1.jar`
- BuildTools output descriptor: `4344-Spigot-a759b62`
- Spigot server SHA-256: `99923F8BB524A9E9DFEA8E54AE745800F41A952B0F13F8F2F1403F0F44291FC9`
- Lodestone plugin SHA-256: `401D98C0901EB6B4A1E54C250A1479478DD72A8B678E4E49ECCBD7AE8A5E0E37`
- Java: 21
- Repeatable test: `verification/spigot-server-matrix.ps1`

The Spigot server was built locally with the official BuildTools artifact for the
1.21.1 revision. The server JAR is external test infrastructure and is not
committed to this repository.

## Result

`PASS`: a fresh server reached `Done`, created the overworld, Nether, and End,
loaded `Lodestone`, and exposed the authenticated loopback MCP endpoint.

The matrix verified:

- MCP initialize/session negotiation and tools listing
- Spigot capability selection and availability
- loaded and far-away unloaded block reads
- bounded bulk reads and region scans
- entity listing
- dry-run block validation, block write, and read-after-write
- chat broadcast and command execution
- structured no-player failures for player state and inventory
- clean command shutdown and crash/bind/plugin-load log scan

This row claims the tested Bukkit-compatible server slice only. Connected-player
semantics, container/NBT automation, broader events/mutations, and Folia are not
implicitly covered by this evidence.
