# Lodestone frozen-candidate compatibility evidence — C0

## Candidate identity

- Frozen manifest: `verification/evidence/release-artifacts-2026-07-12.json`
- Manifest verification after all acceptance runs:
  `VERIFIED source=409ab51a08c2f0107d943a703f6b6a033761303f1606207aada3c658cc2fcf45 files=219 artifacts=32`
- Residual audit after all rows: `0` Java processes and `0` listeners on matrix ports.
- This file preserves the C0 result. Later source/artifact work must create a new candidate instead
  of overwriting C0 or treating these runs as evidence for changed bytes.

## Fresh-world result

Every row used its final C0 host artifact, reached `Done`, created a fresh overworld, negotiated an
authenticated MCP session, exercised its supported control slice, performed and read back a bounded
mutation where supported, rejected unsafe block-entity replacement, stopped normally, and passed
fatal/crash/listener cleanup checks.

| Target | Result | Final log/transcript UTC |
| --- | --- | --- |
| Fabric 1.20.1 / Loader 0.15.11 | PASS | 2026-07-12 23:16 |
| Fabric 1.19.2 / Loader 0.14.25 | PASS | 2026-07-12 23:17 |
| Fabric 1.18.2 / Loader 0.14.25 | PASS | 2026-07-12 23:18 |
| Fabric 1.21.1 / Loader 0.16.10 | PASS | 2026-07-12 23:19 |
| Fabric 26.2 / Loader 0.19.3 | PASS | 2026-07-12 23:20 |
| Quilt 1.20.1 / Loader 0.29.2 compatibility host | PASS | 2026-07-12 23:21 |
| Quilt 1.21.1 / Loader 0.29.2 compatibility host | PASS | 2026-07-12 23:22 |
| NeoForge 1.21.1 / 21.1.211 | PASS | 2026-07-12 23:39 |
| Forge 1.21.1 / 52.1.0 | PASS | 2026-07-12 23:24 |
| Forge 1.20.1 / 47.4.10 | PASS | 2026-07-12 23:25 |
| Forge 1.19.2 / 43.5.2 | PASS | 2026-07-12 23:26 |
| Forge 1.18.2 / 40.3.12 | PASS | 2026-07-12 23:28 |
| Forge 1.16.5 / 36.2.42 | PASS | 2026-07-12 23:37 |
| Paper 1.21.1 / build 133 | PASS | 2026-07-13 00:05 |
| Spigot 1.21.1 / BuildTools `4344-Spigot-a759b62` | PASS | 2026-07-13 00:07 |
| Folia 1.21.4 / build 6 | PASS | 2026-07-12 23:44 |
| Forge 1.12.2 / 14.23.5.2859 native bridge | PASS | 2026-07-13 00:10 |
| Forge 1.8.9 / 11.15.1.2318-1.8.9 native bridge | PASS | 2026-07-13 00:09 |
| Forge 1.7.10 / 10.13.4.1614-1.7.10 native bridge | PASS | 2026-07-13 00:03 |
| Forge 1.12.2 / 14.23.5.2859 via RCON | PASS | 2026-07-13 00:13 |
| Forge 1.8.9 / 11.15.1.2318-1.8.9 via RCON | PASS | 2026-07-13 00:11 |
| Forge 1.7.10 / 10.13.4.1614-1.7.10 via RCON | PASS | 2026-07-13 00:02 |

## Frozen launcher correction

The legacy scripts originally referenced stale exploded `build/install` trees. Those trees differed
from both frozen launcher ZIPs in six JARs, so their earlier results were not accepted as C0 evidence.
The scripts gained an explicit `-LauncherDist` input and all six legacy rows above ran from fresh,
per-row extractions of the exact frozen ZIPs.

- RCON launcher ZIP SHA-256:
  `699a213e0a6ab7aee3ca5946669bd01df3d1c511f10a3967e80fa763bad23cda`
- Legacy bridge launcher ZIP SHA-256:
  `9cadda073b1c6f89b20dd825c8355c52dc8bee84b887e26dcd4a06b241493df7`
- RCON harness SHA-256:
  `ff56e6b6f1c1930f6ced68a24be13bce0793a29cd78fa4b34e3f9c7038ef46d6`
- Native harness SHA-256:
  `08e2d412764679a4e71ec9863cd3e022cb2fd2523a2f324487b4a1692bbe9d68`

The frozen ZIP hash was checked before and after every legacy run. Each extraction was removed only
after both server and launcher processes had exited.

## Scope

This proves C0 compatibility for representative server/native/RCON slices. It does not prove full
client input, menu automation, or VibeCraft feature parity. Those remain separate product gates and
will require a new source/artifact candidate plus affected-row reruns.
