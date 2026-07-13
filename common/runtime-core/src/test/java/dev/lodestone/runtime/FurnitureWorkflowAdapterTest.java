// SPDX-License-Identifier: MIT
package dev.lodestone.runtime;

import dev.lodestone.adapter.CapabilityHandler;
import dev.lodestone.adapter.LodestoneAdapter;
import dev.lodestone.protocol.AdapterDescriptor;
import dev.lodestone.protocol.Availability;
import dev.lodestone.protocol.AvailabilityReason;
import dev.lodestone.protocol.CapabilityManifest;
import dev.lodestone.protocol.Environment;
import dev.lodestone.protocol.ProtocolVersion;
import dev.lodestone.protocol.RequestEnvelope;
import dev.lodestone.protocol.ResultEnvelope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FurnitureWorkflowAdapterTest {
    @Test
    void availabilityTracksTheExactNativeBlockWrite() throws Exception {
        try (var runtime = runtime()) {
            assertEquals(Availability.UNAVAILABLE, furniture(runtime).availability());
            var writes = new FakeBlockWriteAdapter();
            runtime.registerAdapter(writes);
            assertEquals(Availability.AVAILABLE, furniture(runtime).availability());

            writes.availability = Availability.UNAVAILABLE;
            runtime.refreshAdapter(writes);
            assertEquals(Availability.UNAVAILABLE, furniture(runtime).availability());
            assertEquals("test-unavailable", furniture(runtime).reason().code());
        }
    }

    @Test
    void previewExpandsAHashPinnedLayoutAndRotatesItsState() throws Exception {
        var observed = new AtomicReference<Map<String, Object>>();
        try (var runtime = runtime()) {
            runtime.registerAdapter(new FakeBlockWriteAdapter(observed));
            var result = runtime.invoke(request(runtime, Map.of(
                    "furniture_id", "simple_chair", "origin_x", 10, "origin_y", 64, "origin_z", -5,
                    "facing", "east", "place_on_surface", false, "preview_only", true), false))
                    .get(1, TimeUnit.SECONDS);

            assertEquals(ResultEnvelope.Status.OK, result.status(), result::toString);
            assertEquals(true, result.output().get("dryRun"));
            assertEquals("east", result.output().get("facing"));
            assertPosition(result.output().get("placementOrigin"), 10, 64, -5);
            @SuppressWarnings("unchecked")
            var changes = (List<Map<String, Object>>) observed.get().get("changes");
            assertEquals(1, changes.size());
            assertPosition(changes.get(0), 10, 64, -5);
            assertEquals("minecraft:oak_stairs[facing=east,half=bottom]", changes.get(0).get("block"));
        }
    }

    @Test
    void surfacePlacementAndFillExpansionStayInOneBoundedNativeBatch() throws Exception {
        var observed = new AtomicReference<Map<String, Object>>();
        try (var runtime = runtime()) {
            runtime.registerAdapter(new FakeBlockWriteAdapter(observed));
            var result = runtime.invoke(request(runtime, Map.of(
                    "furniture_id", "simple_dining_table", "origin_x", 0, "origin_y", 70, "origin_z", 0,
                    "facing", "north", "place_on_surface", true), false)).get(1, TimeUnit.SECONDS);

            assertEquals(ResultEnvelope.Status.OK, result.status(), result::toString);
            assertPosition(result.output().get("placementOrigin"), 0, 71, 0);
            assertEquals(10, ((Number) result.output().get("requestedCount")).intValue());
            assertEquals(10, ((Number) result.output().get("changedCount")).intValue());
            @SuppressWarnings("unchecked")
            var changes = (List<Map<String, Object>>) observed.get().get("changes");
            assertEquals(10, changes.size());
            assertTrue(changes.stream().allMatch(change -> ((Number) change.get("y")).intValue() >= 71));
        }
    }

    @Test
    void parentDryRunCannotBeOverriddenByTheCompatibilityInput() throws Exception {
        var observed = new AtomicReference<Map<String, Object>>();
        try (var runtime = runtime()) {
            runtime.registerAdapter(new FakeBlockWriteAdapter(observed));
            var result = runtime.invoke(request(runtime, Map.of(
                    "furniture_id", "corner_table", "origin_x", 0, "origin_y", 64, "origin_z", 0,
                    "preview_only", false), true)).get(1, TimeUnit.SECONDS);

            assertEquals(ResultEnvelope.Status.OK, result.status(), result::toString);
            assertEquals(true, observed.get().get("dryRun"));
            assertEquals(0, ((Number) result.output().get("changedCount")).intValue());
        }
    }

    @Test
    void unknownFurnitureFailsBeforeAnyNativeMutation() throws Exception {
        var writes = new FakeBlockWriteAdapter();
        try (var runtime = runtime()) {
            runtime.registerAdapter(writes);
            var result = runtime.invoke(request(runtime, Map.of(
                    "furniture_id", "does_not_exist", "origin_x", 0, "origin_y", 64, "origin_z", 0), false))
                    .get(1, TimeUnit.SECONDS);

            assertEquals(ResultEnvelope.Status.ERROR, result.status());
            assertEquals("ADAPTER_FAILURE", result.error().code());
            assertTrue(result.error().message().contains("unknown furniture_id"));
            assertEquals(0, writes.calls.get());
        }
    }

    private static LodestoneRuntime runtime() {
        return new LodestoneRuntime(AuthorizationPolicy.fromCsv("observe,modify-world"));
    }

    private static dev.lodestone.protocol.CapabilityDescriptor furniture(LodestoneRuntime runtime) {
        return runtime.capabilities(FurnitureWorkflowAdapter.CAPABILITY_ID).stream()
                .filter(capability -> capability.id().equals(FurnitureWorkflowAdapter.CAPABILITY_ID))
                .findFirst().orElseThrow();
    }

    private static RequestEnvelope request(LodestoneRuntime runtime, Map<String, Object> input, boolean dryRun) {
        return new RequestEnvelope(ProtocolVersion.CURRENT, "furniture-" + java.util.UUID.randomUUID(),
                runtime.sessionId(), FurnitureWorkflowAdapter.CAPABILITY_ID, "1.0", input,
                null, null, dryRun);
    }

    private static void assertPosition(Object raw, int x, int y, int z) {
        @SuppressWarnings("unchecked")
        var position = (Map<String, Object>) raw;
        assertEquals(x, ((Number) position.get("x")).intValue());
        assertEquals(y, ((Number) position.get("y")).intValue());
        assertEquals(z, ((Number) position.get("z")).intValue());
    }

    private static final class FakeBlockWriteAdapter implements LodestoneAdapter {
        private final AdapterDescriptor descriptor = new AdapterDescriptor(
                "test.block-write", "1.0.0", "minecraft", "test", "test", Environment.DEDICATED_SERVER);
        private final AtomicReference<Map<String, Object>> observed;
        private final AtomicInteger calls = new AtomicInteger();
        private volatile Availability availability = Availability.AVAILABLE;

        private FakeBlockWriteAdapter() {
            this(new AtomicReference<>());
        }

        private FakeBlockWriteAdapter(AtomicReference<Map<String, Object>> observed) {
            this.observed = observed;
        }

        @Override
        public AdapterDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public CapabilityManifest manifest() {
            var catalog = CoreCatalog.load().stream()
                    .filter(capability -> capability.id().equals(FurnitureWorkflowAdapter.BLOCK_WRITE_ID))
                    .findFirst().orElseThrow();
            var reason = availability == Availability.AVAILABLE ? null
                    : new AvailabilityReason("test-unavailable", "test block write is unavailable", Map.of());
            return new CapabilityManifest(descriptor,
                    List.of(catalog.forAdapter(descriptor, availability, reason)));
        }

        @Override
        public Map<String, CapabilityHandler> handlers() {
            return Map.of(FurnitureWorkflowAdapter.BLOCK_WRITE_ID, context -> {
                calls.incrementAndGet();
                context.cancellation().throwIfCancelled();
                var input = context.request().input();
                observed.set(input);
                var dryRun = Boolean.TRUE.equals(input.get("dryRun"));
                @SuppressWarnings("unchecked")
                var changes = (List<Map<String, Object>>) input.get("changes");
                if (!dryRun) context.cancellation().commitMutation();
                var projected = changes.stream().map(change -> Map.<String, Object>of(
                        "position", Map.of("x", change.get("x"), "y", change.get("y"), "z", change.get("z")),
                        "requestedBlock", change.get("block"), "previousBlock", "minecraft:air",
                        "previousState", "minecraft:air", "changed", true, "applied", !dryRun)).toList();
                return CompletableFuture.completedFuture(Map.of(
                        "dimension", input.get("dimension"), "dryRun", dryRun, "validated", true,
                        "requestedCount", changes.size(), "changedCount", dryRun ? 0 : changes.size(),
                        "changes", projected));
            });
        }
    }
}
