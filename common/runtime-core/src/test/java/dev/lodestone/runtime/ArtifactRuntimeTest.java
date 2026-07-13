// SPDX-License-Identifier: MIT
package dev.lodestone.runtime;

import dev.lodestone.adapter.ArtifactReference;
import dev.lodestone.adapter.CapabilityHandler;
import dev.lodestone.adapter.InvocationAttributes;
import dev.lodestone.adapter.LodestoneAdapter;
import dev.lodestone.protocol.AdapterDescriptor;
import dev.lodestone.protocol.Availability;
import dev.lodestone.protocol.CapabilityDescriptor;
import dev.lodestone.protocol.CapabilityKind;
import dev.lodestone.protocol.CapabilityManifest;
import dev.lodestone.protocol.CapabilityPrerequisites;
import dev.lodestone.protocol.DeliveryGuarantees;
import dev.lodestone.protocol.Environment;
import dev.lodestone.protocol.Idempotency;
import dev.lodestone.protocol.PermissionClass;
import dev.lodestone.protocol.RateLimit;
import dev.lodestone.protocol.RequestEnvelope;
import dev.lodestone.protocol.ResultEnvelope;
import dev.lodestone.protocol.SideEffect;
import dev.lodestone.protocol.Stability;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ArtifactRuntimeTest {
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
    private static final Map<String, Object> ARTIFACT_OUTPUT_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "artifact", Map.of("type", "object"),
                    "width", Map.of("type", "integer"),
                    "height", Map.of("type", "integer")),
            "required", List.of("artifact", "width", "height"));

    @Test
    void successfulInvocationPublishesBinaryContentOnlyToItsCaller() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var descriptor = capability("minecraft.test.artifact.success", ARTIFACT_OUTPUT_SCHEMA, 1_000);
            runtime.registerAdapter(adapter(descriptor, context -> {
                var reference = InvocationAttributes.requireArtifactSink(context).stage("image/png", PNG);
                return CompletableFuture.completedFuture(output(reference));
            }));

            var result = runtime.invoke(request(runtime, descriptor.id(), null), "caller-a")
                    .get(1, TimeUnit.SECONDS);
            assertEquals(ResultEnvelope.Status.OK, result.status());
            var metadata = artifactMetadata(result);
            var uri = (String) metadata.get("uri");

            assertTrue(runtime.resources("caller-a").stream().anyMatch(resource -> uri.equals(resource.uri())));
            assertFalse(runtime.resources("caller-b").stream().anyMatch(resource -> uri.equals(resource.uri())));
            var content = runtime.readResourceContent(uri, "caller-a");
            assertTrue(content.binary());
            assertEquals("image/png", content.mimeType());
            assertArrayEquals(PNG, content.bytes());
            assertThrows(IllegalArgumentException.class,
                    () -> runtime.readResourceContent(uri, "caller-b"));
            assertThrows(IllegalArgumentException.class, () -> runtime.readResource(uri));

            var staticHealth = runtime.readResourceContent("lodestone://health", "caller-a");
            assertFalse(staticHealth.binary());
            assertTrue(staticHealth.text().contains("state"));

            runtime.releaseCallerArtifacts("caller-a");
            assertThrows(IllegalArgumentException.class,
                    () -> runtime.readResourceContent(uri, "caller-a"));
        }
    }

    @Test
    void outputSchemaFailureDiscardsStagedContentBeforePublication() throws Exception {
        var staged = new AtomicReference<ArtifactReference>();
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var descriptor = capability("minecraft.test.artifact.invalid-schema", ARTIFACT_OUTPUT_SCHEMA, 1_000);
            runtime.registerAdapter(adapter(descriptor, context -> {
                staged.set(InvocationAttributes.requireArtifactSink(context).stage("image/png", PNG));
                return CompletableFuture.completedFuture(Map.of("wrong", true));
            }));

            var result = runtime.invoke(request(runtime, descriptor.id(), null), "caller")
                    .get(1, TimeUnit.SECONDS);
            assertEquals("INVALID_OUTPUT", result.error().code());
            assertThrows(IllegalArgumentException.class,
                    () -> runtime.readResourceContent(staged.get().uri(), "caller"));
        }
    }

    @Test
    void mismatchedOrGuessedMetadataFailsPublicationAndDiscardsContent() throws Exception {
        var staged = new AtomicReference<ArtifactReference>();
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var descriptor = capability("minecraft.test.artifact.mismatch", ARTIFACT_OUTPUT_SCHEMA, 1_000);
            runtime.registerAdapter(adapter(descriptor, context -> {
                var reference = InvocationAttributes.requireArtifactSink(context).stage("image/png", PNG);
                staged.set(reference);
                var changed = new java.util.LinkedHashMap<>(reference.toMetadata());
                changed.put("sizeBytes", reference.sizeBytes() + 1L);
                return CompletableFuture.completedFuture(Map.of(
                        "artifact", changed, "width", 1L, "height", 1L));
            }));

            var result = runtime.invoke(request(runtime, descriptor.id(), null), "caller")
                    .get(1, TimeUnit.SECONDS);
            assertEquals("ARTIFACT_PUBLISH_FAILED", result.error().code());
            assertThrows(IllegalArgumentException.class,
                    () -> runtime.readResourceContent(staged.get().uri(), "caller"));
        }

        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var descriptor = capability("minecraft.test.artifact.guess", ARTIFACT_OUTPUT_SCHEMA, 1_000);
            runtime.registerAdapter(adapter(descriptor, context -> CompletableFuture.completedFuture(Map.of(
                    "artifact", Map.of(
                            "uri", "lodestone://artifacts/sha256/" + "f".repeat(64),
                            "mediaType", "image/png", "sha256", "f".repeat(64),
                            "sizeBytes", 1L, "expiresAtEpochMs", 1L),
                    "width", 1L, "height", 1L))));
            var result = runtime.invoke(request(runtime, descriptor.id(), null), "caller")
                    .get(1, TimeUnit.SECONDS);
            assertEquals("ARTIFACT_PUBLISH_FAILED", result.error().code());
        }
    }

    @Test
    void handlerFailureCancellationAndDeadlineDiscardEveryPendingArtifact() throws Exception {
        var failedReference = new AtomicReference<ArtifactReference>();
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var descriptor = capability("minecraft.test.artifact.failure", ARTIFACT_OUTPUT_SCHEMA, 1_000);
            runtime.registerAdapter(adapter(descriptor, context -> {
                failedReference.set(InvocationAttributes.requireArtifactSink(context).stage("image/png", PNG));
                return CompletableFuture.failedFuture(new IllegalStateException("capture failed"));
            }));
            var result = runtime.invoke(request(runtime, descriptor.id(), null), "caller")
                    .get(1, TimeUnit.SECONDS);
            assertEquals("ADAPTER_FAILURE", result.error().code());
            assertThrows(IllegalArgumentException.class,
                    () -> runtime.readResourceContent(failedReference.get().uri(), "caller"));
        }

        var cancelledReference = new AtomicReference<ArtifactReference>();
        var cancelledSink = new AtomicReference<dev.lodestone.adapter.ArtifactSink>();
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var completion = new CompletableFuture<Map<String, Object>>();
            var descriptor = capability("minecraft.test.artifact.cancel", ARTIFACT_OUTPUT_SCHEMA, 1_000);
            runtime.registerAdapter(adapter(descriptor, context -> {
                var sink = InvocationAttributes.requireArtifactSink(context);
                cancelledSink.set(sink);
                cancelledReference.set(sink.stage("image/png", PNG));
                return completion;
            }));
            var invocation = runtime.invoke(request(runtime, descriptor.id(), null), "caller");
            awaitReference(cancelledReference);
            assertTrue(invocation.cancel(true));
            assertThrows(dev.lodestone.adapter.CancellationToken.CancellationException.class,
                    () -> cancelledSink.get().stage("image/png", PNG));
            completion.complete(output(cancelledReference.get()));
            assertThrows(IllegalArgumentException.class,
                    () -> runtime.readResourceContent(cancelledReference.get().uri(), "caller"));
        }

        var timedOutReference = new AtomicReference<ArtifactReference>();
        var timedOutSink = new AtomicReference<dev.lodestone.adapter.ArtifactSink>();
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var descriptor = capability("minecraft.test.artifact.timeout", ARTIFACT_OUTPUT_SCHEMA, 40);
            runtime.registerAdapter(adapter(descriptor, context -> {
                var sink = InvocationAttributes.requireArtifactSink(context);
                timedOutSink.set(sink);
                timedOutReference.set(sink.stage("image/png", PNG));
                return new CompletableFuture<>();
            }));
            var result = runtime.invoke(request(runtime, descriptor.id(), null), "caller")
                    .get(1, TimeUnit.SECONDS);
            assertEquals(ResultEnvelope.Status.TIMED_OUT, result.status());
            assertThrows(dev.lodestone.adapter.CancellationToken.CancellationException.class,
                    () -> timedOutSink.get().stage("image/png", PNG));
            assertThrows(IllegalArgumentException.class,
                    () -> runtime.readResourceContent(timedOutReference.get().uri(), "caller"));
        }
    }

    @Test
    void runtimeCloseDiscardsPendingContent() throws Exception {
        var staged = new AtomicReference<ArtifactReference>();
        var sink = new AtomicReference<dev.lodestone.adapter.ArtifactSink>();
        var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly());
        var descriptor = capability("minecraft.test.artifact.close", ARTIFACT_OUTPUT_SCHEMA, 5_000);
        runtime.registerAdapter(adapter(descriptor, context -> {
            sink.set(InvocationAttributes.requireArtifactSink(context));
            staged.set(sink.get().stage("image/png", PNG));
            return new CompletableFuture<>();
        }));
        var invocation = runtime.invoke(request(runtime, descriptor.id(), null), "caller");
        awaitReference(staged);

        runtime.close();

        assertEquals(ResultEnvelope.Status.CANCELLED, invocation.get(1, TimeUnit.SECONDS).status());
        assertThrows(dev.lodestone.adapter.CancellationToken.CancellationException.class,
                () -> sink.get().stage("image/png", PNG));
        assertThrows(IllegalArgumentException.class,
                () -> runtime.readResourceContent(staged.get().uri(), "caller"));
    }

    @Test
    void stagedArtifactCapabilitiesRejectIdempotencyBeforeHandlerDispatch() throws Exception {
        var calls = new AtomicInteger();
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var descriptor = withFeatureFlags(capability(
                    "minecraft.test.artifact.idempotency", ARTIFACT_OUTPUT_SCHEMA, 1_000),
                    Set.of("staged-artifact"));
            runtime.registerAdapter(adapter(descriptor, context -> {
                calls.incrementAndGet();
                var reference = InvocationAttributes.requireArtifactSink(context).stage("image/png", PNG);
                return CompletableFuture.completedFuture(output(reference));
            }));

            var result = runtime.invoke(new RequestEnvelope("1.0", "request", runtime.sessionId(),
                    descriptor.id(), "1.0", Map.of(), null, "capture-once", false), "caller")
                    .get(1, TimeUnit.SECONDS);

            assertEquals("IDEMPOTENCY_NOT_SUPPORTED", result.error().code());
            assertEquals(0, calls.get());
        }
    }

    @Test
    void runtimeReturnsPublicationCanonicalExpiryAndCanReleaseOnlyThatArtifact() throws Exception {
        var staged = new AtomicReference<ArtifactReference>();
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var completion = new CompletableFuture<Map<String, Object>>();
            var descriptor = capability("minecraft.test.artifact.canonical", ARTIFACT_OUTPUT_SCHEMA, 1_000);
            runtime.registerAdapter(adapter(descriptor, context -> {
                var reference = InvocationAttributes.requireArtifactSink(context).stage("image/png", PNG);
                staged.set(reference);
                return completion;
            }));
            var invocation = runtime.invoke(request(runtime, descriptor.id(), null), "caller");
            awaitReference(staged);
            Thread.sleep(10L);
            completion.complete(output(staged.get()));

            var result = invocation.get(1, TimeUnit.SECONDS);
            var metadata = artifactMetadata(result);
            assertTrue(((Number) metadata.get("expiresAtEpochMs")).longValue()
                    > staged.get().expiresAtEpochMs());
            assertTrue(runtime.releaseArtifact((String) metadata.get("uri"), "caller"));
            assertThrows(IllegalArgumentException.class,
                    () -> runtime.readResourceContent((String) metadata.get("uri"), "caller"));
            assertFalse(runtime.releaseArtifact((String) metadata.get("uri"), "caller"));
        }
    }

    @Test
    void observerViewHijackRollsBackArtifactWhenCanonicalPublicationLoses() throws Exception {
        var staged = new AtomicReference<ArtifactReference>();
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var completion = new CompletableFuture<Map<String, Object>>();
            var descriptor = capability("minecraft.test.artifact.publication-race", ARTIFACT_OUTPUT_SCHEMA, 1_000);
            runtime.registerAdapter(adapter(descriptor, context -> {
                var reference = InvocationAttributes.requireArtifactSink(context).stage("image/png", PNG);
                staged.set(reference);
                return completion;
            }));
            var invocation = runtime.invoke(request(runtime, descriptor.id(), null), "caller");
            awaitReference(staged);
            assertTrue(invocation.complete(ResultEnvelope.ok("forged", Map.of("forged", true))));
            completion.complete(output(staged.get()));

            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (runtime.audit().stream().noneMatch(record -> descriptor.id().equals(record.capability()))
                    && System.nanoTime() < deadline) Thread.onSpinWait();
            assertThrows(IllegalArgumentException.class,
                    () -> runtime.readResourceContent(staged.get().uri(), "caller"));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> artifactMetadata(ResultEnvelope result) {
        return (Map<String, Object>) result.output().get("artifact");
    }

    private static void awaitReference(AtomicReference<ArtifactReference> reference) throws Exception {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (reference.get() == null && System.nanoTime() < deadline) Thread.onSpinWait();
        if (reference.get() == null) throw new AssertionError("handler did not stage an artifact");
    }

    private static Map<String, Object> output(ArtifactReference reference) {
        return Map.of("artifact", reference.toMetadata(), "width", 1L, "height", 1L);
    }

    private static RequestEnvelope request(LodestoneRuntime runtime, String capability, Long deadline) {
        return new RequestEnvelope("1.0", "request", runtime.sessionId(), capability,
                "1.0", Map.of(), deadline, null, false);
    }

    private static LodestoneAdapter adapter(CapabilityDescriptor descriptor, CapabilityHandler handler) {
        var adapterDescriptor = new AdapterDescriptor(
                "test.artifact.adapter", "1.0.0", "minecraft", "test", "test", Environment.REMOTE);
        return new LodestoneAdapter() {
            @Override public AdapterDescriptor descriptor() { return adapterDescriptor; }
            @Override public CapabilityManifest manifest() {
                return new CapabilityManifest(adapterDescriptor, List.of(descriptor));
            }
            @Override public Map<String, CapabilityHandler> handlers() {
                return Map.of(descriptor.id(), handler);
            }
        };
    }

    private static CapabilityDescriptor capability(String id, Map<String, Object> outputSchema, long timeoutMs) {
        return new CapabilityDescriptor(id, CapabilityKind.ACTION, "1.0", Stability.STABLE,
                Availability.AVAILABLE, null, "test.artifact.adapter", "1.0.0",
                "minecraft", "test", "test", Environment.REMOTE,
                Map.of("type", "object"), outputSchema, Map.of(), Set.of(PermissionClass.OBSERVE),
                SideEffect.NONE, Idempotency.NON_IDEMPOTENT,
                new CapabilityPrerequisites(false, false, false, false), "runtime",
                new RateLimit(100, 1_000, 100), timeoutMs, true,
                new DeliveryGuarantees("request-order", "at-most-once", 1),
                "Artifact runtime test capability", Set.of());
    }

    private static CapabilityDescriptor withFeatureFlags(CapabilityDescriptor descriptor, Set<String> flags) {
        return new CapabilityDescriptor(descriptor.id(), descriptor.kind(), descriptor.version(),
                descriptor.stability(), descriptor.availability(), descriptor.reason(), descriptor.adapterId(),
                descriptor.adapterVersion(), descriptor.gameEdition(), descriptor.gameVersion(), descriptor.loader(),
                descriptor.environment(), descriptor.inputSchema(), descriptor.outputSchema(), descriptor.eventSchema(),
                descriptor.permissions(), descriptor.sideEffect(), descriptor.idempotency(), descriptor.prerequisites(),
                descriptor.nativeThread(), descriptor.rateLimit(), descriptor.timeoutMs(), descriptor.cancellable(),
                descriptor.delivery(), descriptor.documentation(), flags);
    }
}
