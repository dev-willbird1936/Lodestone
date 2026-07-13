// SPDX-License-Identifier: MIT
package dev.lodestone.legacybridge.launcher;

import dev.lodestone.gateway.LoopbackHttpServer;
import dev.lodestone.gateway.McpGateway;
import dev.lodestone.legacybridge.LegacyBridgeAdapter;
import dev.lodestone.runtime.AuthorizationPolicy;
import dev.lodestone.runtime.LodestoneRuntime;

import java.util.concurrent.CountDownLatch;
import java.util.logging.Logger;

public final class LegacyBridgeMain {
    private static final Logger LOGGER = Logger.getLogger("lodestone-legacy-bridge");

    private LegacyBridgeMain() { }

    public static void main(String[] args) throws Exception {
        String bridgeToken = required("LODESTONE_LEGACY_TOKEN");
        String gatewayToken = required("LODESTONE_TOKEN");
        String host = environment("LODESTONE_LEGACY_HOST", "127.0.0.1");
        String gameVersion = environment("LODESTONE_LEGACY_VERSION", "1.12.2");
        int bridgePort = integer("LODESTONE_LEGACY_PORT", 37940);
        int mcpPort = integer("LODESTONE_MCP_PORT", 37821);

        LodestoneRuntime runtime = new LodestoneRuntime(AuthorizationPolicy.fromCsv(
                System.getenv("LODESTONE_PERMISSIONS")));
        LegacyBridgeAdapter adapter = new LegacyBridgeAdapter(host, bridgePort, bridgeToken, gameVersion);
        runtime.registerAdapter(adapter);
        adapter.probe();
        runtime.refreshAdapter(adapter);

        LoopbackHttpServer httpServer = new LoopbackHttpServer(new McpGateway(runtime), mcpPort, gatewayToken);
        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { httpServer.close(); runtime.close(); } finally { stopped.countDown(); }
        }, "lodestone-legacy-bridge-shutdown"));
        httpServer.start();
        LOGGER.info("Lodestone Forge " + gameVersion + " native-bridge MCP endpoint listening on 127.0.0.1:"
                + httpServer.port() + "; bridge=" + host + ":" + bridgePort
                + "; available=" + (adapter.health().state() == dev.lodestone.adapter.AdapterHealth.State.READY));
        stopped.await();
    }

    private static String required(String name) { String value = System.getenv(name); if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required"); return value.trim(); }
    private static String environment(String name, String fallback) { String value = System.getenv(name); return value == null || value.isBlank() ? fallback : value.trim(); }
    private static int integer(String name, int fallback) { try { return Integer.parseInt(environment(name, Integer.toString(fallback))); } catch (NumberFormatException failure) { throw new IllegalArgumentException(name + " must be an integer", failure); } }
}
