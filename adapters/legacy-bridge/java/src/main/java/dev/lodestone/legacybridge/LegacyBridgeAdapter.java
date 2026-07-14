// SPDX-License-Identifier: MIT
package dev.lodestone.legacybridge;

import dev.lodestone.adapter.AdapterContext;
import dev.lodestone.adapter.AdapterHealth;
import dev.lodestone.adapter.CapabilityHandler;
import dev.lodestone.adapter.InvocationContext;
import dev.lodestone.adapter.LodestoneAdapter;
import dev.lodestone.protocol.AdapterDescriptor;
import dev.lodestone.protocol.Availability;
import dev.lodestone.protocol.AvailabilityReason;
import dev.lodestone.protocol.CapabilityDescriptor;
import dev.lodestone.protocol.CapabilityManifest;
import dev.lodestone.protocol.Environment;
import dev.lodestone.protocol.JsonSupport;
import dev.lodestone.runtime.CoreCatalog;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Adapter for a Java 8-native legacy Forge Lodestone bridge. */
public final class LegacyBridgeAdapter implements LodestoneAdapter {
    public static final String ADAPTER_ID = "lodestone.forge.mc1_12_2.native-bridge";
    private static final Set<String> IMPLEMENTED = Set.of(
            "minecraft.command.execute", "minecraft.player.state.read",
            "minecraft.world.block.read", "minecraft.world.blocks.read",
            "minecraft.world.region.scan", "minecraft.world.blocks.write",
            "minecraft.entity.list", "minecraft.inventory.read", "minecraft.chat.send");
    private static final Set<String> MUTATING = Set.of(
            "minecraft.command.execute", "minecraft.world.blocks.write", "minecraft.chat.send");

    private final URI endpoint;
    private final String token;
    private final HttpClient client;
    private final String gameVersion;
    private final AdapterDescriptor descriptor;
    private volatile boolean reachable;
    private volatile AvailabilityReason unavailableReason;

    public LegacyBridgeAdapter(String host, int port, String token) {
        this(host, port, token, "1.12.2");
    }

    public LegacyBridgeAdapter(String host, int port, String token, String gameVersion) {
        if (host == null || host.isBlank() || token == null || token.isBlank()) {
            throw new IllegalArgumentException("legacy bridge host and token are required");
        }
        if (port < 1 || port > 65_535) throw new IllegalArgumentException("legacy bridge port is invalid");
        if (gameVersion == null || gameVersion.isBlank()) throw new IllegalArgumentException("legacy bridge game version is required");
        this.gameVersion = gameVersion.trim();
        this.endpoint = URI.create("http://" + host + ":" + port);
        this.token = token;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        this.descriptor = new AdapterDescriptor(
                "lodestone.forge.mc" + this.gameVersion.replace('.', '_') + ".native-bridge",
                "0.1.0", "minecraft-java", this.gameVersion, "forge", Environment.REMOTE);
        this.unavailableReason = reason("not-connected", label() + " native bridge has not been probed yet.");
    }

    @Override
    public AdapterDescriptor descriptor() { return descriptor; }

    @Override
    public CapabilityManifest manifest() {
        List<CapabilityDescriptor> capabilities = CoreCatalog.load().stream()
                .filter(capability -> capability.id().startsWith("minecraft.")
                        && !capability.id().startsWith("minecraft.event."))
                .map(this::adapt)
                .toList();
        return new CapabilityManifest(descriptor, capabilities);
    }

