// SPDX-License-Identifier: MIT
package dev.lodestone.adapter;

import dev.lodestone.protocol.AdapterDescriptor;
import dev.lodestone.protocol.CapabilityManifest;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Loader-neutral boundary. Native loader APIs belong only behind this interface. */
public interface LodestoneAdapter extends AutoCloseable {
    AdapterDescriptor descriptor();

    CapabilityManifest manifest();

    Map<String, CapabilityHandler> handlers();

    default AdapterHealth health() {
        return new AdapterHealth(AdapterHealth.State.READY, "adapter is ready", null);
    }

    default void start(AdapterContext context) {
    }

    @Override
    default void close() {
    }

    /**
     * Force any residual client-side activity this adapter may still be driving into a quiescent
     * state (cancel active native goal actors, release held input), then report fresh
     * authoritative state. This backs {@code minecraft.session.reconcile}, which the runtime uses
     * to safely clear an indeterminate-outcome mutation quarantine (see LodestoneRuntime's
     * SHARED_MUTATION_ORDER): an indeterminate outcome can mean the action is still actively
     * running game-side even though the runtime's own tracking of that invocation gave up, so the
     * quarantine can only be cleared once an adapter explicitly confirms things have actually
     * stopped - never by inference from re-observed state alone.
     * <p>
     * The default implementation reports that it cannot confirm quiescence, so callers must not
     * treat "no override" as success. Adapters that can drive native game/player state (e.g. the
     * NeoForge 1.21.1 adapter) must override this to actually quiesce and report {@code true}.
     */
    default CompletionStage<Map<String, Object>> reconcileSession() {
        return CompletableFuture.completedFuture(Map.of(
                "quiesced", false,
                "reason", "adapter does not support session reconciliation"));
    }
}
