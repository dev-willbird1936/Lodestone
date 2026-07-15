# INTENTION

Lodestone is intended to be an independently authored, compatibility-first Minecraft MCP/control
platform. Its durable product is a negotiated capability protocol: clients can discover the
complete declared surface, invoke typed operations through one gateway, and see honest
unavailable/restricted/degraded states for the active versioned adapter.

The v1.0.0 production path spans exact Fabric, Quilt-compatibility, NeoForge, Forge, Paper,
Spigot, Folia, legacy Forge bridge, and legacy RCON rows. Future loaders and server integrations
must still be separate adapters with their own evidence rather than pretending one binary supports
every Minecraft environment.

## INTENTION MATCHED

- Standalone Lodestone branding, package names, protocol models, runtime, gateway, tests, docs, and
  provenance rules.
- Protocol-first module boundaries with no native Minecraft imports in the MCP gateway.
- Full core capability catalog with structured unavailability/restriction reasons.
- Default-deny mutation policy, bounded events, deadlines, cancellation, session identity, and
  audit records.
- Buildable NeoForge 1.21.1 vertical slice and authenticated loopback transport.
- Live NeoForge dedicated-server startup and representative MCP integration evidence.
- Buildable Fabric 1.21.1 and 1.20.1 adapters with the same loader-neutral MCP contract.
- Live Fabric dedicated-server startup, authorized command execution, security checks, clean
  shutdown, and reproducible CurseForge profile manifests.
- Loader-neutral RCON transport with bounded authenticated command execution, an external MCP
  launcher, and release-certified fresh-world evidence for Forge 1.7.10, 1.8.9, and 1.12.2.
- Pure adapter modules separated from loader host composition; host artifacts carry the loader
  metadata and gateway while adapters do not depend on the MCP gateway implementation.
- Bounded, validated overworld block mutation with dry-run support, cancellation checks, and
  explicit `modify-world` authorization; live-tested with read-back evidence.
- Bounded loaded-entity queries and server-player inventory projection handlers with honest
  no-player behavior; entity query live evidence is recorded.
- Clean packaged dedicated-server matrix for five Fabric rows, two Quilt compatibility rows,
  NeoForge 1.21.1, and five Forge rows from 1.16.5 through 1.21.1; every final host artifact
  reaches a fresh world, passes authenticated MCP checks, and shuts down cleanly.
- Bounded bulk overworld reads and permission-gated server chat broadcast implemented and
  reverified across all three packaged rows.
- Bounded no-chunk-load overworld region scans implemented with loaded/unloaded cell counts and
  block-frequency output across all three packaged rows.
- Quilt Loader 0.29.2 compatibility is verified on Minecraft 1.20.1 and 1.21.1 using the exact
  Fabric-compatible host bytes embedded in each final CurseForge profile; no metadata-rewritten
  surrogate is used for the release rows. This remains compatibility, not a separate Quilt-native
  adapter.
- Native Forge 1.21.1 and 1.20.1 support is packaged and live-tested with the same bounded MCP
  slice; ForgeGradle remains isolated to the Gradle 8.14 build path.
- Fabric 26.2 support is packaged and live-tested with the non-remapping Loom 1.17.1 path,
  Fabric Loader 0.19.3, Fabric API 0.154.2+26.2, Java 25, and the new 26.2 world layout.
- Fabric 1.19.2 support is packaged and fresh-world tested with Loader 0.14.25, Fabric API
  0.76.1+1.19.2, Java 17, and an explicit compatibility path for its older Gson/runtime surface.
- Forge 1.19.2 support is packaged and fresh-world tested with Forge 43.5.2, Java 17, an official
  installer-staged server, and the same explicit older-Gson compatibility path.
- Forge 1.18.2 support is packaged and fresh-world tested with Forge 40.3.12, Java 17, an official
  installer-staged server, and version-specific command/chat API adaptations.
- Fabric 1.18.2 support is packaged and fresh-world tested with Loader 0.14.25, Fabric API
  0.77.0+1.18.2, Java 17, and explicit modular Fabric API dependency staging.
- Fabric 1.18.2, 1.19.2, and 1.20.1 hosts now include compiled client bridges for bounded
  key/mouse input, movement, look, interaction, hotbar selection, UI-state, and UI-key control;
  their refreshed server artifacts still pass fresh-world acceptance.
