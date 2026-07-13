# Paper 1.21.1 live evidence

Date: 2026-07-12

## Runtime

- Official Paper `1.21.1-133`, implementing API `1.21.1-R0.1-SNAPSHOT`.
- Java 21 (Temurin 21.0.11).
- Paper server SHA-256: `39BD8C00B9E18DE91DCABD3CC3DCFA5328685A53B7187A2F63280C22E2D287B9`.
- Lodestone plugin SHA-256: `1C0769ECD9E6BDE918D02F881846C333488CC39FA4461543E5F23EF8E1E5D37`.
- The server reached `Done`, generated `world/level.dat`, and stopped cleanly through the MCP command path.

Official server source used by the repeatable test:
`https://fill-data.papermc.io/v1/objects/39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9/paper-1.21.1-133.jar`

## MCP acceptance

`verification/paper-server-matrix.ps1` passed against a fresh disposable server directory.

Passed operations:

- authenticated MCP initialize and session-bound `tools/list`;
- capability lookup after world load, reporting `available` for the tested Paper world slice;
- block read, bounded bulk read, and bounded region scan;
- loaded entity listing;
- dry-run block validation, actual block write, and read-after-write verification;
- server chat broadcast and permission-gated console command execution;
- structured no-online-player errors for player state and inventory queries;
- clean stop and post-run crash/plugin-load log scan.

Paper is a plugin platform, so this evidence is not a CurseForge modpack profile. It covers the
Bukkit/Paper server path only. Command discovery, connected-player semantics, container/NBT
automation, Folia region scheduling, and Spigot/Folia runtime compatibility remain separate
coverage items.
