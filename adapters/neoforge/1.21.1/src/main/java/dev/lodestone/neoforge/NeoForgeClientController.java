// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.InputConstants;
import dev.lodestone.adapter.InputNumbers;
import dev.lodestone.adapter.InputLease;
import dev.lodestone.adapter.UiBounds;
import dev.lodestone.adapter.UiContracts;
import dev.lodestone.adapter.UiLimits;
import dev.lodestone.adapter.UiNode;
import dev.lodestone.adapter.UiSelector;
import net.minecraft.SharedConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Callable;

/** Client-only bridge. It is never loaded by a dedicated server. */
@EventBusSubscriber(modid = "lodestone", value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class NeoForgeClientController {
    private static final ClientBridgeImpl BRIDGE = new ClientBridgeImpl();
    private static boolean attached;
    private static boolean lastWorld;
    private static boolean lastScreen;
    private static long clientTick;

    private NeoForgeClientController() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        clientTick++;
        BRIDGE.releaseExpiredInput(monotonicMillis());
        BRIDGE.tickSurvivalTreeGoal();
        BRIDGE.tickWoolTreeZombieGoal();
        BRIDGE.tickNetherGoal();
        BRIDGE.tickNavigationGoal();
        BRIDGE.tickCombatGoal();
        var adapter = NeoForgeAdapter.active();
        if (adapter == null) {
            return;
        }
        if (!attached) {
            adapter.attachClientBridge(BRIDGE);
            attached = true;
        }
        var client = Minecraft.getInstance();
        BRIDGE.observeScreen(client.screen);
        var world = client.level != null && client.player != null;
        var screen = client.screen != null;
        if (world != lastWorld || screen != lastScreen) {
            lastWorld = world;
            lastScreen = screen;
            adapter.refreshClientState();
        }
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        var player = event.getPlayer();
        if (player == null) {
            refresh();
            return;
        }
        publish("minecraft.player.joined", Map.of(
                "uuid", player.getUUID().toString(),
                "name", player.getGameProfile().getName()));
        refresh();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        BRIDGE.releaseAllInput();
        var player = event.getPlayer();
        if (player == null) {
            publish("minecraft.player.left", Map.of("uuid", "", "name", "", "reason", "player-unavailable"));
            refresh();
            return;
        }
        publish("minecraft.player.left", Map.of(
                "uuid", player.getUUID().toString(),
                "name", player.getGameProfile().getName()));
        refresh();
    }

    @SubscribeEvent
    public static void onClone(ClientPlayerNetworkEvent.Clone event) {
        var oldPlayer = event.getOldPlayer();
        var newPlayer = event.getNewPlayer();
        if (oldPlayer == null || newPlayer == null) {
            refresh();
            return;
        }
        publish("minecraft.player.respawned", Map.of(
                "oldUuid", oldPlayer.getUUID().toString(),
                "newUuid", newPlayer.getUUID().toString()));
        refresh();
    }

    @SubscribeEvent
    public static void onChat(ClientChatReceivedEvent event) {
        var sender = event.getSender();
        var message = Map.<String, Object>of(
                "message", event.getMessage().getString(),
                "sender", sender == null ? "" : sender.toString(),
                "system", event.isSystem());
        BRIDGE.captureChat(message);
        publish("minecraft.chat.received", message);
    }

    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        publish("minecraft.input.key.received", Map.of(
                "key", event.getKey(), "scanCode", event.getScanCode(),
                "action", event.getAction(), "modifiers", event.getModifiers()));
    }

    @SubscribeEvent
    public static void onOpening(ScreenEvent.Opening event) {
        publish("minecraft.ui.screen.opened", screenProjection(event.getNewScreen()));
        refresh();
    }

    @SubscribeEvent
    public static void onClosing(ScreenEvent.Closing event) {
        publish("minecraft.ui.screen.closed", screenProjection(event.getScreen()));
        refresh();
    }

    private static Map<String, Object> screenProjection(Screen screen) {
        return Map.of("screen", screen == null ? "" : screen.getClass().getName(),
                "title", screen == null ? "" : screen.getTitle().getString());
    }

    private static void publish(String event, Map<String, Object> payload) {
        var adapter = NeoForgeAdapter.active();
        if (adapter != null) {
            adapter.publishClientEvent(event, payload);
        }
    }

    private static void refresh() {
        var adapter = NeoForgeAdapter.active();
        if (adapter != null) {
            adapter.refreshClientState();
        }
    }

    private static long monotonicMillis() {
        return System.nanoTime() / 1_000_000L;
    }

    private static final class ClientBridgeImpl implements NeoForgeAdapter.ClientBridge {
        private final ArrayDeque<Map<String, Object>> chat = new ArrayDeque<>();
        private final InputLease movementLease = new InputLease();
        private final Map<String, KeyMapping> ownedMappings = new LinkedHashMap<>();
        private final Set<String> directlyOwnedMappings = new LinkedHashSet<>();
        private final Set<String> leasedMappings = new LinkedHashSet<>();
        private Screen tokenScreen;
        private boolean tokenInitialized;
        private long screenSequence;
        private String currentScreenToken = "neo121-0";
        private NeoForgeSurvivalTreeGoal survivalTreeGoal;
        private NeoForgeWoolTreeZombieGoal woolTreeZombieGoal;
        private NeoForgeNetherGoal netherGoal;
        private NeoForgeNavigationGoal navigationGoal;
        private NeoForgeCombatGoal combatGoal;

        private record ItemProjection(int rank, String id, String translationKey, String displayName,
                                      int maxStackSize, boolean blockItem) {
            private Map<String, Object> toMap() {
                return Map.of("id", id, "translationKey", translationKey, "displayName", displayName,
                        "maxStackSize", maxStackSize, "blockItem", blockItem);
            }
        }

        private record NearbyEntityProjection(int entityId, String uuid, String type, String name,
                                              double distance, Map<String, Object> position,
                                              boolean player) {
            private Map<String, Object> toMap() {
                return Map.of("entityId", entityId, "uuid", uuid, "type", type, "name", name,
                        "distance", distance, "position", position, "player", player);
            }
        }

        @Override
        public boolean available(String capability) {
            var client = Minecraft.getInstance();
            return switch (capability) {
                case "minecraft.registry.item.search", "minecraft.server.info.read",
                        "minecraft.client.screenshot.capture",
                        "minecraft.input.key.set", "minecraft.input.mouse.set", "minecraft.input.release-all",
                        "minecraft.ui.state.read",
                        "minecraft.chat.read", "minecraft.goal.survival.wooden-axe-tree",
                        "minecraft.goal.creative.wool-tree-zombie-defense",
                        "minecraft.goal.survival.reach-nether",
                        "minecraft.goal.navigation.safe-waypoint",
                        "minecraft.goal.combat.attack-nearest" -> true;
                case "minecraft.world.heightmap.read", "minecraft.world.light.analyze" -> client.level != null;
                case "minecraft.ui.click", "minecraft.ui.text.insert",
                        "minecraft.inventory.container.read", "minecraft.inventory.container.click" -> client.screen != null;
                case "minecraft.ui.key" -> client.screen != null
                        || (client.level != null && client.player != null);
                default -> client.level != null && client.player != null;
            };
        }

        @Override
        public CompletionStage<Map<String, Object>> invoke(String capability, dev.lodestone.adapter.InvocationContext invocation) {
            if ("minecraft.goal.survival.wooden-axe-tree".equals(capability)) {
                return startSurvivalTreeGoal(invocation);
            }
            if ("minecraft.goal.creative.wool-tree-zombie-defense".equals(capability)) {
                return startWoolTreeZombieGoal(invocation);
            }
            if ("minecraft.goal.survival.reach-nether".equals(capability)) {
                return startNetherGoal(invocation);
            }
            if ("minecraft.goal.navigation.safe-waypoint".equals(capability)) {
                return startNavigationGoal(invocation);
            }
            if ("minecraft.goal.combat.attack-nearest".equals(capability)) {
                return startCombatGoal(invocation);
            }
            return onClientThread(() -> {
                invocation.cancellation().throwIfCancelled();
                return switch (capability) {
                case "minecraft.registry.item.search" -> searchItems(invocation);
                case "minecraft.server.info.read" -> serverInfo(invocation);
                case "minecraft.client.screenshot.capture" -> screenshot(invocation);
                case "minecraft.input.key.set" -> setKey(invocation, false);
                case "minecraft.input.mouse.set" -> setKey(invocation, true);
                case "minecraft.input.release-all" -> releaseAllInput(invocation);
                case "minecraft.player.state.read" -> playerState(invocation);
                case "minecraft.player.context.read" -> playerContext(invocation);
                case "minecraft.world.heightmap.read", "minecraft.world.light.analyze" ->
                        worldAnalysis(capability, invocation);
                case "minecraft.player.look" -> look(invocation);
                case "minecraft.player.move" -> move(invocation);
                case "minecraft.player.interact" -> interactKey(invocation);
                case "minecraft.inventory.slot.select" -> selectSlot(invocation);
                case "minecraft.inventory.container.read" -> containerRead();
                case "minecraft.inventory.container.click" -> containerClick(invocation);
                case "minecraft.entity.interact" -> entityInteract(invocation);
                case "minecraft.entity.nearby.read" -> nearbyEntities(invocation);
                case "minecraft.ui.state.read" -> uiState();
                case "minecraft.ui.click" -> uiClick(invocation);
                case "minecraft.ui.key" -> uiKey(invocation);
                case "minecraft.ui.text.insert" -> uiText(invocation);
                case "minecraft.chat.read" -> readChat(invocation);
                default -> throw new IllegalArgumentException("unsupported client capability: " + capability);
                };
            });
        }

        private CompletionStage<Map<String, Object>> startSurvivalTreeGoal(
                dev.lodestone.adapter.InvocationContext invocation) {
            var result = new CompletableFuture<Map<String, Object>>();
            Minecraft.getInstance().execute(() -> {
                try {
                    if (combatGoal != null && !combatGoal.done()) {
                        throw new IllegalStateException("a native Minecraft goal actor is already running");
                    }
                    invocation.cancellation().commitMutation();
                    var token = String.valueOf(invocation.request().input().getOrDefault("continuationToken", ""));
                    if (!token.isBlank()) {
                        if (survivalTreeGoal == null || survivalTreeGoal.done() || !survivalTreeGoal.paused()
                                || !survivalTreeGoal.continuationToken().equals(token)) {
                            throw new IllegalStateException("survival tree continuation token is stale or unknown");
                        }
                        survivalTreeGoal.resume(invocation, result);
                    } else {
                        if (survivalTreeGoal != null && !survivalTreeGoal.done()) {
                            throw new IllegalStateException("a survival wooden-axe tree goal is already running");
                        }
                        survivalTreeGoal = new NeoForgeSurvivalTreeGoal(invocation, result);
                    }
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            });
            return result;
        }

        private void tickSurvivalTreeGoal() {
            var current = survivalTreeGoal;
            if (current == null) return;
            current.tick(Minecraft.getInstance());
            if (current.done()) survivalTreeGoal = null;
        }

        private CompletionStage<Map<String, Object>> startWoolTreeZombieGoal(
                dev.lodestone.adapter.InvocationContext invocation) {
            var result = new CompletableFuture<Map<String, Object>>();
            Minecraft.getInstance().execute(() -> {
                try {
                    if ((survivalTreeGoal != null && !survivalTreeGoal.done())
                            || (woolTreeZombieGoal != null && !woolTreeZombieGoal.done())) {
                        throw new IllegalStateException("a native Minecraft goal actor is already running");
                    }
                    if (combatGoal != null && !combatGoal.done()) {
                        throw new IllegalStateException("a native Minecraft goal actor is already running");
                    }
                    invocation.cancellation().commitMutation();
                    woolTreeZombieGoal = new NeoForgeWoolTreeZombieGoal(invocation, result);
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            });
            return result;
        }

        private void tickWoolTreeZombieGoal() {
            var current = woolTreeZombieGoal;
            if (current == null) return;
            current.tick(Minecraft.getInstance());
            if (current.done()) woolTreeZombieGoal = null;
        }

        private CompletionStage<Map<String, Object>> startNetherGoal(
                dev.lodestone.adapter.InvocationContext invocation) {
            var result = new CompletableFuture<Map<String, Object>>();
            Minecraft.getInstance().execute(() -> {
                try {
                    var token = String.valueOf(invocation.request().input().getOrDefault("continuationToken", ""));
                    if ((survivalTreeGoal != null && !survivalTreeGoal.done())
                            || (woolTreeZombieGoal != null && !woolTreeZombieGoal.done())
                            || (netherGoal != null && !netherGoal.done() && token.isBlank())) {
                        throw new IllegalStateException("a native Minecraft goal actor is already running");
                    }
                    if (combatGoal != null && !combatGoal.done()) {
                        throw new IllegalStateException("a native Minecraft goal actor is already running");
                    }
                    invocation.cancellation().commitMutation();
                    if (!token.isBlank()) {
                        if (netherGoal == null || netherGoal.done() || !netherGoal.paused()
                                || !netherGoal.continuationToken().equals(token)) {
                            throw new IllegalStateException("Nether continuation token is stale or unknown");
                        }
                        netherGoal.resume(invocation, result);
                    } else {
                        netherGoal = new NeoForgeNetherGoal(invocation, result);
                    }
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            });
            return result;
        }

        private void tickNetherGoal() {
            var current = netherGoal;
            if (current == null) return;
            current.tick(Minecraft.getInstance());
            if (current.done()) netherGoal = null;
        }

        private CompletionStage<Map<String, Object>> startNavigationGoal(
                dev.lodestone.adapter.InvocationContext invocation) {
            var result = new CompletableFuture<Map<String, Object>>();
            Minecraft.getInstance().execute(() -> {
                try {
                    if ((survivalTreeGoal != null && !survivalTreeGoal.done())
                            || (woolTreeZombieGoal != null && !woolTreeZombieGoal.done())
                            || (netherGoal != null && !netherGoal.done())
                            || (navigationGoal != null && !navigationGoal.done())) {
                        throw new IllegalStateException("a native Minecraft goal actor is already running");
                    }
                    if (combatGoal != null && !combatGoal.done()) {
                        throw new IllegalStateException("a native Minecraft goal actor is already running");
                    }
                    invocation.cancellation().commitMutation();
                    navigationGoal = new NeoForgeNavigationGoal(invocation, result);
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            });
            return result;
        }

        private void tickNavigationGoal() {
            var current = navigationGoal;
            if (current == null) return;
            current.tick(Minecraft.getInstance());
            if (current.done()) navigationGoal = null;
        }

        private CompletionStage<Map<String, Object>> startCombatGoal(
                dev.lodestone.adapter.InvocationContext invocation) {
            var result = new CompletableFuture<Map<String, Object>>();
            Minecraft.getInstance().execute(() -> {
                try {
                    if ((survivalTreeGoal != null && !survivalTreeGoal.done())
                            || (woolTreeZombieGoal != null && !woolTreeZombieGoal.done())
                            || (netherGoal != null && !netherGoal.done())
                            || (navigationGoal != null && !navigationGoal.done())
                            || (combatGoal != null && !combatGoal.done())) {
                        throw new IllegalStateException("a native Minecraft goal actor is already running");
                    }
                    invocation.cancellation().commitMutation();
                    combatGoal = new NeoForgeCombatGoal(invocation, result);
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            });
            return result;
        }

        private void tickCombatGoal() {
            var current = combatGoal;
            if (current == null) return;
            current.tick(Minecraft.getInstance());
            if (current.done()) combatGoal = null;
        }

        private static Map<String, Object> screenshot(
                dev.lodestone.adapter.InvocationContext invocation) {
            var client = Minecraft.getInstance();
            var pose = pose(client.player);
            var captured = Screenshot.takeScreenshot(client.getMainRenderTarget());
            return NeoForgeScreenshotSupport.capture(invocation,
                    new NativeCapturedImage(captured), pose);
        }

        private static NeoForgeScreenshotSupport.Pose pose(LocalPlayer player) {
            return player == null ? null : new NeoForgeScreenshotSupport.Pose(
                    player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
        }

        private record NativeCapturedImage(NativeImage image)
                implements NeoForgeScreenshotSupport.CapturedImage {
            private NativeCapturedImage {
                if (image == null) throw new IllegalArgumentException("captured image is required");
            }

            @Override public int width() { return image.getWidth(); }
            @Override public int height() { return image.getHeight(); }

            @Override
            public NeoForgeScreenshotSupport.CapturedImage resize(int width, int height) {
                var resized = new NativeImage(width, height, false);
                try {
                    image.resizeSubRectTo(0, 0, image.getWidth(), image.getHeight(), resized);
                    return new NativeCapturedImage(resized);
                } catch (Throwable failure) {
                    resized.close();
                    throw failure;
                }
            }

            @Override public byte[] asByteArray() throws java.io.IOException { return image.asByteArray(); }
            @Override public void close() { image.close(); }
        }

        private void captureChat(Map<String, Object> message) {
            synchronized (chat) {
                while (chat.size() >= 256) {
                    chat.removeFirst();
                }
                chat.addLast(message);
            }
        }

        private void observeScreen(Screen screen) {
            screenToken(screen);
        }

        private Map<String, Object> setKey(dev.lodestone.adapter.InvocationContext invocation, boolean mouse) {
            var input = invocation.request().input();
            var key = text(input, "key", mouse && input.get("button") != null
                    ? "key.mouse." + number(input, "button") : null);
            var mapping = findKey(key);
            if (mapping == null) {
                throw new IllegalArgumentException("unknown client key mapping: " + key);
            }
            var down = bool(input, "down", false);
            invocation.cancellation().commitMutation();
            var name = mapping.getName();
            if (down) {
                directlyOwnedMappings.add(name);
                ownedMappings.put(name, mapping);
                mapping.setDown(true);
            } else {
                directlyOwnedMappings.remove(name);
                if (!leasedMappings.contains(name)) {
                    mapping.setDown(false);
                    ownedMappings.remove(name);
                }
            }
            return Map.of("key", name, "down", down, "mouse", mouse);
        }

        private Map<String, Object> releaseAllInput(dev.lodestone.adapter.InvocationContext invocation) {
            invocation.cancellation().commitMutation();
            return releaseAllInput();
        }

        private Map<String, Object> releaseAllInput() {
            var released = new ArrayList<>(ownedMappings.keySet());
            for (var mapping : ownedMappings.values()) mapping.setDown(false);
            ownedMappings.clear();
            directlyOwnedMappings.clear();
            leasedMappings.clear();
            movementLease.releaseAll();
            released.sort(String::compareTo);
            return Map.of("released", released, "count", released.size(),
                    "leaseGeneration", movementLease.generation());
        }

        private void releaseExpiredInput(long nowMillis) {
            releaseLeased(movementLease.releaseExpired(nowMillis));
        }

        private void releaseLeased(Collection<String> names) {
            for (var name : names) {
                leasedMappings.remove(name);
                var mapping = ownedMappings.get(name);
                if (mapping != null && !directlyOwnedMappings.contains(name)) {
                    mapping.setDown(false);
                    ownedMappings.remove(name);
                }
            }
        }

        private Map<String, Object> searchItems(dev.lodestone.adapter.InvocationContext invocation) {
            var input = input(invocation);
            var query = text(input, "query", null).trim();
            if (query.length() > 256) {
                throw new IllegalArgumentException("query must be <=256 characters");
            }
            var limit = numberOrDefault(input, "limit", 20);
            if (limit < 1 || limit > 50) {
                throw new IllegalArgumentException("limit must be between 1 and 50");
            }

            String namespace = null;
            if (input.get("namespace") != null) {
                namespace = text(input, "namespace", null).trim().toLowerCase(Locale.ROOT);
                if (namespace.length() > 64) {
                    throw new IllegalArgumentException("namespace must be <=64 characters");
                }
            }

            var normalizedQuery = query.toLowerCase(Locale.ROOT);
            var matches = new ArrayList<ItemProjection>();
            for (var id : BuiltInRegistries.ITEM.keySet()) {
                invocation.cancellation().throwIfCancelled();
                if (namespace != null && !namespace.equals(id.getNamespace())) {
                    continue;
                }
                var item = BuiltInRegistries.ITEM.get(id);
                var translationKey = safeText(item.getDescriptionId());
                var displayName = safeText(item.getDescription().getString());
                var rank = itemMatchRank(normalizedQuery, id.toString(), id.getPath(), translationKey, displayName);
                if (rank < 0) {
                    continue;
                }
                matches.add(new ItemProjection(rank, id.toString(), translationKey, displayName,
                        item.getDefaultInstance().getMaxStackSize(), item instanceof BlockItem));
            }

            matches.sort(Comparator.comparingInt(ItemProjection::rank).thenComparing(ItemProjection::id));
            var count = Math.min(limit, matches.size());
            var items = new ArrayList<Map<String, Object>>(count);
            for (var index = 0; index < count; index++) {
                items.add(matches.get(index).toMap());
            }
            return Map.of("query", query, "limit", limit, "count", count,
                    "truncated", matches.size() > count, "items", List.copyOf(items));
        }

        private Map<String, Object> serverInfo(dev.lodestone.adapter.InvocationContext invocation) {
            invocation.cancellation().throwIfCancelled();
            var client = Minecraft.getInstance();
            var level = client.level;
            var connection = client.getConnection();
            var online = new ArrayList<PlayerInfo>();
            if (connection != null) {
                online.addAll(connection.getOnlinePlayers());
            }
            online.sort(Comparator
                    .comparing((PlayerInfo info) -> info.getProfile().getName(), String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(info -> info.getProfile().getId().toString()));

            var localId = client.player == null ? null : client.player.getUUID();
            var playerLimit = Math.min(256, online.size());
            var players = new ArrayList<Map<String, Object>>(playerLimit);
            for (var index = 0; index < playerLimit; index++) {
                invocation.cancellation().throwIfCancelled();
                var profile = online.get(index).getProfile();
                players.add(Map.of("uuid", profile.getId().toString(),
                        "name", safeText(profile.getName()),
                        "local", localId != null && localId.equals(profile.getId())));
            }

            var result = new LinkedHashMap<String, Object>();
            result.put("gameVersion", SharedConstants.getCurrentVersion().getName());
            result.put("loader", "neoforge");
            result.put("environment", client.hasSingleplayerServer() ? "integrated-server"
                    : connection == null ? "client" : "remote");
            result.put("connected", connection != null);
            result.put("integratedServer", client.hasSingleplayerServer());
            result.put("dimension", level == null ? "" : level.dimension().location().toString());
            result.put("gameTime", level == null ? 0L : level.getGameTime());
            result.put("dayTime", level == null ? 0L : level.getDayTime());
            result.put("difficulty", level == null ? "" : level.getDifficulty().getKey());
            result.put("playerCount", online.size());
            result.put("truncated", online.size() > playerLimit);
            result.put("players", List.copyOf(players));
            return Map.copyOf(result);
        }

        private Map<String, Object> playerContext(dev.lodestone.adapter.InvocationContext invocation) {
            var player = requirePlayer();
            var client = Minecraft.getInstance();
            var reach = finiteNumberOrDefault(input(invocation), "reach", 128.0);
            if (reach < 1.0 || reach > 256.0) {
                throw new IllegalArgumentException("reach must be between 1 and 256");
            }
            var eye = player.getEyePosition();
            var look = player.getViewVector(1.0F);
            var blockPosition = player.blockPosition();
            var held = player.getMainHandItem();

            var result = new LinkedHashMap<String, Object>();
            result.put("position", position(player.getX(), player.getY(), player.getZ()));
            result.put("blockPosition", blockPosition(blockPosition));
            result.put("rotation", Map.of("yaw", player.getYRot(), "pitch", player.getXRot()));
            result.put("facing", player.getDirection().getName());
            result.put("eyePosition", position(eye.x, eye.y, eye.z));
            result.put("lookVector", position(look.x, look.y, look.z));
            result.put("heldItem", Map.of("id", itemId(held), "count", held.getCount()));
            result.put("gameMode", client.gameMode == null || client.gameMode.getPlayerMode() == null
                    ? "unknown" : client.gameMode.getPlayerMode().getName());
            result.put("flying", player.getAbilities().flying);
            result.put("onGround", player.onGround());
            result.put("dimension", player.level().dimension().location().toString());
            result.put("target", raycastTarget(player, reach));
            return Map.copyOf(result);
        }

        private Map<String, Object> nearbyEntities(dev.lodestone.adapter.InvocationContext invocation) {
            var client = Minecraft.getInstance();
            var player = requirePlayer();
            var input = input(invocation);
            var radius = finiteNumberOrDefault(input, "radius", 32.0);
            if (radius < 1.0 || radius > 256.0) {
                throw new IllegalArgumentException("radius must be between 1 and 256");
            }
            var limit = numberOrDefault(input, "limit", 64);
            if (limit < 1 || limit > 256) {
                throw new IllegalArgumentException("limit must be between 1 and 256");
            }
            var includePlayers = bool(input, "includePlayers", true);
            String requestedType = null;
            if (input.get("type") != null) {
                requestedType = text(input, "type", null).trim().toLowerCase(Locale.ROOT);
                if (!requestedType.contains(":")) {
                    requestedType = "minecraft:" + requestedType;
                }
            }

            var radiusSquared = radius * radius;
            var matches = new ArrayList<NearbyEntityProjection>();
            for (Entity entity : client.level.entitiesForRendering()) {
                invocation.cancellation().throwIfCancelled();
                if (entity == player) {
                    continue;
                }
                var isPlayer = entity instanceof Player;
                if (!includePlayers && isPlayer) {
                    continue;
                }
                var type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
                if (requestedType != null && !requestedType.equals(type)) {
                    continue;
                }
                var distanceSquared = player.distanceToSqr(entity);
                if (distanceSquared > radiusSquared) {
                    continue;
                }
                matches.add(new NearbyEntityProjection(entity.getId(), entity.getUUID().toString(), type,
                        safeText(entity.getName().getString()), Math.sqrt(distanceSquared),
                        position(entity.getX(), entity.getY(), entity.getZ()), isPlayer));
            }
            matches.sort(Comparator.comparingDouble(NearbyEntityProjection::distance)
                    .thenComparing(NearbyEntityProjection::type)
                    .thenComparing(NearbyEntityProjection::uuid)
                    .thenComparingInt(NearbyEntityProjection::entityId));
            var count = Math.min(limit, matches.size());
            var entities = new ArrayList<Map<String, Object>>(count);
            for (var index = 0; index < count; index++) {
                entities.add(matches.get(index).toMap());
            }
            return Map.of("dimension", client.level.dimension().location().toString(),
                    "radius", radius, "limit", limit, "truncated", matches.size() > count,
                    "entities", List.copyOf(entities));
        }

        private Map<String, Object> worldAnalysis(String capability,
                                                   dev.lodestone.adapter.InvocationContext invocation) {
            return switch (NeoForgeWorldAnalysis.operation(capability)) {
                case HEIGHTMAP -> heightmap(invocation);
                case LIGHT -> lightAnalysis(invocation);
            };
        }

        private Map<String, Object> heightmap(dev.lodestone.adapter.InvocationContext invocation) {
            var level = requireLevel();
            var request = NeoForgeWorldAnalysis.heightmapRequest(input(invocation));
            var chunks = new LoadedChunkLookup(level);
            return NeoForgeWorldAnalysis.heightmap(request, level.dimension().location().toString(),
                    (x, z) -> {
                        var chunk = chunks.get(x, z);
                        if (chunk == null) {
                            return new NeoForgeWorldAnalysis.HeightColumn(false, false, 0, "");
                        }
                        var height = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                        if (height < level.getMinBuildHeight()) {
                            return new NeoForgeWorldAnalysis.HeightColumn(true, true, 0, "");
                        }
                        var surfaceBlock = "";
                        if (request.includeSurfaceBlocks()) {
                            var state = chunk.getBlockState(new BlockPos(x, height, z));
                            surfaceBlock = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                        }
                        return new NeoForgeWorldAnalysis.HeightColumn(true, false, height, surfaceBlock);
                    }, invocation.cancellation()::throwIfCancelled);
        }

        private Map<String, Object> lightAnalysis(dev.lodestone.adapter.InvocationContext invocation) {
            var level = requireLevel();
            var request = NeoForgeWorldAnalysis.lightRequest(input(invocation));
            var chunks = new LoadedChunkLookup(level);
            var position = new BlockPos.MutableBlockPos();
            return NeoForgeWorldAnalysis.lightAnalysis(request, level.dimension().location().toString(),
                    level.getMinBuildHeight(), level.getMaxBuildHeight(), (x, y, z) -> {
                        var chunk = chunks.get(x, z);
                        if (chunk == null) {
                            return new NeoForgeWorldAnalysis.LightCell(false, false, "", 0, 0, 0);
                        }
                        position.set(x, y, z);
                        var state = chunk.getBlockState(position);
                        var emission = state.getLightEmission(level, position);
                        var solid = !state.isAir() && state.canOcclude();
                        var block = emission > 0
                                ? BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString() : "";
                        if (solid) {
                            return new NeoForgeWorldAnalysis.LightCell(true, true, block, emission, 0, 0);
                        }
                        return new NeoForgeWorldAnalysis.LightCell(true, false, block, emission,
                                level.getBrightness(LightLayer.BLOCK, position),
                                level.getBrightness(LightLayer.SKY, position));
                    }, invocation.cancellation()::throwIfCancelled);
        }

        private Map<String, Object> playerState(dev.lodestone.adapter.InvocationContext invocation) {
            var player = requirePlayer();
            var state = new LinkedHashMap<String, Object>();
            state.put("uuid", player.getUUID().toString());
            state.put("name", player.getGameProfile().getName());
            state.put("position", Map.of("x", player.getX(), "y", player.getY(), "z", player.getZ()));
            state.put("rotation", Map.of("yaw", player.getYRot(), "pitch", player.getXRot()));
            state.put("dimension", player.level().dimension().location().toString());
            state.put("health", player.getHealth());
            state.put("food", player.getFoodData().getFoodLevel());
            state.put("selectedSlot", player.getInventory().selected);
            state.put("worldObservation", NeoForgeGoalObservation.capture(Minecraft.getInstance(),
                    NeoForgeGoalPolicy.from(input(invocation))));
            return Map.copyOf(state);
        }

        private Map<String, Object> look(dev.lodestone.adapter.InvocationContext invocation) {
            var player = requirePlayer();
            var yaw = finiteNumber(input(invocation), "yaw");
            var pitch = finiteNumber(input(invocation), "pitch");
            if (pitch < -90 || pitch > 90 || yaw < -3600 || yaw > 3600) {
                throw new IllegalArgumentException("look rotation is outside safe bounds");
            }
            invocation.cancellation().commitMutation();
            player.setYRot((float) yaw);
            player.setXRot((float) pitch);
            player.setYHeadRot((float) yaw);
            return Map.of("yaw", player.getYRot(), "pitch", player.getXRot());
        }

        private Map<String, Object> move(dev.lodestone.adapter.InvocationContext invocation) {
            var client = Minecraft.getInstance();
            requirePlayer();
            var input = input(invocation);
            var forward = finiteNumberOrDefault(input, "forward", 0.0);
            var strafe = finiteNumberOrDefault(input, "strafe", 0.0);
            if (forward < -1 || forward > 1 || strafe < -1 || strafe > 1) {
                throw new IllegalArgumentException("movement values must be between -1 and 1");
            }
            var jump = bool(input, "jump", false);
            var sprint = bool(input, "sprint", false);
            var sneak = bool(input, "sneak", false);
            var durationMs = numberOrDefault(input, "durationMs", 100);
            if (durationMs < 1 || durationMs > 10_000) {
                throw new IllegalArgumentException("durationMs must be between 1 and 10000");
            }

            var next = new LinkedHashMap<String, KeyMapping>();
            ownIf(next, client.options.keyUp, forward > 0);
            ownIf(next, client.options.keyDown, forward < 0);
            ownIf(next, client.options.keyRight, strafe > 0);
            ownIf(next, client.options.keyLeft, strafe < 0);
            ownIf(next, client.options.keyJump, jump);
            ownIf(next, client.options.keySprint, sprint);
            ownIf(next, client.options.keyShift, sneak);
            var previous = movementLease.owned();
            invocation.cancellation().commitMutation();
            releaseLeased(previous.stream().filter(name -> !next.containsKey(name)).toList());
            for (var entry : next.entrySet()) {
                entry.getValue().setDown(true);
                ownedMappings.put(entry.getKey(), entry.getValue());
                leasedMappings.add(entry.getKey());
            }
            var generation = movementLease.replace(next.keySet(), monotonicMillis(), durationMs);
            return Map.of("forward", forward, "strafe", strafe,
                    "jump", jump, "sprint", sprint, "sneak", sneak,
                    "durationMs", durationMs,
                    "leaseGeneration", generation);
        }

        private void ownIf(Map<String, KeyMapping> owned, KeyMapping mapping, boolean down) {
            if (down) {
                owned.put(mapping.getName(), mapping);
            }
        }

        private Map<String, Object> interactKey(dev.lodestone.adapter.InvocationContext invocation) {
            var client = Minecraft.getInstance();
            var player = requirePlayer();
            var arguments = input(invocation);
            var action = text(arguments, "action", "use");
            var intelligence = text(arguments, "intelligence", "").trim().toLowerCase(Locale.ROOT);
            if ("attack".equals(action) && !intelligence.isBlank() && !"raw".equals(intelligence)
                    && !"lowest".equals(intelligence) && !"raw-v1".equals(intelligence)
                    && client.gameMode != null && client.gameMode.getPlayerMode() == net.minecraft.world.level.GameType.SURVIVAL) {
                var blockedBlock = NeoForgeGoalActionGuard.toolRequiredAttackTarget(client.level, player);
                if (blockedBlock != null) {
                    throw new IllegalStateException("intelligent attack refused tool-required block " + blockedBlock
                            + "; acquire and equip the prerequisite tool first");
                }
            }
            var mapping = switch (action) {
                case "use" -> client.options.keyUse;
                case "attack" -> client.options.keyAttack;
                case "pick" -> client.options.keyPickItem;
                default -> throw new IllegalArgumentException("action must be use, attack, or pick");
            };
            invocation.cancellation().commitMutation();
            KeyMapping.click(mapping.getKey());
            return Map.of("action", action, "queued", true,
                    "intelligence", intelligence.isBlank() ? "unspecified" : intelligence);
        }

        private Map<String, Object> selectSlot(dev.lodestone.adapter.InvocationContext invocation) {
            var slot = number(input(invocation), "slot");
            if (slot < 0 || slot > 8) {
                throw new IllegalArgumentException("slot must be between 0 and 8");
            }
            var player = requirePlayer();
            invocation.cancellation().commitMutation();
            player.getInventory().selected = slot;
            return Map.of("selectedSlot", slot);
        }

        private Map<String, Object> containerRead() {
            var client = Minecraft.getInstance();
            requirePlayer();
            if (!(client.screen instanceof AbstractContainerScreen<?> screen)) {
                throw new IllegalStateException("no active container screen is available");
            }
            var menu = screen.getMenu();
            var slots = new ArrayList<Map<String, Object>>(menu.slots.size());
            for (var index = 0; index < menu.slots.size(); index++) {
                var stack = menu.slots.get(index).getItem();
                slots.add(Map.of("slot", index, "item", stack.isEmpty() ? "minecraft:air" : net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                        "count", stack.getCount(), "maxCount", stack.getMaxStackSize(), "empty", stack.isEmpty()));
            }
            return Map.of("open", true, "containerId", menu.containerId,
                    "revision", menu.getStateId(), "slots", slots);
        }

        private Map<String, Object> containerClick(dev.lodestone.adapter.InvocationContext invocation) {
            var client = Minecraft.getInstance();
            var player = requirePlayer();
            if (!(client.screen instanceof AbstractContainerScreen<?> screen) || client.gameMode == null) {
                throw new IllegalStateException("no active container screen is available");
            }
            var input = input(invocation);
            var slot = number(input, "slot");
            var button = numberOrDefault(input, "button", 0);
            var revision = number(input, "revision");
            var currentRevision = screen.getMenu().getStateId();
            if (revision != currentRevision) {
                throw new IllegalStateException("container revision is stale; expected " + currentRevision
                        + " but received " + revision);
            }
            if (slot < 0 || slot >= screen.getMenu().slots.size()) {
                throw new IllegalArgumentException("slot is outside the active container");
            }
            if (button < 0 || button > 8) {
                throw new IllegalArgumentException("button must be between 0 and 8");
            }
            var clickType = ClickType.valueOf(text(input, "clickType", "PICKUP").toUpperCase(java.util.Locale.ROOT));
            invocation.cancellation().commitMutation();
            client.gameMode.handleInventoryMouseClick(screen.getMenu().containerId, slot, button, clickType, player);
            return Map.of("containerId", screen.getMenu().containerId, "slot", slot,
                    "button", button, "clickType", clickType.toString());
        }

        private Map<String, Object> entityInteract(dev.lodestone.adapter.InvocationContext invocation) {
            var client = Minecraft.getInstance();
            var player = requirePlayer();
            if (client.level == null || client.gameMode == null) {
                throw new IllegalStateException("client world or game mode is unavailable");
            }
            var entity = client.level.getEntity(number(input(invocation), "entityId"));
            if (entity == null) {
                throw new IllegalArgumentException("entity is not present in the client world");
            }
            var hand = InteractionHand.valueOf(text(input(invocation), "hand", "MAIN_HAND").toUpperCase(java.util.Locale.ROOT));
            invocation.cancellation().commitMutation();
            var result = client.gameMode.interact(player, entity, hand);
            return Map.of("entityId", entity.getId(), "result", result.toString());
        }

        private Map<String, Object> uiState() {
            return captureUi().toMap();
        }

        private Map<String, Object> uiClick(dev.lodestone.adapter.InvocationContext invocation) {
            var input = input(invocation);
            var expectedToken = text(input, "screenToken", null);
            var expectedRevision = text(input, "snapshotRevision", null);
            var button = numberOrDefault(input, "button", 0);
            if (button < 0 || button > 2) {
                throw new IllegalArgumentException("button must be between 0 and 2");
            }
            var selector = UiSelector.from(input);
            var snapshot = captureUi();
            if (snapshot.screen() == null) {
                throw new IllegalStateException("no screen is open");
            }
            if (!snapshot.screenToken().equals(expectedToken)) {
                throw new IllegalStateException("UI screen token is stale");
            }
            if (!snapshot.snapshotRevision().equals(expectedRevision)) {
                throw new IllegalStateException("UI snapshot revision is stale");
            }
            var node = selector.resolve(snapshot.nodes());
            var x = node == null ? selector.x() : node.bounds().centerX();
            var y = node == null ? selector.y() : node.bounds().centerY();
            if (x < 0 || y < 0 || x >= snapshot.width() || y >= snapshot.height()) {
                throw new IllegalArgumentException("UI click coordinates are outside the guarded screen");
            }
            invocation.cancellation().commitMutation();
            var handled = snapshot.screen().mouseClicked(x, y, button);
            var result = new LinkedHashMap<String, Object>();
            result.put("handled", handled);
            result.put("x", x);
            result.put("y", y);
            result.put("screenToken", snapshot.screenToken());
            result.put("snapshotRevision", snapshot.snapshotRevision());
            if (node != null) {
                result.put("nodeId", node.nodeId());
            }
            return Map.copyOf(result);
        }

        private UiSnapshot captureUi() {
            var client = Minecraft.getInstance();
            var screen = client.screen;
            var inWorld = client.level != null && client.player != null;
            var width = screen == null ? client.getWindow().getGuiScaledWidth() : screen.width;
            var height = screen == null ? client.getWindow().getGuiScaledHeight() : screen.height;
            var guiScale = client.getWindow().getGuiScale();
            if (screen == null) {
                var token = screenToken(null);
                var revision = UiContracts.revision(token, "", "", width, height,
                        List.of(), "complete", false, List.of());
                return new UiSnapshot(null, false, token, revision, clientTick, inWorld, "", "", "",
                        width, height, guiScale, "complete", false, List.of(), List.of());
            }

            var token = screenToken(screen);
            var nodes = new ArrayList<UiNode>();
            var causes = new LinkedHashSet<String>();
            var seen = new IdentityHashMap<GuiEventListener, Boolean>();
            seen.put(screen, Boolean.TRUE);
            traverseChildren(screen, token, List.of(), 0, screen.getClass().getName(),
                    seen, nodes, causes);
            if (nodes.isEmpty()) {
                causes.add("opaque-screen");
            }
            var truncated = !causes.isEmpty();
            var coverage = causes.contains("opaque-screen") ? "opaque"
                    : truncated ? "partial" : "complete";
            var screenClass = screen.getClass().getName();
            var screenName = screen.getClass().getSimpleName().isEmpty()
                    ? screenClass : screen.getClass().getSimpleName();
            var title = safeText(screen.getTitle().getString());
            var revision = UiContracts.revision(token, screenClass, title, width, height,
                    nodes, coverage, truncated, List.copyOf(causes));
            return new UiSnapshot(screen, true, token, revision, clientTick, inWorld,
                    screenName, screenClass, title, width, height, guiScale, coverage, truncated,
                    List.copyOf(causes), List.copyOf(nodes));
        }

        private boolean traverseChildren(ContainerEventHandler parent, String token, List<Integer> prefix,
                                         int depth, String parentType,
                                         IdentityHashMap<GuiEventListener, Boolean> seen,
                                         List<UiNode> nodes, Set<String> causes) {
            var children = parent.children();
            var childCount = Math.min(children.size(), UiLimits.DEFAULT.maxChildren());
            if (children.size() > UiLimits.DEFAULT.maxChildren()) {
                causes.add("child-limit");
            }
            for (var index = 0; index < childCount; index++) {
                var child = children.get(index);
                if (child == null || seen.put(child, Boolean.TRUE) != null) {
                    continue;
                }
                if (nodes.size() >= UiLimits.DEFAULT.maxNodes()) {
                    causes.add("node-limit");
                    return false;
                }
                var path = new ArrayList<>(prefix);
                path.add(index);
                nodes.add(projectNode(child, token, path, depth, parentType, causes));
                if (child instanceof ContainerEventHandler container) {
                    if (depth >= UiLimits.DEFAULT.maxDepth()) {
                        if (!container.children().isEmpty()) {
                            causes.add("depth-limit");
                        }
                    } else if (!traverseChildren(container, token, path, depth + 1,
                            child.getClass().getName(), seen, nodes, causes)) {
                        return false;
                    }
                }
            }
            return true;
        }

        private UiNode projectNode(GuiEventListener listener, String token, List<Integer> path,
                                   int depth, String parentType, Set<String> causes) {
            var nodeId = token + ":" + path.stream().map(String::valueOf)
                    .collect(java.util.stream.Collectors.joining("."));
            if (!(listener instanceof AbstractWidget widget)) {
                if (!(listener instanceof ContainerEventHandler)) {
                    causes.add("unsupported-widget");
                }
                return new UiNode(nodeId, path, depth, listener.getClass().getName(), parentType,
                        null, null, null, listener.isFocused(), null, null, Set.of(), null, null);
            }
            var label = safeText(widget.getMessage().getString());
            var bounds = new UiBounds(widget.getX(), widget.getY(),
                    Math.max(0, widget.getWidth()), Math.max(0, widget.getHeight()));
            var clickable = widget.active && widget.visible && bounds.width() > 0 && bounds.height() > 0;
            Integer textLength = null;
            Boolean textPresent = null;
            if (widget instanceof EditBox editBox) {
                textLength = editBox.getValue().length();
                textPresent = textLength > 0;
            }
            return new UiNode(nodeId, path, depth, widget.getClass().getName(), parentType,
                    label, label, bounds, widget.isFocused(), widget.active, widget.visible,
                    clickable ? Set.of("click") : Set.of(), textLength, textPresent);
        }

        private String screenToken(Screen screen) {
            if (!tokenInitialized || screen != tokenScreen) {
                tokenInitialized = true;
                tokenScreen = screen;
                currentScreenToken = "neo121-" + Long.toUnsignedString(++screenSequence, 36);
            }
            return currentScreenToken;
        }

        private static String safeText(String value) {
            if (value == null || value.isEmpty()) {
                return "";
            }
            return value.length() <= 512 ? value : value.substring(0, 512);
        }

        private record UiSnapshot(Screen screen, boolean open, String screenToken,
                                  String snapshotRevision, long capturedAtTick, boolean inWorld,
                                  String screenName, String screenClass, String title, int width, int height,
                                  double guiScale, String coverage, boolean truncated,
                                  List<String> truncationCauses, List<UiNode> nodes) {
            private Map<String, Object> toMap() {
                var result = new LinkedHashMap<String, Object>();
                result.put("open", open);
                result.put("screenToken", screenToken);
                result.put("snapshotRevision", snapshotRevision);
                result.put("capturedAtTick", capturedAtTick);
                result.put("inWorld", inWorld);
                result.put("screen", screenName);
                result.put("screenClass", screenClass);
                result.put("title", title);
                result.put("width", width);
                result.put("height", height);
                result.put("guiScale", guiScale);
                result.put("coverage", coverage);
                result.put("truncated", truncated);
                result.put("truncationCauses", truncationCauses);
                result.put("widgets", nodes.stream().map(UiNode::toMap).toList());
                return Map.copyOf(result);
            }
        }

        private Map<String, Object> uiKey(dev.lodestone.adapter.InvocationContext invocation) {
            var input = input(invocation);
            var key = number(input, "key");
            var scanCode = numberOrDefault(input, "scanCode", 0);
            var modifiers = numberOrDefault(input, "modifiers", 0);
            var client = Minecraft.getInstance();
            if (client.screen == null && key == 256 && client.level != null && client.player != null) {
                invocation.cancellation().commitMutation();
                client.setScreen(new PauseScreen(true));
                return Map.of("handled", true, "openedPause", true);
            }
            if (client.screen == null && client.level != null && client.player != null) {
                invocation.cancellation().commitMutation();
                KeyMapping.click(InputConstants.getKey(key, scanCode));
                return Map.of("handled", true, "openedPause", false);
            }
            var screen = requireScreen();
            invocation.cancellation().commitMutation();
            var handled = screen.keyPressed(key, scanCode, modifiers);
            return Map.of("handled", handled, "openedPause", false);
        }

        private Map<String, Object> uiText(dev.lodestone.adapter.InvocationContext invocation) {
            var screen = requireScreen();
            var value = text(input(invocation), "text", null);
            if (value.length() > 4096) {
                throw new IllegalArgumentException("text must be <=4096 characters");
            }
            for (GuiEventListener child : screen.children()) {
                if (child instanceof EditBox editBox && child.isFocused()) {
                    invocation.cancellation().commitMutation();
                    editBox.insertText(value);
                    return Map.of("inserted", true, "length", value.length());
                }
            }
            throw new IllegalStateException("no focused text input is available");
        }

        private Map<String, Object> readChat(dev.lodestone.adapter.InvocationContext invocation) {
            var limit = numberOrDefault(input(invocation), "limit", 100);
            if (limit < 1 || limit > 256) {
                throw new IllegalArgumentException("limit must be between 1 and 256");
            }
            var messages = new ArrayList<Map<String, Object>>();
            synchronized (chat) {
                for (var message : chat) {
                    if (messages.size() >= limit) break;
                    messages.add(message);
                }
            }
            return Map.of("messages", messages, "count", messages.size());
        }

        private static int itemMatchRank(String query, String id, String path,
                                         String translationKey, String displayName) {
            var normalizedId = id.toLowerCase(Locale.ROOT);
            var normalizedPath = path.toLowerCase(Locale.ROOT);
            var normalizedTranslation = translationKey.toLowerCase(Locale.ROOT);
            var normalizedDisplay = displayName.toLowerCase(Locale.ROOT);
            if (normalizedId.equals(query) || normalizedPath.equals(query)
                    || normalizedTranslation.equals(query) || normalizedDisplay.equals(query)) {
                return 0;
            }
            if (normalizedId.startsWith(query) || normalizedPath.startsWith(query)
                    || normalizedTranslation.startsWith(query) || normalizedDisplay.startsWith(query)) {
                return 1;
            }
            return normalizedId.contains(query) || normalizedPath.contains(query)
                    || normalizedTranslation.contains(query) || normalizedDisplay.contains(query) ? 2 : -1;
        }

        private static Map<String, Object> raycastTarget(LocalPlayer player, double reach) {
            var level = Minecraft.getInstance().level;
            if (level == null) {
                throw new IllegalStateException("client world is unavailable");
            }
            Vec3 start = player.getEyePosition();
            Vec3 end = start.add(player.getViewVector(1.0F).scale(reach));
            var hit = level.clip(new ClipContext(start, end, ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE, player));
            if (hit.getType() != HitResult.Type.BLOCK) {
                return Map.of("kind", "miss", "distance", reach, "reach", reach,
                        "position", position(end.x, end.y, end.z));
            }

            var hitPosition = hit.getBlockPos();
            var state = level.getBlockState(hitPosition);
            var adjacent = hitPosition.relative(hit.getDirection());
            var target = new LinkedHashMap<String, Object>();
            target.put("kind", "block");
            target.put("distance", start.distanceTo(hit.getLocation()));
            target.put("reach", reach);
            target.put("position", position(hit.getLocation().x, hit.getLocation().y, hit.getLocation().z));
            target.put("block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
            target.put("blockPosition", blockPosition(hitPosition));
            target.put("face", hit.getDirection().getName());
            target.put("adjacentPosition", blockPosition(adjacent));
            target.put("state", state.toString());
            return Map.copyOf(target);
        }

        private static Map<String, Object> position(double x, double y, double z) {
            return Map.of("x", x, "y", y, "z", z);
        }

        private static Map<String, Object> blockPosition(BlockPos position) {
            return Map.of("x", position.getX(), "y", position.getY(), "z", position.getZ());
        }

        private static String itemId(ItemStack stack) {
            return stack.isEmpty() ? "minecraft:air"
                    : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        }

        private LocalPlayer requirePlayer() {
            var player = Minecraft.getInstance().player;
            if (player == null || Minecraft.getInstance().level == null) {
                throw new IllegalStateException("client player/world is unavailable");
            }
            return player;
        }

        private ClientLevel requireLevel() {
            var level = Minecraft.getInstance().level;
            if (level == null) {
                throw new IllegalStateException("client world is unavailable");
            }
            return level;
        }

        private static final class LoadedChunkLookup {
            private final ClientLevel level;
            private final Map<Long, LevelChunk> loaded = new HashMap<>();
            private final Set<Long> unloaded = new HashSet<>();

            private LoadedChunkLookup(ClientLevel level) {
                this.level = level;
            }

            private LevelChunk get(int blockX, int blockZ) {
                var chunkX = Math.floorDiv(blockX, 16);
                var chunkZ = Math.floorDiv(blockZ, 16);
                var key = ChunkPos.asLong(chunkX, chunkZ);
                var cached = loaded.get(key);
                if (cached != null) {
                    return cached;
                }
                if (unloaded.contains(key)) {
                    return null;
                }
                var chunk = level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
                if (chunk == null) {
                    unloaded.add(key);
                    return null;
                }
                loaded.put(key, chunk);
                return chunk;
            }
        }

        private Screen requireScreen() {
            var screen = Minecraft.getInstance().screen;
            if (screen == null) {
                throw new IllegalStateException("no screen is open");
            }
            return screen;
        }

        private KeyMapping findKey(String name) {
            var options = Minecraft.getInstance().options;
            if ("key.mouse.0".equals(name) || "mouse.left".equals(name)) {
                return options.keyAttack;
            }
            if ("key.mouse.1".equals(name) || "mouse.right".equals(name)) {
                return options.keyUse;
            }
            if ("key.mouse.2".equals(name) || "mouse.middle".equals(name)) {
                return options.keyPickItem;
            }
            for (var mapping : Minecraft.getInstance().options.keyMappings) {
                if (mapping.getName().equals(name)) {
                    return mapping;
                }
            }
            return null;
        }

        private static void setDown(KeyMapping mapping, boolean down) {
            mapping.setDown(down);
        }

        private static Map<String, Object> input(dev.lodestone.adapter.InvocationContext invocation) {
            return invocation.request().input();
        }

        private static String text(Map<String, Object> input, String key, String fallback) {
            var value = input.get(key);
            if (value == null && fallback != null) {
                return fallback;
            }
            if (!(value instanceof String string) || string.isBlank()) {
                throw new IllegalArgumentException("input field must be a non-empty string: " + key);
            }
            return string;
        }

        private static int number(Map<String, Object> input, String key) {
            var value = input.get(key);
            if (!(value instanceof Number number)) {
                throw new IllegalArgumentException("input field must be numeric: " + key);
            }
            return InputNumbers.exactInt(number, key);
        }

        private static int numberOrDefault(Map<String, Object> input, String key, int fallback) {
            var value = input.get(key);
            return value == null ? fallback : number(input, key);
        }

        private static double finiteNumber(Map<String, Object> input, String key) {
            var value = input.get(key);
            if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
                throw new IllegalArgumentException("input field must be a finite number: " + key);
            }
            return number.doubleValue();
        }

        private static double finiteNumberOrDefault(Map<String, Object> input, String key, double fallback) {
            return input.get(key) == null ? fallback : finiteNumber(input, key);
        }

        private static boolean bool(Map<String, Object> input, String key, boolean fallback) {
            var value = input.get(key);
            if (value == null) {
                return fallback;
            }
            if (!(value instanceof Boolean booleanValue)) {
                throw new IllegalArgumentException("input field must be boolean: " + key);
            }
            return booleanValue;
        }

        private static CompletionStage<Map<String, Object>> onClientThread(Callable<Map<String, Object>> operation) {
            var result = new CompletableFuture<Map<String, Object>>();
            Minecraft.getInstance().execute(() -> {
                try {
                    result.complete(operation.call());
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            });
            return result;
        }
    }
}
