// SPDX-License-Identifier: MIT
package dev.lodestone.fabric;

import dev.lodestone.adapter.InputNumbers;
import dev.lodestone.adapter.InvocationAttributes;
import dev.lodestone.adapter.InvocationContext;
import dev.lodestone.adapter.ScreenshotDimensions;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/** Pure JDK projection and lifecycle boundary for asynchronous Fabric 26.2 screenshots. */
final class FabricScreenshotSupport {
    private static final int DEFAULT_MAX_WIDTH = 1920;
    private static final int DEFAULT_MAX_HEIGHT = 1080;
    private static final int MAX_AXIS = 8192;
    private static final int MAX_ORIGINAL_AXIS = 32768;
    private static final long MAX_PIXELS = 16_777_216L;

    private FabricScreenshotSupport() {
    }

    static ScreenshotDimensions targetDimensions(Map<String, Object> input,
                                                 int originalWidth, int originalHeight) {
        requireOriginalDimensions(originalWidth, originalHeight);
        var maxWidth = boundedAxis(input, "maxWidth", DEFAULT_MAX_WIDTH);
        var maxHeight = boundedAxis(input, "maxHeight", DEFAULT_MAX_HEIGHT);
        return ScreenshotDimensions.fit(originalWidth, originalHeight, maxWidth, maxHeight, MAX_PIXELS);
    }

    static CompletionStage<Map<String, Object>> capture(
            InvocationContext invocation, CapturedImage source, Pose pose, Executor encoder) {
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(encoder, "encoder");

        final PixelSnapshot snapshot;
        try (source) {
            invocation.cancellation().throwIfCancelled();
            var originalWidth = source.width();
            var originalHeight = source.height();
            var dimensions = targetDimensions(invocation.request().input(), originalWidth, originalHeight);
            var pixels = Objects.requireNonNull(source.getPixels(), "captured pixels");
            var expectedPixels = (long) originalWidth * (long) originalHeight;
            if (expectedPixels != pixels.length) {
                throw new IllegalArgumentException("captured pixel count does not match image dimensions");
            }
            invocation.cancellation().throwIfCancelled();
            snapshot = new PixelSnapshot(originalWidth, originalHeight, dimensions, pixels);
        } catch (Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }

        try {
            return CompletableFuture.supplyAsync(() -> {
                invocation.cancellation().throwIfCancelled();
                var png = encodePng(snapshot);
                invocation.cancellation().throwIfCancelled();
                var artifact = InvocationAttributes.requireArtifactSink(invocation).stage("image/png", png);
                return output(artifact.toMetadata(), snapshot.dimensions(),
                        snapshot.originalWidth(), snapshot.originalHeight(), pose);
            }, encoder);
        } catch (Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private static byte[] encodePng(PixelSnapshot snapshot) {
        BufferedImage original = null;
        BufferedImage rendered = null;
        try {
            original = new BufferedImage(snapshot.originalWidth(), snapshot.originalHeight(),
                    BufferedImage.TYPE_INT_ARGB);
            original.setRGB(0, 0, snapshot.originalWidth(), snapshot.originalHeight(),
                    snapshot.pixels(), 0, snapshot.originalWidth());
            var dimensions = snapshot.dimensions();
            if (dimensions.width() == snapshot.originalWidth()
                    && dimensions.height() == snapshot.originalHeight()) {
                rendered = original;
            } else {
                rendered = new BufferedImage(dimensions.width(), dimensions.height(),
                        BufferedImage.TYPE_INT_ARGB);
                var graphics = rendered.createGraphics();
                try {
                    graphics.setComposite(AlphaComposite.Src);
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                            RenderingHints.VALUE_RENDER_QUALITY);
                    if (!graphics.drawImage(original, 0, 0,
                            dimensions.width(), dimensions.height(), null)) {
                        throw new IllegalStateException("captured screenshot could not be scaled");
                    }
                } finally {
                    graphics.dispose();
                }
            }

            try (var bytes = new ByteArrayOutputStream()) {
                if (!ImageIO.write(rendered, "png", bytes)) {
                    throw new IOException("PNG image writer is unavailable");
                }
                return bytes.toByteArray();
            }
        } catch (IOException failure) {
            throw new IllegalStateException("captured screenshot could not be encoded as PNG", failure);
        } finally {
            if (rendered != null && rendered != original) rendered.flush();
            if (original != null) original.flush();
        }
    }

    private static Map<String, Object> output(Map<String, Object> artifact,
                                              ScreenshotDimensions dimensions,
                                              int originalWidth, int originalHeight, Pose pose) {
        var result = new LinkedHashMap<String, Object>();
        result.put("artifact", artifact);
        result.put("width", dimensions.width());
        result.put("height", dimensions.height());
        result.put("originalWidth", originalWidth);
        result.put("originalHeight", originalHeight);
        if (pose != null) {
            result.put("playerPosition", Map.of("x", pose.x(), "y", pose.y(), "z", pose.z()));
            result.put("playerRotation", Map.of("yaw", pose.yaw(), "pitch", pose.pitch()));
        }
        return Map.copyOf(result);
    }

    private static int boundedAxis(Map<String, Object> input, String key, int fallback) {
        var value = input.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("input field must be numeric: " + key);
        }
        var parsed = InputNumbers.exactInt(number, key);
        if (parsed < 1 || parsed > MAX_AXIS) {
            throw new IllegalArgumentException(key + " must be between 1 and " + MAX_AXIS);
        }
        return parsed;
    }

    private static void requireOriginalDimensions(int width, int height) {
        if (width < 1 || height < 1 || width > MAX_ORIGINAL_AXIS || height > MAX_ORIGINAL_AXIS) {
            throw new IllegalArgumentException("captured screenshot dimensions are outside protocol bounds");
        }
    }

    interface CapturedImage extends AutoCloseable {
        int width();
        int height();
        int[] getPixels();
        @Override void close();
    }

    record Pose(double x, double y, double z, float yaw, float pitch) {
        Pose {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
                throw new IllegalArgumentException("player pose must be finite");
            }
        }
    }

    private record PixelSnapshot(int originalWidth, int originalHeight,
                                 ScreenshotDimensions dimensions, int[] pixels) {
    }
}
