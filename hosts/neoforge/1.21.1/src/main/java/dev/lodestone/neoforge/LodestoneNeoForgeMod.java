// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import dev.lodestone.gateway.LoopbackHttpServer;
import dev.lodestone.gateway.McpGateway;
import dev.lodestone.runtime.AuthorizationPolicy;
import dev.lodestone.runtime.InstanceRegistry;
import dev.lodestone.runtime.InstanceRegistryEntry;
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
import java.time.Instant;
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
        final int boundPort;
        try {
            httpServer.start();
            boundPort = httpServer.port();
            LOGGER.info("Lodestone MCP loopback endpoint listening on 127.0.0.1:{}; token file: {}",
                    boundPort, tokenPath());
            writeInstanceRegistryEntry(boundPort, container);
        } catch (IOException failure) {
            throw new IllegalStateException("unable to start Lodestone MCP loopback endpoint", failure);
        }

        // Deletes only this instance's own discovery-registry entry. Deliberately separate from
        // the dedicated-server-specific cleanup in serverStopped(): a normal client/singleplayer
        // session's MinecraftServer never reports isDedicatedServer() == true (that only holds for
        // DedicatedServer, not the client's IntegratedServer), so that cleanup path is never
        // reached on exit from a client session. A JVM shutdown hook instead fires on both normal
        // and most abnormal JVM exits regardless of which lifecycle path applies, without touching
        // the existing httpServer/runtime cleanup logic. The port is captured here (rather than
        // read from the httpServer field at hook-execution time) because the dedicated-server path
        // above nulls that field out before this hook may run.
        Runtime.getRuntime().addShutdownHook(new Thread(
                () -> InstanceRegistry.delete(boundPort), "lodestone-instance-registry-cleanup"));
    }

    /** Best-effort discovery bookkeeping; a failure here must not block the loopback endpoint from starting. */
    private void writeInstanceRegistryEntry(int boundPort, ModContainer container) {
        try {
            InstanceRegistry.write(new InstanceRegistryEntry(
                    boundPort,
                    tokenPath().toAbsolutePath().toString(),
                    ProcessHandle.current().pid(),
                    System.getProperty("user.dir"),
                    container.getModInfo().getVersion().toString(),
                    Instant.now()));
        } catch (IOException failure) {
            LOGGER.warn("unable to write Lodestone instance registry entry; discovery tools will not find "
                    + "this instance", failure);
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