- Fabric 1.18.2 and 1.19.2 now have committed, exact client-flow evidence on Java 17: each passed
  104 main-menu assertions, 27 fresh-world assertions with reversible block mutation/readback,
  and 6 clean-shutdown assertions. The shared benchmark detects whether that exact vanilla build
  opens SelectWorldScreen or CreateWorldScreen, and both legacy adapters can open PauseScreen from
  active gameplay through authenticated MCP Escape.
- Fabric 1.20.1 now has a manual post-v1 client-menu/control slice: Java 17 Loom launch,
  authenticated typed menu navigation, fresh-world creation, player-look readback, and clean
  client shutdown. The three bounded UI-transition defaults are 120 seconds, preventing a normal
  16.5-second world load from becoming an indeterminate mutation; this is evidence for a later
  Fabric patch artifact, not a changed v1.0.0 claim.
- NeoForge 1.21.1 now has a manual post-v1 main-menu/control slice: authenticated guarded UI
  navigation reached a fresh world through its two modern `Create New World` screens, and player
  look read back exactly. It uses the same bounded 120-second UI defaults and clean shutdown;
  English vanilla menu labels, broad automation, and v1.0.0 release bytes remain outside this
  narrow claim.
- Fabric 1.21.1 now has the same manual post-v1 main-menu/control slice, including a
  revision-guarded unique target from a partial world-list projection. It reached a fresh world,
  read player look back exactly, and shut down cleanly; its English vanilla route, partial-screen
  boundary, broad automation, and v1.0.0 release bytes remain outside this narrow claim.
- The negotiated catalog now includes normalized open-container reads; Fabric 1.18.2/1.19.2/
  1.20.1/1.21.1 and NeoForge 1.21.1 expose stable slot projections and revisions, while Fabric
  click operations enforce revision, slot, and button bounds.
- Forge 1.16.5 / 36.2.42 is packaged and fresh-world tested through an isolated ForgeGradle 5.1 /
  Gradle 7.6.4 / Java 17 path, exact MCP-era API mappings, one required module export, one required
  module opening, and a dedicated CurseForge profile.
- Legacy Forge 1.8.9, 1.7.10, and 1.12.2 are live-tested through both loader-neutral RCON and
  true Java 8-native mod bridges, with explicit legacy limits.
- Forge 1.12.2, 1.7.10, and 1.8.9 native bridges have Java 21 Lodestone launchers translating
  their native world/player/entity/chat surfaces into the negotiated MCP runtime.
- The 26.2 Vulkan backend is documented as a client-only experimental graphics setting; the
  headless acceptance test intentionally claims server/world/MCP compatibility only.
- Real NeoForge 1.21.1 and Fabric 1.21.1 integrated-client runs now reach a joined world and
  exercise the first connected-player control contract through MCP.
- The packaged matrix now verifies server-minted MCP session isolation, owned event subscriptions,
  and duplicate Lodestone-jar cleanup before every row, preventing stale artifacts from masking the
  selected compatibility result.
- The post-advisor packaged matrix now performs an applied block mutation and readback on every
  packaged loader row, so the green startup evidence covers a real bounded write path rather than
  dry-run validation only.
- Modern Fabric, Forge, NeoForge, Paper, and Folia block-write batches now preserve prepared prior
  states and restore applied changes in reverse order after cancellation or a native mutation
  failure. An atomic mutation-commit boundary prevents a timeout from being reported after the
  final write has become irrevocable; cancellation/failure/commit-race regressions cover it.
- Standalone Fabric manifests are pinned to the exact tested Minecraft, Fabric Loader, and Fabric
  API versions. Quilt profile acceptance extracts and hashes the exact embedded host from its final
  profile ZIP before fresh-world control testing, so the certified compatibility claim names the
  actual consumer bytes.
- Generated `runs/` runtime state is excluded from synced/source scans, while disposable matrix
  roots remain the authoritative scope for crash-free acceptance evidence.
- Paper 1.21.1 server-plugin coverage now reaches a fresh world, passes authenticated MCP reads,
  bounded mutation, chat, command, structured no-player errors, and clean shutdown through a
  repeatable official-server acceptance script.
