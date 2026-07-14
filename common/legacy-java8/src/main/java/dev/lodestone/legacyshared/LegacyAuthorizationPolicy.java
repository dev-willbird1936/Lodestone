// SPDX-License-Identifier: MIT
package dev.lodestone.legacyshared;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Java 8-safe, fail-closed permission ceiling for native legacy Forge bridge endpoints. */
public final class LegacyAuthorizationPolicy {
    public static final String OBSERVE = "observe";
    public static final String MODIFY_WORLD = "modify-world";
    public static final String COMMUNICATE = "communicate";
    public static final String ADMINISTER_SERVER = "administer-server";

    private final Set<String> allowed;

    private LegacyAuthorizationPolicy(Set<String> allowed) {
        this.allowed = Collections.unmodifiableSet(new HashSet<String>(allowed));
    }

    public static LegacyAuthorizationPolicy fromConfiguredEnvironment() {
        return fromCsv(System.getProperty("lodestone.permissions", System.getenv("LODESTONE_PERMISSIONS")));
    }

    public static LegacyAuthorizationPolicy fromCsv(String csv) {
        Set<String> values = new HashSet<String>();
        if (csv == null || csv.trim().length() == 0) {
            values.add(OBSERVE);
            return new LegacyAuthorizationPolicy(values);
        }
        String[] entries = csv.split(",");
        for (String entry : entries) {
            String value = entry == null ? "" : entry.trim();
            if (OBSERVE.equals(value) || MODIFY_WORLD.equals(value) || COMMUNICATE.equals(value)
                    || ADMINISTER_SERVER.equals(value)) {
                values.add(value);
            } else if (value.length() > 0) {
                throw new IllegalArgumentException("unknown Lodestone permission: " + value);
            }
        }
        return new LegacyAuthorizationPolicy(values);
    }

    /** Returns the only authority that permits the capability, or {@code null} if unsupported. */
    public static String requiredPermission(String capability) {
        if ("minecraft.command.execute".equals(capability)) return ADMINISTER_SERVER;
        if ("minecraft.world.blocks.write".equals(capability)) return MODIFY_WORLD;
        if ("minecraft.chat.send".equals(capability)) return COMMUNICATE;
        if ("minecraft.player.state.read".equals(capability)
                || "minecraft.world.block.read".equals(capability)
                || "minecraft.world.blocks.read".equals(capability)
                || "minecraft.world.region.scan".equals(capability)
                || "minecraft.entity.list".equals(capability)
                || "minecraft.inventory.read".equals(capability)) {
            return OBSERVE;
        }
        return null;
    }

    public boolean allows(String capability) {
        String required = requiredPermission(capability);
        return required != null && allowed.contains(required);
    }

    public String deniedMessage(String capability) {
        String required = requiredPermission(capability);
        return required == null
                ? "capability is unavailable on this legacy bridge: " + capability
                : "capability requires explicit permission '" + required + "': " + capability;
    }
}
