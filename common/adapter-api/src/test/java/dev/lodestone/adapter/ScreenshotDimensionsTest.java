// SPDX-License-Identifier: MIT
package dev.lodestone.adapter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ScreenshotDimensionsTest {
    @Test
    void preservesAspectRatioWithoutUpscaling() {
        assertEquals(new ScreenshotDimensions(1920, 1080),
                ScreenshotDimensions.fit(2560, 1440, 1920, 1080, 16_777_216L));
        assertEquals(new ScreenshotDimensions(1728, 1080),
                ScreenshotDimensions.fit(1920, 1200, 1920, 1080, 16_777_216L));
        assertEquals(new ScreenshotDimensions(800, 600),
                ScreenshotDimensions.fit(800, 600, 1920, 1080, 16_777_216L));
    }

    @Test
    void enforcesTotalPixelBoundAfterAxisBounds() {
        assertEquals(new ScreenshotDimensions(4096, 4096),
                ScreenshotDimensions.fit(32768, 32768, 8192, 8192, 16_777_216L));
        assertEquals(new ScreenshotDimensions(1, 8192),
                ScreenshotDimensions.fit(1, 32768, 8192, 8192, 16_777_216L));
    }

    @Test
    void rejectsInvalidSourceAndRequestDimensions() {
        assertThrows(IllegalArgumentException.class,
                () -> ScreenshotDimensions.fit(0, 1080, 1920, 1080, 16_777_216L));
        assertThrows(IllegalArgumentException.class,
                () -> ScreenshotDimensions.fit(1920, 1080, 0, 1080, 16_777_216L));
        assertThrows(IllegalArgumentException.class,
                () -> ScreenshotDimensions.fit(1920, 1080, 1920, 1080, 0L));
    }
}
