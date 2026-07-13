// SPDX-License-Identifier: MIT
package dev.lodestone.fabric;

import dev.lodestone.adapter.ArtifactReference;
import dev.lodestone.adapter.ArtifactSink;
import dev.lodestone.adapter.CancellationToken;
import dev.lodestone.adapter.InvocationAttributes;
import dev.lodestone.adapter.InvocationContext;
import dev.lodestone.adapter.ScreenshotDimensions;
import dev.lodestone.protocol.ProtocolVersion;
import dev.lodestone.protocol.RequestEnvelope;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class FabricScreenshotSupportTest {
    @Test
    void defaultsTo1920By1080WithoutUpscaling() {
        assertEquals(new ScreenshotDimensions(1920, 1080),
                FabricScreenshotSupport.targetDimensions(Map.of(), 2560, 1440));
        assertEquals(new ScreenshotDimensions(800, 600),
                FabricScreenshotSupport.targetDimensions(Map.of(), 800, 600));
    }

    @Test
    @SuppressWarnings("unchecked")
    void convertsPublicArgbPixelsToPngAndPublishesCanonicalMetadataWithOptionalPose() throws Exception {
        var sink = new RecordingSink();
        var source = new FakeImage(2, 1, new int[]{0xFF112233, 0x80445566});
        var pose = new FabricScreenshotSupport.Pose(1.5, 64.0, -2.5, 90.0f, -15.0f);

        var output = FabricScreenshotSupport.capture(
                invocation(Map.of(), CancellationToken.none(), sink), source, pose, Runnable::run)
                .toCompletableFuture().join();

        assertEquals(1, source.closeCalls);
        assertEquals(1, source.pixelCalls);
        var decoded = ImageIO.read(new ByteArrayInputStream(sink.content));
        assertEquals(2, decoded.getWidth());
        assertEquals(1, decoded.getHeight());
        assertEquals(0xFF112233, decoded.getRGB(0, 0));
        assertEquals(0x80445566, decoded.getRGB(1, 0));
        assertEquals(sink.reference.toMetadata(), output.get("artifact"));
        assertEquals(2, output.get("width"));
        assertEquals(1, output.get("height"));
        assertEquals(2, output.get("originalWidth"));
        assertEquals(1, output.get("originalHeight"));
        assertEquals(Map.of("x", 1.5, "y", 64.0, "z", -2.5), output.get("playerPosition"));
        assertEquals(Map.of("yaw", 90.0f, "pitch", -15.0f), output.get("playerRotation"));
        assertEquals(sink.reference.toMetadata(),
                (Map<String, Object>) output.get("artifact"));
    }

    @Test
    void scalesWithinRequestedBoundsAndOmitsPoseWhenNoPlayerExists() throws Exception {
        var sink = new RecordingSink();
        var source = new FakeImage(4, 2, new int[]{
                0xFF000000, 0xFFFFFFFF, 0xFF000000, 0xFFFFFFFF,
                0xFFFFFFFF, 0xFF000000, 0xFFFFFFFF, 0xFF000000});

        var output = FabricScreenshotSupport.capture(
                invocation(Map.of("maxWidth", 2, "maxHeight", 2), CancellationToken.none(), sink),
                source, null, Runnable::run).toCompletableFuture().join();

        var decoded = ImageIO.read(new ByteArrayInputStream(sink.content));
        assertEquals(2, decoded.getWidth());
        assertEquals(1, decoded.getHeight());
        assertFalse(output.containsKey("playerPosition"));
        assertFalse(output.containsKey("playerRotation"));
    }

    @Test
    void cancellationClosesCaptureWithoutReadingPixelsOrStaging() {
        var sink = new RecordingSink();
        var source = new FakeImage(2, 1, new int[]{0, 0});

        var failure = assertThrows(CompletionException.class, () -> FabricScreenshotSupport.capture(
                invocation(Map.of(), () -> true, sink), source, null, Runnable::run)
                .toCompletableFuture().join());

        assertInstanceOf(CancellationToken.CancellationException.class, failure.getCause());
        assertEquals(1, source.closeCalls);
        assertEquals(0, source.pixelCalls);
        assertEquals(0, sink.stageCalls);
    }

    private static InvocationContext invocation(Map<String, Object> input, CancellationToken cancellation,
                                                ArtifactSink sink) {
        var request = new RequestEnvelope(ProtocolVersion.CURRENT, "request-1", "session-1",
                "minecraft.client.screenshot.capture", "1.0", input, null, null, false);
        return new InvocationContext(request, cancellation, Runnable::run,
                Map.of(InvocationAttributes.ARTIFACT_SINK, sink));
    }

    private static final class FakeImage implements FabricScreenshotSupport.CapturedImage {
        private final int width;
        private final int height;
        private final int[] pixels;
        private int pixelCalls;
        private int closeCalls;

        private FakeImage(int width, int height, int[] pixels) {
            this.width = width;
            this.height = height;
            this.pixels = pixels;
        }

        @Override public int width() { return width; }
        @Override public int height() { return height; }
        @Override public int[] getPixels() { pixelCalls++; return pixels.clone(); }
        @Override public void close() { closeCalls++; }
    }

    private static final class RecordingSink implements ArtifactSink {
        private byte[] content;
        private ArtifactReference reference;
        private int stageCalls;

        @Override
        public ArtifactReference stage(String mediaType, byte[] content) {
            stageCalls++;
            this.content = content.clone();
            var hash = hex(sha256(content));
            reference = new ArtifactReference("lodestone://artifacts/sha256/" + hash,
                    mediaType, hash, content.length, 60_000);
            return reference;
        }

        private static byte[] sha256(byte[] value) {
            try {
                return MessageDigest.getInstance("SHA-256").digest(value);
            } catch (java.security.NoSuchAlgorithmException impossible) {
                throw new AssertionError(impossible);
            }
        }

        private static String hex(byte[] value) {
            var result = new StringBuilder(value.length * 2);
            for (var element : value) result.append(String.format("%02x", element));
            return result.toString();
        }
    }
}
