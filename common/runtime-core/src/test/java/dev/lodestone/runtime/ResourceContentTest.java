// SPDX-License-Identifier: MIT
package dev.lodestone.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ResourceContentTest {
    @Test
    void binaryContentDefensivelyCopiesBytesAndRejectsTextAccess() {
        var source = new byte[] {1, 2, 3};
        var content = ResourceContent.binary("image/png", source);
        source[0] = 9;

        assertTrue(content.binary());
        assertArrayEquals(new byte[] {1, 2, 3}, content.bytes());
        var copy = content.bytes();
        copy[0] = 9;
        assertArrayEquals(new byte[] {1, 2, 3}, content.bytes());
        assertThrows(IllegalStateException.class, content::text);
    }

    @Test
    void textContentPreservesTheTrustedStaticResourceShape() {
        var content = ResourceContent.text("application/json", "{}");
        assertFalse(content.binary());
        assertEquals("{}", content.text());
        assertArrayEquals("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8), content.bytes());
    }
}
