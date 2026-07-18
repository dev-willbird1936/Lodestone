// SPDX-License-Identifier: MIT
package dev.lodestone.runtime;

import dev.lodestone.adapter.CapabilityHandler;
import dev.lodestone.adapter.InvocationContext;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class UiNavigateWorkflowAdapterTest {
    @Test
    void availabilityRequiresExactStateAndClickV2Capabilities() {
        try (var runtime = runtime()) {
            assertEquals(Availability.UNAVAILABLE, navigate(runtime).availability());
            runtime.registerAdapter(new FakeUiAdapter("2.0", "9.0", call -> state("screen-1", "a",
                    List.of(widget("n0", "Singleplayer"))), UiNavigateWorkflowAdapterTest::click));
            assertEquals(Availability.UNAVAILABLE, navigate(runtime).availability());
            assertEquals("workflow-prerequisite-unavailable", navigate(runtime).reason().code());
        }
        try (var runtime = runtime()) {
            runtime.registerAdapter(new FakeUiAdapter("2.0", "2.0", call -> state("screen-1", "a",
                    List.of(widget("n0", "Singleplayer"))), UiNavigateWorkflowAdapterTest::click));
            assertEquals(Availability.AVAILABLE, navigate(runtime).availability());
        }
    }

    @Test
    void semanticTargetUsesAFreshRevisionGuardedNodeClickAndReadsPostState() throws Exception {
        var clicked = new AtomicReference<Map<String, Object>>();
        var adapter = new FakeUiAdapter("2.0", "2.0",
                call -> call == 0
                        ? state("screen-1", "a", List.of(widget("n0", "Singleplayer")))
                        : state("screen-2", "b", List.of(widget("n1", "Create New World"))),
                context -> {
                    clicked.set(context.request().input());
                    return click(context);
                });
        try (var runtime = runtime()) {
            runtime.registerAdapter(adapter);
            var result = runtime.invoke(request(runtime, "singleplayer")).get(1, TimeUnit.SECONDS);

            assertEquals(ResultEnvelope.Status.OK, result.status(), result::toString);
            assertEquals("Singleplayer", result.output().get("label"));
            assertEquals("exact", result.output().get("match"));
            assertEquals(true, result.output().get("handled"));
            assertEquals("n0", clicked.get().get("nodeId"));
            assertEquals("screen-1", clicked.get().get("screenToken"));
            assertEquals("a".repeat(64), clicked.get().get("snapshotRevision"));
            assertEquals(2, adapter.stateCalls.get());
            assertEquals(1, adapter.clickCalls.get());
            assertTrue(result.output().containsKey("after"));
        }
    }

    @Test
    void ambiguousContainsFallbackFailsBeforeTheClickCommit() throws Exception {
        var adapter = new FakeUiAdapter("2.0", "2.0",
                call -> state("screen-1", "a", List.of(
                        widget("n0", "Options..."), widget("n1", "Options Plus"))),
                UiNavigateWorkflowAdapterTest::click);
        try (var runtime = runtime()) {
            runtime.registerAdapter(adapter);
            var result = runtime.invoke(request(runtime, "options")).get(1, TimeUnit.SECONDS);

            assertEquals(ResultEnvelope.Status.ERROR, result.status());
            assertTrue(result.error().message().contains("ambiguous"));
            assertEquals(0, adapter.clickCalls.get());
        }
    }

    @Test
    void createNewWorldTreatsAnEmptySaveListAutoAdvanceToCreateWorldScreenAsAlreadyArrived() throws Exception {
        var adapter = new FakeUiAdapter("2.0", "2.0",
                call -> state("screen-1", "a", "CreateWorldScreen",
                        "net.minecraft.client.gui.screens.worldselection.CreateWorldScreen",
                        List.of(widget("n0", "Create New World"))),
                UiNavigateWorkflowAdapterTest::click);
        try (var runtime = runtime()) {
            runtime.registerAdapter(adapter);
            var result = runtime.invoke(request(runtime, "create_new_world")).get(1, TimeUnit.SECONDS);

            assertEquals(ResultEnvelope.Status.OK, result.status(), result::toString);
            assertEquals(Boolean.TRUE, result.output().get("skipped"));
            assertEquals("empty_save_list_auto_advance", result.output().get("skippedReason"));
            assertEquals("already_arrived", result.output().get("match"));
            assertEquals(false, result.output().get("handled"));
            assertEquals(0, adapter.clickCalls.get());
            assertEquals(1, adapter.stateCalls.get());
        }
    }

    @Test
    void createWorldRejectsTheWorldSelectScreenInsteadOfMisclicking() throws Exception {
        var adapter = new FakeUiAdapter("2.0", "2.0",
                call -> state("screen-1", "a", "SelectWorldScreen",
                        "net.minecraft.client.gui.screens.worldselection.SelectWorldScreen",
                        List.of(widget("n0", "Create New World"))),
                UiNavigateWorkflowAdapterTest::click);
        try (var runtime = runtime()) {
            runtime.registerAdapter(adapter);
            var result = runtime.invoke(request(runtime, "create_world")).get(1, TimeUnit.SECONDS);

            assertEquals(ResultEnvelope.Status.ERROR, result.status());
            assertTrue(result.error().message().contains("create_world"));
            assertTrue(result.error().message().contains("CreateWorldScreen"));
            assertEquals(0, adapter.clickCalls.get());
        }
    }

    @Test
    void createNewWorldClicksTheWorldSelectScreenButtonWhenThatScreenIsActive() throws Exception {
        var adapter = new FakeUiAdapter("2.0", "2.0",
                call -> call == 0
                        ? state("screen-1", "a", "SelectWorldScreen",
                                "net.minecraft.client.gui.screens.worldselection.SelectWorldScreen",
                                List.of(widget("n0", "Create New World")))
                        : state("screen-2", "b", "CreateWorldScreen",
                                "net.minecraft.client.gui.screens.worldselection.CreateWorldScreen", List.of()),
                UiNavigateWorkflowAdapterTest::click);
        try (var runtime = runtime()) {
            runtime.registerAdapter(adapter);
            var result = runtime.invoke(request(runtime, "create_new_world")).get(1, TimeUnit.SECONDS);

            assertEquals(ResultEnvelope.Status.OK, result.status(), result::toString);
            assertEquals("Create New World", result.output().get("label"));
            assertEquals(1, adapter.clickCalls.get());
        }
    }

    @Test
    void createWorldClicksTheCreateWorldScreenButtonWhenThatScreenIsActive() throws Exception {
        var adapter = new FakeUiAdapter("2.0", "2.0",
                call -> state("screen-1", "a", "CreateWorldScreen",
                        "net.minecraft.client.gui.screens.worldselection.CreateWorldScreen",
                        List.of(widget("n0", "Create New World"))),
                UiNavigateWorkflowAdapterTest::click);
        try (var runtime = runtime()) {
            runtime.registerAdapter(adapter);
            var result = runtime.invoke(request(runtime, "create_world")).get(1, TimeUnit.SECONDS);

            assertEquals(ResultEnvelope.Status.OK, result.status(), result::toString);
            assertEquals("Create New World", result.output().get("label"));
            assertEquals(1, adapter.clickCalls.get());
        }
    }

    @Test
    void quitDoesNotRequireAClientSnapshotAfterDispatch() throws Exception {
        var adapter = new FakeUiAdapter("2.0", "2.0",
                call -> state("screen-1", "a", List.of(widget("n0", "Quit Game"))),
                UiNavigateWorkflowAdapterTest::click);
        try (var runtime = runtime()) {
            runtime.registerAdapter(adapter);
            var result = runtime.invoke(request(runtime, "quit_game")).get(1, TimeUnit.SECONDS);

            assertEquals(ResultEnvelope.Status.OK, result.status(), result::toString);
            assertFalse(result.output().containsKey("after"));
            assertEquals(1, adapter.stateCalls.get());
            assertEquals(1, adapter.clickCalls.get());
        }
    }

    private static LodestoneRuntime runtime() {
        return new LodestoneRuntime(AuthorizationPolicy.fromCsv("observe,control-player"));
    }

    private static CapabilityDescriptor navigate(LodestoneRuntime runtime) {
        return runtime.capabilities(UiWorkflowAdapter.NAVIGATE_CAPABILITY_ID).stream()
                .filter(capability -> capability.id().equals(UiWorkflowAdapter.NAVIGATE_CAPABILITY_ID))
                .findFirst().orElseThrow();
    }

    private static RequestEnvelope request(LodestoneRuntime runtime, String target) {
        return new RequestEnvelope(ProtocolVersion.CURRENT, "ui-navigate-" + java.util.UUID.randomUUID(),
                runtime.sessionId(), UiWorkflowAdapter.NAVIGATE_CAPABILITY_ID, "1.0", Map.of("target", target),
                null, null, false);
    }

    private static Map<String, Object> state(String token, String revisionCharacter,
                                              List<Map<String, Object>> widgets) {
        return state(token, revisionCharacter, "title", "net.minecraft.client.gui.screens.TitleScreen", widgets);
    }

    private static Map<String, Object> state(String token, String revisionCharacter, String screen,
                                              String screenClass, List<Map<String, Object>> widgets) {
        return Map.ofEntries(
                Map.entry("open", true), Map.entry("inWorld", false), Map.entry("screen", screen),
                Map.entry("screenClass", screenClass),
                Map.entry("title", "Minecraft"), Map.entry("screenToken", token),
                Map.entry("snapshotRevision", revisionCharacter.repeat(64)), Map.entry("capturedAtTick", 1),
                Map.entry("width", 426), Map.entry("height", 240), Map.entry("guiScale", 2),
                Map.entry("coverage", "complete"), Map.entry("truncated", false),
                Map.entry("truncationCauses", List.of()), Map.entry("widgets", widgets));
    }

    private static Map<String, Object> widget(String nodeId, String label) {
        return Map.ofEntries(
                Map.entry("nodeId", nodeId), Map.entry("path", List.of(0)), Map.entry("depth", 0),
                Map.entry("type", "button"), Map.entry("label", label), Map.entry("focused", false),
                Map.entry("active", true), Map.entry("visible", true), Map.entry("actions", List.of("click")));
    }

    private static Map<String, Object> click(InvocationContext context) {
        context.cancellation().commitMutation();
        var input = context.request().input();
        return Map.of("handled", true, "x", 100, "y", 80,
                "screenToken", input.get("screenToken"), "snapshotRevision", input.get("snapshotRevision"),
                "nodeId", input.get("nodeId"));
    }

    private static final class FakeUiAdapter implements LodestoneAdapter {
        private final AdapterDescriptor descriptor = new AdapterDescriptor(
                "test.ui-navigation", "1.0.0", "minecraft", "test", "test", Environment.CLIENT);
        private final String stateVersion;
        private final String clickVersion;
        private final Function<Integer, Map<String, Object>> state;
        private final Function<InvocationContext, Map<String, Object>> click;
        private final AtomicInteger stateCalls = new AtomicInteger();
        private final AtomicInteger clickCalls = new AtomicInteger();

        private FakeUiAdapter(String stateVersion, String clickVersion,
                              Function<Integer, Map<String, Object>> state,
                              Function<InvocationContext, Map<String, Object>> click) {
            this.stateVersion = stateVersion;
            this.clickVersion = clickVersion;
            this.state = state;
            this.click = click;
        }

        @Override
        public AdapterDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public CapabilityManifest manifest() {
            var catalog = CoreCatalog.load();
            var stateContract = catalog.stream()
                    .filter(capability -> capability.id().equals(UiWorkflowAdapter.UI_STATE_ID))
                    .findFirst().orElseThrow();
            var clickContract = catalog.stream()
                    .filter(capability -> capability.id().equals(UiWorkflowAdapter.UI_CLICK_ID))
                    .findFirst().orElseThrow();
            return new CapabilityManifest(descriptor, List.of(
                    withVersion(stateContract.forAdapter(descriptor, Availability.AVAILABLE, null), stateVersion),
                    withVersion(clickContract.forAdapter(descriptor, Availability.AVAILABLE, null), clickVersion)));
        }

        @Override
        public Map<String, CapabilityHandler> handlers() {
            return Map.of(
                    UiWorkflowAdapter.UI_STATE_ID, context -> CompletableFuture.completedFuture(
                            state.apply(stateCalls.getAndIncrement())),
                    UiWorkflowAdapter.UI_CLICK_ID, context -> {
                        clickCalls.incrementAndGet();
                        return CompletableFuture.completedFuture(click.apply(context));
                    });
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
