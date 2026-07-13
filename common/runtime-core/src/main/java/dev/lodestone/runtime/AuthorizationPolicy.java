// SPDX-License-Identifier: MIT
package dev.lodestone.runtime;

import dev.lodestone.protocol.CapabilityDescriptor;
import dev.lodestone.protocol.PermissionClass;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

/** Explicit permission allow-list. A new process starts with observation only. */
public final class AuthorizationPolicy {
    private final Set<PermissionClass> allowed;

    public AuthorizationPolicy(Set<PermissionClass> allowed) {
        this.allowed = allowed == null || allowed.isEmpty()
                ? EnumSet.noneOf(PermissionClass.class) : EnumSet.copyOf(allowed);
    }

    public static AuthorizationPolicy observeOnly() {
        return new AuthorizationPolicy(Set.of(PermissionClass.OBSERVE));
    }

    public static AuthorizationPolicy fromCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return observeOnly();
        }
        var permissions = EnumSet.noneOf(PermissionClass.class);
        Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(value -> value.replace('-', '_').toUpperCase(java.util.Locale.ROOT))
                .forEach(value -> permissions.add(PermissionClass.valueOf(value)));
        return new AuthorizationPolicy(permissions);
    }

    public boolean allows(CapabilityDescriptor capability) {
        return allowed.containsAll(capability.permissions());
    }

    public AuthorizationPolicy intersect(AuthorizationPolicy ceiling) {
        var narrowed = allowed.isEmpty()
                ? EnumSet.noneOf(PermissionClass.class) : EnumSet.copyOf(allowed);
        narrowed.retainAll(ceiling == null ? Set.of() : ceiling.allowed);
        return new AuthorizationPolicy(narrowed);
    }

    public Set<PermissionClass> allowed() {
        return Set.copyOf(allowed);
    }
}
