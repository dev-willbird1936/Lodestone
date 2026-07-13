// SPDX-License-Identifier: MIT
package dev.lodestone.runtime;

import dev.lodestone.adapter.CapabilityHandler;
import dev.lodestone.adapter.LodestoneAdapter;
import dev.lodestone.protocol.AdapterDescriptor;
import dev.lodestone.protocol.Availability;
import dev.lodestone.protocol.AvailabilityReason;
import dev.lodestone.protocol.CapabilityDescriptor;
import dev.lodestone.protocol.CapabilityManifest;
import dev.lodestone.protocol.Environment;

import java.util.List;
import java.util.Map;

/** Honest compatibility surface for Vibecraft's unshipped structured building-pattern data. */
final class BuildingPatternWorkflowAdapter implements LodestoneAdapter {
    static final String CAPABILITY_ID = "lodestone.building.pattern.place";

    private final AdapterDescriptor descriptor = new AdapterDescriptor(
            "lodestone.workflow", "0.3.0", "minecraft", "negotiated", "runtime", Environment.REMOTE);
    private final CapabilityDescriptor contract = CoreCatalog.load().stream()
            .filter(capability -> capability.id().equals(CAPABILITY_ID))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("core catalog is missing " + CAPABILITY_ID));

    @Override
    public AdapterDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public CapabilityManifest manifest() {
        return new CapabilityManifest(descriptor, List.of(contract.forAdapter(descriptor,
                Availability.UNAVAILABLE, new AvailabilityReason(
                        "structured-pattern-data-absent",
                        "The audited Vibecraft source does not ship building_patterns_structured.json; "
                                + "metadata-only patterns cannot be placed safely.",
                        Map.of("resource", "lodestone://vibecraft/building/patterns",
                                "missingSourceFile", "building_patterns_structured.json")))));
    }

    @Override
    public Map<String, CapabilityHandler> handlers() {
        return Map.of();
    }
}
