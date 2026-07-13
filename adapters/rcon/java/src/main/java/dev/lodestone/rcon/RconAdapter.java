// SPDX-License-Identifier: MIT
package dev.lodestone.rcon;

import dev.lodestone.adapter.AdapterContext;
import dev.lodestone.adapter.AdapterHealth;
import dev.lodestone.adapter.CapabilityHandler;
import dev.lodestone.adapter.CancellationToken;
import dev.lodestone.adapter.InvocationContext;
import dev.lodestone.adapter.LodestoneAdapter;
import dev.lodestone.protocol.AdapterDescriptor;
import dev.lodestone.protocol.Availability;
import dev.lodestone.protocol.AvailabilityReason;
import dev.lodestone.protocol.CapabilityDescriptor;
import dev.lodestone.protocol.CapabilityManifest;
import dev.lodestone.protocol.Environment;
import dev.lodestone.runtime.CoreCatalog;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class RconAdapter implements LodestoneAdapter {
    public static final String ADAPTER_ID = "lodestone.rcon.java";
    public static final String CAPABILITY_ID = "minecraft.command.rcon.execute";

    private final String host;
    private final int port;
    private final RconClient client;
    private final AdapterDescriptor descriptor = new AdapterDescriptor(
            ADAPTER_ID, "0.1.0", "minecraft-java", "negotiated", "rcon", Environment.REMOTE);
    private volatile AdapterContext context;
    private volatile AvailabilityReason unavailableReason;
    private volatile boolean reachable;
    private volatile Runnable refreshHook = () -> { };
    private final ScheduledExecutorService probeExecutor = Executors.newSingleThreadScheduledExecutor();

    public RconAdapter(String host, int port, String password, int maxOutputBytes) {
        this.host = host;
        this.port = port;
        this.client = new RconClient(host, port, password, maxOutputBytes);
        this.unavailableReason = reason("not-connected", "RCON endpoint has not been probed yet.");
    }

    @Override
    public AdapterDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public CapabilityManifest manifest() {
        var catalogCapability = CoreCatalog.load().stream()
                .filter(capability -> CAPABILITY_ID.equals(capability.id()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("RCON capability is missing from the catalog"));
        var availability = reachable ? Availability.AVAILABLE : Availability.UNAVAILABLE;
        var reason = reachable ? null : unavailableReason;
        return new CapabilityManifest(descriptor, java.util.List.of(
                catalogCapability.forAdapter(descriptor, availability, reason)));
    }

    @Override
    public Map<String, CapabilityHandler> handlers() {
        return Map.of(CAPABILITY_ID, this::executeCommand);
    }

    @Override
    public void start(AdapterContext context) {
        this.context = context;
        probeExecutor.scheduleWithFixedDelay(this::scheduledProbe, 5, 15, TimeUnit.SECONDS);
    }

    public void setRefreshHook(Runnable refreshHook) {
        this.refreshHook = refreshHook == null ? () -> { } : refreshHook;
    }

    public void probe() {
        var wasReachable = reachable;
        try {
            client.connect(CancellationToken.none(), System.currentTimeMillis() + 5_000);
            client.close();
            reachable = true;
            unavailableReason = null;
        } catch (Exception failure) {
            reachable = false;
            unavailableReason = reason("rcon-unreachable", "RCON endpoint is not reachable or rejected authentication.");
        }
        if (wasReachable != reachable) refreshHook.run();
    }

    private void scheduledProbe() {
        try {
            probe();
        } catch (RuntimeException ignored) {
            // Probe loop must survive transient endpoint and shutdown failures.
        }
    }

    @Override
    public AdapterHealth health() {
        if (reachable) {
            return new AdapterHealth(AdapterHealth.State.READY, "RCON endpoint passed the latest probe", null);
        }
        return new AdapterHealth(AdapterHealth.State.FAILED,
                "RCON endpoint is unavailable at " + host + ":" + port, null);
    }

    private java.util.concurrent.CompletionStage<Map<String, Object>> executeCommand(InvocationContext invocation) {
        var command = text(invocation.request().input());
        var deadline = invocation.request().deadlineEpochMs() == null
                ? System.currentTimeMillis() + 10_000 : invocation.request().deadlineEpochMs();
        try {
            var response = client.execute(command, invocation.cancellation(), deadline);
            var wasReachable = reachable;
            reachable = true;
            unavailableReason = null;
            if (!wasReachable) refreshHook.run();
            return CompletableFuture.completedFuture(Map.of(
                    "command", command,
                    "text", response.text(),
                    "truncated", response.truncated(),
                    "transport", "rcon"));
        } catch (CancellationToken.CancellationException cancellation) {
            throw cancellation;
        } catch (Exception failure) {
            var wasReachable = reachable;
            reachable = false;
            unavailableReason = reason("rcon-request-failed", "RCON command request failed; retry after endpoint recovery.");
            if (wasReachable) refreshHook.run();
            return CompletableFuture.failedFuture(failure);
        }
    }

    @Override
    public void close() {
        client.close();
        reachable = false;
        probeExecutor.shutdownNow();
    }

    private static String text(Map<String, Object> input) {
        var value = input.get("command");
        if (!(value instanceof String command) || command.isBlank() || command.length() > 32_768) {
            throw new IllegalArgumentException("input field must be a non-empty command up to 32768 characters: command");
        }
        return command;
    }

    private AvailabilityReason reason(String code, String message) {
        return new AvailabilityReason(code, message, Map.of("host", host, "port", port));
    }
}
