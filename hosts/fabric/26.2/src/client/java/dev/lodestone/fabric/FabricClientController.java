// SPDX-License-Identifier: MIT
package dev.lodestone.fabric;

import com.mojang.blaze3d.platform.NativeImage;
import dev.lodestone.adapter.InvocationContext;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ForkJoinPool;

/** Client-only bridge for Fabric 26.2 read primitives. */
public final class FabricClientController implements ClientModInitializer {
    private static final Bridge BRIDGE = new Bridge();
    private static boolean attached;
    private static boolean lastWorld;

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
        var world = client.level != null && client.player != null;
        if (world != lastWorld) {
            lastWorld = world;
            adapter.refreshClientState();
        }
    }

    private static final class Bridge implements FabricAdapter.ClientBridge {
        private volatile boolean wasInWorld;

        private void tick(Minecraft client) {
            wasInWorld = client.level != null && client.player != null;
        }

        @Override
        public boolean available(String capability) {
            return switch (capability) {
                case "minecraft.registry.item.search", "minecraft.server.info.read",
                        "minecraft.client.screenshot.capture" -> true;
                case "minecraft.player.context.read", "minecraft.entity.nearby.read" -> wasInWorld;
                default -> false;
            };
        }

        @Override
        public CompletionStage<Map<String, Object>> invoke(String capability, InvocationContext invocation) {
            if ("minecraft.client.screenshot.capture".equals(capability)) {
                return captureScreenshot(invocation);
            }
            return onClientThread(() -> {
                invocation.cancellation().throwIfCancelled();
                return switch (capability) {
                    case "minecraft.registry.item.search" -> searchItems(invocation);
                    case "minecraft.server.info.read" -> serverInfo(invocation);
                    case "minecraft.player.context.read" -> playerContext(invocation);
                    case "minecraft.entity.nearby.read" -> nearbyEntities(invocation);
                    default -> throw new IllegalArgumentException("unsupported client capability: " + capability);
                };
            });
        }

        private static CompletionStage<Map<String, Object>> captureScreenshot(InvocationContext invocation) {
            var result = new CompletableFuture<Map<String, Object>>();
            var client = Minecraft.getInstance();
            client.execute(() -> {
                try {
                    invocation.cancellation().throwIfCancelled();
                    var pose = pose(client.player);
                    Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
                        if (result.isDone()) {
                            image.close();
                            return;
                        }
                        FabricScreenshotSupport.capture(invocation,
                                        new NativeCapturedImage(image), pose, ForkJoinPool.commonPool())
                                .whenComplete((output, failure) -> complete(result, output, failure));
                    });
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            });
            return result;
        }

        private static FabricScreenshotSupport.Pose pose(LocalPlayer player) {
            return player == null ? null : new FabricScreenshotSupport.Pose(
                    player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
        }

        private static void complete(CompletableFuture<Map<String, Object>> target,
                                     Map<String, Object> output, Throwable failure) {
            if (failure == null) {
                target.complete(output);
                return;
            }
            var cause = failure instanceof CompletionException completion && completion.getCause() != null
                    ? completion.getCause() : failure;
            target.completeExceptionally(cause);
        }

        private record NativeCapturedImage(NativeImage image)
                implements FabricScreenshotSupport.CapturedImage {
            private NativeCapturedImage {
                if (image == null) throw new IllegalArgumentException("captured image is required");
            }

            @Override public int width() { return image.getWidth(); }
            @Override public int height() { return image.getHeight(); }
            @Override public int[] getPixels() { return image.getPixels(); }
            @Override public void close() { image.close(); }
        }

        private static Map<String, Object> searchItems(InvocationContext invocation) {
            var input = invocation.request().input();
            var query = FabricReadPrimitiveSupport.requiredSchemaText(input, "query", 256);
            var normalizedQuery = query.toLowerCase(Locale.ROOT);
            var limit = FabricReadPrimitiveSupport.boundedInt(input, "limit", 20, 1, 50);
            var namespace = FabricReadPrimitiveSupport.optionalSchemaText(input, "namespace", 64);
            if (namespace != null && !namespace.matches("[a-z0-9_.-]{1,64}")) {
                throw new IllegalArgumentException("namespace is not a valid resource namespace");
            }

            var matches = new ArrayList<RegistryItem>();
            var scanned = 0;
            for (var item : BuiltInRegistries.ITEM) {
                if ((scanned++ & 63) == 0) {
                    invocation.cancellation().throwIfCancelled();
                }
                var key = BuiltInRegistries.ITEM.getKey(item);
                if (key == null || namespace != null && !namespace.equals(key.getNamespace())) {
                    continue;
                }
                var id = key.toString();
                var translationKey = item.getDescriptionId();
                var stack = item.getDefaultInstance();
                var displayName = stack.getHoverName().getString();
                if (!id.toLowerCase(Locale.ROOT).contains(normalizedQuery)
                        && !translationKey.toLowerCase(Locale.ROOT).contains(normalizedQuery)
                        && !displayName.toLowerCase(Locale.ROOT).contains(normalizedQuery)) {
                    continue;
                }
                matches.add(new RegistryItem(
                        FabricReadPrimitiveSupport.boundedText(id, 256),
                        FabricReadPrimitiveSupport.boundedText(translationKey, 512),
                        FabricReadPrimitiveSupport.boundedText(displayName, 4096),
                        stack.getMaxStackSize(),
                        item instanceof BlockItem));
            }

            var bounded = FabricReadPrimitiveSupport.sortedBounded(
                    matches, Comparator.comparing(RegistryItem::id), limit);
            var items = bounded.values().stream().map(RegistryItem::toMap).toList();
            var result = new LinkedHashMap<String, Object>();
            result.put("query", query);
            result.put("limit", limit);
            result.put("count", items.size());
            result.put("truncated", bounded.truncated());
            result.put("items", items);
            return Map.copyOf(result);
        }

        private static Map<String, Object> serverInfo(InvocationContext invocation) {
            invocation.cancellation().throwIfCancelled();
            var client = Minecraft.getInstance();
            var connection = client.getConnection();
            var connected = connection != null;
            var integratedServer = client.hasSingleplayerServer();
            var localUuid = client.player == null ? null : client.player.getUUID();
            var players = new ArrayList<PlayerSummary>();
            if (connection != null) {
                for (var playerInfo : connection.getOnlinePlayers()) {
                    var profile = playerInfo.getProfile();
                    if (profile.id() == null || profile.name() == null || profile.name().isBlank()) {
                        continue;
                    }
                    players.add(new PlayerSummary(
                            profile.id().toString(),
                            FabricReadPrimitiveSupport.boundedText(profile.name(), 256),
                            profile.id().equals(localUuid)));
                }
            }
            var boundedPlayers = FabricReadPrimitiveSupport.sortedBounded(
                    players,
                    Comparator.comparing(PlayerSummary::uuid).thenComparing(PlayerSummary::name),
                    256);
            var level = client.level;
            var result = new LinkedHashMap<String, Object>();
            result.put("gameVersion", FabricReadPrimitiveSupport.boundedText(
                    SharedConstants.getCurrentVersion().name(), 128));
            result.put("loader", "fabric");
            result.put("environment", integratedServer ? "integrated-server" : connected ? "remote" : "client");
            result.put("connected", connected);
            result.put("integratedServer", integratedServer);
            result.put("dimension", level == null ? "" : FabricReadPrimitiveSupport.boundedText(
                    level.dimension().identifier().toString(), 256));
            result.put("gameTime", level == null ? 0L : Math.max(0L, level.getGameTime()));
            result.put("dayTime", level == null ? 0L : Math.max(0L, level.getDefaultClockTime()));
            result.put("difficulty", level == null ? "" : FabricReadPrimitiveSupport.boundedText(
                    level.getDifficulty().getSerializedName(), 128));
            result.put("playerCount", players.size());
            result.put("truncated", boundedPlayers.truncated());
            result.put("players", boundedPlayers.values().stream().map(PlayerSummary::toMap).toList());
            return Map.copyOf(result);
        }

        private static Map<String, Object> playerContext(InvocationContext invocation) {
            var client = Minecraft.getInstance();
            var player = requirePlayer();
            var level = client.level;
            var reach = FabricReadPrimitiveSupport.boundedDouble(
                    invocation.request().input(), "reach", 128.0, 1.0, 256.0);
            var position = player.position();
            var blockPosition = player.blockPosition();
            var eyePosition = player.getEyePosition();
            var lookVector = player.getViewVector(1.0F);
            var heldItem = player.getMainHandItem();
            var playerMode = client.gameMode == null ? null : client.gameMode.getPlayerMode();

            var result = new LinkedHashMap<String, Object>();
            result.put("position", position(position));
            result.put("blockPosition", position(blockPosition));
            result.put("rotation", Map.of("yaw", player.getYRot(), "pitch", player.getXRot()));
            result.put("facing", player.getDirection().getName());
            result.put("eyePosition", position(eyePosition));
            result.put("lookVector", position(lookVector));
            result.put("heldItem", Map.of(
                    "id", FabricReadPrimitiveSupport.boundedText(itemId(heldItem), 256),
                    "count", heldItem.getCount()));
            result.put("gameMode", playerMode == null ? "unknown" : playerMode.getName());
            result.put("flying", player.getAbilities().flying);
            result.put("onGround", player.onGround());
            result.put("dimension", FabricReadPrimitiveSupport.boundedText(
                    level.dimension().identifier().toString(), 256));
            result.put("target", blockTarget(player, reach));
            return Map.copyOf(result);
        }

        private static Map<String, Object> nearbyEntities(InvocationContext invocation) {
            var input = invocation.request().input();
            var player = requirePlayer();
            var level = Minecraft.getInstance().level;
            var radius = FabricReadPrimitiveSupport.boundedDouble(input, "radius", 32.0, 1.0, 256.0);
            var limit = FabricReadPrimitiveSupport.boundedInt(input, "limit", 64, 1, 256);
            var requestedType = FabricReadPrimitiveSupport.optionalSchemaText(input, "type", 256);
            String normalizedType = requestedType == null ? null : requestedType.toLowerCase(Locale.ROOT);
            var includePlayers = bool(input, "includePlayers", true);
            var radiusSquared = radius * radius;
            var matches = new ArrayList<NearbyEntity>();
            var scanned = 0;

            // The client tracker view never requests or acquires chunks.
            for (Entity entity : level.entitiesForRendering()) {
                if ((scanned++ & 63) == 0) {
                    invocation.cancellation().throwIfCancelled();
                }
                if (entity == player) {
                    continue;
                }
                var isPlayer = entity instanceof Player;
                if (isPlayer && !includePlayers) {
                    continue;
                }
                var typeKey = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
                if (typeKey == null) {
                    continue;
                }
                var type = typeKey.toString();
                if (normalizedType != null
                        && !type.equalsIgnoreCase(normalizedType)
                        && (normalizedType.indexOf(':') >= 0
                        || !typeKey.getPath().equalsIgnoreCase(normalizedType))) {
                    continue;
                }
                var distanceSquared = player.distanceToSqr(entity);
                if (!Double.isFinite(distanceSquared) || distanceSquared > radiusSquared) {
                    continue;
                }
                matches.add(new NearbyEntity(
                        entity.getId(),
                        entity.getUUID().toString(),
                        FabricReadPrimitiveSupport.boundedText(type, 256),
                        FabricReadPrimitiveSupport.boundedText(entity.getName().getString(), 4096),
                        Math.sqrt(Math.max(0.0, distanceSquared)),
                        entity.position(),
                        isPlayer));
            }

            var bounded = FabricReadPrimitiveSupport.sortedBounded(
                    matches,
                    Comparator.comparingDouble(NearbyEntity::distance)
                            .thenComparingInt(NearbyEntity::entityId)
                            .thenComparing(NearbyEntity::uuid),
                    limit);
            var result = new LinkedHashMap<String, Object>();
            result.put("dimension", FabricReadPrimitiveSupport.boundedText(
                    level.dimension().identifier().toString(), 256));
            result.put("radius", radius);
            result.put("limit", limit);
            result.put("truncated", bounded.truncated());
            result.put("entities", bounded.values().stream().map(NearbyEntity::toMap).toList());
            return Map.copyOf(result);
        }

        private static Map<String, Object> blockTarget(LocalPlayer player, double reach) {
            var start = player.getEyePosition();
            var end = start.add(player.getViewVector(1.0F).scale(reach));
            var hit = player.level().clip(new ClipContext(
                    start, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
            var target = new LinkedHashMap<String, Object>();
            target.put("reach", reach);
            if (hit.getType() != HitResult.Type.BLOCK) {
                target.put("kind", "miss");
                target.put("distance", reach);
                target.put("position", position(end));
                return Map.copyOf(target);
            }

            var hitPosition = hit.getBlockPos();
            var state = player.level().getBlockState(hitPosition);
            target.put("kind", "block");
            target.put("distance", Math.max(0.0, start.distanceTo(hit.getLocation())));
            target.put("position", position(hit.getLocation()));
            target.put("block", FabricReadPrimitiveSupport.boundedText(
                    BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), 256));
            target.put("blockPosition", position(hitPosition));
            target.put("face", hit.getDirection().getName());
            target.put("adjacentPosition", position(hitPosition.relative(hit.getDirection())));
            target.put("state", FabricReadPrimitiveSupport.boundedText(state.toString(), 4096));
            return Map.copyOf(target);
        }

        private static Map<String, Object> position(Vec3 position) {
            return Map.of("x", position.x, "y", position.y, "z", position.z);
        }

        private static Map<String, Object> position(BlockPos position) {
            return Map.of("x", position.getX(), "y", position.getY(), "z", position.getZ());
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

        private static CompletionStage<Map<String, Object>> onClientThread(
                Callable<Map<String, Object>> operation) {
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

        private record RegistryItem(String id, String translationKey, String displayName,
                                    int maxStackSize, boolean blockItem) {
            private Map<String, Object> toMap() {
                return Map.of(
                        "id", id,
                        "translationKey", translationKey,
                        "displayName", displayName,
                        "maxStackSize", maxStackSize,
                        "blockItem", blockItem);
            }
        }

        private record PlayerSummary(String uuid, String name, boolean local) {
            private Map<String, Object> toMap() {
                return Map.of("uuid", uuid, "name", name, "local", local);
            }
        }

        private record NearbyEntity(int entityId, String uuid, String type, String name,
                                    double distance, Vec3 position, boolean player) {
            private Map<String, Object> toMap() {
                return Map.of(
                        "entityId", entityId,
                        "uuid", uuid,
                        "type", type,
                        "name", name,
                        "distance", distance,
                        "position", Bridge.position(position),
                        "player", player);
            }
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
    }
}
