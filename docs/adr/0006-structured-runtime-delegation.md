# ADR 0006: Structured runtime delegation

Status: accepted

## Decision

Runtime workflows are `lodestone.*` capabilities explicitly marked `delegates-native`. They may delegate only to native
`minecraft.*` capabilities; `lodestone.system.*`, `minecraft.event.*`, and native handlers never
receive a delegated invoker. This depth-one boundary prevents cross-workflow wait cycles.

Every root invocation owns one cancellation tree. Mutation commit and cancellation linearize under
the same lock. A successful child commit atomically marks its ancestors; cancellation always stops
later commits. Cancel-before-commit therefore proves no native dispatch, while commit-before-cancel
forces a non-retryable indeterminate outcome if acknowledgement is lost.

A workflow owns every child it starts. The scope seals when the handler stage ends, rejects escaped
late invocations, and waits for both each child's semantic result and underlying termination. An
ignored or recovered child failure still fails the parent. Public future cancellation requests tree
cancellation first and cannot claim cancellation after a committed mutation.

Caller grants are frozen at the root, intersected with the process permission ceiling, and inherited
by children. Workflow permissions must include every child permission. The additive
`lodestone://audit/trace` resource records transport caller and delegation path.

Delegated mutation idempotency uses length-prefixed SHA-256 domains containing the root seed, full
path, step ID, target capability, and version. A step ID denotes one logical occurrence. Polling uses
deterministic monotonic IDs (`poll.0`, `poll.1`, ...); side-effect-free idempotent reads are not cached
between iterations. Live cache entries cannot be pruned or evicted before underlying termination.

`minecraft.event.poll` is a caller-owned at-most-once drain, not a Minecraft mutation boundary.
Workflows that wait for UI state poll `minecraft.ui.state.read` asynchronously and never use event
drains as a timer.
