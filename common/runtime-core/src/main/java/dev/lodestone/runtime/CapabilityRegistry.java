// SPDX-License-Identifier: MIT
package dev.lodestone.runtime;

import dev.lodestone.adapter.LodestoneAdapter;
import dev.lodestone.protocol.CapabilityDescriptor;
import dev.lodestone.protocol.Availability;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CapabilityRegistry {
    private final Map<String, CapabilityEntry> entries = new LinkedHashMap<>();

    public synchronized void register(LodestoneAdapter adapter) {
        var replacement = new LinkedHashMap<>(entries);
        addTo(adapter, replacement);
        entries.clear();
        entries.putAll(replacement);
    }

    private void addTo(LodestoneAdapter adapter, Map<String, CapabilityEntry> target) {
        adapter.manifest().capabilities().forEach(descriptor -> {
            var handler = adapter.handlers().get(descriptor.id());
            if ((descriptor.availability() == Availability.AVAILABLE
                    || descriptor.availability() == Availability.RESTRICTED) && handler == null) {
                throw new IllegalArgumentException("invocable capability has no handler: " + descriptor.id());
            }
            var existing = target.get(descriptor.id());
            if (existing != null && (existing.descriptor().availability() == Availability.AVAILABLE
                    || existing.descriptor().availability() == Availability.RESTRICTED)) {
                if (descriptor.availability() == Availability.AVAILABLE
                        || descriptor.availability() == Availability.RESTRICTED) {
                    throw new IllegalArgumentException("duplicate invocable capability: " + descriptor.id());
                }
                return;
            }
            target.put(descriptor.id(), new CapabilityEntry(descriptor, adapter, handler));
        });
    }

    public synchronized CapabilityEntry get(String id) {
        return entries.get(id);
    }

    public synchronized List<CapabilityDescriptor> list() {
        return entries.values().stream()
                .map(CapabilityEntry::descriptor)
                .sorted(Comparator.comparing(CapabilityDescriptor::id))
                .toList();
    }

    public synchronized List<CapabilityDescriptor> search(String query) {
        var normalized = query == null ? "" : query.toLowerCase();
        return list().stream()
                .filter(capability -> normalized.isBlank()
                        || capability.id().toLowerCase().contains(normalized)
                        || capability.documentation().toLowerCase().contains(normalized))
                .toList();
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized void refresh(dev.lodestone.adapter.LodestoneAdapter adapter) {
        var replacement = new LinkedHashMap<>(entries);
        replacement.entrySet().removeIf(entry -> entry.getValue().adapter() == adapter);
        addTo(adapter, replacement);
        entries.clear();
        entries.putAll(replacement);
    }
}
