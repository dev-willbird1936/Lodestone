// SPDX-License-Identifier: MIT
package dev.lodestone.adapter;

/** Pure scaling contract shared by version-specific client screenshot adapters. */
public record ScreenshotDimensions(int width, int height) {
    public ScreenshotDimensions {
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("screenshot dimensions must be positive");
        }
    }

    /**
     * Fits an image within axis and total-pixel limits while preserving aspect ratio, flooring
     * scaled dimensions, and never upscaling the source image.
     */
    public static ScreenshotDimensions fit(int originalWidth, int originalHeight,
                                           int maxWidth, int maxHeight, long maxPixels) {
        if (originalWidth < 1 || originalHeight < 1) {
            throw new IllegalArgumentException("source dimensions must be positive");
        }
        if (maxWidth < 1 || maxHeight < 1 || maxPixels < 1L) {
            throw new IllegalArgumentException("screenshot limits must be positive");
        }

        var sourcePixels = (long) originalWidth * (long) originalHeight;
        var scale = Math.min(1.0d, Math.min(
                maxWidth / (double) originalWidth,
                maxHeight / (double) originalHeight));
        if (sourcePixels > maxPixels) {
            scale = Math.min(scale, Math.sqrt(maxPixels / (double) sourcePixels));
        }

        var width = Math.max(1, Math.min(maxWidth, (int) Math.floor(originalWidth * scale)));
        var height = Math.max(1, Math.min(maxHeight, (int) Math.floor(originalHeight * scale)));
        while ((long) width * (long) height > maxPixels) {
            if (width >= height && width > 1) {
                width--;
            } else if (height > 1) {
                height--;
            } else {
                throw new IllegalArgumentException("pixel limit cannot contain a positive image");
            }
        }
        return new ScreenshotDimensions(width, height);
    }
}
