# Security model

Mutations are default-deny. Permission classes: `observe`, `communicate`, `control-player`,
`modify-world`, `administer-server`, and `manage-files-or-processes`.

All capabilities declare side effects, input schemas, deadlines, cancellation behavior, and audit
metadata. Loopback is the default listener scope; non-loopback exposure requires explicit
configuration and replaceable authentication. Arbitrary native/RCON commands require
`administer-server`; communicate authorization alone cannot execute console commands.

The in-game hosts' loopback MCP endpoint is zero-config: it requires no token. Its trust boundary
is the local machine, not a shared secret. The listener binds only IPv4 loopback, accepts only
POST, and rejects any request whose `Origin` or `Host` header names something other than the
loopback listener, which blocks browser-driven access such as CSRF and DNS rebinding. Any local
process can therefore use the endpoint at the granted permission level; on a machine whose local
processes are not trusted, grant only narrow permission classes. The launcher transports keep
token authentication: the RCON and legacy-bridge launchers require `LODESTONE_TOKEN` for their own
MCP endpoints because they bridge onward to servers that may not be local.

Generated token files (now read by the launcher transports and discovery tooling rather than the
in-game hosts' loopback HTTP listener) are created atomically and restricted to the current owner
before secret bytes are written. The Java 8 bridge implementation enforces POSIX owner read/write permissions or
a Windows owner-only ACL and fails closed on symlinks or unsupported permission models.

World-write batches prepare prior states, apply changes transactionally, restore in reverse order
after cancellation/native failure, and atomically commit only after the final cancellation check.
This prevents a request from being reported timed out while its completed writes remain applied.
Existing block entities are rejected before mutation until Lodestone can preserve and restore their
NBT; silently replacing a container or other tile entity is not an accepted rollback strategy.