    @Override
    public Map<String, CapabilityHandler> handlers() {
        return IMPLEMENTED.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(id -> id, id -> this::invoke));
    }

    @Override
    public void start(AdapterContext context) { probe(); }

    @Override
    public AdapterHealth health() {
        return reachable
                ? new AdapterHealth(AdapterHealth.State.READY, label() + " native bridge is reachable", null)
                : new AdapterHealth(AdapterHealth.State.FAILED,
                label() + " native bridge is unavailable: " + unavailableReason.message(), null);
    }

    public void probe() {
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint.resolve("/health"))
                    .timeout(Duration.ofSeconds(3)).header("X-Lodestone-Token", token).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IllegalStateException("bridge health returned HTTP " + response.statusCode());
            @SuppressWarnings("unchecked") Map<String, Object> envelope = JsonSupport.MAPPER.fromJson(response.body(), Map.class);
            @SuppressWarnings("unchecked") Map<String, Object> health = (Map<String, Object>) envelope.get("result");
            if (health == null || !gameVersion.equals(String.valueOf(health.get("gameVersion")))) {
                throw new IllegalStateException("bridge game version does not match requested " + gameVersion);
            }
            reachable = true;
            unavailableReason = null;
        } catch (Exception failure) {
            reachable = false;
            unavailableReason = reason("bridge-unreachable", label() + " native bridge rejected or missed its health probe.");
        }
    }

    private CapabilityDescriptor adapt(CapabilityDescriptor capability) {
        if (!IMPLEMENTED.contains(capability.id())) {
            return capability.forAdapter(descriptor, Availability.UNAVAILABLE,
                    new AvailabilityReason("not-implemented", "This operation is not implemented by the Java 8 " + label() + " bridge yet.", Map.of()));
        }
        if (!reachable) return capability.forAdapter(descriptor, Availability.DEGRADED, unavailableReason);
        Availability availability = capability.availability() == Availability.RESTRICTED
                ? Availability.RESTRICTED : Availability.AVAILABLE;
        return capability.forAdapter(descriptor, availability,
                availability == Availability.RESTRICTED ? capability.reason() : null);
    }

    private CompletionStage<Map<String, Object>> invoke(InvocationContext invocation) {
        if (!reachable) return CompletableFuture.failedFuture(new IllegalStateException(label() + " native bridge is unavailable"));
        try {
            long deadline = invocation.request().deadlineEpochMs() == null
                    ? System.currentTimeMillis() + 10_000L : invocation.request().deadlineEpochMs();
            long remaining = Math.max(1L, deadline - System.currentTimeMillis());
            invocation.cancellation().throwIfCancelled();
            String capability = invocation.request().capability();
            Map<String, Object> input = invocation.request().input();
            boolean mutating = MUTATING.contains(capability);

            // The legacy bridge can deterministically validate a block batch without applying it.
            // Keep a rejected precondition retryable; only commit once a real mutation is about to
            // cross the process boundary. Any later transport or remote failure stays quarantined.
            if ("minecraft.world.blocks.write".equals(capability) && !Boolean.TRUE.equals(input.get("dryRun"))) {
                Map<String, Object> preflightInput = new LinkedHashMap<String, Object>(input);
                preflightInput.put("dryRun", true);
                invokeBridge(capability, preflightInput, deadline, remaining);
                invocation.cancellation().throwIfCancelled();
            }
            if (mutating && !Boolean.TRUE.equals(input.get("dryRun"))) {
                invocation.cancellation().commitMutation();
            }
            Map<String, Object> result = invokeBridge(capability, input, deadline, remaining);
            if (!mutating) {
                invocation.cancellation().throwIfCancelled();
            }
            return CompletableFuture.completedFuture(result);
        } catch (Exception failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeBridge(String capability, Map<String, Object> input,
                                              long deadline, long remaining) throws Exception {
        String body = JsonSupport.MAPPER.toJson(Map.of(
                "capability", capability, "input", input, "deadlineEpochMs", deadline));
        HttpRequest request = HttpRequest.newBuilder(endpoint.resolve("/invoke"))
                .timeout(Duration.ofMillis(Math.min(remaining, 30_000L)))
                .header("X-Lodestone-Token", token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        Map<String, Object> envelope = JsonSupport.MAPPER.fromJson(response.body(), Map.class);
        if (response.statusCode() != 200 || !Boolean.TRUE.equals(envelope.get("ok"))) {
            Object error = envelope.get("error");
            throw new IllegalStateException(error == null ? "legacy bridge operation failed" : String.valueOf(error));
        }
        Map<String, Object> result = (Map<String, Object>) envelope.get("result");
        if (result == null) throw new IllegalStateException("legacy bridge returned no result");
        return result;
    }

    private AvailabilityReason reason(String code, String message) {
        return new AvailabilityReason(code, message, Map.of("endpoint", endpoint.toString()));
    }

    private String label() { return "Forge " + gameVersion; }
}
