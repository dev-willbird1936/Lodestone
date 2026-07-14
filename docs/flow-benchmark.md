# Lodestone ordered client-flow benchmark

`verification/neoforge-keepfocus-flow-benchmark.ps1` is the profile-backed ordered acceptance
flow for the NeoForge 1.21.1 client artifact plus KeepFocus. The Fabric 1.20.1 wrapper is
`verification/fabric-1.20.1-flow-benchmark.ps1`; Fabric 1.21.1 uses
`verification/fabric-1.21.1-flow-benchmark.ps1`; Forge 1.21.1 uses
`verification/forge-1.21.1-flow-benchmark.ps1`. Each flow is stateful: every stage starts from
the state left by the previous stage, and every MCP request uses the authenticated loopback
session.

Order:

1. `main-menu`: initialize MCP, verify typed discovery/status, exercise menu navigation and honest
   unavailable states, capture the title screen, test UI selector variants, key/mouse inputs and
   release-all, subscribe to UI events, create a fresh world, then poll and unsubscribe.
2. `world`: wait for the joined world, read player/server/registry/entity context, read a known air
   cell, dry-run a bounded block write, perform gold-block write/readback, restore air/readback,
   send/read chat, capture a screenshot, and clean up inputs.
3. `focus-baseline`: record the in-world tick/player baseline.
4. `focus-readback`: move focus to another desktop application, then verify KeepFocus keeps the
   world ticking and prevents an accidental pause.
5. `shutdown`: invoke MCP Escape from an active world, click Save & Quit through the guarded UI
   selector, invoke MCP `quit_game`, and verify normal stop, saved chunks, no fatal/crash marker,
   and no remaining Java client process.

The companion `verification/neoforge-keepfocus-mcp-coverage.ps1` enumerates all 32 MCP tools and
49 negotiated capabilities, generates schema/enum/conditional cases, classifies generated inputs,
retries declared rate limits, and records every result under `verification/evidence/`. Each flow
report binds the tested artifact hashes and source state. `CAPABILITY_UNAVAILABLE`,
`DRY_RUN_UNSUPPORTED`, and the deliberate event-capability routing guard are expected outcomes;
NPEs, crashes, malformed envelopes, or unexplained adapter failures fail the flow.

The profile runner uses explicit expected statuses/error codes and aborts on any unexpected
outcome. Fabric uses port `37829` by default so its flow can run separately from NeoForge.
Fabric 1.20.1 also selects its exact-version direct `singleplayer` → `CreateWorldScreen` path and
records its adapter-level unavailable `minecraft.chat.read` state as an asserted outcome.
Fabric 1.21.1 selects its exact-version direct fresh-world route when no saves exist and records
the same unavailable chat-read capability explicitly.
Forge 1.21.1 uses the artifact-only ForgeGradle runner, records the first-run Forge loading-warning
dismissal, and asserts its exact unavailable registry/screenshot/input states instead of treating
them as client failures.
