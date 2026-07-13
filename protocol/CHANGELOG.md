# Protocol changelog

## 0.3.0-draft

- Break `minecraft.ui.state.read` and `minecraft.ui.click` to capability version `2.0`: bounded typed
  UI trees, explicit coverage/truncation metadata, and stale-state guards using `screenToken` plus
  `snapshotRevision`.
- Break `minecraft.player.move` to capability version `2.0`: add sneak state and a finite 1-10,000 ms
  lease, with `leaseGeneration` in the strict result.
- Add restricted `minecraft.input.release-all` capability for idempotent logical-input cleanup.
- Publish a strict experimental `minecraft.inventory.craft` item/count contract while keeping the
  capability honestly unavailable until a native actor exists.

## 0.1.0-draft

- Initial handshake, capability, request, result, event, and error contracts.
