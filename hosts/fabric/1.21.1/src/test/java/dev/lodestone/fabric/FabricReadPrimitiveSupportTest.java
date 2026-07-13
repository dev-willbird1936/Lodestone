// SPDX-License-Identifier: MIT
package dev.lodestone.fabric;

import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FabricReadPrimitiveSupportTest {
    @Test
    void appliesDefaultsAndRejectsUnsafeNumericBounds() {
        assertEquals(20, FabricReadPrimitiveSupport.boundedInt(Map.of(), "limit", 20, 1, 50));
        assertEquals(32.0, FabricReadPrimitiveSupport.boundedDouble(Map.of(), "radius", 32.0, 1.0, 256.0));

        assertThrows(IllegalArgumentException.class,
                () -> FabricReadPrimitiveSupport.boundedInt(Map.of("limit", 0), "limit", 20, 1, 50));
        assertThrows(IllegalArgumentException.class,
                () -> FabricReadPrimitiveSupport.boundedDouble(
                        Map.of("radius", Double.NaN), "radius", 32.0, 1.0, 256.0));
        assertThrows(IllegalArgumentException.class,
                () -> FabricReadPrimitiveSupport.boundedDouble(
                        Map.of("radius", 257.0), "radius", 32.0, 1.0, 256.0));
    }

    @Test
    void sortsBeforeLimitingAndReportsTruncation() {
        var bounded = FabricReadPrimitiveSupport.sortedBounded(
                List.of("zombie", "allay", "creeper"), Comparator.naturalOrder(), 2);

        assertEquals(List.of("allay", "creeper"), bounded.values());
        assertTrue(bounded.truncated());

        var complete = FabricReadPrimitiveSupport.sortedBounded(
                List.of("zombie", "allay"), Comparator.naturalOrder(), 2);
        assertEquals(List.of("allay", "zombie"), complete.values());
        assertFalse(complete.truncated());
    }

    @Test
    void boundsUntrustedDisplayTextWithoutSplittingASurrogatePair() {
        assertEquals("abcd", FabricReadPrimitiveSupport.boundedText("abcdef", 4));
        assertEquals("abc\uD83D\uDE00", FabricReadPrimitiveSupport.boundedText("abc\uD83D\uDE00z", 4));
    }

    @Test
    void validatesSchemaStringsByUnicodeCodePointWithoutRejectingWhitespace() {
        assertEquals(" ", FabricReadPrimitiveSupport.requiredSchemaText(
                Map.of("query", " "), "query", 256));
        assertEquals("\uD83D\uDE00", FabricReadPrimitiveSupport.requiredSchemaText(
                Map.of("query", "\uD83D\uDE00"), "query", 1));
        assertNull(FabricReadPrimitiveSupport.optionalSchemaText(Map.of(), "type", 256));

        assertThrows(IllegalArgumentException.class,
                () -> FabricReadPrimitiveSupport.requiredSchemaText(Map.of("query", ""), "query", 256));
        assertThrows(IllegalArgumentException.class,
                () -> FabricReadPrimitiveSupport.requiredSchemaText(
                        Map.of("query", "\uD83D\uDE00\uD83D\uDE00"), "query", 1));
    }
}
