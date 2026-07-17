# Lodestone

> [!IMPORTANT]
> **v1.0.0 is published from its immutable tag.** Its 32 release
> artifacts are bound to 22 exact fresh-world rows, a source input snapshot, and checksummed
> retained logs in [`verification/evidence/release-conformance-v1.0.0.json`](verification/evidence/release-conformance-v1.0.0.json).
> The published release contains 36 uploaded files whose names, lengths, and SHA-256 values are bound
> to the retained release record; the complete downloaded asset attestation is retained at
> [`verification/evidence/github-release-v1.0.0-attestation.json`](verification/evidence/github-release-v1.0.0-attestation.json).
> Untagged builds remain development snapshots.

Lodestone is an independent, version-aware Minecraft MCP/control platform. Its durable product is
a typed capability protocol: each loader, server, client, or fallback integration is a separate
adapter that reports what it can actually do for the running game.

## v1.0.0 at a glance

| What | Release-certified coverage |
| --- | --- |
| Mod loaders | Fabric 1.18.2, 1.19.2, 1.20.1, 1.21.1, 26.2; Quilt compatibility 1.20.1/1.21.1; NeoForge 1.21.1; Forge 1.16.5 through 1.21.1 |
| Legacy Forge | Native Java 8 bridges for 1.7.10, 1.8.9, and 1.12.2, plus authenticated RCON fallback |
| Server plugins | Paper 1.21.1, Spigot 1.21.1, Folia 1.21.4 |
| Profiles | Thirteen local CurseForge-compatible profiles with byte-identical embedded host artifacts |
| Security | Hardened against basic attacks |

Read the full [compatibility matrix](docs/compatibility-matrix.md) before choosing an asset.

## Start here

1. Choose an exact mod, plugin, profile, or launcher from the tagged release and the
   [compatibility matrix](docs/compatibility-matrix.md).
2. Follow [Getting started](docs/getting-started.md) to install it and connect an MCP client.
3. Grant only the required permissions. Mutations are denied until explicitly authorized.
4. Use capability discovery before invocation. An unsupported operation is a structured state, not
   a silent fallback.

## What Lodestone controls

The supported server-side slice varies by adapter but includes typed command discovery/execution,
player and inventory projections, bounded world reads/scans/writes, chat, and selected entity
queries. Client input, UI/menu, container, and screen capabilities are explicit, versioned
contracts; they never become available merely because another adapter supports a nearby feature.

RCON is intentionally command-only (`minecraft.command.rcon.execute`). It does not claim native
player, inventory, UI, event, or structured-world semantics.

## Minecraft goals

NeoForge 1.21.1 also exposes bounded `minecraft_goal`, `minecraft_goal_tasks`, and
`minecraft_goal_benchmark` MCP tools. Script mode executes segmented capability plans with
structured state handoff; realtime mode selects one action at a time, observes fresh state, and
continues with the same verification kernel. See [Minecraft goals](docs/minecraft-goals.md).

## Safety model

Lodestone is hardened against basic attacks. See the [security model](docs/security-model.md) for
threat boundaries and configuration details.

## Build from source

Most current hosts build with Java 21 and the included Gradle wrapper:

```text
gradlew.bat :hosts:neoforge:mc1_21_1:build
gradlew.bat :hosts:fabric:mc1_21_1:build
gradlew.bat :hosts:forge:mc1_20_1:build
gradlew.bat :gateway:rcon-launcher:installDist
```

On Windows, [`Run-Lodestone-Checks.bat`](Run-Lodestone-Checks.bat) starts the core Java 8,
legacy-bridge, and release-contract checks from the repository root.

Fabric 26.2 is isolated to Java 25 and Gradle 9.6.1. Forge 1.16.5 and Java 8 legacy hosts use
their documented isolated toolchains. See [Compatibility](docs/compatibility.md) for exact build
and runtime requirements.

## Project map

- `protocol/`: versioned schemas and capability catalog
- `common/`: protocol model, adapter API, and runtime
- `gateway/`: authenticated MCP transports
- `adapters/`: exact-version platform integrations
- `hosts/`: loader and plugin entrypoints
- `verification/`: contract, matrix, profile, and release assembly checks

For release policy, integrity files, and promotion gates, see [RELEASING.md](RELEASING.md).