- Spigot 1.21.1 server-plugin coverage now reaches a fresh world, passes the shared Bukkit-compatible
  MCP slice through a distinct Spigot adapter/host, and shuts down cleanly through a repeatable
  BuildTools-backed acceptance script.
- Folia 1.21.4 server-plugin coverage now reaches a fresh world and passes a scheduler-aware MCP
  slice through distinct global-region, region, and entity scheduling paths. Folia 1.21.1 remains
  pending because the official build service does not currently publish that server version.
- v1.0.0 release conformance binds 32 exact artifacts to 22 fresh-world rows, including final
  CurseForge profile ZIPs and both launchers. Assembly rejects any source byte that no longer
  matches `verification/evidence/release-conformance-v1.0.0.json`.
- Final 2026-07-14 compatibility evidence covers every release-certified native/plugin/RCON row,
  with authenticated MCP operations, real mutation/readback within its supported slice, and clean
  server shutdown. The legacy RCON rows use a gamerule mutation/query readback because it is
  available across 1.7.10 through 1.12.2.
- Final Forge 1.16.5 startup compatibility avoids newer-only Gson APIs in the shared runtime and
  MCP gateway; the focused compatibility scan and fresh-world row pass on its old Gson runtime.
- Final hardening adds bounded event subscriptions and HTTP admission, rejects coordinate overflow,
  and refuses implicit chunk loading during native/Paper/Folia writes; acceptance fixtures explicitly
  prepare loaded test chunks before exercising writes.
- Final compatibility hardening adds crash-report/normal-stop assertions to the packaged/native/RCON
  matrices, cancels queued legacy operations after timeout, guards expired legacy deadlines, fixes
  stale legacy-mod cleanup, and keeps RCON packet framing synchronized across fragmented reads.
- Final advisor hardening adds caller-isolated runtime ordering/rate limits, fail-fast quarantine
  for timed-out non-cooperative handlers, bounded HTTP body reads, atomic owner-restricted token
  creation on modern hosts, legacy bridge game-version verification, chunk-batched Folia reads,
  and a Gradle 9.5.1 isolation path for Fabric 26.2.
- Final adversarial follow-up closes failed-initialize session-capacity leaks, refreshes installed
  launcher distributions and CurseForge profile binaries from current hardened artifacts, rebuilds
  the Java 8 legacy bridge artifacts with version identity health data, and makes the native legacy
  matrix assert isolated server ports.
- Protocol responses now serialize the schema-defined lowercase/kebab-case status values, required
  nullable capability reasons remain present on the wire, and MCP tool discovery advertises the
  accepted `capabilityVersion` field; live serialization and gateway tests enforce all three.
- Existing block entities are rejected before every supported native/plugin block write until an
  NBT-safe mutation contract exists. Matrix fixtures prove rejection/readback on every packaged and
  native legacy row, with Paper additionally proving populated `CustomName` data survives.
- Java 8 legacy bridges share transactional reverse rollback and owner-only atomic token-file code;
  tests inject native failure/deadline failure and validate POSIX or Windows ACL ownership.
- Folia world load/unload events refresh runtime adapter metadata after the scheduler-safe world
  snapshot changes.
- The v1.0.0 format-2 certificate now retains checksummed fresh-world logs in source control, binds
  all 32 final artifact bytes to the source input snapshot that produced them, and rejects a
  release assembly when any retained log, artifact byte, source input, tag, or matrix row differs.
- Final-byte re-certification reran only changed Forge, NeoForge, Bukkit-family, Java 8 native, and
  launcher rows; byte-identical Fabric and generated Quilt host rows retain their unchanged green
  evidence. The final legacy and RCON tests use distributions extracted from their exact release
  ZIPs, not `build/install` directories.
- Java 8 native endpoints default to `observe` only and deny direct world writes, chat, and command
  execution before game scheduling when no permission grant is configured; all three final legacy
  rows have clean direct default-deny proof.
- Final release-audit remediation adds HTTP 401 invalid-token proof to Paper, Spigot, and Folia;
  reruns their three final plugin artifacts; and binds the retained Bukkit log to the certificate.
