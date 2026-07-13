// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import dev.lodestone.adapter.InputNumbers;
import dev.lodestone.adapter.InvocationAttributes;
import dev.lodestone.adapter.InvocationContext;
import dev.lodestone.adapter.ScreenshotDimensions;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Transactional projection and exactly-once image lifecycle for NeoForge screenshots. */
final class NeoForgeScreenshotSupport {
    private static final int DEFAULT_MAX_WIDTH = 1920;
    private static final int DEFAULT_MAX_HEIGHT = 1080;
    private static final int MAX_AXIS = 8192;
    private static final int MAX_ORIGINAL_AXIS = 32768;
    private static final long MAX_PIXELS = 16_777_216L;

    private NeoForgeScreenshotSupport() {
    }

    static ScreenshotDimensions targetDimensions(Map<String, Object> input,
                                                 int originalWidth, int originalHeight) {
        requireOriginalDimensions(originalWidth, originalHeight);
        var maxWidth = boundedAxis(input, "maxWidth", DEFAULT_MAX_WIDTH);
        var maxHeight = boundedAxis(input, "maxHeight", DEFAULT_MAX_HEIGHT);
        return ScreenshotDimensions.fit(originalWidth, originalHeight, maxWidth, maxHeight, MAX_PIXELS);
    }

    static Map<String, Object> capture(InvocationContext invocation, CapturedImage source, Pose pose) {
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(source, "source");
        try (source) {
            invocation.cancellation().throwIfCancelled();
            var originalWidth = source.width();
            var originalHeight = source.height();
            var dimensions = targetDimensions(invocation.request().input(), originalWidth, originalHeight);
            final byte[] png;
            if (dimensions.width() == originalWidth && dimensions.height() == originalHeight) {
                png = source.asByteArray();
            } else {
                try (var resized = Objects.requireNonNull(
                        source.resize(dimensions.width(), dimensions.height()), "resized screenshot")) {
                    invocation.cancellation().throwIfCancelled();
                    png = resized.asByteArray();
                }
            }
            invocation.cancellation().throwIfCancelled();
            var artifact = InvocationAttributes.requireArtifactSink(invocation).stage("image/png", png);
            return output(artifact.toMetadata(), dimensions, originalWidth, originalHeight, pose);
        } catch (IOException failure) {
            throw new IllegalStateException("captured screenshot could not be encoded as PNG", failure);
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
        CapturedImage resize(int width, int height);
        byte[] asByteArray() throws IOException;
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
}
