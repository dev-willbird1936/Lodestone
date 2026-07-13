// SPDX-License-Identifier: MIT
package dev.lodestone.forge;

import dev.lodestone.gateway.LoopbackHttpServer;
import dev.lodestone.gateway.McpGateway;
import dev.lodestone.runtime.AuthorizationPolicy;
import dev.lodestone.runtime.LodestoneRuntime;
import dev.lodestone.runtime.TokenFile;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

@Mod(LodestoneForgeMod.MOD_ID)
public final class LodestoneForgeMod {
    public static final String MOD_ID = "lodestone";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private final LodestoneRuntime runtime;
    private final ForgeAdapter adapter;
    private LoopbackHttpServer httpServer;

    public LodestoneForgeMod() {
        runtime = new LodestoneRuntime(AuthorizationPolicy.fromCsv(
                System.getProperty("lodestone.permissions", System.getenv("LODESTONE_PERMISSIONS"))));
        adapter = new ForgeAdapter();
        runtime.registerAdapter(adapter);
        MinecraftForge.EVENT_BUS.register(this);

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

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public void serverStarted(ServerStartedEvent event) {
        adapter.onServerStarted(event.getServer());
        runtime.refreshAdapter(adapter);
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public void serverStopping(ServerStoppingEvent event) {
        adapter.onServerStopped();
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
        if (configured != null && !configured.isBlank()) return configured.trim();
        var path = tokenPath();
        try {
            var bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
            var generated = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            return TokenFile.readOrCreate(path, generated);
        } catch (IOException failure) {
            throw new IllegalStateException("unable to create Lodestone token file", failure);
        }
    }

    private static Path tokenPath() { return FMLPaths.CONFIGDIR.get().resolve("lodestone.token"); }
}
