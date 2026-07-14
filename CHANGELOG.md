# Changelog

All notable user-visible changes are recorded here. Lodestone follows semantic versioning for the
product as a whole; Minecraft and loader compatibility is listed per asset in each release.

## Unreleased

### Fixed

- Fabric 1.18.2 and 1.20.1 client `minecraft.ui.click` now honor the protocol's optional
  `button` field by defaulting it to left-click (`0`).
- Fabric 1.20.1 Loom `runClient` now uses its required Java 17 toolchain while the monorepo's
  newer projects continue to configure with Java 21.

## 1.0.0 - release candidate

v1.0.0 is bound by `verification/evidence/release-conformance-v1.0.0.json`, which records the
exact 32 release artifact hashes and 22 fresh-world compatibility rows. It is promoted only after
the final audit, clean tagged assembly, verification, and GitHub publication.

### Added

- Loader-neutral MCP runtime, authenticated loopback HTTP gateway, and typed capability model.
- Native Fabric, Quilt-compatibility, Forge, NeoForge, Paper, Spigot, Folia, legacy Forge, and
  RCON integration lines documented in the compatibility matrix.
- Bounded server, player, world, inventory, UI/input, geometry, structure, and WorldEdit-facing
  capability contracts with explicit availability and permission metadata.
- Hash-bound release evidence, fresh-world verification harnesses, and per-loader CurseForge test
  profiles.

### Security

- Default-deny mutations, loopback-only listeners, owner-only token files, request bounds,
  cancellation-aware rollback, and audit resources.
- Legacy bridge write preflight keeps remote command rejections retryable while quarantining only
  uncertain post-dispatch mutations.
- Older-Gson compatibility fixes cover the Forge 1.16.5 runtime path.

## C0 compatibility baseline - 2026-07-12

C0 is an immutable historical baseline rather than a reconstructed Git tag. Its exact source and
32 artifacts are frozen by `verification/evidence/release-artifacts-2026-07-12.json`; it is not
used to certify v1.0.0 bytes.
