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
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyBridgeAdapterTest {
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

    private static void respond(HttpExchange exchange, int status, Map<String, Object> body) throws IOException {
        byte[] bytes = JsonSupport.MAPPER.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
