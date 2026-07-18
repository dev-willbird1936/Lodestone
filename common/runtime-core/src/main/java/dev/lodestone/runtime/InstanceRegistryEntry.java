// SPDX-License-Identifier: MIT
package dev.lodestone.runtime;

import java.time.Instant;

/**
 * Discovery record for one running Lodestone-enabled instance. Written by {@link InstanceRegistry}
 * to a well-known, instance-independent location so a caller can find "which of my Minecraft
 * instances is currently running Lodestone" without already knowing the instance's own folder.
 *
 * <p>{@code tokenFile} points at the instance's existing token file rather than duplicating the
 * token value itself; see {@link InstanceRegistry} for the reasoning.
 */
public record InstanceRegistryEntry(
        int port,
        String tokenFile,
        long pid,
        String workingDir,
        String modVersion,
        Instant startedAtUtc) {
}
