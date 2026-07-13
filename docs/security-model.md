# Security model

Mutations are default-deny. Permission classes: `observe`, `communicate`, `control-player`,
`modify-world`, `administer-server`, and `manage-files-or-processes`.

All capabilities declare side effects, input schemas, deadlines, cancellation behavior, and audit
metadata. Loopback is the default listener scope; non-loopback exposure requires explicit
configuration and replaceable authentication. Arbitrary native/RCON commands require
`administer-server`; communicate authorization alone cannot execute console commands.

Generated token files are created atomically and restricted to the current owner before secret
bytes are written. The Java 8 bridge implementation enforces POSIX owner read/write permissions or
a Windows owner-only ACL and fails closed on symlinks or unsupported permission models.

World-write batches prepare prior states, apply changes transactionally, restore in reverse order
after cancellation/native failure, and atomically commit only after the final cancellation check.
This prevents a request from being reported timed out while its completed writes remain applied.
Existing block entities are rejected before mutation until Lodestone can preserve and restore their
NBT; silently replacing a container or other tile entity is not an accepted rollback strategy.
