// SPDX-License-Identifier: MIT
package dev.lodestone.adapter;

import java.util.Map;

/** GUI-scaled bounds for one projected client UI node. */
public record UiBounds(int x, int y, int width, int height) {
    public UiBounds {
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException("UI bounds must not have negative size");
        }
    }

    public double centerX() {
        return x + width / 2.0;
    }

    public double centerY() {
        return y + height / 2.0;
    }

    public Map<String, Object> toMap() {
        return Map.of("x", x, "y", y, "width", width, "height", height);
    }
}
