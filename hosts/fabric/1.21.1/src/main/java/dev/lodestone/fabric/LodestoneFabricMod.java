// SPDX-License-Identifier: MIT
package dev.lodestone.fabric;

import dev.lodestone.gateway.LoopbackHttpServer;
import dev.lodestone.gateway.McpGateway;
import dev.lodestone.runtime.AuthorizationPolicy;
import dev.lodestone.runtime.LodestoneRuntime;
import dev.lodestone.runtime.TokenFile;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

public final class LodestoneFabricMod implements ModInitializer {
    public static final String MOD_ID = "lodestone";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static volatile LodestoneFabricMod active;
    private LodestoneRuntime runtime;
    private FabricAdapter adapter;
    private LoopbackHttpServer httpServer;

    @Override
    public void onInitialize() {
        active = this;
        runtime = new LodestoneRuntime(AuthorizationPolicy.fromCsv(
                System.getProperty("lodestone.permissions", System.getenv("LODESTONE_PERMISSIONS"))));
        adapter = new FabricAdapter();
        adapter.setRefreshHook(() -> runtime.refreshAdapter(adapter));
        runtime.registerAdapter(adapter);

        ServerLifecycleEvents.SERVER_STARTED.register(this::serverStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::serverStopping);

        var port = Integer.parseInt(System.getProperty("lodestone.port", "37821"));
        ensureTokenFile();
        httpServer = new LoopbackHttpServer(new McpGateway(runtime), port);
        try {
            httpServer.start();
            LOGGER.info("Lodestone MCP loopback endpoint listening on 127.0.0.1:{} (no token required)",
                    httpServer.port());
        } catch (IOException failure) {
            throw new IllegalStateException("unable to start Lodestone MCP loopback endpoint", failure);
        }
    }

    private void serverStarted(net.minecraft.server.MinecraftServer server) {
        adapter.onServerStarted(server);
        runtime.refreshAdapter(adapter);
    }

    private void serverStopping(net.minecraft.server.MinecraftServer server) {
        adapter.onServerStopped();
        runtime.refreshAdapter(adapter);
        if (server.isDedicatedServer()) {
            if (httpServer != null) {
                httpServer.close();
                httpServer = null;
            }
            if (runtime != null) {
                runtime.close();
            }
        }
    }

    public static void clientStopping() {
        var current = active;
        if (current != null && current.httpServer != null) {
            current.httpServer.close();
            current.httpServer = null;
            if (current.runtime != null) {
                current.runtime.close();
            }
        }
    }

    /**
     * The loopback endpoint no longer reads this token; the file is still created because the
     * launcher transports and discovery/registry tooling continue to reference it.
     */
    private static void ensureTokenFile() {
        var configured = System.getProperty("lodestone.token", System.getenv("LODESTONE_TOKEN"));
        if (configured != null && !configured.isBlank()) {
            return;
        }
        var path = tokenPath();
        try {
            var bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            var generated = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            TokenFile.readOrCreate(path, generated);
        } catch (IOException failure) {
            throw new IllegalStateException("unable to create Lodestone token file", failure);
        }
    }

    private static Path tokenPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("lodestone.token");
    }
}
