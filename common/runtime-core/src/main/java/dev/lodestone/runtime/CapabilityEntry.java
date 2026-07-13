// SPDX-License-Identifier: MIT
package dev.lodestone.runtime;

import dev.lodestone.adapter.CapabilityHandler;
import dev.lodestone.adapter.LodestoneAdapter;
import dev.lodestone.protocol.CapabilityDescriptor;

public record CapabilityEntry(CapabilityDescriptor descriptor, LodestoneAdapter adapter,
                              CapabilityHandler handler) {
}
