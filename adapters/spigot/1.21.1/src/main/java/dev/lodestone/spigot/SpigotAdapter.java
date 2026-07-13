// SPDX-License-Identifier: MIT
package dev.lodestone.spigot;

import dev.lodestone.adapter.AdapterContext;
import dev.lodestone.adapter.AdapterHealth;
import dev.lodestone.adapter.CapabilityHandler;
import dev.lodestone.adapter.LodestoneAdapter;
import dev.lodestone.paper.PaperAdapter;
import dev.lodestone.protocol.AdapterDescriptor;
import dev.lodestone.protocol.Availability;
import dev.lodestone.protocol.AvailabilityReason;
import dev.lodestone.protocol.CapabilityDescriptor;
import dev.lodestone.protocol.CapabilityManifest;
import dev.lodestone.protocol.Environment;
import org.bukkit.plugin.Plugin;

import java.util.LinkedHashMap;
import java.util.Map;

/** Spigot host boundary over the Bukkit-only Paper adapter implementation. */
public final class SpigotAdapter implements LodestoneAdapter {
    public static final String ADAPTER_ID = "lodestone.spigot.mc1_21_1";

    private final PaperAdapter delegate;
    private final AdapterDescriptor descriptor = new AdapterDescriptor(
            ADAPTER_ID, "0.1.0", "minecraft-java", "1.21.1", "spigot", Environment.DEDICATED_SERVER);

    public SpigotAdapter(Plugin plugin) {
        this.delegate = new PaperAdapter(plugin);
    }

    @Override
    public AdapterDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public CapabilityManifest manifest() {
        var capabilities = delegate.manifest().capabilities().stream()
                .map(this::adapt)
                .toList();
        return new CapabilityManifest(descriptor, capabilities);
    }

    @Override
    public Map<String, CapabilityHandler> handlers() {
        return Map.copyOf(new LinkedHashMap<>(delegate.handlers()));
    }

    @Override
    public void start(AdapterContext context) {
        delegate.start(context);
    }

    @Override
    public AdapterHealth health() {
        var health = delegate.health();
        return new AdapterHealth(health.state(), health.message().replace("Paper", "Spigot"), health.observedAt());
    }

    public void publishEvent(String event, Map<String, Object> payload) {
        delegate.publishEvent(event, payload);
    }

    public void refreshWorldAvailability() {
        delegate.refreshWorldAvailability();
    }

    public void markWorldUnavailable(org.bukkit.World world) {
        delegate.markWorldUnavailable(world);
    }

    @Override
    public void close() {
        delegate.close();
    }

    private CapabilityDescriptor adapt(CapabilityDescriptor capability) {
        if (capability.availability() == Availability.UNAVAILABLE) {
            return capability.forAdapter(descriptor, Availability.UNAVAILABLE,
                    new AvailabilityReason("not-implemented",
                            "This Spigot 1.21.1 adapter does not implement the operation yet.",
                            Map.of("adapter", ADAPTER_ID)));
        }
        if (capability.availability() == Availability.DEGRADED) {
            return capability.forAdapter(descriptor, Availability.DEGRADED,
                    new AvailabilityReason("server-not-ready", "Spigot is loaded without a ready world.", Map.of()));
        }
        return capability.forAdapter(descriptor, capability.availability(),
                capability.availability() == Availability.RESTRICTED ? capability.reason() : null);
    }
}
