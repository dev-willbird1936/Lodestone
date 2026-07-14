// SPDX-License-Identifier: MIT
package dev.lodestone.legacybridge;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.lodestone.protocol.JsonSupport;
import dev.lodestone.protocol.RequestEnvelope;
import dev.lodestone.protocol.ResultEnvelope;
import dev.lodestone.runtime.AuthorizationPolicy;
import dev.lodestone.runtime.LodestoneRuntime;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyBridgeAdapterTest {
    @Test
    void successfulWritePreflightCommitsOnlyBeforeTheSingleRealDispatch() throws Exception {
        var invocations = new AtomicInteger();
        var dryRuns = new ArrayList<Boolean>();
        var server = bridgeServer((exchange, capability, input) -> {
            invocations.incrementAndGet();
            if ("minecraft.world.blocks.write".equals(capability)) {
                boolean dryRun = Boolean.TRUE.equals(input.get("dryRun"));
                dryRuns.add(dryRun);
                respond(exchange, 200, Map.of("ok", true, "result", writeResult(dryRun)));
                return;
            }
            respond(exchange, 500, failure(capability));
        });
        try (var runtime = runtimeFor(server)) {
            var write = runtime.invoke(request(runtime, "write", "minecraft.world.blocks.write", writeInput())).get();
            assertEquals(ResultEnvelope.Status.OK, write.status(), String.valueOf(write));
            assertEquals(1.0, write.output().get("changedCount"));
            assertEquals(List.of(true, false), dryRuns);
            assertEquals(2, invocations.get(), "exactly one dry-run and one irreversible dispatch are allowed");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void postCommitBridgeFailureIsIndeterminateAndQuarantinesEveryMutation() throws Exception {
        var invocations = new AtomicInteger();
        var server = bridgeServer((exchange, capability, input) -> {
            invocations.incrementAndGet();
            if ("minecraft.world.blocks.write".equals(capability) && Boolean.TRUE.equals(input.get("dryRun"))) {
                respond(exchange, 200, Map.of("ok", true, "result", writeResult(true)));
                return;
            }
            if ("minecraft.world.blocks.write".equals(capability)) {
                respond(exchange, 500, Map.of("ok", false,
                        "error", Map.of("code", "TRANSPORT_UNCERTAIN", "message", "acknowledgement lost")));
                return;
            }
            respond(exchange, 500, failure(capability));
        });
        try (var runtime = runtimeFor(server)) {
            var first = runtime.invoke(request(runtime, "write", "minecraft.world.blocks.write", writeInput())).get();
            assertEquals(ResultEnvelope.Status.ERROR, first.status());
            assertEquals("OUTCOME_INDETERMINATE", first.error().code());
            assertFalse(first.error().retryable());
            assertEquals(true, first.error().details().get("mutationCommitted"));

            var quarantined = runtime.invoke(request(runtime, "chat", "minecraft.chat.send", Map.of("message", "must not dispatch"))).get();
            assertEquals(ResultEnvelope.Status.ERROR, quarantined.status());
            assertEquals("CAPABILITY_QUARANTINED", quarantined.error().code());
            assertEquals(2, invocations.get(), "shared mutation quarantine must block a later chat dispatch");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectedWritePreflightDoesNotQuarantineLaterMutationCapability() throws Exception {
        var invokes = new AtomicInteger();
        var sawDryRun = new AtomicBoolean();
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/health", exchange -> respond(exchange, 200,
                Map.of("ok", true, "result", Map.of("gameVersion", "1.12.2", "loader", "forge"))));
        server.createContext("/invoke", exchange -> {
            @SuppressWarnings("unchecked")
            var request = (Map<String, Object>) JsonSupport.MAPPER.fromJson(
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), Map.class);
            String capability = String.valueOf(request.get("capability"));
            @SuppressWarnings("unchecked")
            var input = (Map<String, Object>) request.get("input");
            invokes.incrementAndGet();
            if ("minecraft.world.blocks.write".equals(capability) && Boolean.TRUE.equals(input.get("dryRun"))) {
                sawDryRun.set(true);
                respond(exchange, 400, Map.of("ok", false,
                        "error", Map.of("code", "ADAPTER_FAILURE", "message", "protected block entity")));
                return;
            }
            if ("minecraft.chat.send".equals(capability)) {
                respond(exchange, 200, Map.of("ok", true,
                        "result", Map.of("sent", true, "message", "still available", "recipientCount", 0)));
                return;
            }
            respond(exchange, 500, Map.of("ok", false, "error", Map.of("code", "UNEXPECTED", "message", capability)));
        });
        server.start();
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.fromCsv("modify-world,communicate"))) {
            var adapter = new LegacyBridgeAdapter("127.0.0.1", server.getAddress().getPort(), "test-token", "1.12.2");
            runtime.registerAdapter(adapter);
            adapter.probe();
            runtime.refreshAdapter(adapter);

            var rejectedWrite = runtime.invoke(request(runtime, "write", "minecraft.world.blocks.write", Map.of(
                    "changes", java.util.List.of(Map.of("x", 1, "y", 64, "z", 1, "block", "minecraft:stone"))))).get();
            assertEquals(ResultEnvelope.Status.ERROR, rejectedWrite.status());
            assertEquals("ADAPTER_FAILURE", rejectedWrite.error().code());

            var chat = runtime.invoke(request(runtime, "chat", "minecraft.chat.send", Map.of("message", "still available"))).get();
            assertEquals(ResultEnvelope.Status.OK, chat.status(), String.valueOf(chat));
            assertEquals(true, chat.output().get("sent"));
            assertTrue(sawDryRun.get());
            assertEquals(2, invokes.get());
        } finally {
            server.stop(0);
        }
    }

    private static RequestEnvelope request(LodestoneRuntime runtime, String requestId, String capability,
                                           Map<String, Object> input) {
        return new RequestEnvelope("1.0", requestId, runtime.sessionId(), capability, "1.0", input,
                System.currentTimeMillis() + 10_000L, null, false);
    }

    private static LodestoneRuntime runtimeFor(HttpServer server) {
        var runtime = new LodestoneRuntime(AuthorizationPolicy.fromCsv("modify-world,communicate"));
        var adapter = new LegacyBridgeAdapter("127.0.0.1", server.getAddress().getPort(), "test-token", "1.12.2");
        runtime.registerAdapter(adapter);
        adapter.probe();
        runtime.refreshAdapter(adapter);
        return runtime;
    }

    private static HttpServer bridgeServer(BridgeHandler handler) throws IOException {
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/health", exchange -> respond(exchange, 200,
                Map.of("ok", true, "result", Map.of("gameVersion", "1.12.2", "loader", "forge"))));
        server.createContext("/invoke", exchange -> {
            @SuppressWarnings("unchecked")
            var request = (Map<String, Object>) JsonSupport.MAPPER.fromJson(
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), Map.class);
            @SuppressWarnings("unchecked")
            var input = (Map<String, Object>) request.get("input");
            handler.handle(exchange, String.valueOf(request.get("capability")), input);
        });
        server.start();
        return server;
    }

    private static Map<String, Object> writeInput() {
        return Map.of("changes", List.of(Map.of("x", 1, "y", 64, "z", 1, "block", "minecraft:stone")));
    }

    private static Map<String, Object> writeResult(boolean dryRun) {
        return Map.of("dimension", "minecraft:overworld", "dryRun", dryRun, "validated", true,
                "requestedCount", 1, "changedCount", dryRun ? 0 : 1,
                "changes", List.of(Map.of("position", Map.of("x", 1, "y", 64, "z", 1),
                        "requestedBlock", "minecraft:stone", "previousBlock", "minecraft:air",
                        "changed", true, "applied", !dryRun)));
    }

    private static Map<String, Object> failure(String capability) {
        return Map.of("ok", false, "error", Map.of("code", "UNEXPECTED", "message", capability));
    }

    @FunctionalInterface
    private interface BridgeHandler {
        void handle(HttpExchange exchange, String capability, Map<String, Object> input) throws IOException;
    }

    private static void respond(HttpExchange exchange, int status, Map<String, Object> body) throws IOException {
        byte[] bytes = JsonSupport.MAPPER.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
