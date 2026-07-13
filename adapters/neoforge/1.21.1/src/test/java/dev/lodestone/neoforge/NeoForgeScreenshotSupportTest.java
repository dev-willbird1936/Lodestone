// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import dev.lodestone.adapter.ArtifactReference;
import dev.lodestone.adapter.ArtifactSink;
import dev.lodestone.adapter.CancellationToken;
import dev.lodestone.adapter.InvocationAttributes;
import dev.lodestone.adapter.InvocationContext;
import dev.lodestone.adapter.ScreenshotDimensions;
import dev.lodestone.protocol.ProtocolVersion;
import dev.lodestone.protocol.RequestEnvelope;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class NeoForgeScreenshotSupportTest {
    @Test
    void defaultsTo1920By1080WithoutUpscaling() {
        assertEquals(new ScreenshotDimensions(1920, 1080),
                NeoForgeScreenshotSupport.targetDimensions(Map.of(), 2560, 1440));
        assertEquals(new ScreenshotDimensions(800, 600),
                NeoForgeScreenshotSupport.targetDimensions(Map.of(), 800, 600));
    }

    @Test
    void synchronouslyResizesEncodesClosesAndPublishesCanonicalMetadata() {
        var sink = new RecordingSink();
        var scaled = new FakeImage(2, 1, new byte[]{1, 2, 3, 4});
        var source = new FakeImage(4, 2, new byte[]{9}, scaled);
        var pose = new NeoForgeScreenshotSupport.Pose(1.5, 64.0, -2.5, 90.0f, -15.0f);

        var output = NeoForgeScreenshotSupport.capture(
                invocation(Map.of("maxWidth", 2, "maxHeight", 2), CancellationToken.none(), sink),
                source, pose);

        assertEquals(1, source.closeCalls);
        assertEquals(0, source.byteArrayCalls);
        assertEquals(1, source.resizeCalls);
        assertEquals(1, scaled.closeCalls);
        assertEquals(1, scaled.byteArrayCalls);
        assertArrayEquals(new byte[]{1, 2, 3, 4}, sink.content);
        assertEquals(sink.reference.toMetadata(), output.get("artifact"));
        assertEquals(2, output.get("width"));
        assertEquals(1, output.get("height"));
        assertEquals(4, output.get("originalWidth"));
        assertEquals(2, output.get("originalHeight"));
        assertEquals(Map.of("x", 1.5, "y", 64.0, "z", -2.5), output.get("playerPosition"));
        assertEquals(Map.of("yaw", 90.0f, "pitch", -15.0f), output.get("playerRotation"));
    }

    @Test
    void usesSourceEncodingAndOmitsPoseWhenNoPlayerExists() {
        var sink = new RecordingSink();
        var source = new FakeImage(2, 1, new byte[]{1, 2, 3, 4});

        var output = NeoForgeScreenshotSupport.capture(
                invocation(Map.of(), CancellationToken.none(), sink), source, null);

        assertEquals(1, source.closeCalls);
        assertEquals(1, source.byteArrayCalls);
        assertEquals(0, source.resizeCalls);
        assertFalse(output.containsKey("playerPosition"));
        assertFalse(output.containsKey("playerRotation"));
    }

    @Test
    void cancellationClosesCaptureWithoutEncodingOrStaging() {
        var sink = new RecordingSink();
        var source = new FakeImage(2, 1, new byte[]{1, 2, 3, 4});

        assertThrows(CancellationToken.CancellationException.class, () ->
                NeoForgeScreenshotSupport.capture(
                        invocation(Map.of(), () -> true, sink), source, null));

        assertEquals(1, source.closeCalls);
        assertEquals(0, source.byteArrayCalls);
        assertEquals(0, sink.stageCalls);
    }

    private static InvocationContext invocation(Map<String, Object> input, CancellationToken cancellation,
                                                ArtifactSink sink) {
        var request = new RequestEnvelope(ProtocolVersion.CURRENT, "request-1", "session-1",
                "minecraft.client.screenshot.capture", "1.0", input, null, null, false);
        return new InvocationContext(request, cancellation, Runnable::run,
                Map.of(InvocationAttributes.ARTIFACT_SINK, sink));
    }

    private static final class FakeImage implements NeoForgeScreenshotSupport.CapturedImage {
        private final int width;
        private final int height;
        private final byte[] encoded;
        private final FakeImage scaled;
        private int byteArrayCalls;
        private int resizeCalls;
        private int closeCalls;

        private FakeImage(int width, int height, byte[] encoded) {
            this(width, height, encoded, null);
        }

        private FakeImage(int width, int height, byte[] encoded, FakeImage scaled) {
            this.width = width;
            this.height = height;
            this.encoded = encoded;
            this.scaled = scaled;
        }

        @Override public int width() { return width; }
        @Override public int height() { return height; }

        @Override
        public NeoForgeScreenshotSupport.CapturedImage resize(int width, int height) {
            resizeCalls++;
            assertEquals(this.scaled.width, width);
            assertEquals(this.scaled.height, height);
            return scaled;
        }

        @Override public byte[] asByteArray() { byteArrayCalls++; return encoded.clone(); }
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
