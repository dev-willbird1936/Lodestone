// SPDX-License-Identifier: MIT
package dev.lodestone.protocol;

public record CapabilityPrerequisites(boolean requiresWorld, boolean requiresPlayer, boolean requiresScreen,
                                      boolean requiresContainer) {
}
