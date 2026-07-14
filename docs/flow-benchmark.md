# Lodestone ordered client-flow benchmark

`verification/neoforge-keepfocus-flow-benchmark.ps1` is the ordered acceptance flow for the
NeoForge 1.21.1 client artifact plus KeepFocus. It is stateful: each stage starts from the state
left by the previous stage, and every MCP request uses the authenticated loopback session.

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
