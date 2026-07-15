# `/watch` audit — realtime mode

- Source: `minecraft-goal-wool-tree-zombie-defense-realtime-20260715T203016Z.mp4`
- Format: 854x480 H.264, 88.300 seconds, no audio stream
- Full audit output: `watch-wool-tree-realtime-20260715T203016Z/`
- Invocation: one `minecraft_goal` call, `mode=realtime`, `suppressInGameMessages=true`

## Visual result

Reviewed representative frames across the full run, including setup, construction, combat, and the final sequence. The recording shows normal Minecraft HUD/gameplay with no Lodestone per-action chat or status overlay: world creation, manual oak-log trunk and green-wool leaf placement, nearby zombie, diamond sword in survival, reactive sword attacks while mining, and the final tree cleared.

The machine-readable report is `minecraft-goal-wool-tree-zombie-defense-realtime-20260715T203016Z.json` and records `status=PASS`, raw MCP `SUCCEEDED`, one invocation, `inGameMessagesEmitted=0`, `fullTreeMined=true`, `defensiveAttacks=13`, `playerAlive=true`, and `listenerAfterCleanup=false`.
