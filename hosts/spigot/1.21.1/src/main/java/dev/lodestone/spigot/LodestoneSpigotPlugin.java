// SPDX-License-Identifier: MIT
package dev.lodestone.spigot;

import dev.lodestone.gateway.LoopbackHttpServer;
import dev.lodestone.gateway.McpGateway;
import dev.lodestone.runtime.AuthorizationPolicy;
import dev.lodestone.runtime.LodestoneRuntime;
import dev.lodestone.runtime.TokenFile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

public final class LodestoneSpigotPlugin extends JavaPlugin implements Listener {
    private LodestoneRuntime runtime;
    private SpigotAdapter adapter;
    private LoopbackHttpServer httpServer;

    @Override
    public void onEnable() {
        requireRuntime("1.21.1", "spigot");
        runtime = new LodestoneRuntime(AuthorizationPolicy.fromCsv(
                System.getProperty("lodestone.permissions", System.getenv("LODESTONE_PERMISSIONS"))));
        adapter = new SpigotAdapter(this);
        runtime.registerAdapter(adapter);
        getServer().getPluginManager().registerEvents(this, this);
        var port = Integer.parseInt(System.getProperty("lodestone.port", "37821"));
        httpServer = new LoopbackHttpServer(new McpGateway(runtime), port, token());
        try {
            httpServer.start();
        } catch (IOException failure) {
            throw new IllegalStateException("unable to start Lodestone MCP loopback endpoint", failure);
        }
        adapter.publishEvent("minecraft.lifecycle.server.started", Map.of("adapter", SpigotAdapter.ADAPTER_ID));
        getLogger().info("Lodestone MCP loopback endpoint listening on 127.0.0.1:" + httpServer.port());
    }

    @Override
    public void onDisable() {
        if (adapter != null) adapter.publishEvent("minecraft.lifecycle.server.stopped", Map.of("adapter", SpigotAdapter.ADAPTER_ID));
        if (httpServer != null) httpServer.close();
        if (runtime != null) runtime.close();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        adapter.publishEvent("minecraft.player.joined", Map.of("uuid", event.getPlayer().getUniqueId().toString(), "name", event.getPlayer().getName()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        adapter.publishEvent("minecraft.player.left", Map.of("uuid", event.getPlayer().getUniqueId().toString(), "name", event.getPlayer().getName()));
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        adapter.refreshWorldAvailability();
        runtime.refreshAdapter(adapter);
    }

    @EventHandler
    public void onWorldUnload(WorldUnloadEvent event) {
        adapter.markWorldUnavailable(event.getWorld());
        runtime.refreshAdapter(adapter);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        adapter.publishEvent("minecraft.chat.received", Map.of("uuid", event.getPlayer().getUniqueId().toString(), "name", event.getPlayer().getName(), "message", event.getMessage()));
    }

    private String token() {
        var configured = System.getProperty("lodestone.token", System.getenv("LODESTONE_TOKEN"));
        if (configured != null && !configured.isBlank()) return configured.trim();
        var path = Path.of(getDataFolder().getPath(), "lodestone.token");
        try {
            var bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
            var generated = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            return TokenFile.readOrCreate(path, generated);
        } catch (IOException failure) {
            throw new IllegalStateException("unable to create Lodestone token file", failure);
        }
    }

    private void requireRuntime(String expectedGame, String expectedBrand) {
        var bukkitVersion = getServer().getBukkitVersion();
        var separator = bukkitVersion.indexOf('-');
        var game = separator < 0 ? bukkitVersion : bukkitVersion.substring(0, separator);
        var implementation = (getServer().getName() + " " + getServer().getVersion())
                .toLowerCase(java.util.Locale.ROOT);
        if (!expectedGame.equals(game) || !implementation.contains(expectedBrand)
                || implementation.contains("paper") || implementation.contains("folia")) {
            throw new IllegalStateException("Lodestone Spigot host requires Spigot " + expectedGame
                    + "; detected " + getServer().getName() + " " + game + " (" + getServer().getVersion() + ")");
        }
    }
}
