# Changelog

All notable user-visible changes are recorded here. Lodestone follows semantic versioning for the
product as a whole; Minecraft and loader compatibility is listed per asset in each release.

## 1.1.0 - control release candidate

Post-v1 control release. Immutable v1.0.0 assets and certification remain unchanged.

### Added

- Ordered authenticated client flows for menu navigation, input cleanup, containers, and bounded furniture/readback on the verified modern client rows.
- Exact-version 1.1.0 artifact naming and release provenance path.

## Unreleased

### Added

- New `deliberate-v1` goal intelligence tier (aliases: `deliberate`, `deliberate-v1`, `perfect`,
  `xhigh`), sitting above `adaptive-v1` and inheriting all of its guardrail, prerequisite-planning,
  obstruction-mining, and action-segment-replanning behavior unchanged. It adds realtime
  lookahead-plan consultation at segment boundaries (a bounded plan summary is stashed into the
  decision state seen by the next per-step choice, even when the native task is already supported)
  and a situational deliberation budget: a realtime decision that is not currently hazardous may
  request the model's `xhigh` reasoning effort and a wider timeout, while any hazardous decision or
  any other tier keeps the fast configured default. A new `lastDecisionReasoningEffort` state field
  records what was actually requested per realtime decision, alongside the existing per-run
  `reasoningEffort` base value.
- `LODESTONE_GOAL_MODEL_REASONING_EFFORT` now also accepts `xhigh`.

### Changed

- `highest` remains a legacy alias frozen at `adaptive-v1` for backward compatibility with existing
  MCP callers; it does not resolve to the new `deliberate-v1` tier. Use `deliberate`,
  `deliberate-v1`, `perfect`, or `xhigh` to opt into the new top tier.

### Fixed

- `LODESTONE_GOAL_MODEL_REASONING_EFFORT=xhigh` previously fell through to the unrecognized-value
  fallback and silently ran at `low` effort instead — the opposite of what was requested. `xhigh`
  is now a recognized, accepted value.
- Fabric 1.18.2 and 1.20.1 client `minecraft.ui.click` now honor the protocol's optional
  `button` field by defaulting it to left-click (`0`).
- Fabric 1.20.1 Loom `runClient` now uses its required Java 17 toolchain while the monorepo's
  newer projects continue to configure with Java 21.
- UI click, UI key, and semantic menu navigation now use a bounded 120-second default deadline,
  preventing normal integrated-world startup from being reported as an indeterminate mutation.
- NeoForge 1.21.1 now has a post-v1 authenticated main-menu route through fresh-world creation,
  with guarded typed UI state and player-look readback.
- Fabric 1.21.1 now has the same post-v1 authenticated main-menu/fresh-world route, including
  its explicit partial-screen boundary and player-look readback.

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
