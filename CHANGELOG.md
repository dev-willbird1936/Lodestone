# Changelog

All notable user-visible changes are recorded here. Lodestone follows semantic versioning for the
product as a whole; Minecraft and loader compatibility is listed per asset in each release.

## Unreleased — C1

C1 is the current parity candidate and is not a release. Its source and artifacts differ from the
frozen C0 baseline, so C0 evidence must not be used to certify C1 binaries.

### Added

- Loader-neutral MCP runtime, authenticated loopback HTTP gateway, and versioned capability model.
- Native Fabric, Quilt-compatibility, Forge, NeoForge, Paper, Spigot, Folia, legacy Forge, and RCON
  integration lines documented in the compatibility matrix.
- Bounded server, player, world, inventory, UI/input, geometry, structure, and WorldEdit-facing
  capability contracts with explicit availability and permission metadata.
- Hash-bound release evidence, fresh-world verification harnesses, and per-loader CurseForge test
  profiles.

### Security

- Default-deny mutations, loopback-only listeners, owner-only token files, request bounds,
  cancellation-aware rollback, and audit resources.

### Release status

- Source backup on `main` is allowed after secret and import review.
- `v0.1.0-rc.1` remains blocked until all affected C1 rows pass fresh-world certification and the
  C1 artifact manifest and final audit are complete.

## C0 compatibility baseline — 2026-07-12

C0 is an immutable evidence baseline rather than a reconstructed Git tag. Its exact source and 32
artifacts are frozen by `verification/evidence/release-artifacts-2026-07-12.json`; the consolidated
22-row result is in `verification/evidence/compatibility-live-2026-07-13-c0.md`.
