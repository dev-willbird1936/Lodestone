// SPDX-License-Identifier: MIT
package dev.lodestone.protocol;

public enum PermissionClass {
    OBSERVE,
    COMMUNICATE,
    CONTROL_PLAYER,
    MODIFY_WORLD,
    ADMINISTER_SERVER,
    MANAGE_FILES_OR_PROCESSES;

    @Override
    public String toString() {
        return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }
}
