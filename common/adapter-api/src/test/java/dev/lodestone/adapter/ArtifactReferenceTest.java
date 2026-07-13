// SPDX-License-Identifier: MIT
package dev.lodestone.adapter;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ArtifactReferenceTest {
    @Test
    void exposesExactImmutableWireMetadata() {
        var reference = new ArtifactReference(
                "lodestone://artifacts/sha256/" + "a".repeat(64),
                "image/png", "a".repeat(64), 123L, 456L);

        assertEquals(Map.of(
                "uri", reference.uri(),
                "mediaType", "image/png",
                "sha256", "a".repeat(64),
                "sizeBytes", 123L,
                "expiresAtEpochMs", 456L), reference.toMetadata());
        assertThrows(UnsupportedOperationException.class,
                () -> reference.toMetadata().put("uri", "changed"));
    }

    @Test
    void invocationAttributeRequiresTheRuntimeSink() {
        var context = new InvocationContext(
                new dev.lodestone.protocol.RequestEnvelope("1.0", "request", "session",
                        "minecraft.test", "1.0", Map.of(), null, null, false),
                CancellationToken.none(), Runnable::run, Map.of());

        assertThrows(IllegalStateException.class, () -> InvocationAttributes.requireArtifactSink(context));
    }
}
