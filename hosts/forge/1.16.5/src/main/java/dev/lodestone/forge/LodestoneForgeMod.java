// SPDX-License-Identifier: MIT
package dev.lodestone.forge;

import dev.lodestone.gateway.LoopbackHttpServer;
import dev.lodestone.gateway.McpGateway;
import dev.lodestone.runtime.AuthorizationPolicy;
import dev.lodestone.runtime.LodestoneRuntime;
import dev.lodestone.runtime.TokenFile;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.server.FMLServerStartedEvent;
import net.minecraftforge.fml.event.server.FMLServerStoppingEvent;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

@Mod(LodestoneForgeMod.MOD_ID)
public final class LodestoneForgeMod {
    public static final String MOD_ID = "lodestone";
    private static final Logger LOGGER = LogManager.getLogger(MOD_ID);
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

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public void serverStarted(FMLServerStartedEvent event) {
        adapter.onServerStarted(event.getServer());
        runtime.refreshAdapter(adapter);
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public void serverStopping(FMLServerStoppingEvent event) {
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

    /**
     * The loopback endpoint no longer reads this token; the file is still created because the
     * launcher transports and discovery/registry tooling continue to reference it.
     */
    private static void ensureTokenFile() {
        var configured = System.getProperty("lodestone.token", System.getenv("LODESTONE_TOKEN"));
        if (configured != null && !configured.isBlank()) return;
        var path = tokenPath();
        try {
            var bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
            var generated = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            TokenFile.readOrCreate(path, generated);
        } catch (IOException failure) {
            throw new IllegalStateException("unable to create Lodestone token file", failure);
        }
    }

    private static Path tokenPath() { return FMLPaths.CONFIGDIR.get().resolve("lodestone.token"); }
}
