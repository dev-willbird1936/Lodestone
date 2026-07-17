# Provenance

Lodestone is independently authored from this repository's specifications, official platform
documentation, and public behavior. Contributions must not introduce unreviewed third-party
material.

Contributors must disclose the source and license of every imported dependency, asset, fixture,
or code sample in `THIRD_PARTY_NOTICES.md`. Source files carry SPDX identifiers. The NeoForge
and Fabric adapters are authored against official API contracts and do not copy unreviewed donor
source.

This is an engineering-provenance policy, not legal advice or a legal review.

## Concepts-surveyed sources (2026-07-17)

`other-repos/` contains fifteen sibling Minecraft AI-agent projects, surveyed for their control/
intelligence-layer architecture as Phase 1 of the goal-engine competence work (full judgment:
`verification/evidence/other-repos-intelligence-layer-judgment-2026-07-17.md`). No source code,
identifiers, comments, or distinctive names were copied from any of them into Lodestone. Mechanisms
were described in this repository's own words, in behavior/algorithm terms, and reimplemented (when
ported) from that description alone.

The following repos are LGPL-licensed and are therefore **concepts-only**: their architecture is
described and may inform an independent Lodestone reimplementation, but their source is closed to
direct reuse and nothing was copied from them.

- `baritone` (LGPL-3.0)
- `craftagent` (LGPL-3.0)
- `minecraft-numen` (LGPL-3.0 core; its separate companion-API package is MIT but the core logic
  surveyed here is not)
- `pendulum` (LGPL-3.0)
- `player-engine` (LGPL-3.0; itself a fork combining a rebranded AltoClef with a Baritone fork)

The remaining surveyed repos with a real intelligence layer (`ai-player`, `gemini-minecraft`,
`mindcraft`, `minecraft-mcp-fundamentallabs`, `open-player`, `steve`, `voyager`) are MIT-licensed
(or, for `steve`, claim MIT in its README/mod metadata without a formal `LICENSE` file present —
treated with the same no-code-copied discipline as the LGPL repos out of caution) and are safe to
reference with attribution; large code blocks were still not reproduced. `fabric-claude-plugin`,
`minecraft-mcp-yuniko`, and `mcp-mcp` were dismissed as pure command/MCP bridges with no
decision logic of their own and contributed nothing to port.
