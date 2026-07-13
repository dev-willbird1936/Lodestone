// SPDX-License-Identifier: MIT
package dev.lodestone.protocol;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public record CapabilityManifest(AdapterDescriptor adapter, List<CapabilityDescriptor> capabilities) {
    public CapabilityManifest {
        Objects.requireNonNull(adapter, "adapter");
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        var ids = new HashSet<String>();
        for (var capability : capabilities) {
            Objects.requireNonNull(capability, "capability");
            if (!ids.add(capability.id())) {
                throw new IllegalArgumentException("duplicate capability id: " + capability.id());
            }
        }
    }
}
