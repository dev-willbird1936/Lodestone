// SPDX-License-Identifier: MIT
package dev.lodestone.gateway;

import dev.lodestone.runtime.AuthorizationPolicy;
import dev.lodestone.runtime.LodestoneRuntime;
import org.junit.jupiter.api.Test;

import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code port=0} is what {@link GoalOrchestratorLauncher} relies on to stand up a private, throwaway
 * loopback endpoint for a spawned orchestrator subprocess without contending for (or hardcoding) a
 * specific port.
 */
class LoopbackHttpServerTest {
    @Test
    void portZeroBindsToARealOsAssignedPortAndAcceptsRequests() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var gateway = new McpGateway(runtime);
            try (var loopback = new LoopbackHttpServer(gateway, 0, "test-token")) {
                assertEquals(0, loopback.port(), "before start(), an ephemeral request still reports the requested 0");

                loopback.start();
                var boundPort = loopback.port();
                assertNotEquals(0, boundPort, "start() must resolve port=0 to a real OS-assigned port");
                assertTrue(boundPort > 0 && boundPort <= 65_535, "bound port out of range: " + boundPort);

                var connection = (HttpURLConnection) URI.create("http://127.0.0.1:" + boundPort + "/mcp")
                        .toURL().openConnection();
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("X-Lodestone-Token", "test-token");
                var body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                        + "\"params\":{\"protocolVersion\":\"2025-11-25\"}}";
                connection.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
                assertEquals(200, connection.getResponseCode(),
                        "the ephemeral-port server must actually answer requests on the port it reports");
                connection.disconnect();
            }
        }
    }

    @Test
    void constructorAllowsPortZeroButRejectsNegativePorts() {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var gateway = new McpGateway(runtime);
            assertThrows(IllegalArgumentException.class, () -> new LoopbackHttpServer(gateway, -1, "test-token"));
            new LoopbackHttpServer(gateway, 0, "test-token").close();
        }
    }
}
