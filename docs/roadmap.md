# Roadmap

1. Scaffold, protocol schemas, fixtures, and provenance checks.
2. Protocol model and contract validation.
3. Adapter API and runtime core.
4. MCP gateway.
5. NeoForge 1.21.1 query, command, input, action, and event vertical slices.
6. Real-game evidence and first protocol release.
7. Fabric 1.21.1 adapter and shared cross-loader evidence.
8. Fabric 1.20.1 long-lived pack-line evidence.
9. Add a loader-neutral RCON fallback profile with bounded authenticated command execution and
   live evidence across Fabric and NeoForge.
10. Add Quilt Loader compatibility coverage through the proven Fabric 1.20.1 host path; keep
    Quilt-native APIs separate if a later capability requires them.
11. Completed: Fabric 26.2 non-remapping support with Java 25, an isolated Gradle 9.5.1 build
    path, and the documented client-only Vulkan acceptance boundary.
12. Completed: Forge 1.21.1 and 1.20.1 native adapter/hosts plus Java 8 Forge 1.12.2, 1.7.10,
    and 1.8.9 native bridges, with RCON fallback coverage retained across all three legacy rows.
13. Deepen native adapters next: player-connected inventory/container state and event coverage
    with explicit permission and threading contracts; then extend world writes to block-entity
    NBT, regions, and additional dimensions before prioritizing Forge/Bukkit-family coverage.
14. Completed: Paper 1.21.1 server-plugin coverage with a Bukkit/Paper main-thread adapter and
    live MCP evidence.
15. Completed: Spigot 1.21.1 and Folia 1.21.4 server-plugin coverage with distinct host/adapter
    artifacts, live MCP evidence, and explicit global/region/entity scheduler boundaries. Folia
     1.21.1 remains pending because the official build service does not currently publish it.
16. Completed hardening pass: caller-isolated runtime sequencing/rate limits, fail-fast timeout
    quarantine, bounded HTTP body reads, atomic token creation, legacy bridge identity checks,
    chunk-batched Folia volume reads, failed-initialize session cleanup, fresh launcher/profile
    packaging, and rebuilt Java 8 bridge artifacts. Remaining work is deeper player/container/NBT
    control, Bedrock/later-line adapters, and broader third-party interoperability.
17. Completed: Fabric 1.19.2 compatibility adapter/host, Fabric Loader 0.14.25 profile, Java 17
    build, and fresh-world packaged MCP acceptance. The catalog loader now constructs record-backed
    protocol values explicitly so older Minecraft-bundled Gson versions do not crash startup.
18. Completed: Forge 1.19.2 compatibility adapter/host, Forge 43.5.2 profile, Java 17 official
    server staging, and fresh-world packaged MCP acceptance.
19. Completed: Forge 1.18.2 compatibility adapter/host, Forge 40.3.12 profile, Java 17 official
    server staging, version-specific command/chat API adaptation, and fresh-world packaged MCP
    acceptance.
20. Completed: Fabric 1.18.2 compatibility adapter/host, Fabric Loader 0.14.25 profile, Fabric
    API 0.77.0+1.18.2 module-graph staging, Java 17 build, and fresh-world packaged MCP
    acceptance.
21. Completed: Fabric 1.18.2/1.19.2/1.20.1 client host bridges for bounded key/mouse, movement,
    look, interaction, hotbar, UI-state, and UI-key control; all three hosts compile, package,
    and the updated server artifacts pass fresh-world acceptance.
22. Completed: `minecraft.inventory.container.read` normalized open-container snapshots with
    stable slot projections and revisions on Fabric 1.18.2/1.19.2/1.20.1/1.21.1 and NeoForge
    1.21.1; all affected hosts compile, package, and pass fresh-world acceptance. Fabric click
    paths enforce revision, slot, and button bounds.
23. Completed: Forge 1.16.5 / Forge 36.2.42 native adapter and host. It uses ForgeGradle 5.1,
    Gradle 7.6.4, exact 1.16.5 MCP mappings, an isolated Java 17 launch with required module
    openings, an importable profile, and fresh-world packaged MCP acceptance.
24. Completed: post-advisor compatibility hardening. Modern packaged rows now apply and read back
    a bounded world write, Fabric standalone manifests are exact-pinned, Quilt has an explicit
    compatibility-host variant, rejected idempotency requests no longer poison retry keys,
    generated runtime `runs/` state is ignored, and modern/plugin block-write batches restore
    prior states in reverse order after cancellation or native mutation failure.
25. Completed: Quilt Loader 0.29.2 compatibility coverage on Minecraft 1.21.1, including exact
    Fabric API 0.116.9+1.21.1 staging, generated compatibility metadata, a CurseForge profile,
    and the full fresh-world applied-write/readback gate.
26. Completed: final adversarial contract and mutation hardening. Schema-exact wire statuses and
    nullable fields are live-tested; mutation timeout races use an atomic commit boundary; existing
    block entities fail closed until NBT-safe writes exist; Java 8 bridges share transactional
    rollback and owner-only token storage; all prepared native/plugin/RCON rows pass fresh-world
    acceptance after the fixes.
