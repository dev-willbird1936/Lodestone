# Getting started

Use Lodestone v1.0.0 only from its tagged GitHub release; untagged builds are development
snapshots. The current artifact evidence is
`verification/evidence/release-conformance-v1.0.0.json`, not the historical C0 baseline.

## 1. Choose one integration

Use the release compatibility table to match all three fields: Minecraft version, loader or server,
and Java version.

| Deployment | Install |
| --- | --- |
| Fabric, Quilt compatibility, Forge, or NeoForge | The one matching mod JAR in `mods/` |
| Paper, Spigot, or Folia | The one matching plugin JAR in `plugins/` |
| CurseForge test profile | Import the matching profile ZIP; do not also add another Lodestone JAR |
| Legacy/RCON fallback | Run the RCON launcher beside the server and configure its environment |

Never mix artifacts from different Minecraft versions or install two Lodestone host artifacts in
one instance. The exact supported rows and caveats live in
[compatibility-matrix.md](compatibility-matrix.md).

## 2. Start Minecraft or the server

Modern hosts and plugins start an authenticated MCP HTTP endpoint at
`http://127.0.0.1:37821/mcp`. On first launch they create `config/lodestone.token` with owner-only
permissions. Keep this token secret; do not paste it into logs, issues, or configuration committed
to Git.

An HTTP MCP client must send the token in `X-Lodestone-Token`. Lodestone accepts MCP protocol
versions `2025-11-25`, `2025-06-18`, and `2025-03-26`. Preserve the `Mcp-Session-Id` returned by
initialization for later requests in that session.

The listener intentionally binds only to IPv4 loopback. For remote use, keep Lodestone local and
use a separately secured tunnel; do not expose the endpoint directly to a LAN or the internet.

## 3. Grant the minimum permissions

Observation is the safe starting point. Additional operations remain unavailable until their
permission class is granted through `LODESTONE_PERMISSIONS` or the JVM property
`-Dlodestone.permissions=...`.

| Permission | Allows |
| --- | --- |
| `observe` | Read-only state and discovery |
| `communicate` | Chat and other communication operations |
| `control-player` | Client/player input and UI control |
| `modify-world` | World-changing operations |
| `administer-server` | Native or RCON server command administration |
| `manage-files-or-processes` | Explicit file/process management capabilities |

Example for a read-only deployment:

```text
LODESTONE_PERMISSIONS=observe
```

Restart the host after changing process environment or JVM properties. Capability discovery reports
operations as available, restricted, degraded, or unavailable; clients should honor that status
instead of assuming parity across loaders.

## 4. RCON fallback

The RCON launcher requires Java 21 and these environment variables:

```text
LODESTONE_RCON_HOST=127.0.0.1
LODESTONE_RCON_PORT=25575
LODESTONE_RCON_PASSWORD=replace-with-server-rcon-password
LODESTONE_TOKEN=replace-with-a-separate-mcp-token
LODESTONE_PERMISSIONS=administer-server
```

RCON exposes authenticated command execution only. It does not provide native player input, menu,
inventory, or structured world capabilities.

## Troubleshooting

- **401 unauthorized:** use the exact token from `config/lodestone.token` and the
  `X-Lodestone-Token` header.
- **Connection refused:** confirm the game/server finished starting, port 37821 is unused, and the
  client is on the same machine or inside the intended secure tunnel.
- **Capability is restricted:** add only its documented permission class, then restart.
- **Capability is unavailable:** check the discovered reason and the exact compatibility row; do
  not substitute a nearby loader or Minecraft version.
- **Wrong or duplicate mod:** remove all Lodestone artifacts, then install exactly one matching
  host artifact.
- **Crash or failed fresh-world load:** retain the first useful stack trace and exact asset name,
  then follow [CONTRIBUTING.md](../CONTRIBUTING.md) and [SECURITY.md](../SECURITY.md) as applicable.
