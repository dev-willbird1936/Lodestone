// SPDX-License-Identifier: MIT
package dev.lodestone.fabric;

import com.mojang.blaze3d.platform.NativeImage;
import dev.lodestone.adapter.InputNumbers;
import dev.lodestone.adapter.InputLease;
import dev.lodestone.adapter.InvocationContext;
import dev.lodestone.adapter.InvocationAttributes;
import dev.lodestone.adapter.ScreenshotDimensions;
import dev.lodestone.adapter.UiBounds;
import dev.lodestone.adapter.UiContracts;
import dev.lodestone.adapter.UiLimits;
import dev.lodestone.adapter.UiNode;
import dev.lodestone.adapter.UiSelector;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
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
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Client-only bridge for the Fabric 1.20.1 player/input and guarded UI contract. */
public final class FabricClientController implements ClientModInitializer {
    private static final Bridge BRIDGE = new Bridge();
    private static boolean attached;
    private static boolean lastLevel;
    private static boolean lastWorld;
    private static Screen lastScreen;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(FabricClientController::tick);
    }

    private static void tick(Minecraft client) {
        BRIDGE.tick(client);
        var adapter = FabricAdapter.active();
        if (adapter == null) {
            return;
        }
        if (!attached) {
            adapter.attachClientBridge(BRIDGE);
            attached = true;
        }
        var level = client.level != null;
        var world = level && client.player != null;
        var screen = client.screen;
        if (level != lastLevel || world != lastWorld || screen != lastScreen) {
            lastLevel = level;
            lastWorld = world;
            lastScreen = screen;
            adapter.refreshClientState();
        }
    }

    private static final class Bridge implements FabricAdapter.ClientBridge {
        private static final int DEFAULT_SCREENSHOT_WIDTH = 1920;
        private static final int DEFAULT_SCREENSHOT_HEIGHT = 1080;
        private static final int MAX_SCREENSHOT_AXIS = 8192;
        private static final long MAX_SCREENSHOT_PIXELS = 16_777_216L;
        private final InputLease inputLease = new InputLease();
        private final Map<String, KeyMapping> directlyOwnedKeys = new LinkedHashMap<>();
        private final FabricWorldAvailability worldAvailability = new FabricWorldAvailability();
        private volatile boolean wasInWorld;
        private Screen tokenScreen;
        private boolean tokenInitialized;
        private String screenToken = UUID.randomUUID().toString();

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

        private void tick(Minecraft client) {
            updateScreenToken(client.screen);
            worldAvailability.update(client.level != null);
            var owned = inputLease.owned();
            var inWorld = client.level != null && client.player != null;
            var disconnected = wasInWorld && !inWorld;
            wasInWorld = inWorld;
            if (owned.isEmpty() && directlyOwnedKeys.isEmpty()) return;
            if (disconnected) {
                releaseEverything(client);
                return;
            }
            if (inWorld) {
                releaseBindings(client, inputLease.releaseExpired(System.nanoTime() / 1_000_000L));
            } else if (!owned.isEmpty()) {
                releaseBindings(client, inputLease.releaseAll());
            }
        }

        @Override
        public boolean available(String capability) {
            return switch (capability) {
                case "minecraft.registry.item.search", "minecraft.server.info.read",
                        "minecraft.input.key.set", "minecraft.input.mouse.set", "minecraft.input.release-all",
                        "minecraft.ui.state.read", "minecraft.client.screenshot.capture" -> true;
                case "minecraft.world.heightmap.read", "minecraft.world.light.analyze" ->
                        worldAvailability.available();
                case "minecraft.player.context.read", "minecraft.entity.nearby.read" -> wasInWorld;
                case "minecraft.ui.key", "minecraft.ui.click", "minecraft.ui.text.insert",
                        "minecraft.inventory.container.read",
                        "minecraft.inventory.container.click" -> Minecraft.getInstance().screen != null;
                default -> {
                    var client = Minecraft.getInstance();
                    yield client.level != null && client.player != null;
                }
            };
        }

        @Override
        public CompletionStage<Map<String, Object>> invoke(String capability, InvocationContext invocation) {
            return onClientThread(() -> {
                invocation.cancellation().throwIfCancelled();
                return switch (capability) {
                    case "minecraft.registry.item.search" -> searchItems(invocation);
                    case "minecraft.server.info.read" -> serverInfo(invocation);
                    case "minecraft.client.screenshot.capture" -> captureScreenshot(invocation);
                    case "minecraft.input.key.set" -> setKey(invocation, false);
                    case "minecraft.input.mouse.set" -> setKey(invocation, true);
                    case "minecraft.input.release-all" -> releaseAll(invocation);
                    case "minecraft.player.state.read" -> playerState();
                    case "minecraft.player.context.read" -> playerContext(invocation);
                    case "minecraft.player.look" -> look(invocation);
                    case "minecraft.player.move" -> move(invocation);
                    case "minecraft.player.interact" -> interact(invocation);
                    case "minecraft.inventory.slot.select" -> selectSlot(invocation);
                    case "minecraft.inventory.container.read" -> containerRead();
                    case "minecraft.inventory.container.click" -> containerClick(invocation);
                    case "minecraft.entity.interact" -> entityInteract(invocation);
                    case "minecraft.entity.nearby.read" -> nearbyEntities(invocation);
                    case "minecraft.world.heightmap.read", "minecraft.world.light.analyze" ->
                            worldAnalysis(capability, invocation);
                    case "minecraft.ui.state.read" -> captureUi().toMap();
                    case "minecraft.ui.click" -> uiClick(invocation);
                    case "minecraft.ui.key" -> uiKey(invocation);
                    case "minecraft.ui.text.insert" -> uiText(invocation);
                    default -> throw new IllegalArgumentException("unsupported client capability: " + capability);
                };
            });
        }

        private static Map<String, Object> captureScreenshot(InvocationContext invocation) throws IOException {
            var client = Minecraft.getInstance();
            var input = invocation.request().input();
            var maxWidth = screenshotAxis(input, "maxWidth", DEFAULT_SCREENSHOT_WIDTH);
            var maxHeight = screenshotAxis(input, "maxHeight", DEFAULT_SCREENSHOT_HEIGHT);
            var player = client.player;
            var playerPosition = player == null ? null : Map.<String, Object>of(
                    "x", player.getX(), "y", player.getY(), "z", player.getZ());
            var playerRotation = player == null ? null : Map.<String, Object>of(
                    "yaw", player.getYRot(), "pitch", player.getXRot());

            try (var original = Screenshot.takeScreenshot(client.getMainRenderTarget())) {
                var originalWidth = original.getWidth();
                var originalHeight = original.getHeight();
                var dimensions = ScreenshotDimensions.fit(originalWidth, originalHeight,
                        maxWidth, maxHeight, MAX_SCREENSHOT_PIXELS);
                byte[] png;
                if (dimensions.width() == originalWidth && dimensions.height() == originalHeight) {
                    png = original.asByteArray();
                } else {
                    try (var resized = new NativeImage(dimensions.width(), dimensions.height(), false)) {
                        original.resizeSubRectTo(0, 0, originalWidth, originalHeight, resized);
                        png = resized.asByteArray();
                    }
                }
                invocation.cancellation().throwIfCancelled();
                var artifact = InvocationAttributes.requireArtifactSink(invocation).stage("image/png", png);
                var result = new LinkedHashMap<String, Object>();
                result.put("artifact", artifact.toMetadata());
                result.put("width", dimensions.width());
                result.put("height", dimensions.height());
                result.put("originalWidth", originalWidth);
                result.put("originalHeight", originalHeight);
                if (playerPosition != null) result.put("playerPosition", playerPosition);
                if (playerRotation != null) result.put("playerRotation", playerRotation);
                return Map.copyOf(result);
            }
        }

        private static int screenshotAxis(Map<String, Object> input, String key, int fallback) {
            var value = input.get(key);
            if (value == null) return fallback;
            if (!(value instanceof Number number)) {
                throw new IllegalArgumentException(
                        key + " must be an integer between 1 and " + MAX_SCREENSHOT_AXIS);
            }
            var parsed = InputNumbers.exactInt(number, key);
            if (parsed < 1 || parsed > MAX_SCREENSHOT_AXIS) {
                throw new IllegalArgumentException(
                        key + " must be an integer between 1 and " + MAX_SCREENSHOT_AXIS);
            }
            return parsed;
        }

        private Map<String, Object> setKey(InvocationContext invocation, boolean mouse) {
            var input = invocation.request().input();
            var key = text(input, "key", mouse && input.get("button") != null
                    ? "key.mouse." + number(input, "button") : null);
            var mapping = findKey(key);
            if (mapping == null) {
                throw new IllegalArgumentException("unknown client key mapping: " + key);
            }
            var down = bool(input, "down", false);
            invocation.cancellation().commitMutation();
            if (down) {
                directlyOwnedKeys.put(mapping.getName(), mapping);
            } else {
                directlyOwnedKeys.remove(mapping.getName());
            }
            mapping.setDown(down || movementOwns(mapping));
            return Map.of("key", mapping.getName(), "down", down, "mouse", mouse);
        }

        private static Map<String, Object> searchItems(InvocationContext invocation) {
            var input = invocation.request().input();
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

        private static Map<String, Object> serverInfo(InvocationContext invocation) {
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
            result.put("loader", "fabric");
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

        private static Map<String, Object> playerContext(InvocationContext invocation) {
            var player = requirePlayer();
            var client = Minecraft.getInstance();
            var reach = finiteNumberOrDefault(invocation.request().input(), "reach", 128.0);
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

        private static Map<String, Object> nearbyEntities(InvocationContext invocation) {
            var client = Minecraft.getInstance();
            var player = requirePlayer();
            var input = invocation.request().input();
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

        private static Map<String, Object> worldAnalysis(String capability, InvocationContext invocation) {
            return switch (FabricWorldAnalysis.operation(capability)) {
                case HEIGHTMAP -> heightmap(invocation);
                case LIGHT -> lightAnalysis(invocation);
            };
        }

        private static Map<String, Object> heightmap(InvocationContext invocation) {
            var level = requireLevel();
            var request = FabricWorldAnalysis.heightmapRequest(invocation.request().input());
            var chunks = loadedChunks(level);
            return FabricWorldAnalysis.heightmap(request, level.dimension().location().toString(),
                    (x, z) -> {
                        var chunk = chunks.get(x, z);
                        if (chunk == null) {
                            return new FabricWorldAnalysis.HeightColumn(false, false, 0, "");
                        }
                        var height = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                        if (height < level.getMinBuildHeight()) {
                            return new FabricWorldAnalysis.HeightColumn(true, true, 0, "");
                        }
                        var surfaceBlock = "";
                        if (request.includeSurfaceBlocks()) {
                            var state = chunk.getBlockState(new BlockPos(x, height, z));
                            surfaceBlock = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                        }
                        return new FabricWorldAnalysis.HeightColumn(true, false, height, surfaceBlock);
                    }, invocation.cancellation()::throwIfCancelled);
        }

        private static Map<String, Object> lightAnalysis(InvocationContext invocation) {
            var level = requireLevel();
            var request = FabricWorldAnalysis.lightRequest(invocation.request().input());
            var chunks = loadedChunks(level);
            var position = new BlockPos.MutableBlockPos();
            return FabricWorldAnalysis.lightAnalysis(request, level.dimension().location().toString(),
                    level.getMinBuildHeight(), level.getMaxBuildHeight(), (x, y, z) -> {
                        var chunk = chunks.get(x, z);
                        if (chunk == null) {
                            return new FabricWorldAnalysis.LightCell(false, false, "", 0, 0, 0);
                        }
                        position.set(x, y, z);
                        var state = chunk.getBlockState(position);
                        // Fabric/Mojang 1.20.1 exposes actual state emission only; no
                        // NeoForge-style level/position contextual overload exists.
                        var emission = state.getLightEmission();
                        var solid = !state.isAir() && state.canOcclude();
                        var block = emission > 0
                                ? BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString() : "";
                        if (solid) {
                            return new FabricWorldAnalysis.LightCell(true, true, block, emission, 0, 0);
                        }
                        return new FabricWorldAnalysis.LightCell(true, false, block, emission,
                                level.getBrightness(LightLayer.BLOCK, position),
                                level.getBrightness(LightLayer.SKY, position));
                    }, invocation.cancellation()::throwIfCancelled);
        }

        private static FabricLoadedChunkCache<LevelChunk> loadedChunks(ClientLevel level) {
            return new FabricLoadedChunkCache<>((chunkX, chunkZ) ->
                    level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false));
        }

        private static Map<String, Object> playerState() {
            var player = requirePlayer();
            return Map.of("uuid", player.getUUID().toString(),
                    "name", player.getGameProfile().getName(),
                    "position", Map.of("x", player.getX(), "y", player.getY(), "z", player.getZ()),
                    "rotation", Map.of("yaw", player.getYRot(), "pitch", player.getXRot()),
                    "dimension", player.level().dimension().location().toString(),
                    "health", player.getHealth(), "food", player.getFoodData().getFoodLevel(),
                    "selectedSlot", player.getInventory().selected);
        }

        private static Map<String, Object> look(InvocationContext invocation) {
            var player = requirePlayer();
            var input = invocation.request().input();
            var yaw = decimal(input, "yaw");
            var pitch = decimal(input, "pitch");
            if (pitch < -90 || pitch > 90 || yaw < -3600 || yaw > 3600) {
                throw new IllegalArgumentException("look rotation is outside safe bounds");
            }
            invocation.cancellation().commitMutation();
            player.setYRot(yaw);
            player.setXRot(pitch);
            player.setYHeadRot(yaw);
            return Map.of("yaw", player.getYRot(), "pitch", player.getXRot());
        }

        private Map<String, Object> move(InvocationContext invocation) {
            var client = Minecraft.getInstance();
            requirePlayer();
            var input = invocation.request().input();
            var forward = decimalOrDefault(input, "forward", 0);
            var strafe = decimalOrDefault(input, "strafe", 0);
            if (forward < -1 || forward > 1 || strafe < -1 || strafe > 1) {
                throw new IllegalArgumentException("movement values must be between -1 and 1");
            }
            var durationMs = numberOrDefault(input, "durationMs", 100);
            if (durationMs < 1 || durationMs > 10_000) {
                throw new IllegalArgumentException("durationMs must be between 1 and 10000");
            }
            var desired = new LinkedHashSet<String>();
            if (forward > 0) desired.add("forward");
            if (forward < 0) desired.add("back");
            if (strafe > 0) desired.add("right");
            if (strafe < 0) desired.add("left");
            if (bool(input, "jump", false)) desired.add("jump");
            if (bool(input, "sprint", false)) desired.add("sprint");
            if (bool(input, "sneak", false)) desired.add("sneak");
            var previouslyOwned = inputLease.owned();
            var affected = new LinkedHashSet<>(previouslyOwned);
            affected.addAll(desired);
            invocation.cancellation().commitMutation();
            var leaseGeneration = inputLease.replace(desired, System.nanoTime() / 1_000_000L, durationMs);
            for (var binding : affected) {
                var mapping = movementMapping(client, binding);
                mapping.setDown(desired.contains(binding) || directlyOwnedKeys.containsKey(mapping.getName()));
            }
            var result = new LinkedHashMap<String, Object>();
            result.put("forward", forward);
            result.put("strafe", strafe);
            result.put("jump", desired.contains("jump"));
            result.put("sprint", desired.contains("sprint"));
            result.put("sneak", desired.contains("sneak"));
            result.put("durationMs", durationMs);
            result.put("leaseGeneration", leaseGeneration);
            return Map.copyOf(result);
        }

        private Map<String, Object> releaseAll(InvocationContext invocation) {
            var client = Minecraft.getInstance();
            invocation.cancellation().commitMutation();
            var released = releaseEverything(client);
            return Map.of("released", released.stream().sorted().toList(), "count", released.size(),
                    "leaseGeneration", inputLease.generation());
        }

        private Set<String> releaseEverything(Minecraft client) {
            var releasedBindings = inputLease.releaseAll();
            var released = new LinkedHashSet<>(releasedBindings);
            released.addAll(directlyOwnedKeys.keySet());
            var directMappings = List.copyOf(directlyOwnedKeys.values());
            directlyOwnedKeys.clear();
            for (var binding : releasedBindings) movementMapping(client, binding).setDown(false);
            for (var mapping : directMappings) mapping.setDown(false);
            return Set.copyOf(released);
        }

        private void releaseBindings(Minecraft client, Set<String> bindings) {
            for (var binding : bindings) {
                var mapping = movementMapping(client, binding);
                if (!directlyOwnedKeys.containsKey(mapping.getName())) mapping.setDown(false);
            }
        }

        private boolean movementOwns(KeyMapping mapping) {
            var client = Minecraft.getInstance();
            for (var binding : inputLease.owned()) {
                if (movementMapping(client, binding) == mapping) return true;
            }
            return false;
        }

        private static KeyMapping movementMapping(Minecraft client, String binding) {
            return switch (binding) {
                case "forward" -> client.options.keyUp;
                case "back" -> client.options.keyDown;
                case "right" -> client.options.keyRight;
                case "left" -> client.options.keyLeft;
                case "jump" -> client.options.keyJump;
                case "sprint" -> client.options.keySprint;
                case "sneak" -> client.options.keyShift;
                default -> throw new IllegalArgumentException("unknown owned movement binding: " + binding);
            };
        }

        private static Map<String, Object> interact(InvocationContext invocation) {
            var client = Minecraft.getInstance();
            requirePlayer();
            var action = text(invocation.request().input(), "action", "use");
            var mapping = switch (action) {
                case "use" -> client.options.keyUse;
                case "attack" -> client.options.keyAttack;
                case "pick" -> client.options.keyPickItem;
                default -> throw new IllegalArgumentException("action must be use, attack, or pick");
            };
            invocation.cancellation().commitMutation();
            KeyMapping.click(mapping.getDefaultKey());
            return Map.of("action", action, "queued", true);
        }

        private static Map<String, Object> selectSlot(InvocationContext invocation) {
            var slot = number(invocation.request().input(), "slot");
            if (slot < 0 || slot > 8) {
                throw new IllegalArgumentException("slot must be between 0 and 8");
            }
            var player = requirePlayer();
            invocation.cancellation().commitMutation();
            player.getInventory().selected = slot;
            return Map.of("selectedSlot", slot);
        }

        private static Map<String, Object> containerRead() {
            var client = Minecraft.getInstance();
            requirePlayer();
            if (!(client.screen instanceof AbstractContainerScreen<?> screen)) {
                throw new IllegalStateException("no active container screen is available");
            }
            var menu = screen.getMenu();
            var slots = new ArrayList<Map<String, Object>>(menu.slots.size());
            for (var index = 0; index < menu.slots.size(); index++) {
                var stack = menu.slots.get(index).getItem();
                slots.add(Map.of("slot", index, "item", itemId(stack),
                        "count", stack.getCount(), "maxCount", stack.getMaxStackSize(), "empty", stack.isEmpty()));
            }
            return Map.of("open", true, "containerId", menu.containerId,
                    "revision", menu.getStateId(), "slots", slots);
        }

        private static Map<String, Object> containerClick(InvocationContext invocation) {
            var client = Minecraft.getInstance();
            var player = requirePlayer();
            if (!(client.screen instanceof AbstractContainerScreen<?> screen) || client.gameMode == null) {
                throw new IllegalStateException("no active container screen is available");
            }
            var input = invocation.request().input();
            var slot = number(input, "slot");
            var button = numberOrDefault(input, "button", 0);
            var revision = number(input, "revision");
            var currentRevision = screen.getMenu().getStateId();
            if (revision != currentRevision) {
                throw new IllegalStateException("container revision is stale; expected " + currentRevision + " but received " + revision);
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

        private static Map<String, Object> entityInteract(InvocationContext invocation) {
            var client = Minecraft.getInstance();
            var player = requirePlayer();
            if (client.level == null || client.gameMode == null) {
                throw new IllegalStateException("client world or game mode is unavailable");
            }
            var entity = client.level.getEntity(number(invocation.request().input(), "entityId"));
            if (entity == null) {
                throw new IllegalArgumentException("entity is not present in the client world");
            }
            var hand = InteractionHand.valueOf(text(invocation.request().input(), "hand", "MAIN_HAND")
                    .toUpperCase(java.util.Locale.ROOT));
            invocation.cancellation().commitMutation();
            var result = client.gameMode.interact(player, entity, hand);
            return Map.of("entityId", entity.getId(), "result", result.toString());
        }

        private UiSnapshot captureUi() {
            var client = Minecraft.getInstance();
            var screen = client.screen;
            updateScreenToken(screen);
            var inWorld = client.level != null && client.player != null;
            var capturedAtTick = client.level == null ? 0L : client.level.getGameTime();
            var width = screen == null ? client.getWindow().getGuiScaledWidth() : screen.width;
            var height = screen == null ? client.getWindow().getGuiScaledHeight() : screen.height;
            var screenClass = screen == null ? "" : screen.getClass().getName();
            var screenName = screen == null ? "" : simpleName(screen.getClass());
            var title = screen == null ? "" : screen.getTitle().getString();
            var nodes = new ArrayList<UiNode>();
            var causes = new LinkedHashSet<String>();
            var opaque = false;
            if (screen != null) {
                var seen = Collections.newSetFromMap(new IdentityHashMap<GuiEventListener, Boolean>());
                seen.add(screen);
                try {
                    captureChildren(screen, List.of(), 0, nodes, causes, seen);
                } catch (RuntimeException failure) {
                    causes.add("unsupported-widget");
                }
                if (nodes.isEmpty()) {
                    causes.add("opaque-screen");
                    opaque = true;
                }
            }
            var truncated = !causes.isEmpty();
            var coverage = opaque ? "opaque" : truncated ? "partial" : "complete";
            var truncationCauses = List.copyOf(causes);
            var revision = UiContracts.revision(screenToken, screenClass, title, width, height,
                    nodes, coverage, truncated, truncationCauses);
            return new UiSnapshot(screen, screenToken, revision, capturedAtTick, inWorld,
                    screenName, screenClass, title, width, height, client.getWindow().getGuiScale(),
                    coverage, truncated, truncationCauses, List.copyOf(nodes));
        }

        private static void captureChildren(ContainerEventHandler container, List<Integer> parentPath, int childDepth,
                                            List<UiNode> nodes, Set<String> causes,
                                            Set<GuiEventListener> seen) {
            List<? extends GuiEventListener> children;
            try {
                children = List.copyOf(container.children());
            } catch (RuntimeException failure) {
                causes.add("unsupported-widget");
                return;
            }
            if (children.isEmpty()) return;
            if (childDepth > UiLimits.DEFAULT.maxDepth()) {
                causes.add("depth-limit");
                return;
            }
            var childCount = Math.min(children.size(), UiLimits.DEFAULT.maxChildren());
            if (children.size() > childCount) causes.add("child-limit");
            for (var index = 0; index < childCount; index++) {
                if (nodes.size() >= UiLimits.DEFAULT.maxNodes()) {
                    causes.add("node-limit");
                    return;
                }
                var child = children.get(index);
                if (child == null) {
                    causes.add("unsupported-widget");
                    continue;
                }
                if (!seen.add(child)) {
                    causes.add("unsupported-widget");
                    continue;
                }
                var path = new ArrayList<Integer>(parentPath.size() + 1);
                path.addAll(parentPath);
                path.add(index);
                nodes.add(projectNode(child, container, path, childDepth, causes));
                if (child instanceof ContainerEventHandler nested) {
                    captureChildren(nested, path, childDepth + 1, nodes, causes, seen);
                }
            }
        }

        private static UiNode projectNode(GuiEventListener child, ContainerEventHandler parent,
                                          List<Integer> path, int depth, Set<String> causes) {
            UiBounds bounds = null;
            Boolean active = null;
            Boolean visible = null;
            String label = null;
            var actions = new LinkedHashSet<String>();
            Integer textLength = null;
            Boolean textPresent = null;
            if (child instanceof AbstractWidget widget) {
                bounds = new UiBounds(widget.getX(), widget.getY(), widget.getWidth(), widget.getHeight());
                active = widget.active;
                visible = widget.visible;
                label = safeWidgetLabel(widget, causes);
                actions.add("click");
            } else {
                causes.add("unsupported-widget");
            }
            if (child instanceof EditBox editBox) {
                textLength = editBox.getValue().length();
                textPresent = !editBox.getValue().isEmpty();
                actions.add("text-insert");
            }
            return new UiNode(nodeId(path), path, depth, boundedType(child.getClass(), causes),
                    boundedType(parent.getClass(), causes), label, null, bounds, child.isFocused(),
                    active, visible, actions, textLength, textPresent);
        }

        private static String safeWidgetLabel(AbstractWidget widget, Set<String> causes) {
            var label = widget.getMessage().getString();
            if (widget instanceof EditBox editBox && !editBox.getValue().isEmpty()
                    && label.contains(editBox.getValue())) {
                return "";
            }
            if (label.length() <= 4096) return label;
            causes.add("unsupported-widget");
            return label.substring(0, 4096);
        }

        private static String boundedType(Class<?> type, Set<String> causes) {
            var name = type.getName();
            if (name.length() <= 256) return name;
            causes.add("unsupported-widget");
            return name.substring(0, 256);
        }

        private Map<String, Object> uiClick(InvocationContext invocation) {
            var input = invocation.request().input();
            var expectedToken = requiredText(input, "screenToken");
            var expectedRevision = requiredText(input, "snapshotRevision");
            var selector = UiSelector.from(input);
            var button = numberOrDefault(input, "button", 0);
            if (button < 0 || button > 8) {
                throw new IllegalArgumentException("button must be between 0 and 8");
            }
            var snapshot = captureUi();
            if (!snapshot.screenToken().equals(expectedToken)) {
                throw new IllegalStateException("UI screen token is stale");
            }
            if (!snapshot.snapshotRevision().equals(expectedRevision)) {
                throw new IllegalStateException("UI snapshot revision is stale");
            }
            if (snapshot.nativeScreen() == null) {
                throw new IllegalStateException("no screen is open");
            }
            UiNode node = null;
            double x;
            double y;
            if (selector.x() != null) {
                x = selector.x();
                y = selector.y();
                requireInScreen(snapshot, x, y);
                node = uniqueProjectedHit(snapshot.widgets(), x, y);
            } else {
                node = selector.resolve(snapshot.widgets());
                x = node.bounds().centerX();
                y = node.bounds().centerY();
            }
            invocation.cancellation().commitMutation();
            var handled = snapshot.nativeScreen().mouseClicked(x, y, button);
            var result = new LinkedHashMap<String, Object>();
            result.put("handled", handled);
            result.put("x", x);
            result.put("y", y);
            result.put("screenToken", snapshot.screenToken());
            result.put("snapshotRevision", snapshot.snapshotRevision());
            if (node != null) result.put("nodeId", node.nodeId());
            return Map.copyOf(result);
        }

        private static void requireInScreen(UiSnapshot snapshot, double x, double y) {
            if (x < 0 || y < 0 || x >= snapshot.width() || y >= snapshot.height()) {
                throw new IllegalArgumentException("UI coordinates are outside the active screen");
            }
        }

        private static UiNode uniqueProjectedHit(List<UiNode> nodes, double x, double y) {
            var matches = nodes.stream().filter(node -> contains(node.bounds(), x, y)).toList();
            return matches.size() == 1 ? matches.get(0) : null;
        }

        private static boolean contains(UiBounds bounds, double x, double y) {
            return bounds != null && bounds.width() > 0 && bounds.height() > 0
                    && x >= bounds.x() && y >= bounds.y()
                    && x < bounds.x() + bounds.width() && y < bounds.y() + bounds.height();
        }

        private void updateScreenToken(Screen screen) {
            if (!tokenInitialized || screen != tokenScreen) {
                tokenInitialized = true;
                tokenScreen = screen;
                screenToken = UUID.randomUUID().toString();
            }
        }

        private static String nodeId(List<Integer> path) {
            return "widget:" + path.stream().map(String::valueOf)
                    .collect(java.util.stream.Collectors.joining("."));
        }

        private static String simpleName(Class<?> type) {
            return type.getSimpleName().isEmpty() ? type.getName() : type.getSimpleName();
        }

        private static Map<String, Object> uiKey(InvocationContext invocation) {
            var screen = Minecraft.getInstance().screen;
            if (screen == null) {
                throw new IllegalStateException("no screen is open");
            }
            var input = invocation.request().input();
            invocation.cancellation().commitMutation();
            var handled = screen.keyPressed(number(input, "key"), numberOrDefault(input, "scanCode", 0),
                    numberOrDefault(input, "modifiers", 0));
            return Map.of("handled", handled);
        }

        private static Map<String, Object> uiText(InvocationContext invocation) {
            var screen = requireScreen();
            var value = text(invocation.request().input(), "text", null);
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

        private static String safeText(String value) {
            if (value == null || value.isEmpty()) {
                return "";
            }
            return value.length() <= 512 ? value : value.substring(0, 512);
        }

        private static Screen requireScreen() {
            var screen = Minecraft.getInstance().screen;
            if (screen == null) {
                throw new IllegalStateException("no screen is open");
            }
            return screen;
        }

        private static String itemId(ItemStack stack) {
            return stack.isEmpty() ? "minecraft:air" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        }

        private static LocalPlayer requirePlayer() {
            var client = Minecraft.getInstance();
            if (client.player == null || client.level == null) {
                throw new IllegalStateException("client player/world is unavailable");
            }
            return client.player;
        }

        private static ClientLevel requireLevel() {
            var level = Minecraft.getInstance().level;
            if (level == null) {
                throw new IllegalStateException("client world is unavailable");
            }
            return level;
        }

        private static KeyMapping findKey(String name) {
            var options = Minecraft.getInstance().options;
            if ("key.mouse.0".equals(name) || "mouse.left".equals(name)) return options.keyAttack;
            if ("key.mouse.1".equals(name) || "mouse.right".equals(name)) return options.keyUse;
            if ("key.mouse.2".equals(name) || "mouse.middle".equals(name)) return options.keyPickItem;
            for (var mapping : options.keyMappings) {
                if (mapping.getName().equals(name)) return mapping;
            }
            return null;
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

        private record UiSnapshot(Screen nativeScreen, String screenToken, String snapshotRevision,
                                  long capturedAtTick, boolean inWorld, String screen, String screenClass,
                                  String title, int width, int height, double guiScale, String coverage,
                                  boolean truncated, List<String> truncationCauses, List<UiNode> widgets) {
            private Map<String, Object> toMap() {
                var result = new LinkedHashMap<String, Object>();
                result.put("open", nativeScreen != null);
                result.put("screenToken", screenToken);
                result.put("snapshotRevision", snapshotRevision);
                result.put("capturedAtTick", capturedAtTick);
                result.put("inWorld", inWorld);
                result.put("screen", screen);
                result.put("screenClass", screenClass);
                result.put("title", title);
                result.put("width", width);
                result.put("height", height);
                result.put("guiScale", guiScale);
                result.put("coverage", coverage);
                result.put("truncated", truncated);
                result.put("truncationCauses", truncationCauses);
                result.put("widgets", widgets.stream().map(UiNode::toMap).toList());
                return Map.copyOf(result);
            }
        }

        private static String text(Map<String, Object> input, String key, String fallback) {
            var value = input.get(key);
            if (value == null && fallback != null) return fallback;
            if (!(value instanceof String string) || string.isBlank()) {
                throw new IllegalArgumentException("input field must be a non-empty string: " + key);
            }
            return string;
        }

        private static String requiredText(Map<String, Object> input, String key) {
            return text(input, key, null);
        }

        private static int number(Map<String, Object> input, String key) {
            var value = input.get(key);
            if (!(value instanceof Number number)) throw new IllegalArgumentException("input field must be numeric: " + key);
            return InputNumbers.exactInt(number, key);
        }

        private static int numberOrDefault(Map<String, Object> input, String key, int fallback) {
            return input.get(key) == null ? fallback : number(input, key);
        }

        private static float decimal(Map<String, Object> input, String key) {
            var value = input.get(key);
            if (!(value instanceof Number number)) throw new IllegalArgumentException("input field must be numeric: " + key);
            return number.floatValue();
        }

        private static float decimalOrDefault(Map<String, Object> input, String key, float fallback) {
            return input.get(key) == null ? fallback : decimal(input, key);
        }

        private static double finiteNumberOrDefault(Map<String, Object> input, String key, double fallback) {
            var value = input.get(key);
            if (value == null) {
                return fallback;
            }
            if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
                throw new IllegalArgumentException("input field must be a finite number: " + key);
            }
            return number.doubleValue();
        }

        private static boolean bool(Map<String, Object> input, String key, boolean fallback) {
            var value = input.get(key);
            if (value == null) return fallback;
            if (!(value instanceof Boolean booleanValue)) throw new IllegalArgumentException("input field must be boolean: " + key);
            return booleanValue;
        }
    }
}
