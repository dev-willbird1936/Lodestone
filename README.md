# Lodestone

> [!WARNING]
> **UNRELEASED — C1 is not certified.** The frozen C0 evidence proves its exact compatibility
> baseline, but current source contains later parity work. Do not treat untagged builds as a
> release. See [RELEASING.md](RELEASING.md) for the certification and asset policy.

Lodestone is a version-aware Minecraft MCP and automation platform. Its stable product is a
capability protocol; client mods, server plugins, and fallback integrations are independent
adapters that report exactly what they can do.

## Status

Active vertical slice. The protocol, runtime, MCP stdio/loopback gateway, and concrete NeoForge
1.21.1, Fabric 1.21.1/1.20.1/1.19.2/1.18.2/26.2, Forge
1.21.1/1.20.1/1.19.2/1.18.2/1.16.5, Paper 1.21.1, Spigot 1.21.1, and Folia 1.21.4
adapter/host distributions build and have fresh-world dedicated-server evidence. Fabric 1.20.1
and 1.21.1 also pass Quilt Loader 0.29.2 compatibility rows. Forge 1.12.2, 1.8.9, and 1.7.10
have Java 8 native bridges plus a loader-neutral authenticated RCON fallback.
Native mod adapters expose command discovery/execution; the Paper, Spigot, and Folia plugins expose
their documented server-control subsets. Native/plugin capabilities vary honestly by adapter: most
provide server-player state, bounded overworld reads/scans/writes, inventory projection, and chat;
Folia omits loaded-entity queries, and RCON intentionally exposes only authenticated command
execution. All unsupported catalog operations remain explicitly unavailable or restricted. Full cross-version client control, complete UI/container
semantics, block-entity/NBT mutation, broader mutation coverage, and additional loader/version
lines remain open.

## Start here

- [Getting started](docs/getting-started.md) — choose the right artifact, install it, connect an
  MCP client, and enable only the permissions you need.
- [Compatibility matrix](docs/compatibility-matrix.md) — exact loader, Minecraft, Java, and
  evidence rows.
- [Security model](docs/security-model.md) — loopback authentication, permission classes, and
  mutation safeguards.
- [Release policy](RELEASING.md) — certification gates, asset naming, checksums, and tags.
- [Changelog](CHANGELOG.md) — user-visible changes and release status.

## Design rules

- No universal binary claim. Compatibility is version-, loader-, and environment-specific.
- Every discoverable native operation is represented by an available, unavailable, restricted, or
  degraded capability.
- Mutating capabilities are default-deny until explicitly authorized.
- The MCP gateway contains no loader-specific implementation.
- Protocol contracts and tests are written before adapter behavior.

## Layout

- `hosts/` — loader entrypoints that compose one pure adapter with the MCP gateway.

- `protocol/` — versioned JSON Schema contracts, capability catalog, and fixtures.
- `common/` — protocol model, adapter API, and runtime orchestration boundaries.
- `gateway/` — MCP-facing service.
- `adapters/` — active native platform integrations for NeoForge 1.21.1, Fabric
  1.21.1/1.20.1/1.19.2/1.18.2/26.2, Forge 1.21.1/1.20.1/1.19.2/1.18.2/1.16.5,
  Paper 1.21.1, Spigot 1.21.1, and Folia 1.21.4; legacy Forge bridges live with their hosts.
- `verification/` — cross-adapter contract tests.
- `docs/` — architecture, security, compatibility, and implementation requirements.

Run `gradlew.bat check` for protocol/runtime/gateway contracts and catalog checks. Build the
NeoForge artifact with:

```text
gradlew.bat :hosts:neoforge:mc1_21_1:build
```

Build the Fabric artifact with:

```text
gradlew.bat :hosts:fabric:mc1_21_1:build
```

Build the long-lived Fabric 1.20.1 artifact with:

```text
gradlew.bat :hosts:fabric:mc1_20_1:build
```

Build the non-remapping Fabric 26.2 artifact with Java 25 and Gradle 9.5.1. Loom 1.17.1
requires Gradle 9.5+, while ForgeGradle remains on the default Gradle 8.14 path, so the
incompatible Forge projects are excluded for this isolated build:

```text
gradle-9.5.1/bin/gradle.bat :hosts:fabric:mc1_26_2:build -PincludeFabric262=true -PincludeForge=false -PincludeForge121=false --no-daemon --console=plain
```

Minecraft 26.2's Vulkan backend is an experimental client graphics option; the dedicated-server
acceptance row proves the server/MCP path and does not claim to exercise a GPU renderer.

Build the RCON transport and launcher with:

```text
gradlew.bat :gateway:rcon-launcher:build
```

Run the launcher with `LODESTONE_RCON_HOST`, `LODESTONE_RCON_PORT`, `LODESTONE_RCON_PASSWORD`,
and `LODESTONE_TOKEN` configured. It exposes only `minecraft.command.rcon.execute`; output stays
unstructured by design.

Current Java hosts and server plugins bind an authenticated loopback MCP endpoint at
`127.0.0.1:37821` by default. The token is atomically generated with owner-only access at
`config/lodestone.token`; override with `LODESTONE_TOKEN` and grant mutation permissions explicitly
with `LODESTONE_PERMISSIONS` or `-Dlodestone.permissions=...`.
