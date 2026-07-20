// SPDX-License-Identifier: MIT
package dev.lodestone.gateway;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Optional authenticated loopback transport for an in-game adapter.
 *
 * <p>{@code port=0} requests an OS-assigned ephemeral port instead of a fixed one - used by
 * {@link GoalOrchestratorLauncher} to stand up a private, throwaway loopback endpoint for a
 * spawned orchestrator subprocess without contending for (or hardcoding) a specific port. Call
 * {@link #port()} after {@link #start()} to read back the port the OS actually bound.
 */
public final class LoopbackHttpServer implements AutoCloseable {
    private static final int MAX_BODY_BYTES = 1_048_576;
    private static final long BODY_READ_TIMEOUT_MILLIS = 10_000;
    private static final int MAX_ACTIVE_EXCHANGES = 64;
    private final McpGateway gateway;
    private final int port;
    private final byte[] token;
    private HttpServer server;
    private ExecutorService executor;
    private final Semaphore activeExchanges = new Semaphore(MAX_ACTIVE_EXCHANGES);

    public LoopbackHttpServer(McpGateway gateway, int port, String token) {
        if (port < 0 || port > 65_535 || token == null || token.isBlank()) {
            throw new IllegalArgumentException("loopback port and token are required");
        }
        this.gateway = gateway;
        this.port = port;
        this.token = token.getBytes(StandardCharsets.UTF_8);
    }

    public synchronized void start() throws IOException {
        if (server != null) {
            return;
        }
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/mcp", this::handle);
        executor = createExecutor();
        server.setExecutor(executor);
        server.start();
    }

    public synchronized int port() {
        return server == null ? port : server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        if (!activeExchanges.tryAcquire()) {
            try (exchange) {
                send(exchange, 429, "too many active requests");
            }
            return;
        }
        try (exchange) {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                send(exchange, 405, "method not allowed");
                return;
            }
            var origin = exchange.getRequestHeaders().getFirst("Origin");
            if (origin != null && !allowedOrigin(origin)) {
                send(exchange, 403, "origin not allowed");
                return;
            }
            var protocolHeader = exchange.getRequestHeaders().getFirst("MCP-Protocol-Version");
            if (protocolHeader != null && !McpProtocol.supports(protocolHeader)) {
                send(exchange, 400, "unsupported MCP protocol version");
                return;
            }
            var provided = exchange.getRequestHeaders().getFirst("X-Lodestone-Token");
            if (provided == null || !MessageDigest.isEqual(token, provided.getBytes(StandardCharsets.UTF_8))) {
                send(exchange, 401, "unauthorized");
                return;
            }
            var length = exchange.getRequestHeaders().getFirst("Content-Length");
            if (length != null) {
                final long declaredLength;
                try {
                    declaredLength = Long.parseLong(length);
                } catch (NumberFormatException failure) {
                    send(exchange, 400, "invalid content length");
                    return;
                }
                if (declaredLength < 0) {
                    send(exchange, 400, "invalid content length");
                    return;
                }
                if (declaredLength > MAX_BODY_BYTES) {
                    send(exchange, 413, "request too large");
                    return;
                }
            }
            var requestBody = exchange.getRequestBody();
            Future<byte[]> bodyRead = executor.submit(() -> requestBody.readNBytes(MAX_BODY_BYTES + 1));
            final byte[] body;
            try {
                body = bodyRead.get(BODY_READ_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException timeout) {
                bodyRead.cancel(true);
                try {
                    requestBody.close();
                } catch (IOException ignored) {
                    // Closing is best effort after a stalled upload.
                }
                send(exchange, 408, "request body read timed out");
                return;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                bodyRead.cancel(true);
                send(exchange, 503, "request body read interrupted");
                return;
            } catch (ExecutionException failure) {
                bodyRead.cancel(true);
                send(exchange, 400, "unable to read request body");
                return;
            }
            if (body.length > MAX_BODY_BYTES) {
                send(exchange, 413, "request too large");
                return;
            }
            var sessionId = exchange.getRequestHeaders().getFirst("Mcp-Session-Id");
            try {
                var response = gateway.handleHttp(new String(body, StandardCharsets.UTF_8), sessionId, protocolHeader);
                var responseSessionId = gateway.responseSessionId();
                if (responseSessionId != null && !responseSessionId.isBlank()) {
                    exchange.getResponseHeaders().set("Mcp-Session-Id", responseSessionId);
                }
                if (response == null) {
                    exchange.sendResponseHeaders(202, -1);
                    return;
                }
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                send(exchange, 200, response);
            } finally {
                gateway.clearResponseSessionId();
            }
        } finally {
            activeExchanges.release();
        }
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static boolean allowedOrigin(String origin) {
        try {
            var uri = java.net.URI.create(origin);
            if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath())) {
                return false;
            }
            var host = uri.getHost();
            return "localhost".equalsIgnoreCase(host)
                    || "127.0.0.1".equals(host)
                    || "0:0:0:0:0:0:0:1".equals(host)
                    || "::1".equals(host)
                    || "[::1]".equals(host);
        } catch (IllegalArgumentException invalidOrigin) {
            return false;
        }
    }

    private static ExecutorService createExecutor() {
        try {
            var virtualThreadFactory = Executors.class.getMethod("newVirtualThreadPerTaskExecutor");
            return (ExecutorService) virtualThreadFactory.invoke(null);
        } catch (ReflectiveOperationException ignored) {
            return Executors.newCachedThreadPool();
        }
    }

    @Override
    public synchronized void close() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }
}
