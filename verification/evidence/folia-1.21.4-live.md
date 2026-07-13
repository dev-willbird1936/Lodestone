# Folia 1.21.4 live evidence

Date: 2026-07-12

## Artifacts

- Folia server: official `folia-1.21.4-6.jar`
- Official download: `https://fill-data.papermc.io/v1/objects/dcf2333211c1468c8eddc482bc8549600818cc661a709124a79c752f8fa2ac3a/folia-1.21.4-6.jar`
- Folia server SHA-256: `DCF2333211C1468C8EDDC482BC8549600818CC661A709124A79C752F8FA2AC3A`
- Lodestone plugin SHA-256: `C4CBE99F30342EFC681570D60F0479E2DAB05C918B079232F7B4A57087CB2D36`
- Java: 21
- Repeatable test: `verification/folia-server-matrix.ps1`

The official build service currently lists Folia 1.21.4 as the nearest available
1.21 integration target; it does not currently publish a Folia 1.21.1 build.
Therefore this evidence does not silently claim a 1.21.1 Folia server row.

## Result

`PASS`: a fresh Folia server reached `Done`, created the overworld, Nether, and
End, loaded `Lodestone`, and exposed the authenticated loopback MCP endpoint.

The matrix verified:

- MCP initialize/session negotiation and tools listing
- Folia capability selection and availability
- region-scheduled loaded and far-away unloaded block reads
- region-scheduled bounded bulk reads and scans
- honest unavailability for entity listing, which is not safe to implement as a
  cross-region world sweep yet
- region-scheduled dry-run block validation, block write, and read-after-write
- global-region chat broadcast and command execution
- structured no-player failures through entity-scheduler lookup
- clean command shutdown and crash/bind/plugin-load log scan

This row claims the tested scheduler-aware server slice only. Full cross-region
entity discovery, connected-player semantics, container/NBT automation, and
broader events/mutations remain open.
