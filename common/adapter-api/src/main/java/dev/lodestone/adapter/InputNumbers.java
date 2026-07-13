// SPDX-License-Identifier: MIT
package dev.lodestone.adapter;

/** Checked numeric conversions at the native adapter boundary. */
public final class InputNumbers {
    private InputNumbers() {
    }

    public static int exactInt(Number value, String key) {
        if (value == null) {
            throw new IllegalArgumentException("input field must be numeric: " + key);
        }
        var decimal = value.doubleValue();
        if (!Double.isFinite(decimal) || decimal != Math.rint(decimal)
                || decimal < Integer.MIN_VALUE || decimal > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("input field is outside the 32-bit integer range: " + key);
        }
        return value.intValue();
    }

    public static void requireRegionBounds(int x, int y, int z, int sizeX, int sizeY, int sizeZ) {
        requireAxisBounds("x", x, sizeX);
        requireAxisBounds("y", y, sizeY);
        requireAxisBounds("z", z, sizeZ);
    }

    private static void requireAxisBounds(String axis, int origin, int size) {
        var end = (long) origin + size - 1;
        if (end > Integer.MAX_VALUE || end < Integer.MIN_VALUE) {
            throw new IllegalArgumentException("region exceeds the 32-bit coordinate range on " + axis);
        }
    }
}
