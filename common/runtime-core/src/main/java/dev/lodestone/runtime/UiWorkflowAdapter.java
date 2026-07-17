// SPDX-License-Identifier: MIT
package dev.lodestone.runtime;

import dev.lodestone.adapter.CapabilityHandler;
import dev.lodestone.adapter.InvocationAttributes;
import dev.lodestone.adapter.InvocationContext;
import dev.lodestone.adapter.LodestoneAdapter;
import dev.lodestone.protocol.AdapterDescriptor;
import dev.lodestone.protocol.Availability;
import dev.lodestone.protocol.AvailabilityReason;
import dev.lodestone.protocol.CapabilityDescriptor;
import dev.lodestone.protocol.CapabilityManifest;
import dev.lodestone.protocol.Environment;
import dev.lodestone.protocol.ResultEnvelope;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/** Runtime-owned, loader-neutral UI workflows composed from negotiated native capabilities. */
final class UiWorkflowAdapter implements LodestoneAdapter {
    static final String CAPABILITY_ID = "lodestone.ui.wait";
    static final String NAVIGATE_CAPABILITY_ID = "lodestone.ui.navigate";
    static final String UI_STATE_ID = "minecraft.ui.state.read";
    static final String UI_CLICK_ID = "minecraft.ui.click";
    private static final String UI_STATE_VERSION = "2.0";
    private static final String UI_CLICK_VERSION = "2.0";
    private static final int DEFAULT_TIMEOUT_MS = 30_000;
    private static final int DEFAULT_POLL_INTERVAL_MS = 500;