- Final Quilt remediation reruns only 1.20.1 and 1.21.1 against the exact JARs extracted from their
  final profile ZIPs, hashes both embedded hosts before startup, and records those byte bindings in
  the retained fresh-world log.
- The tag-gated GitHub workflow is designed to run the full source/unit/gateway/adapter/contract
  gate, create an inaccessible draft containing all 36 staged release files, download and
  SHA-256-verify the complete remote inventory, and publish only after that verification succeeds.
  Earlier hosted v1.0.0 rebuild attempts were red because of Java/Gradle and source-snapshot
  failures. The corrected workflow now pins its release-tool commit/tree/raw blobs, derives all
  sidecars from immutable certification data, and contract-tests two byte-identical 36-file
  assemblies; hosted run 29381863511 is the affected release verification.
- Fabric 1.18.2 profile staging now accepts both namespace-qualified and dependency-less cached
  Maven POMs under strict PowerShell execution. A fresh 49-entry profile rebuild matches the
  certified final ZIP SHA-256 exactly; the immutable v1.0.0 workflow runs its historical stager in
  the compatible Windows PowerShell runtime before the same final-byte assembly gate. During an
  immutable-tag recovery, the workflow overlays only this audited portability parser, then
  streams and raw-blob-verifies the exact tag file before clean-tree assembly; final profile ZIP
  bytes must still equal C0.
- The release gate's asynchronous furniture workflow assertions now tolerate a bounded five-second
  cold-runner scheduling window rather than spuriously failing at one second; any failed module's
  retained JUnit XML is printed without masking the original gate failure.
- v1.0.0 is published at https://github.com/dev-willbird1936/Lodestone/releases/tag/v1.0.0.
  Its 36-file asset set was independently downloaded and compared by filename, byte length, and
  SHA-256 before publication. The complete downloaded asset attestation is retained at
  `verification/evidence/github-release-v1.0.0-attestation.json`; the hosted rebuild is separately
  tracked by run 29381863511.
- The release assembler now emits deterministic manifest, provenance, SBOM, and checksum sidecars;
  provenance records the pinned release-tool commit/tree and raw assembler/stager blobs. The
  immutable retry workflow never resolves `origin/main`.

## Benchmark evidence added 2026-07-14

- The NeoForge 1.21.1 + KeepFocus client benchmark now runs in user-visible order from main menu
  to fresh world, exhaustive typed MCP coverage, focus-loss readback, and MCP clean shutdown. The
  rebuilt artifact passed 106 asserted main-menu records, 27/27 world records, focus-loss tick readback, a
  six-step Escape/UI-quit flow, and a 315-case 32-tool/49-capability typed coverage sweep. The
  flow now fails on unexpected outcomes, asserts gold-block write/readback/restore invariants,
  and records exact Lodestone/KeepFocus hashes plus source state.
- MCP `minecraft.ui.key` supports guarded Escape-to-Pause from an active world without an existing
  screen; shutdown evidence proves Save & Quit, title return, normal client stop, saved chunks,
  zero Java client processes, and no fatal/crash markers.
- Fabric 1.20.1 now has the same profile-backed ordered flow: 97 asserted main-menu records,
  27/27 world records, reversible mutation/readback, an explicit unavailable chat-read state,
  MCP Escape-to-Pause, and clean shutdown with zero Java processes. The final artifact hash is
  `2e6c6b29aa95b33ada97e3f68064708ca54d9cf4f881d332738a87548cb7dd45`.
- Fabric 1.21.1 now has the exact-version profile too: 97 asserted main-menu records, 27/27 world
  records, reversible mutation/readback, direct fresh-world route handling, MCP Escape-to-Pause,
  and clean shutdown with zero Java processes. The final artifact hash is
  `1ad86d7d6b63f2f039eed75942873b9fe6df9dba7fd0dc52ed11c496282a5899`.
- Forge 1.21.1 now has an artifact-only exact-version client profile: 100/100 main-menu records,
  15/15 minimal-world mutation/readback records, and 6/6 clean-shutdown records. The first-run
  Forge loading warning and unsupported registry/screenshot/input states remain explicit. The final
  artifact hash is `86bf8ec7c5c2d414af10288bbc1d4205656eb0949294168f64b249095764d2b9`.
