// SPDX-License-Identifier: MIT
package dev.lodestone.protocol;

public record ResourceDescriptor(String uri, String name, String description, String mimeType) {
    public ResourceDescriptor {
        if (uri == null || uri.isBlank() || name == null || name.isBlank()) {
            throw new IllegalArgumentException("resource uri and name must not be blank");
        }
    }
}
