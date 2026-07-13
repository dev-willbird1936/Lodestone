// SPDX-License-Identifier: MIT
package dev.lodestone.gateway;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import dev.lodestone.protocol.JsonSupport;
import dev.lodestone.protocol.PermissionClass;
import dev.lodestone.protocol.RateLimit;
import dev.lodestone.protocol.SideEffect;
import dev.lodestone.protocol.Stability;
import dev.lodestone.runtime.AuthorizationPolicy;
import dev.lodestone.runtime.LodestoneRuntime;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class McpGatewayAuthorizationTest {
    @Test
    void freezesPerSessionGrantForDirectAndDelegatedControl() {
        var direct = capability("minecraft.test.gateway-control", false);
        var workflow = capability("lodestone.test.gateway-control-workflow", true);
        var controlCalls = new AtomicInteger();
        var elevateAAfterAdmission = new AtomicBoolean();
        var aResolutions = new AtomicInteger();

        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.fromCsv("observe,control-player"))) {
            runtime.registerAdapter(adapter(List.of(direct, workflow), Map.of(
                    direct.id(), context -> {
                        controlCalls.incrementAndGet();
                        return CompletableFuture.completedFuture(Map.of());
                    },
                    workflow.id(), context -> InvocationAttributes.requireDelegatedInvoker(context)
                            .invoke("control.0", direct.id(), "1.0", Map.of())
                            .thenApply(ignored -> Map.of()))));
            var gateway = new McpGateway(runtime, principal -> {
                if ("client-a".equals(principal)) {
                    aResolutions.incrementAndGet();
                    return elevateAAfterAdmission.get()
                            ? AuthorizationPolicy.fromCsv("observe,control-player")
                            : AuthorizationPolicy.observeOnly();
                }
                if ("client-b".equals(principal)) {
                    return AuthorizationPolicy.fromCsv("control-player");
                }
                return null;
            });

            initialize(gateway, "client-a", "2025-11-25");
            elevateAAfterAdmission.set(true);
            initialize(gateway, "client-b", "2025-11-25");

            assertAuthorizationDenied(invoke(gateway, "client-a", direct.id()));
            assertAuthorizationDenied(invoke(gateway, "client-a", workflow.id()));
            assertAllowed(invoke(gateway, "client-b", direct.id()));
            assertAllowed(invoke(gateway, "client-b", workflow.id()));

            assertEquals(1, aResolutions.get(), "session authorization must be resolved exactly once");
            assertEquals(2, controlCalls.get(), "denied caller must never reach direct or delegated handlers");
            assertTrue(runtime.auditTrace().stream().anyMatch(record ->
                    "client-a".equals(record.callerSessionId()) && direct.id().equals(record.capability())));
            assertTrue(runtime.auditTrace().stream().anyMatch(record ->
                    "client-a".equals(record.callerSessionId()) && workflow.id().equals(record.capability())));
            assertTrue(runtime.auditTrace().stream().anyMatch(record ->
                    "client-b".equals(record.callerSessionId()) && direct.id().equals(record.capability())
                            && record.delegationPath().equals(List.of(workflow.id(), direct.id()))));
        }
    }

    @Test
    void nullOrFailedGrantResolutionAndFailedInitializeDoNotConsumeCapacity() {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var nullResolverGateway = new McpGateway(runtime, null);
            assertEquals(-32004, errorCode(initialize(nullResolverGateway, "null-resolver", "2025-11-25")));

            var gateway = new McpGateway(runtime, principal -> {
                if ("null-grant".equals(principal)) return null;
                if ("failed-grant".equals(principal)) throw new IllegalStateException("resolver unavailable");
                return AuthorizationPolicy.observeOnly();
            });
            assertEquals(-32004, errorCode(initialize(gateway, "null-grant", "2025-11-25")));
            assertEquals(-32004, errorCode(initialize(gateway, "failed-grant", "2025-11-25")));

            for (var attempt = 0; attempt < 128; attempt++) {
                assertEquals(-32603, errorCode(initialize(gateway, "invalid-" + attempt, "unsupported")));
            }
            var admitted = initialize(gateway, "valid-after-failures", "2025-11-25");
            assertNotNull(admitted.getAsJsonObject("result"));
        }
    }

    private static CapabilityDescriptor capability(String id, boolean workflow) {
        return new CapabilityDescriptor(id, CapabilityKind.ACTION, "1.0", Stability.STABLE,
                Availability.AVAILABLE, null, "gateway.test.adapter", "1.0.0", "minecraft", "test",
                "test", Environment.REMOTE,
                Map.of("type", "object", "additionalProperties", false),
                Map.of("type", "object", "additionalProperties", false), Map.of(),
                Set.of(PermissionClass.CONTROL_PLAYER), SideEffect.CONTROL_PLAYER,
                Idempotency.NON_IDEMPOTENT, new CapabilityPrerequisites(false, false, false, false),
                "runtime", new RateLimit(100, 60_000, 100), 5_000, true,
                new DeliveryGuarantees("request-order", "at-most-once", 1), "Gateway authorization test",
                workflow ? Set.of("delegates-native") : Set.of());
    }

    private static LodestoneAdapter adapter(List<CapabilityDescriptor> descriptors,
                                             Map<String, CapabilityHandler> handlers) {
        var descriptor = new AdapterDescriptor("gateway.test.adapter", "1.0.0", "minecraft", "test",
                "test", Environment.REMOTE);
        return new LodestoneAdapter() {
            @Override
            public AdapterDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public CapabilityManifest manifest() {
                return new CapabilityManifest(descriptor, descriptors);
            }

            @Override
            public Map<String, CapabilityHandler> handlers() {
                return handlers;
            }
        };
    }

    private static JsonObject initialize(McpGateway gateway, String session, String protocolVersion) {
        var request = JsonSupport.MAPPER.toJson(Map.of(
                "jsonrpc", "2.0", "id", 1, "method", "initialize",
                "params", Map.of("protocolVersion", protocolVersion)));
        return JsonParser.parseString(gateway.handle(request, session)).getAsJsonObject();
    }

    private static JsonObject invoke(McpGateway gateway, String session, String capability) {
        var request = JsonSupport.MAPPER.toJson(Map.of(
                "jsonrpc", "2.0", "id", capability, "method", "tools/call",
                "params", Map.of("name", "lodestone_capability_invoke", "arguments", Map.of(
                        "capability", capability, "capabilityVersion", "1.0", "input", Map.of()))));
        return JsonParser.parseString(gateway.handle(request, session)).getAsJsonObject()
                .getAsJsonObject("result");
    }

    private static void assertAuthorizationDenied(JsonObject result) {
        assertTrue(result.get("isError").getAsBoolean());
        assertEquals("AUTHORIZATION_DENIED", result.getAsJsonObject("structuredContent")
                .getAsJsonObject("error").get("code").getAsString());
    }

    private static void assertAllowed(JsonObject result) {
        assertFalse(result.get("isError").getAsBoolean());
    }

    private static int errorCode(JsonObject response) {
        return response.getAsJsonObject("error").get("code").getAsInt();
    }
}
