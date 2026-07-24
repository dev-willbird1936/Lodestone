// SPDX-License-Identifier: MIT
package dev.lodestone.gateway;

import dev.lodestone.runtime.AuthorizationPolicy;
import dev.lodestone.runtime.LodestoneRuntime;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code port=0} is what {@link GoalOrchestratorLauncher} relies on to stand up a private, throwaway
 * loopback endpoint for a spawned orchestrator subprocess without contending for (or hardcoding) a
 * specific port.
 */
class LoopbackHttpServerTest {
    private static final String INITIALIZE_BODY = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
            + "\"params\":{\"protocolVersion\":\"2025-11-25\"}}";

    @Test
    void portZeroBindsToARealOsAssignedPortAndAcceptsRequests() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var gateway = new McpGateway(runtime);
            try (var loopback = new LoopbackHttpServer(gateway, 0)) {
                assertEquals(0, loopback.port(), "before start(), an ephemeral request still reports the requested 0");

                loopback.start();
                var boundPort = loopback.port();
                assertNotEquals(0, boundPort, "start() must resolve port=0 to a real OS-assigned port");
                assertTrue(boundPort > 0 && boundPort <= 65_535, "bound port out of range: " + boundPort);
                assertEquals(200, post(boundPort, null),
                        "the ephemeral-port server must actually answer requests on the port it reports");
            }
        }
    }

    @Test
    void acceptsLoopbackRequestsWithoutAnyTokenHeader() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var gateway = new McpGateway(runtime);
            try (var loopback = new LoopbackHttpServer(gateway, 0)) {
                loopback.start();
                assertEquals(200, post(loopback.port(), null),
                        "the zero-config loopback endpoint must answer without an X-Lodestone-Token header");
            }
        }
    }

    @Test
    void tokenConstructorStillGatesLauncherTransports() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var gateway = new McpGateway(runtime);
            try (var loopback = new LoopbackHttpServer(gateway, 0, "test-token")) {
                loopback.start();
                var port = loopback.port();
                assertEquals(401, post(port, null), "a token-mode endpoint must reject a missing token");
                assertEquals(401, post(port, "wrong-token"), "a token-mode endpoint must reject a wrong token");
                assertEquals(200, post(port, "test-token"), "a token-mode endpoint must accept its token");
            }
        }
    }

    @Test
    void rejectsNonLoopbackOriginAndHostHeaders() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var gateway = new McpGateway(runtime);
            try (var loopback = new LoopbackHttpServer(gateway, 0)) {
                loopback.start();
                var port = loopback.port();
                assertEquals(403, rawStatus(port, "127.0.0.1:" + port, "https://evil.example"),
                        "a browser Origin outside loopback must be rejected");
                assertEquals(403, rawStatus(port, "evil.example", null),
                        "a DNS-rebinding Host outside loopback must be rejected");
                assertEquals(403, rawStatus(port, "evil.example:" + port, null),
                        "a DNS-rebinding Host with a port must be rejected");
                assertEquals(200, rawStatus(port, "localhost:" + port, null),
                        "a localhost Host must be accepted");
                assertEquals(200, rawStatus(port, "127.0.0.1:" + port, "http://localhost"),
                        "a loopback Origin must be accepted");
            }
        }
    }

    @Test
    void deleteWithSessionIdTerminatesTheSessionAndFreesItsSlot() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var gateway = new McpGateway(runtime);
            try (var loopback = new LoopbackHttpServer(gateway, 0)) {
                loopback.start();
                var port = loopback.port();

                var initializeConnection = openConnection(port, "POST");
                initializeConnection.setDoOutput(true);
                initializeConnection.getOutputStream().write(INITIALIZE_BODY.getBytes(StandardCharsets.UTF_8));
                assertEquals(200, initializeConnection.getResponseCode());
                var sessionId = initializeConnection.getHeaderField("Mcp-Session-Id");
                assertNotNull(sessionId, "a successful initialize must mint a session id");
                initializeConnection.disconnect();

                var deleteConnection = openConnection(port, "DELETE");
                deleteConnection.setRequestProperty("Mcp-Session-Id", sessionId);
                assertEquals(204, deleteConnection.getResponseCode(), "DELETE on a live session must succeed");
                deleteConnection.disconnect();

                var reuseConnection = openConnection(port, "POST");
                reuseConnection.setRequestProperty("Mcp-Session-Id", sessionId);
                reuseConnection.setDoOutput(true);
                reuseConnection.getOutputStream().write(
                        "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}"
                                .getBytes(StandardCharsets.UTF_8));
                assertEquals(200, reuseConnection.getResponseCode());
                var body = new String(reuseConnection.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(body.contains("-32001"), "a DELETEd session id must no longer be usable: " + body);
                reuseConnection.disconnect();
            }
        }
    }

    @Test
    void deleteRequiresASessionIdHeaderAndReports404ForAnUnknownSession() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var gateway = new McpGateway(runtime);
            try (var loopback = new LoopbackHttpServer(gateway, 0)) {
                loopback.start();
                var port = loopback.port();

                var missingHeader = openConnection(port, "DELETE");
                assertEquals(400, missingHeader.getResponseCode(), "DELETE without Mcp-Session-Id must be rejected");
                missingHeader.disconnect();

                var unknownSession = openConnection(port, "DELETE");
                unknownSession.setRequestProperty("Mcp-Session-Id", "does-not-exist");
                assertEquals(404, unknownSession.getResponseCode(),
                        "DELETE for an unknown session must be reported honestly, not silently accepted");
                unknownSession.disconnect();
            }
        }
    }

    @Test
    void constructorAllowsPortZeroButRejectsNegativePortsAndBlankTokens() {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var gateway = new McpGateway(runtime);
            assertThrows(IllegalArgumentException.class, () -> new LoopbackHttpServer(gateway, -1));
            assertThrows(IllegalArgumentException.class, () -> new LoopbackHttpServer(gateway, -1, "test-token"));
            assertThrows(IllegalArgumentException.class, () -> new LoopbackHttpServer(gateway, 0, " "));
            new LoopbackHttpServer(gateway, 0).close();
            new LoopbackHttpServer(gateway, 0, "test-token").close();
        }
    }

    private static HttpURLConnection openConnection(int port, String method) throws Exception {
        var connection = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + "/mcp")
                .toURL().openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(10_000);
        connection.setRequestMethod(method);
        return connection;
    }

    private static int post(int port, String token) throws Exception {
        var connection = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + "/mcp")
                .toURL().openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(10_000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        if (token != null) {
            connection.setRequestProperty("X-Lodestone-Token", token);
        }
        connection.getOutputStream().write(INITIALIZE_BODY.getBytes(StandardCharsets.UTF_8));
        var status = connection.getResponseCode();
        connection.disconnect();
        return status;
    }

    /** {@link HttpURLConnection} silently drops Origin and Host overrides (restricted headers), so send them raw. */
    private static int rawStatus(int port, String hostHeader, String originHeader) throws Exception {
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            var body = INITIALIZE_BODY.getBytes(StandardCharsets.UTF_8);
            var head = new StringBuilder("POST /mcp HTTP/1.1\r\n")
                    .append("Host: ").append(hostHeader).append("\r\n");
            if (originHeader != null) {
                head.append("Origin: ").append(originHeader).append("\r\n");
            }
            head.append("Content-Type: application/json\r\n")
                    .append("Content-Length: ").append(body.length).append("\r\n")
                    .append("Connection: close\r\n\r\n");
            var out = socket.getOutputStream();
            out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            out.flush();
            var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            var statusLine = reader.readLine();
            assertNotNull(statusLine, "server closed the connection without a status line");
            return Integer.parseInt(statusLine.split(" ")[1]);
        }
    }
}
