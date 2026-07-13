// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import dev.lodestone.gateway.LoopbackHttpServer;
import dev.lodestone.gateway.McpGateway;
import dev.lodestone.runtime.AuthorizationPolicy;
import dev.lodestone.runtime.LodestoneRuntime;
import dev.lodestone.runtime.TokenFile;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

@Mod(LodestoneNeoForgeMod.MOD_ID)
public final class LodestoneNeoForgeMod {
    public static final String MOD_ID = "lodestone";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private final LodestoneRuntime runtime;
    private final NeoForgeAdapter adapter;
    private LoopbackHttpServer httpServer;

    public LodestoneNeoForgeMod(IEventBus modBus, ModContainer container) {
        runtime = new LodestoneRuntime(AuthorizationPolicy.fromCsv(
                System.getProperty("lodestone.permissions", System.getenv("LODESTONE_PERMISSIONS"))));
        adapter = new NeoForgeAdapter();
        adapter.setRefreshHook(() -> runtime.refreshAdapter(adapter));
        runtime.registerAdapter(adapter);

        NeoForge.EVENT_BUS.addListener(this::serverStarted);
        NeoForge.EVENT_BUS.addListener(this::serverStopped);

        var port = Integer.parseInt(System.getProperty("lodestone.port", "37821"));
        var token = token();
        httpServer = new LoopbackHttpServer(new McpGateway(runtime), port, token);
        try {
            httpServer.start();
            LOGGER.info("Lodestone MCP loopback endpoint listening on 127.0.0.1:{}; token file: {}",
                    httpServer.port(), tokenPath());
        } catch (IOException failure) {
            throw new IllegalStateException("unable to start Lodestone MCP loopback endpoint", failure);
        }
    }

    private void serverStarted(ServerStartedEvent event) {
        adapter.onServerStarted(event);
        runtime.refreshAdapter(adapter);
    }

    private void serverStopped(ServerStoppedEvent event) {
        adapter.onServerStopped(event);
        runtime.refreshAdapter(adapter);
        if (event.getServer().isDedicatedServer()) {
            if (httpServer != null) {
                httpServer.close();
                httpServer = null;
            }
            runtime.close();
        }
    }

    private static String token() {
        var configured = System.getProperty("lodestone.token", System.getenv("LODESTONE_TOKEN"));
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        var path = tokenPath();
        try {
            var bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            var generated = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            return TokenFile.readOrCreate(path, generated);
        } catch (IOException failure) {
            throw new IllegalStateException("unable to create Lodestone token file", failure);
        }
    }

    private static Path tokenPath() {
        return Path.of(System.getProperty("user.dir"), "config", "lodestone.token");
    }
}
