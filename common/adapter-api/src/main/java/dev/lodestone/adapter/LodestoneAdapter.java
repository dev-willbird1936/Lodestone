// SPDX-License-Identifier: MIT
package dev.lodestone.adapter;

import dev.lodestone.protocol.AdapterDescriptor;
import dev.lodestone.protocol.CapabilityManifest;

import java.util.Map;

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
}