- Forge 1.20.1 now has an artifact-only exact-version client profile: 100/100 main-menu records,
  15/15 minimal-world mutation/readback records, and 6/6 clean-shutdown records. Its exact Forge
  client controller handles the older tick event and fresh-world button press/release behavior;
  unavailable text/legacy input states remain explicit. The final artifact hash is
  `1b6aaa36a40841c0cb92fe7213e3b25b89f728cf9efed8288a3971fda8757096`.
- Fabric 26.2 now has an exact Java 25/Loom 1.17.1 client profile: 99 main-menu records, 27
  world records including reversible block mutation/readback and player-look readback, and clean
  MCP shutdown. The final artifact hash is
  `8872c45706e5d019d8eec4ed62a66bf1d26e8dce5fcbdef320c88d46bdea9127`.
- The final Forge 1.20.1 and Fabric 26.2 client-flow JSON reports are retained in
  `verification/evidence/` and bound to their exact source commits and artifact SHA-256 values;
  these are post-v1 evidence and do not rewrite the immutable v1.0.0 certificate.
- Fabric 1.18.2 final client artifact `41bbf58f69d6e613946df565d60ea4610a991734e23d2576dfc5d7c550b916a5`
  is bound to source commit `17f2bab`: 104 main-menu, 27 world, and 6 shutdown records, all asserted.
- Fabric 1.19.2 final client artifact `61dd917922393c3e8eb367cfc601407b4216496b72ee0af0fe0709bb4fcea9c2`
  passed 104 main-menu, 27 world, and 6 shutdown records. The run was built from the same committed
  source change set but its report also records the pre-commit working-tree overlay honestly.

## Benchmark evidence added 2026-07-15

- NeoForge 1.21.1 now has a rebuilt exact artifact with state-aware native block parsing. The fresh
  KeepFocus run passed 108 main-menu precondition records and 33/33 world records, including bounded
  Vibecraft `simple_chair` preview, real placement with `facing=north,half=bottom`, block readback,
  restoration, and clean shutdown. The artifact SHA-256 is
  `7caf8200c6d5a22cec7bcc702a7cbdbc1f31674101a07c8c8d59a7634e1d0fbd`.
- The furniture evidence remains an exact tested NeoForge slice, not a claim that every bundled layout,
  loader, version, block entity, or menu path is supported. The benchmark report records the source
  overlay honestly and the shutdown report records `stoppingMarker=true`, `fatalMarker=false`, and
  `javaProcessCount=0`.
- Fabric 1.20.1 now has the same state-aware native writer and fresh-client furniture evidence: 100
  main-menu precondition records, 33/33 world records, bundled `simple_chair` preview and placement,
  block readback/restoration, and clean shutdown with zero Java client processes. This remains a tested
  exact-version slice rather than a cross-loader parity claim.
- Fabric 1.21.1 now passes the same exact-version furniture benchmark: 100 main-menu precondition
  records, 33/33 world records, state-aware `simple_chair` placement/readback/restoration, and clean
  shutdown with `stoppingMarker=true`, `fatalMarker=false`, and `javaProcessCount=0`.
- Forge 1.21.1 now passes the same bounded furniture benchmark: 102 main-menu precondition records,
  21/21 world records, state-aware `simple_chair` placement/readback/restoration, and clean shutdown
  with `stoppingMarker=true`, `fatalMarker=false`, and `javaProcessCount=0`. The final artifact hash is
  `929a6a770d3da33ceb73c65e6536af07028dbf2a1fabca45e075779a7643e2f9`.
- Forge 1.20.1 now passes the same bounded furniture benchmark: 102 main-menu precondition records,
  21/21 world records, state-aware `simple_chair` placement/readback/restoration, and clean shutdown
  with `stoppingMarker=true`, `fatalMarker=false`, and `javaProcessCount=0`. Its ForgeGradle client
  evidence uses mapped userdev test bytes; the separately built production reobf artifact is retained
  and hashed independently so the evidence does not conflate the two artifact roles.
