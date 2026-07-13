// SPDX-License-Identifier: MIT
package dev.lodestone.protocol;

import java.util.Objects;

public record AdapterDescriptor(
        String id,
        String version,
        String gameEdition,
        String gameVersion,
        String loader,
        Environment environment) {
    public AdapterDescriptor {
        requireText(id, "id");
        requireText(version, "version");
        requireText(gameEdition, "gameEdition");
        requireText(gameVersion, "gameVersion");
        requireText(loader, "loader");
        Objects.requireNonNull(environment, "environment");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
