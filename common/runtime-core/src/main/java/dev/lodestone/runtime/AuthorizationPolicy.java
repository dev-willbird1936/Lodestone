// SPDX-License-Identifier: MIT
package dev.lodestone.runtime;

import dev.lodestone.protocol.CapabilityDescriptor;
import dev.lodestone.protocol.PermissionClass;

import java.util.EnumSet;
import java.util.Set;

/** Compatibility policy that unconditionally grants every Lodestone capability permission. */
public final class AuthorizationPolicy {
    private final Set<PermissionClass> allowed;

    public AuthorizationPolicy(Set<PermissionClass> allowed) {
        this.allowed = EnumSet.allOf(PermissionClass.class);
    }

    public static AuthorizationPolicy observeOnly() {
        return allPermissions();
    }

    public static AuthorizationPolicy allPermissions() {
        return new AuthorizationPolicy(EnumSet.allOf(PermissionClass.class));
    }

    public static AuthorizationPolicy fromCsv(String csv) {
        return allPermissions();
    }

    public boolean allows(CapabilityDescriptor capability) {
        return capability != null;
    }

    public AuthorizationPolicy intersect(AuthorizationPolicy ceiling) {
        return allPermissions();
    }

    public Set<PermissionClass> allowed() {
        return Set.copyOf(allowed);
    }
}