- NeoForge 1.21.1 now has retained live menu/container evidence on the exact artifact: 108 main-menu
  records, 23 world records, inventory key dispatch into `InventoryScreen`, revision/46-slot container
  read, revision-guarded empty-slot click and readback, close dispatch, and clean shutdown. The tested
  artifact SHA-256 is `b5a23cd9bc1127f8679fb10fa3f186a565e525aed4767bed59595db630688bcc`.

## Container/menu evidence added 2026-07-15

- Fabric 1.20.1 now has retained live menu/container evidence on the exact artifact: 100 main-menu
  records, 33 world records, inventory key dispatch into `InventoryScreen`, revision/46-slot container
  read, revision-guarded empty-slot click and readback, close dispatch, and clean shutdown. The tested
  artifact SHA-256 is `e341cd8244621ca9a07829f4b6d735e9edc7545d95015efb67f1ea00e6e81f85`.
- Fabric 1.21.1 now has the same retained live menu/container evidence: 100 main-menu records, 33
  world records, inventory key dispatch into `InventoryScreen`, revision/46-slot container read,
  revision-guarded empty-slot click and readback, close dispatch, and clean shutdown. The tested
  artifact SHA-256 is `df29dd3a20ed595f977f6b93c8ad6856c887a383aec0c515e2fac497d8b89905`.

## Forge container/menu evidence added 2026-07-15

- Forge 1.20.1 now has retained live menu/container evidence on the exact artifact: 102 main-menu
  records, 30 world records, residual pause-screen recovery, inventory key dispatch into
  `InventoryScreen`, revision/46-slot container read, revision-guarded empty-slot click and readback,
  close dispatch, and clean shutdown. The tested artifact SHA-256 is
  `36652b426bedcf554937f848b8c28793a01633032f82ead5f6d057df0c3df0c7`.
- Forge 1.21.1 now has the same retained live menu/container evidence: 102 main-menu records, 30
  world records, residual pause-screen recovery, inventory key dispatch into `InventoryScreen`,
  revision/46-slot container read, revision-guarded empty-slot click and readback, close dispatch,
  and clean shutdown. The tested artifact SHA-256 is
  `c86777d97800dc4bad57f50af818c80d0db0832312e05028f4d389ac83d5c194`.

## INTENTION NOT MATCHED

- Full manual acceptance beyond the tested NeoForge/Fabric 1.18.2/1.19.2/1.20.1/1.21.1,
  Fabric 26.2, Forge 1.20.1, and Forge 1.21.1 client flows is still pending; 26.2 heightmap/light/move/slot/chat-read and
  broader client container/entity interaction remain unsupported.
- Full cross-loader semantic container automation, block-entity NBT, non-overworld bulk mutation,
  and broad cross-loader chat/event coverage remain cataloged but not implemented. Existing block
  entities are intentionally rejected rather than overwritten without NBT restoration; Fabric,
  NeoForge, and Forge client container clicks have bounded revision/slot validation and are now
  manually client-tested only on the retained modern rows above.
- Bedrock remains unimplemented; Paper and Spigot are covered only by their tested 1.21.1
  Bukkit-compatible server slices, and Folia by its tested 1.21.4 scheduler-aware slice. Quilt has
  compatibility evidence but no separate native adapter.
  Forge 1.8.9, 1.12.2, and 1.7.10 have the native bridges above.
- Player-connected inventory mutation, cross-loader container/NBT automation, inventory/entity
  mutation, and broad event coverage remain open; RCON intentionally does not claim those semantics.
- Remaining product scope includes richer capability input/output schemas, Bedrock and later
  Minecraft lines, full connected-player container/NBT control, and broad third-party mod
  interoperability.
- The typed coverage sweep still records honest negative coverage for unsupported WorldEdit/
  player-command paths and runtime validation guards; those are harness/catalog coverage findings,
  not claims of support. The client benchmark evidence now covers separate exact rows for NeoForge
  1.21.1, Fabric 1.20.1, Fabric 1.21.1, Forge 1.20.1, and Forge 1.21.1; other client loaders and versions remain
  separate rows.
- Vibecraft parity is not yet established: the pinned comparison inventory still has 16 implemented,
  50 partial, and 17 missing rows, and no full retained live parity suite. One bounded furniture layout
  is now live-tested on NeoForge 1.21.1; Lodestone must not claim parity for the remaining rows until
  they are independently exercised on clean pinned trees.
