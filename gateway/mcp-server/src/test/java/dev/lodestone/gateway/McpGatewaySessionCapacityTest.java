// SPDX-License-Identifier: MIT
package dev.lodestone.gateway;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.lodestone.adapter.CapabilityHandler;
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
import dev.lodestone.protocol.SideEffect;
import dev.lodestone.protocol.Stability;
import dev.lodestone.runtime.AuthorizationPolicy;
import dev.lodestone.runtime.LodestoneRuntime;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gateway's HTTP session table is bounded (MAX_SESSIONS). These tests cover its three admission
 * outcomes once the table is full: an explicit HTTP DELETE frees a slot immediately, an idle
 * least-recently-used session is evicted automatically to admit a new one, and only a table where
 * every session is genuinely mid-request is honestly reported as exhausted rather than silently
 * minting a session with no id.
 */
final class McpGatewaySessionCapacityTest {
    private static final int MAX_SESSIONS = 128;
    private static final String PROTOCOL_VERSION = "2025-11-25";

    @Test
    void deletingASessionFreesItsSlotWithoutDisturbingAnyOtherSession() {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var gateway = new McpGateway(runtime);
            var sessionIds = new ArrayList<String>();
            for (var i = 0; i < MAX_SESSIONS; i++) {
                sessionIds.add(initialize(gateway));
            }

            var deleted = sessionIds.get(0);
            assertTrue(gateway.terminateSession(deleted), "DELETE must report success for a live session");
            assertFalse(gateway.terminateSession(deleted), "DELETE on an already-terminated session must report false");
            assertEquals(-32001, errorCode(toolsList(gateway, deleted)),
                    "a DELETEd session id must no longer be usable");

            var freshSessionId = initialize(gateway);
            assertNotNull(freshSessionId, "the freed slot must admit a brand-new session");

            for (var i = 1; i < sessionIds.size(); i++) {
                var stillAlive = toolsList(gateway, sessionIds.get(i));
                assertNotNull(stillAlive.getAsJsonObject("result"),
                        "session " + i + " must survive a DELETE targeted at a different session: " + stillAlive);
            }
        }
    }

    @Test
    void tableFullWithEveryIdleSessionEvictsTheLeastRecentlyUsedInsteadOfRefusingService() {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var gateway = new McpGateway(runtime);
            var sessionIds = new ArrayList<String>();
            for (var i = 0; i < MAX_SESSIONS; i++) {
                sessionIds.add(initialize(gateway));
            }
            var oldest = sessionIds.get(0);
            var secondOldest = sessionIds.get(1);

            var admittedId = initialize(gateway);
            assertNotNull(admittedId, "a full table of idle sessions must still admit a new one via eviction");

            assertEquals(-32001, errorCode(toolsList(gateway, oldest)),
                    "the least-recently-used idle session must be evicted to admit the new one");
            assertNotNull(toolsList(gateway, secondOldest).getAsJsonObject("result"),
                    "a session that is not the LRU must survive the eviction");
            assertNotNull(toolsList(gateway, admittedId).getAsJsonObject("result"),
                    "the newly admitted session must work");
        }
    }

    @Test
    void exhaustionIsReportedHonestlyOnlyWhenEverySessionIsGenuinelyInFlight() throws InterruptedException {
        var entered = new CountDownLatch(MAX_SESSIONS);
        var release = new CountDownLatch(1);
        var blocker = blockingCapability("test.block-until-released");
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            runtime.registerAdapter(adapter(blocker, context -> {
                entered.countDown();
                awaitQuietly(release);
                return CompletableFuture.completedFuture(Map.of());
            }));
            var gateway = new McpGateway(runtime);

            var sessionIds = new ArrayList<String>();
            for (var i = 0; i < MAX_SESSIONS; i++) {
                sessionIds.add(initialize(gateway));
            }

            var threads = new ArrayList<Thread>();
            for (var sessionId : sessionIds) {
                var thread = new Thread(() -> invokeBlockingCapability(gateway, sessionId, blocker.id()));
                threads.add(thread);
                thread.start();
            }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS),
                        "every existing session must be genuinely mid-request before the exhaustion assertion");

                var exhausted = JsonParser.parseString(gateway.handleHttp(initializeRequest(), null, PROTOCOL_VERSION))
                        .getAsJsonObject();
                assertEquals(-32000, errorCode(exhausted));
                assertTrue(exhausted.getAsJsonObject("error").get("message").getAsString()
                                .toLowerCase(Locale.ROOT).contains("exhaust"),
                        "the failure must honestly name session exhaustion instead of omitting the session id: "
                                + exhausted);
            } finally {
                release.countDown();
                for (var thread : threads) {
                    thread.join(5_000);
                }
            }
        }
    }

    private static void invokeBlockingCapability(McpGateway gateway, String sessionId, String capability) {
        var request = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"lodestone_capability_invoke\","
                + "\"arguments\":{\"capability\":\"" + capability + "\",\"capabilityVersion\":\"1.0\",\"input\":{}}}}";
        gateway.handleHttp(request, sessionId, PROTOCOL_VERSION);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static String initialize(McpGateway gateway) {
        var response = JsonParser.parseString(gateway.handleHttp(initializeRequest(), null, PROTOCOL_VERSION))
                .getAsJsonObject();
        assertNotNull(response.getAsJsonObject("result"), "initialize must succeed: " + response);
        var sessionId = gateway.responseSessionId();
        assertNotNull(sessionId, "a successful initialize must mint a session id");
        return sessionId;
    }

    private static String initializeRequest() {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\""
                + PROTOCOL_VERSION + "\"}}";
    }

    private static JsonObject toolsList(McpGateway gateway, String sessionId) {
        var request = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}";
        return JsonParser.parseString(gateway.handleHttp(request, sessionId, PROTOCOL_VERSION)).getAsJsonObject();
    }

    private static int errorCode(JsonObject response) {
        return response.getAsJsonObject("error").get("code").getAsInt();
    }

    private static CapabilityDescriptor blockingCapability(String id) {
        return new CapabilityDescriptor(id, CapabilityKind.ACTION, "1.0", Stability.STABLE,
                Availability.AVAILABLE, null, "gateway.test.session-capacity-adapter", "1.0.0",
                "minecraft", "test", "test", Environment.REMOTE,
                Map.of("type", "object", "additionalProperties", false),
                Map.of("type", "object", "additionalProperties", false), Map.of(),
                Set.of(PermissionClass.OBSERVE), SideEffect.NONE, Idempotency.IDEMPOTENT,
                new CapabilityPrerequisites(false, false, false, false),
                "runtime", new RateLimit(1_000, 60_000, 1_000), 60_000, true,
                new DeliveryGuarantees("request-order", "at-most-once", 1),
                "Blocks until released; holds a session genuinely in-flight for capacity tests", Set.of());
    }

    private static LodestoneAdapter adapter(CapabilityDescriptor descriptor, CapabilityHandler handler) {
        var adapterDescriptor = new AdapterDescriptor("gateway.test.session-capacity-adapter", "1.0.0",
                "minecraft", "test", "test", Environment.REMOTE);
        return new LodestoneAdapter() {
            @Override
            public AdapterDescriptor descriptor() {
                return adapterDescriptor;
            }

            @Override
            public CapabilityManifest manifest() {
                return new CapabilityManifest(adapterDescriptor, List.of(descriptor));
            }

            @Override
            public Map<String, CapabilityHandler> handlers() {
                return Map.of(descriptor.id(), handler);
            }
        };
    }
}