    private final CapabilityRegistry registry;
    private final AdapterDescriptor descriptor = new AdapterDescriptor(
            "lodestone.workflow", "0.1.0", "minecraft", "negotiated", "runtime", Environment.REMOTE);
    private final CapabilityDescriptor waitContract = CoreCatalog.load().stream()
            .filter(capability -> capability.id().equals(CAPABILITY_ID))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("core catalog is missing " + CAPABILITY_ID));
    private final CapabilityDescriptor navigateContract = CoreCatalog.load().stream()
            .filter(capability -> capability.id().equals(NAVIGATE_CAPABILITY_ID))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("core catalog is missing " + NAVIGATE_CAPABILITY_ID));

    UiWorkflowAdapter(CapabilityRegistry registry) {
        this.registry = registry;
    }

    @Override
    public AdapterDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public CapabilityManifest manifest() {
        return new CapabilityManifest(descriptor, List.of(
                negotiatedWaitDescriptor(), negotiatedNavigateDescriptor()));
    }

    @Override
    public Map<String, CapabilityHandler> handlers() {
        return Map.of(CAPABILITY_ID, this::waitForUi,
                NAVIGATE_CAPABILITY_ID, this::navigate);
    }

    private CapabilityDescriptor negotiatedWaitDescriptor() {
        var child = registry.get(UI_STATE_ID);
        if (child == null || !UI_STATE_VERSION.equals(child.descriptor().version())) {
            return waitContract.forAdapter(descriptor, Availability.UNAVAILABLE,
                    new AvailabilityReason("workflow-prerequisite-unavailable",
                            "UI wait requires minecraft.ui.state.read version 2.0 from a live client adapter.",
                            Map.of("capability", UI_STATE_ID, "version", UI_STATE_VERSION)));
        }
        var childDescriptor = child.descriptor();
        if (childDescriptor.availability() == Availability.AVAILABLE) {
            return waitContract.forAdapter(descriptor, Availability.AVAILABLE, null);
        }
        if (childDescriptor.availability() == Availability.RESTRICTED) {
            return waitContract.forAdapter(descriptor, Availability.RESTRICTED, childDescriptor.reason());
        }
        return waitContract.forAdapter(descriptor, childDescriptor.availability(), childDescriptor.reason());
    }

    private CapabilityDescriptor negotiatedNavigateDescriptor() {
        var state = exactCapability(UI_STATE_ID, UI_STATE_VERSION);
        var click = exactCapability(UI_CLICK_ID, UI_CLICK_VERSION);
        if (state == null || click == null) {
            return navigateContract.forAdapter(descriptor, Availability.UNAVAILABLE,
                    new AvailabilityReason("workflow-prerequisite-unavailable",
                            "UI navigation requires minecraft.ui.state.read@2.0 and minecraft.ui.click@2.0.",
                            Map.of("stateCapability", UI_STATE_ID + "@" + UI_STATE_VERSION,
                                    "clickCapability", UI_CLICK_ID + "@" + UI_CLICK_VERSION)));
        }
        for (var availability : List.of(Availability.UNAVAILABLE, Availability.RESTRICTED, Availability.DEGRADED)) {
            if (state.availability() == availability) {
                return navigateContract.forAdapter(descriptor, availability, state.reason());
            }
            if (click.availability() == availability) {
                return navigateContract.forAdapter(descriptor, availability, click.reason());
            }
        }
        return navigateContract.forAdapter(descriptor, Availability.AVAILABLE, null);
    }

    private CapabilityDescriptor exactCapability(String id, String version) {
        var entry = registry.get(id);
        return entry != null && version.equals(entry.descriptor().version()) ? entry.descriptor() : null;
    }

    private CompletionStage<Map<String, Object>> navigate(InvocationContext context) {
        var target = String.valueOf(context.request().input().get("target"));
        var requestedLabel = navigationLabel(target);
        var invoker = InvocationAttributes.requireDelegatedInvoker(context);
        context.cancellation().throwIfCancelled();
        return invoker.invoke("navigate.before", UI_STATE_ID, UI_STATE_VERSION, Map.of())
                .thenCompose(beforeResult -> {
                    var before = requireOk(beforeResult, "UI state read before navigation");
                    context.cancellation().throwIfCancelled();
                    var selection = selectNavigationTarget(before, requestedLabel);
                    var clickInput = Map.<String, Object>of(
                            "screenToken", requiredOutputString(before, "screenToken"),
                            "snapshotRevision", requiredOutputString(before, "snapshotRevision"),
                            "nodeId", selection.nodeId());
                    return invoker.invoke("navigate.click", UI_CLICK_ID, UI_CLICK_VERSION, clickInput)
                            .thenCompose(clickResult -> {
                                var click = requireOk(clickResult, "guarded UI click");
                                context.cancellation().throwIfCancelled();
                                if (target.equals("quit") || target.equals("quit_game")) {
                                    return CompletableFuture.completedFuture(navigationOutput(
                                            target, selection, before, click, null));
                                }
                                return invoker.invoke("navigate.after", UI_STATE_ID, UI_STATE_VERSION, Map.of())
                                        .thenApply(afterResult -> navigationOutput(target, selection, before, click,
                                                requireOk(afterResult, "UI state read after navigation")));
                            });
                });
    }

    private static Map<String, Object> requireOk(ResultEnvelope result, String operation) {
        if (result.status() == ResultEnvelope.Status.OK) return result.output();
        var error = result.error();
        throw new IllegalStateException(operation + " failed: " + error.code() + ": " + error.message());
    }

    private static String navigationLabel(String target) {
        return switch (target) {
            case "singleplayer" -> "Singleplayer";
            case "multiplayer" -> "Multiplayer";
            case "mods" -> "Mods";
            case "options" -> "Options";
            case "language" -> "Language";
            case "accessibility" -> "Accessibility";
            case "credits" -> "Credits";
            case "quit", "quit_game" -> "Quit Game";
            case "back" -> "Back";
            case "cancel" -> "Cancel";
            case "done" -> "Done";
            case "create_new_world", "create_world" -> "Create New World";
            case "world_tab" -> "World";
            case "world_seed" -> "Seed for the World Generator";
            case "play_selected_world" -> "Play Selected World";
            case "join_server" -> "Join Server";
            case "direct_connect" -> "Direct Connection";
            default -> throw new IllegalArgumentException("unknown UI navigation target: " + target);
        };
    }

    private static NavigationSelection selectNavigationTarget(Map<String, Object> state, String requestedLabel) {
        if (!Boolean.TRUE.equals(state.get("open"))) {
            throw new IllegalArgumentException("UI navigation requires an open screen");
        }
        if (!(state.get("widgets") instanceof List<?> widgets)) {
            throw new IllegalStateException("UI state omitted its widgets projection");
        }
        var candidates = new ArrayList<WidgetCandidate>();
        for (var raw : widgets) {
            if (!(raw instanceof Map<?, ?> widget)) continue;
            if (Boolean.FALSE.equals(widget.get("visible")) || Boolean.FALSE.equals(widget.get("active"))) continue;
            if (!(widget.get("nodeId") instanceof String nodeId) || nodeId.isBlank()) continue;
            if (!(widget.get("actions") instanceof Collection<?> actions) || !actions.contains("click")) continue;
            var label = widget.get("label") instanceof String value ? value.trim() : "";
            var narration = widget.get("narration") instanceof String value ? value.trim() : "";
            candidates.add(new WidgetCandidate(nodeId, label, narration));
        }
        var normalized = requestedLabel.toLowerCase(Locale.ROOT);
        var exact = candidates.stream()
                .filter(candidate -> candidate.label().toLowerCase(Locale.ROOT).equals(normalized))
                .map(candidate -> candidate.selection("exact", requestedLabel)).toList();
        if (!exact.isEmpty()) return unique(exact, requestedLabel, "exact");
        var contains = candidates.stream()
                .filter(candidate -> candidate.label().toLowerCase(Locale.ROOT).contains(normalized))
                .map(candidate -> candidate.selection("contains", requestedLabel)).toList();
        if (!contains.isEmpty()) return unique(contains, requestedLabel, "contains");
        var narration = candidates.stream()
                .filter(candidate -> candidate.narration().toLowerCase(Locale.ROOT).contains(normalized))
                .map(candidate -> candidate.selection("narration", requestedLabel)).toList();
        if (!narration.isEmpty()) return unique(narration, requestedLabel, "narration");
        var coverage = String.valueOf(state.getOrDefault("coverage", "unknown"));
        var truncated = Boolean.TRUE.equals(state.get("truncated"));
        throw new IllegalArgumentException("navigation target label was not found: " + requestedLabel
                + " (coverage=" + coverage + ", truncated=" + truncated + ")");
    }

    private static NavigationSelection unique(List<NavigationSelection> matches, String label, String matchType) {
        if (matches.size() != 1) {
            throw new IllegalArgumentException("navigation target is ambiguous for " + matchType
                    + " label '" + label + "': " + matches.size() + " clickable widgets");
        }
        return matches.get(0);
    }

    private static String requiredOutputString(Map<String, Object> output, String field) {
        var value = output.get(field);
        if (!(value instanceof String string) || string.isBlank()) {
            throw new IllegalStateException("UI state omitted required field: " + field);
        }
        return string;
    }

    private static Map<String, Object> navigationOutput(String target, NavigationSelection selection,
                                                         Map<String, Object> before, Map<String, Object> click,
                                                         Map<String, Object> after) {
        var output = new LinkedHashMap<String, Object>();
        output.put("target", target);
        output.put("label", selection.label());
        output.put("match", selection.match());
        output.put("handled", Boolean.TRUE.equals(click.get("handled")));
        output.put("before", before);
        output.put("click", click);
        if (after != null) output.put("after", after);
        return Map.copyOf(output);
    }

    private CompletionStage<Map<String, Object>> waitForUi(InvocationContext context) {
        var input = context.request().input();
        var waitedFor = (String) input.get("until");
        var timeoutMs = intValue(input.get("timeoutMs"), DEFAULT_TIMEOUT_MS);
        var pollIntervalMs = intValue(input.get("pollIntervalMs"), DEFAULT_POLL_INTERVAL_MS);
        var startedNanos = System.nanoTime();
        var timeoutNanos = startedNanos + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        return poll(context, waitedFor, pollIntervalMs, startedNanos, timeoutNanos, 0);
    }

    private CompletionStage<Map<String, Object>> poll(InvocationContext context, String waitedFor,
                                                       int pollIntervalMs, long startedNanos,
                                                       long timeoutNanos, int pollIndex) {
        context.cancellation().throwIfCancelled();
        var invoker = InvocationAttributes.requireDelegatedInvoker(context);
        return invoker.invoke("poll." + pollIndex, UI_STATE_ID, UI_STATE_VERSION, Map.of())
                .thenCompose(result -> {
                    context.cancellation().throwIfCancelled();
                    var state = result.output();
                    var completedNanos = System.nanoTime();
                    var pollCount = pollIndex + 1;
                    if (completedNanos - timeoutNanos >= 0) {
                        return CompletableFuture.completedFuture(output(
                                true, waitedFor, pollCount, startedNanos, completedNanos, state));
                    }
                    if (matches(waitedFor, state)) {
                        return CompletableFuture.completedFuture(output(
                                false, waitedFor, pollCount, startedNanos, completedNanos, state));
                    }
                    var remainingNanos = timeoutNanos - completedNanos;
                    var remainingMs = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
                    var delayMs = Math.min((long) pollIntervalMs, remainingMs);
                    return CompletableFuture.runAsync(context.cancellation()::throwIfCancelled,
                                    CompletableFuture.delayedExecutor(
                                            delayMs, TimeUnit.MILLISECONDS, context.gameExecutor()))
                            .thenCompose(ignored -> poll(context, waitedFor, pollIntervalMs,
                                    startedNanos, timeoutNanos, pollIndex + 1));
                });
    }

    private static Map<String, Object> output(boolean timedOut, String waitedFor, int pollCount,
                                               long startedNanos, long completedNanos,
                                               Map<String, Object> state) {
        return Map.of("timedOut", timedOut,
                "waitedFor", waitedFor,
                "pollCount", pollCount,
                "elapsedMs", TimeUnit.NANOSECONDS.toMillis(Math.max(0L, completedNanos - startedNanos)),
                "state", state);
    }

    private static boolean matches(String waitedFor, Map<String, Object> state) {
        return switch (waitedFor) {
            case "in_world" -> Boolean.TRUE.equals(state.get("inWorld"));
            case "screen_open" -> Boolean.TRUE.equals(state.get("open"));
            case "screen_closed" -> Boolean.FALSE.equals(state.get("open"));
            default -> {
                var screenClass = waitedFor.substring("screen_class:".length());
                yield screenClass.equals(state.get("screenClass")) || screenClass.equals(state.get("screen"));
            }
        };
    }

    private static int intValue(Object value, int defaultValue) {
        return value instanceof Number number ? number.intValue() : defaultValue;
    }

    private record WidgetCandidate(String nodeId, String label, String narration) {
        private NavigationSelection selection(String match, String fallbackLabel) {
            return new NavigationSelection(nodeId, label.isBlank() ? fallbackLabel : label, match);
        }
    }

    private record NavigationSelection(String nodeId, String label, String match) {}
}
