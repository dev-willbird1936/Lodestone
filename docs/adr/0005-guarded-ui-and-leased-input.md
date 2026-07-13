# ADR 0005: Guard UI mutation and lease logical input

## Decision

`minecraft.ui.state.read` and `minecraft.ui.click` use capability contract version `2.0`.
UI reads return a bounded typed tree, explicit projection coverage, a per-screen `screenToken`, and a
content-derived `snapshotRevision`. Every click supplies both concurrency values. Native validation
rejects a click when either value is stale before dispatching any input.

Clicks select exactly one target: `nodeId`, `path`, `label`, or the coordinate pair `x` and `y`. The
published schema and native `UiSelector` both enforce selector exclusivity; labels must resolve
unambiguously before mutation. `button` is optional and defaults to the primary mouse button (`0`).

UI projection coverage is `complete`, `partial`, or `opaque`. Stable truncation causes are
`depth-limit`, `node-limit`, `child-limit`, `unsupported-widget`, and `opaque-screen`. Implementations
fail capture errors instead of reporting them as valid partial trees.

`minecraft.player.move` uses capability contract version `2.0`. Movement includes sneak state and a
finite `durationMs` lease from 1 through 10,000 ms, defaulting to 100 ms. Lease expiry releases movement
keys. `minecraft.input.release-all` is the idempotent emergency cleanup primitive: it releases all
Lodestone-owned logical inputs and advances `leaseGeneration` so old expiry work cannot affect newer
input state.

`minecraft.inventory.craft` remains experimental and unavailable. Its strict item/count contract is
discoverable, but no adapter may advertise it as available until a real native actor exists.

## Consequences

The UI and movement `2.0` contracts are breaking changes. Clients must read a fresh UI snapshot before
each guarded click and must renew finite movement leases instead of relying on indefinite held input.
Semantic selectors survive layout movement, while stale or ambiguous state fails closed. Bounded trees
and stable truncation metadata let clients distinguish full observation from partial or opaque screens.
