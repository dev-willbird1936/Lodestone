// SPDX-License-Identifier: MIT
package dev.lodestone.rcon.launcher;

import dev.lodestone.gateway.LoopbackHttpServer;
import dev.lodestone.gateway.McpGateway;
import dev.lodestone.rcon.RconAdapter;
import dev.lodestone.runtime.AuthorizationPolicy;
import dev.lodestone.runtime.LodestoneRuntime;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Logger;

public final class LodestoneRconMain {
    private static final Logger LOGGER = Logger.getLogger("lodestone-rcon");

    private LodestoneRconMain() {
    }

    public static void main(String[] args) throws Exception {
        var password = required("LODESTONE_RCON_PASSWORD");
        var token = required("LODESTONE_TOKEN");
        var host = environment("LODESTONE_RCON_HOST", "127.0.0.1");
        var rconPort = integer("LODESTONE_RCON_PORT", 25575);
        var mcpPort = integer("LODESTONE_MCP_PORT", 37821);
        var maxOutputBytes = integer("LODESTONE_RCON_MAX_OUTPUT_BYTES", 262_144);

        var runtime = new LodestoneRuntime(AuthorizationPolicy.fromCsv(
                System.getenv("LODESTONE_PERMISSIONS")));
        var adapter = new RconAdapter(host, rconPort, password, maxOutputBytes);
        adapter.setRefreshHook(() -> runtime.refreshAdapter(adapter));
        runtime.registerAdapter(adapter);
        adapter.probe();
        runtime.refreshAdapter(adapter);

        var httpServer = new LoopbackHttpServer(new McpGateway(runtime), mcpPort, token);
        var stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                httpServer.close();
                runtime.close();
            } finally {
                stopped.countDown();
            }
        }, "lodestone-rcon-shutdown"));

        httpServer.start();
        LOGGER.info("Lodestone RCON MCP endpoint listening on 127.0.0.1:" + httpServer.port()
                + "; RCON target " + host + ":" + rconPort + "; available="
                + (adapter.health().state() == dev.lodestone.adapter.AdapterHealth.State.READY));
        stopped.await();
    }

    private static String required(String name) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }

    private static String environment(String name, String fallback) {
        var value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int integer(String name, int fallback) {
        var value = environment(name, Integer.toString(fallback));
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(name + " must be an integer", failure);
        }
    }
}
