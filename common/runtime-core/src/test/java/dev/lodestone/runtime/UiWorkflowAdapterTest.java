// SPDX-License-Identifier: MIT
package dev.lodestone.runtime;

import dev.lodestone.adapter.CapabilityHandler;
import dev.lodestone.adapter.LodestoneAdapter;
import dev.lodestone.protocol.AdapterDescriptor;
import dev.lodestone.protocol.Availability;
import dev.lodestone.protocol.AvailabilityReason;
import dev.lodestone.protocol.CapabilityDescriptor;
import dev.lodestone.protocol.CapabilityManifest;
import dev.lodestone.protocol.Environment;
import dev.lodestone.protocol.ProtocolVersion;
import dev.lodestone.protocol.RequestEnvelope;
import dev.lodestone.protocol.ResultEnvelope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class UiWorkflowAdapterTest {
    @Test
    void availabilityTracksTheExactNegotiatedUiStateCapability() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            assertEquals(Availability.UNAVAILABLE, waitDescriptor(runtime).availability());
            var ui = new FakeUiAdapter("2.0", call -> CompletableFuture.completedFuture(
                    uiState(true, "pause", "net.minecraft.client.gui.screens.PauseScreen")));

            runtime.registerAdapter(ui);

            assertEquals(Availability.AVAILABLE, waitDescriptor(runtime).availability());
            var result = runtime.invoke(request(runtime, "screen_open", null, Map.of()))
                    .get(1, TimeUnit.SECONDS);
            assertEquals(ResultEnvelope.Status.OK, result.status());
            assertEquals(false, result.output().get("timedOut"));
            assertEquals(1, ((Number) result.output().get("pollCount")).intValue());

            ui.availability = Availability.UNAVAILABLE;
            runtime.refreshAdapter(ui);
            assertEquals(Availability.UNAVAILABLE, waitDescriptor(runtime).availability());
        }
    }

    @Test
    void wrongUiStateVersionKeepsTheWorkflowUnavailable() {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            runtime.registerAdapter(new FakeUiAdapter("9.0", call -> CompletableFuture.completedFuture(
                    uiState(false, "none", "none"))));

            var wait = waitDescriptor(runtime);
            assertEquals(Availability.UNAVAILABLE, wait.availability());
            assertEquals("workflow-prerequisite-unavailable", wait.reason().code());
        }
    }

    @Test
    void pollsAsynchronouslyWithDeterministicStepsUntilTheConditionMatches() throws Exception {
        var observedCalls = new AtomicInteger();
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            runtime.registerAdapter(new FakeUiAdapter("2.0", call -> {
                observedCalls.incrementAndGet();
                return CompletableFuture.completedFuture(call == 0
                        ? uiState(false, "none", "none")
                        : uiState(true, "pause", "net.minecraft.client.gui.screens.PauseScreen"));
            }));

            var result = runtime.invoke(request(runtime, "screen_open", null,
                            Map.of("pollIntervalMs", 100, "timeoutMs", 2_000)))
                    .get(2, TimeUnit.SECONDS);

            assertEquals(ResultEnvelope.Status.OK, result.status());
            assertEquals(false, result.output().get("timedOut"));
            assertEquals(2, ((Number) result.output().get("pollCount")).intValue());
            assertEquals(2, observedCalls.get());
            @SuppressWarnings("unchecked")
            var state = (Map<String, Object>) result.output().get("state");
            assertEquals("net.minecraft.client.gui.screens.PauseScreen", state.get("screenClass"));
        }
    }

    @Test
    void operationTimeoutReturnsTheLastSnapshotAsASuccessfulTimedOutResult() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            runtime.registerAdapter(new FakeUiAdapter("2.0", call -> CompletableFuture.supplyAsync(
                    () -> uiState(false, "none", "none"),
                    CompletableFuture.delayedExecutor(25, TimeUnit.MILLISECONDS))));

            var result = runtime.invoke(request(runtime, "in_world", null, Map.of("timeoutMs", 1)))
                    .get(1, TimeUnit.SECONDS);

            assertEquals(ResultEnvelope.Status.OK, result.status());
            assertEquals(true, result.output().get("timedOut"));
            assertEquals(1, ((Number) result.output().get("pollCount")).intValue());
            assertTrue(((Number) result.output().get("elapsedMs")).longValue() >= 1);
            assertTrue(result.output().get("state") instanceof Map<?, ?>);
        }
    }

    @Test
    void cancellationDuringDelayPreventsAnotherNativePoll() throws Exception {
        var firstPoll = new CountDownLatch(1);
        var calls = new AtomicInteger();
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            runtime.registerAdapter(new FakeUiAdapter("2.0", call -> {
                calls.incrementAndGet();
                firstPoll.countDown();
                return CompletableFuture.completedFuture(uiState(false, "none", "none"));
            }));
            var invocation = runtime.invoke(request(runtime, "screen_open", null,
                    Map.of("pollIntervalMs", 1_000, "timeoutMs", 10_000)));
            assertTrue(firstPoll.await(1, TimeUnit.SECONDS));

            assertTrue(invocation.cancel(false));

            assertTrue(invocation.isCancelled());
            assertEquals(1, calls.get());
        }
    }

    @Test
    void externalIdempotencyCachesTheCanonicalWorkflowOutcome() throws Exception {
        var calls = new AtomicInteger();
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            runtime.registerAdapter(new FakeUiAdapter("2.0", call -> {
                calls.incrementAndGet();
                return CompletableFuture.completedFuture(uiState(true, "PauseScreen", "screen.Class"));
            }));
            var first = runtime.invoke(request(runtime, "screen_class:PauseScreen", "stable-wait", Map.of()))
                    .get(1, TimeUnit.SECONDS);
            var replay = runtime.invoke(request(runtime, "screen_class:PauseScreen", "stable-wait", Map.of()))
                    .get(1, TimeUnit.SECONDS);

            assertEquals(ResultEnvelope.Status.OK, first.status());
            assertEquals(first.output(), replay.output());
            assertEquals(1, calls.get());
        }
    }

    private static CapabilityDescriptor waitDescriptor(LodestoneRuntime runtime) {
        return runtime.capabilities(UiWorkflowAdapter.CAPABILITY_ID).stream()
                .filter(capability -> capability.id().equals(UiWorkflowAdapter.CAPABILITY_ID))
                .findFirst().orElseThrow();
    }

    private static RequestEnvelope request(LodestoneRuntime runtime, String until, String idempotencyKey,
                                           Map<String, Object> extra) {
        var input = new java.util.LinkedHashMap<String, Object>(extra);
        input.put("until", until);
        return new RequestEnvelope(ProtocolVersion.CURRENT, "ui-wait-" + java.util.UUID.randomUUID(),
                runtime.sessionId(), UiWorkflowAdapter.CAPABILITY_ID, "1.0", Map.copyOf(input),
                null, idempotencyKey, false);
    }

    private static Map<String, Object> uiState(boolean open, String screen, String screenClass) {
        return Map.ofEntries(
                Map.entry("open", open), Map.entry("inWorld", false), Map.entry("screen", screen),
                Map.entry("screenClass", screenClass), Map.entry("title", open ? "Game Menu" : ""),
                Map.entry("screenToken", "screen-1"), Map.entry("snapshotRevision", "a".repeat(64)),
                Map.entry("capturedAtTick", 1), Map.entry("width", 426), Map.entry("height", 240),
                Map.entry("guiScale", 2), Map.entry("coverage", "complete"), Map.entry("truncated", false),
                Map.entry("truncationCauses", List.of()), Map.entry("widgets", List.of()));
    }

    private static final class FakeUiAdapter implements LodestoneAdapter {
        private final AdapterDescriptor descriptor = new AdapterDescriptor(
                "test.ui", "1.0.0", "minecraft", "test", "test", Environment.CLIENT);
        private final String version;
        private final Function<Integer, CompletionStage<Map<String, Object>>> response;
        private final AtomicInteger calls = new AtomicInteger();
        private volatile Availability availability = Availability.AVAILABLE;

        private FakeUiAdapter(String version,
                              Function<Integer, CompletionStage<Map<String, Object>>> response) {
            this.version = version;
            this.response = response;
        }

        @Override
        public AdapterDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public CapabilityManifest manifest() {
            var catalog = CoreCatalog.load().stream()
                    .filter(capability -> capability.id().equals(UiWorkflowAdapter.UI_STATE_ID))
                    .findFirst().orElseThrow();
            var reason = availability == Availability.AVAILABLE ? null
                    : new AvailabilityReason("test-unavailable", "test UI state is unavailable", Map.of());
            var capability = withVersion(catalog.forAdapter(descriptor, availability, reason), version);
            return new CapabilityManifest(descriptor, List.of(capability));
        }

        @Override
        public Map<String, CapabilityHandler> handlers() {
            return Map.of(UiWorkflowAdapter.UI_STATE_ID,
                    context -> response.apply(calls.getAndIncrement()));
        }

        private static CapabilityDescriptor withVersion(CapabilityDescriptor capability, String version) {
            return new CapabilityDescriptor(capability.id(), capability.kind(), version, capability.stability(),
                    capability.availability(), capability.reason(), capability.adapterId(), capability.adapterVersion(),
                    capability.gameEdition(), capability.gameVersion(), capability.loader(), capability.environment(),
                    capability.inputSchema(), capability.outputSchema(), capability.eventSchema(), capability.permissions(),
                    capability.sideEffect(), capability.idempotency(), capability.prerequisites(), capability.nativeThread(),
                    capability.rateLimit(), capability.timeoutMs(), capability.cancellable(), capability.delivery(),
                    capability.documentation(), capability.featureFlags());
        }
    }
}
