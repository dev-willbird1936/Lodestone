// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import dev.lodestone.adapter.AdapterContext;
import dev.lodestone.adapter.AdapterHealth;
import dev.lodestone.adapter.CapabilityHandler;
import dev.lodestone.adapter.CancellationToken;
import dev.lodestone.adapter.InvocationContext;
import dev.lodestone.adapter.InputNumbers;
import dev.lodestone.adapter.LodestoneAdapter;
import dev.lodestone.protocol.AdapterDescriptor;
import dev.lodestone.protocol.Availability;
import dev.lodestone.protocol.AvailabilityReason;
import dev.lodestone.protocol.CapabilityDescriptor;
import dev.lodestone.protocol.CapabilityManifest;
import dev.lodestone.protocol.Environment;
import dev.lodestone.protocol.JsonSupport;
import dev.lodestone.runtime.CoreCatalog;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

public final class NeoForgeAdapter implements LodestoneAdapter {
    public static final String ADAPTER_ID = "lodestone.neoforge.mc1_21_1";
    private static final Set<String> IMPLEMENTED = Set.of(
            "minecraft.command.discover",
            "minecraft.command.execute",
            "minecraft.registry.item.search",
            "minecraft.server.info.read",
            "minecraft.client.screenshot.capture",
            "minecraft.player.state.read",
            "minecraft.player.context.read",
            "minecraft.world.block.read",
            "minecraft.world.blocks.read",
            "minecraft.world.region.scan",
            "minecraft.world.heightmap.read",
            "minecraft.world.light.analyze",
            "minecraft.world.blocks.write",
            "minecraft.entity.list",
            "minecraft.entity.nearby.read",
            "minecraft.inventory.read",
            "minecraft.inventory.container.read",
            "minecraft.chat.send",
            "minecraft.input.key.set",
            "minecraft.input.mouse.set",
            "minecraft.input.release-all",
            "minecraft.player.look",
            "minecraft.player.move",
            "minecraft.player.interact",
            "minecraft.inventory.slot.select",
            "minecraft.inventory.container.click",
            "minecraft.entity.interact",
            "minecraft.ui.state.read",
            "minecraft.ui.click",
            "minecraft.ui.key",
            "minecraft.ui.text.insert",
            "minecraft.chat.read",
            "minecraft.goal.survival.wooden-axe-tree");
    private static final Set<String> CLIENT_CAPABILITIES = Set.of(
            "minecraft.registry.item.search", "minecraft.server.info.read",
            "minecraft.client.screenshot.capture",
            "minecraft.player.context.read", "minecraft.entity.nearby.read",
            "minecraft.world.heightmap.read", "minecraft.world.light.analyze",
            "minecraft.input.key.set", "minecraft.input.mouse.set", "minecraft.input.release-all",
            "minecraft.player.look", "minecraft.player.move", "minecraft.player.interact",
            "minecraft.inventory.slot.select", "minecraft.inventory.container.read", "minecraft.inventory.container.click",
            "minecraft.entity.interact", "minecraft.ui.state.read", "minecraft.ui.click",
            "minecraft.ui.key", "minecraft.ui.text.insert", "minecraft.chat.read",
            "minecraft.goal.survival.wooden-axe-tree");
    private static volatile NeoForgeAdapter active;

    private final AdapterDescriptor descriptor = new AdapterDescriptor(
            ADAPTER_ID, "0.1.0", "minecraft-java", "1.21.1", "neoforge", environment());
    private volatile AdapterContext context;
    private volatile MinecraftServer server;
    private volatile boolean serverReady;
    private volatile ClientBridge clientBridge;
    private volatile Runnable refreshHook = () -> { };

    public NeoForgeAdapter() {
        active = this;
    }

    static NeoForgeAdapter active() {
        return active;
    }

    @Override
    public AdapterDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public CapabilityManifest manifest() {
        var capabilities = CoreCatalog.load().stream()
                .filter(capability -> capability.id().startsWith("minecraft.")
                        && !capability.id().startsWith("minecraft.event."))
                .map(this::adapt)
                .toList();
        return new CapabilityManifest(descriptor, capabilities);
    }

    @Override
    public Map<String, CapabilityHandler> handlers() {
        var handlers = new LinkedHashMap<String, CapabilityHandler>();
        handlers.put("minecraft.command.discover", this::discoverCommands);
        handlers.put("minecraft.command.execute", this::executeCommand);
        handlers.put("minecraft.registry.item.search", this::clientCall);
        handlers.put("minecraft.server.info.read", this::clientCall);
        handlers.put("minecraft.client.screenshot.capture", this::clientCall);
        handlers.put("minecraft.player.state.read", this::readPlayerState);
        handlers.put("minecraft.player.context.read", this::clientCall);
        handlers.put("minecraft.world.block.read", this::readBlock);
        handlers.put("minecraft.world.blocks.read", this::readBlocks);
        handlers.put("minecraft.world.region.scan", this::scanRegion);
        handlers.put("minecraft.world.heightmap.read", this::clientCall);
        handlers.put("minecraft.world.light.analyze", this::clientCall);
        handlers.put("minecraft.world.blocks.write", this::writeBlocks);
        handlers.put("minecraft.entity.list", this::listEntities);
        handlers.put("minecraft.entity.nearby.read", this::clientCall);
        handlers.put("minecraft.inventory.read", this::readInventory);
        handlers.put("minecraft.chat.send", this::sendChat);
        handlers.put("minecraft.input.key.set", this::clientCall);
        handlers.put("minecraft.input.mouse.set", this::clientCall);
        handlers.put("minecraft.input.release-all", this::clientCall);
        handlers.put("minecraft.player.look", this::clientCall);
        handlers.put("minecraft.player.move", this::clientCall);
        handlers.put("minecraft.player.interact", this::clientCall);
        handlers.put("minecraft.inventory.slot.select", this::clientCall);
        handlers.put("minecraft.inventory.container.read", this::clientCall);
        handlers.put("minecraft.inventory.container.click", this::clientCall);
        handlers.put("minecraft.entity.interact", this::clientCall);
        handlers.put("minecraft.ui.state.read", this::clientCall);
        handlers.put("minecraft.ui.click", this::clientCall);
        handlers.put("minecraft.ui.key", this::clientCall);
        handlers.put("minecraft.ui.text.insert", this::clientCall);
        handlers.put("minecraft.chat.read", this::clientCall);
        handlers.put("minecraft.goal.survival.wooden-axe-tree", this::clientCall);
        return Map.copyOf(handlers);
    }

    @Override
    public void start(AdapterContext context) {
        this.context = context;
    }

    public void setRefreshHook(Runnable refreshHook) {
        this.refreshHook = refreshHook == null ? () -> { } : refreshHook;
    }

    void attachClientBridge(ClientBridge bridge) {
        clientBridge = bridge;
        refreshHook.run();
    }

    void detachClientBridge(ClientBridge bridge) {
        if (clientBridge == bridge) {
            clientBridge = null;
            refreshHook.run();
        }
    }

    void refreshClientState() {
        refreshHook.run();
    }

    void publishClientEvent(String event, Map<String, Object> payload) {
        publish(event, payload, -1);
    }

    @Override
    public AdapterHealth health() {
        if (serverReady) {
            return new AdapterHealth(AdapterHealth.State.READY, "NeoForge server is available", null);
        }
        return new AdapterHealth(AdapterHealth.State.NO_WORLD, "NeoForge loaded without a running world", null);
    }

    public void onServerStarted(ServerStartedEvent event) {
        server = event.getServer();
        serverReady = true;
        publish("minecraft.lifecycle.server.started", Map.of("adapter", ADAPTER_ID), -1);
    }

    public void onServerStopped(ServerStoppedEvent event) {
        serverReady = false;
        server = null;
        publish("minecraft.lifecycle.server.stopped", Map.of("adapter", ADAPTER_ID), -1);
    }

    private CapabilityDescriptor adapt(CapabilityDescriptor capability) {
        if (!IMPLEMENTED.contains(capability.id())) {
            return capability.forAdapter(descriptor, Availability.UNAVAILABLE,
                    new AvailabilityReason("not-implemented", "This operation is not implemented by the NeoForge 1.21.1 adapter yet.",
                            Map.of("adapter", ADAPTER_ID)));
        }
        if (CLIENT_CAPABILITIES.contains(capability.id())) {
            if (clientBridge == null || !clientBridge.available(capability.id())) {
                return capability.forAdapter(descriptor, Availability.UNAVAILABLE,
                        new AvailabilityReason("client-not-ready",
                                "This client capability requires a physical client, player, world, or screen that is not currently available.",
                                Map.of("capability", capability.id())));
            }
            var readyAvailability = capability.availability() == Availability.RESTRICTED
                    ? Availability.RESTRICTED : Availability.AVAILABLE;
            var readyReason = readyAvailability == Availability.RESTRICTED ? capability.reason() : null;
            return capability.forAdapter(descriptor, readyAvailability, readyReason);
        }
        if (!serverReady) {
            return capability.forAdapter(descriptor, Availability.DEGRADED,
                    new AvailabilityReason("server-not-ready", "The adapter is loaded, but no logical server/world is running.", Map.of()));
        }
        var readyAvailability = capability.availability() == Availability.RESTRICTED
                ? Availability.RESTRICTED : Availability.AVAILABLE;
        var readyReason = readyAvailability == Availability.RESTRICTED ? capability.reason() : null;
        return capability.forAdapter(descriptor, readyAvailability, readyReason);
    }

    private CompletionStage<Map<String, Object>> discoverCommands(InvocationContext invocation) {
        return serverCall(invocation.cancellation(), server -> {
            var root = server.getCommands().getDispatcher().getRoot();
            return Map.of("root", describeCommand(root));
        });
    }

    private CompletionStage<Map<String, Object>> executeCommand(InvocationContext invocation) {
        var command = text(invocation.request().input(), "command");
        return serverCall(invocation.cancellation(), server -> {
            var normalized = command.startsWith("/") ? command.substring(1) : command;
            var source = server.createCommandSourceStack().withSuppressedOutput();
            var parsed = server.getCommands().getDispatcher().parse(normalized, source);
            invocation.cancellation().commitMutation();
            server.getCommands().performCommand(parsed, normalized);
            return Map.of("executed", true, "command", normalized);
        });
    }

    private CompletionStage<Map<String, Object>> readPlayerState(InvocationContext invocation) {
        if (clientBridge != null && clientBridge.available("minecraft.player.state.read")) {
            return clientBridge.invoke("minecraft.player.state.read", invocation);
        }
        return serverCall(invocation.cancellation(), server -> {
            var requested = invocation.request().input().get("player");
            var player = findPlayer(server, requested == null ? null : String.valueOf(requested));
            if (player == null) {
                throw new IllegalStateException("no matching server player is available");
            }
            return Map.of(
                    "uuid", player.getUUID().toString(),
                    "name", player.getGameProfile().getName(),
                    "position", Map.of("x", player.getX(), "y", player.getY(), "z", player.getZ()),
                    "rotation", Map.of("yaw", player.getYRot(), "pitch", player.getXRot()),
                    "dimension", player.level().dimension().location().toString(),
                    "health", player.getHealth(),
                    "food", player.getFoodData().getFoodLevel());
        });
    }

    private CompletionStage<Map<String, Object>> readBlock(InvocationContext invocation) {
        var input = invocation.request().input();
        return serverCall(invocation.cancellation(), server -> {
            var pos = new BlockPos(number(input, "x"), number(input, "y"), number(input, "z"));
            var world = server.overworld();
            if (!world.hasChunkAt(pos)) {
                return Map.of("position", Map.of("x", pos.getX(), "y", pos.getY(), "z", pos.getZ()),
                        "dimension", "minecraft:overworld", "block", "lodestone:unloaded", "air", true, "loaded", false);
            }
            BlockState state = world.getBlockState(pos);
            return Map.of(
                    "position", Map.of("x", pos.getX(), "y", pos.getY(), "z", pos.getZ()),
                    "dimension", "minecraft:overworld",
                    "block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),
                    "air", state.isAir(), "loaded", true);
        });
    }

    private CompletionStage<Map<String, Object>> readBlocks(InvocationContext invocation) {
        var input = invocation.request().input();
        var dimension = input.get("dimension");
        if (dimension != null && !"minecraft:overworld".equals(String.valueOf(dimension))) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "only minecraft:overworld is supported by this adapter slice"));
        }
        var x = number(input, "x");
        var y = number(input, "y");
        var z = number(input, "z");
        var sizeX = boundedSize(input, "sizeX");
        var sizeY = boundedSize(input, "sizeY");
        var sizeZ = boundedSize(input, "sizeZ");
        InputNumbers.requireRegionBounds(x, y, z, sizeX, sizeY, sizeZ);
        if ((long) sizeX * sizeY * sizeZ > 4096) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "block volume must contain at most 4096 cells"));
        }
        return serverCall(invocation.cancellation(), server -> {
            var world = server.overworld();
            var blocks = new ArrayList<Map<String, Object>>(sizeX * sizeY * sizeZ);
            for (var offsetY = 0; offsetY < sizeY; offsetY++) {
                for (var offsetZ = 0; offsetZ < sizeZ; offsetZ++) {
                    for (var offsetX = 0; offsetX < sizeX; offsetX++) {
                        invocation.cancellation().throwIfCancelled();
                        var position = new BlockPos(x + offsetX, y + offsetY, z + offsetZ);
                        if (!world.hasChunkAt(position)) {
                            blocks.add(Map.of("position", Map.of("x", position.getX(), "y", position.getY(), "z", position.getZ()),
                                    "block", "lodestone:unloaded", "air", false, "loaded", false));
                            continue;
                        }
                        var state = world.getBlockState(position);
                        blocks.add(Map.of("position", Map.of("x", position.getX(), "y", position.getY(), "z", position.getZ()),
                                "block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),
                                "air", state.isAir(), "loaded", true));
                    }
                }
            }
            return Map.of("dimension", "minecraft:overworld",
                    "origin", Map.of("x", x, "y", y, "z", z),
                    "size", Map.of("x", sizeX, "y", sizeY, "z", sizeZ),
                    "count", blocks.size(), "blocks", blocks);
        });
    }

    private CompletionStage<Map<String, Object>> sendChat(InvocationContext invocation) {
        var message = text(invocation.request().input(), "message");
        if (message.length() > 256) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("message must be <=256 characters"));
        }
        return serverCall(invocation.cancellation(), server -> {
            invocation.cancellation().commitMutation();
            server.getPlayerList().broadcastSystemMessage(Component.literal(message), false);
            return Map.of("sent", true, "message", message,
                    "recipientCount", server.getPlayerList().getPlayers().size());
        });
    }

    private CompletionStage<Map<String, Object>> clientCall(InvocationContext invocation) {
        var bridge = clientBridge;
        if (bridge == null || !bridge.available(invocation.request().capability())) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "client capability is not available in the current environment"));
        }
        return bridge.invoke(invocation.request().capability(), invocation);
    }

    private CompletionStage<Map<String, Object>> scanRegion(InvocationContext invocation) {
        var input = invocation.request().input();
        var dimension = input.get("dimension");
        if (dimension != null && !"minecraft:overworld".equals(String.valueOf(dimension))) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "only minecraft:overworld is supported by this adapter slice"));
        }
        var x = number(input, "x");
        var y = number(input, "y");
        var z = number(input, "z");
        var sizeX = boundedSize(input, "sizeX");
        var sizeY = boundedSize(input, "sizeY");
        var sizeZ = boundedSize(input, "sizeZ");
        InputNumbers.requireRegionBounds(x, y, z, sizeX, sizeY, sizeZ);
        if ((long) sizeX * sizeY * sizeZ > 4096) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "region volume must contain at most 4096 cells"));
        }
        return serverCall(invocation.cancellation(), server -> {
            var world = server.overworld();
            var blockCounts = new LinkedHashMap<String, Integer>();
            var loadedCells = 0;
            var unloadedCells = 0;
            for (var offsetY = 0; offsetY < sizeY; offsetY++) {
                for (var offsetZ = 0; offsetZ < sizeZ; offsetZ++) {
                    for (var offsetX = 0; offsetX < sizeX; offsetX++) {
                        invocation.cancellation().throwIfCancelled();
                        var position = new BlockPos(x + offsetX, y + offsetY, z + offsetZ);
                        if (!world.hasChunkAt(position)) {
                            unloadedCells++;
                            continue;
                        }
                        loadedCells++;
                        var block = BuiltInRegistries.BLOCK.getKey(world.getBlockState(position).getBlock()).toString();
                        blockCounts.merge(block, 1, Integer::sum);
                    }
                }
            }
            return Map.of("dimension", "minecraft:overworld",
                    "origin", Map.of("x", x, "y", y, "z", z),
                    "size", Map.of("x", sizeX, "y", sizeY, "z", sizeZ),
                    "totalCells", sizeX * sizeY * sizeZ,
                    "loadedCells", loadedCells, "unloadedCells", unloadedCells,
                    "blockCounts", blockCounts);
        });
    }

    private CompletionStage<Map<String, Object>> writeBlocks(InvocationContext invocation) {
        var input = invocation.request().input();
        var dimension = input.get("dimension");
        if (dimension != null && !"minecraft:overworld".equals(String.valueOf(dimension))) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "only minecraft:overworld is supported by this adapter slice"));
        }
        var changes = parseChanges(input);
        var dryRun = booleanValue(input, "dryRun");
        return serverCall(invocation.cancellation(), server -> {
            var world = server.overworld();
            var prepared = new ArrayList<PreparedChange>(changes.size());
            for (var change : changes) {
                invocation.cancellation().throwIfCancelled();
                var position = new BlockPos(change.x(), change.y(), change.z());
                if (!world.isInWorldBounds(position)) {
                    throw new IllegalArgumentException("block position is outside world bounds: " + change.position());
                }
                var chunk = world.getChunkSource().getChunkNow(position.getX() >> 4, position.getZ() >> 4);
                if (chunk == null) {
                    throw new IllegalArgumentException("block position is not in a loaded chunk: " + change.position());
                }
                if (world.getBlockEntity(position) != null) {
                    throw new IllegalArgumentException("block-entity writes require NBT-safe mutation support: " + change.position());
                }
                BlockState state;
                try {
                    state = BlockStateParser.parseForBlock(
                            BuiltInRegistries.BLOCK.asLookup(), change.block(), false).blockState();
                } catch (Exception failure) {
                    throw new IllegalArgumentException("invalid block state: " + change.block(), failure);
                }
                prepared.add(new PreparedChange(change, position, state, chunk.getBlockState(position)));
            }
            var results = new ArrayList<Map<String, Object>>(prepared.size());
            var changedCount = 0;
            var appliedChanges = new ArrayList<PreparedChange>();
            try {
                for (var change : prepared) {
                    invocation.cancellation().throwIfCancelled();
                    var changed = !change.previous().equals(change.next());
                    if (!dryRun && changed) appliedChanges.add(change);
                    var applied = dryRun || !changed || world.setBlock(change.position(), change.next(), Block.UPDATE_ALL);
                    if (!applied) {
                        throw new IllegalStateException("native block write was rejected at " + change.source().position());
                    }
                    if (!dryRun && changed) {
                        changedCount++;
                    }
                    results.add(Map.of(
                            "position", Map.of("x", change.position().getX(), "y", change.position().getY(), "z", change.position().getZ()),
                            "requestedBlock", change.source().block(),
                            "previousBlock", BuiltInRegistries.BLOCK.getKey(change.previous().getBlock()).toString(),
                            "changed", changed,
                            "applied", !dryRun));
                }
                if (!dryRun && changedCount > 0) invocation.cancellation().commitMutation();
                else invocation.cancellation().throwIfCancelled();
            } catch (RuntimeException failure) {
                if (!dryRun) dev.lodestone.adapter.BatchMutationRollback.restore(appliedChanges, rollback -> {
                    if (!world.setBlock(rollback.position(), rollback.previous(), Block.UPDATE_ALL)) {
                        throw new IllegalStateException("rollback was rejected at " + rollback.source().position());
                    }
                }, failure);
                throw failure;
            }
            return Map.of(
                    "dimension", "minecraft:overworld",
                    "dryRun", dryRun,
                    "validated", true,
                    "requestedCount", prepared.size(),
                    "changedCount", dryRun ? 0 : changedCount,
                    "changes", results);
        });
    }

    private CompletionStage<Map<String, Object>> listEntities(InvocationContext invocation) {
        var input = invocation.request().input();
        var dimension = input.get("dimension");
        if (dimension != null && !"minecraft:overworld".equals(String.valueOf(dimension))) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "only minecraft:overworld is supported by this adapter slice"));
        }
        var limit = boundedLimit(input, "limit", 128, 256);
        var requestedType = optionalText(input, "type");
        var includePlayers = input.get("includePlayers") == null || booleanValue(input, "includePlayers");
        return serverCall(invocation.cancellation(), server -> {
            var typeFilter = requestedType == null ? null : BuiltInRegistries.ENTITY_TYPE
                    .getOptional(ResourceLocation.tryParse(requestedType))
                    .orElseThrow(() -> new IllegalArgumentException("unknown entity type: " + requestedType));
            var entities = new ArrayList<Map<String, Object>>();
            var truncated = false;
            for (var entity : server.overworld().getAllEntities()) {
                invocation.cancellation().throwIfCancelled();
                if (!includePlayers && entity instanceof ServerPlayer) {
                    continue;
                }
                if (typeFilter != null && entity.getType() != typeFilter) {
                    continue;
                }
                if (entities.size() >= limit) {
                    truncated = true;
                    break;
                }
                entities.add(entityProjection(entity));
            }
            return Map.of("dimension", "minecraft:overworld", "limit", limit,
                    "truncated", truncated, "entities", entities);
        });
    }

    private CompletionStage<Map<String, Object>> readInventory(InvocationContext invocation) {
        return serverCall(invocation.cancellation(), server -> {
            var requested = invocation.request().input().get("player");
            var player = findPlayer(server, requested == null ? null : String.valueOf(requested));
            if (player == null) {
                throw new IllegalStateException("no matching server player is available");
            }
            var inventory = player.getInventory();
            var slots = new ArrayList<Map<String, Object>>(inventory.getContainerSize());
            for (var index = 0; index < inventory.getContainerSize(); index++) {
                invocation.cancellation().throwIfCancelled();
                var stack = inventory.getItem(index);
                slots.add(Map.of("slot", index,
                        "item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                        "count", stack.getCount(), "empty", stack.isEmpty()));
            }
            return Map.of("player", Map.of("uuid", player.getUUID().toString(),
                            "name", player.getGameProfile().getName()),
                    "selectedSlot", inventory.selected, "slots", slots);
        });
    }

    private static List<BlockChange> parseChanges(Map<String, Object> input) {
        var raw = input.get("changes");
        if (!(raw instanceof List<?> values) || values.isEmpty() || values.size() > 64) {
            throw new IllegalArgumentException("changes must contain 1-64 block entries");
        }
        var seen = new HashSet<String>();
        var changes = new ArrayList<BlockChange>(values.size());
        for (var value : values) {
            if (!(value instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("each change must be an object");
            }
            var change = new BlockChange(changeInteger(map, "x"), changeInteger(map, "y"), changeInteger(map, "z"), changeText(map, "block"));
            if (change.block().length() > 256 || !seen.add(change.position())) {
                throw new IllegalArgumentException("block ids must be <=256 characters and positions must be unique");
            }
            changes.add(change);
        }
        return changes;
    }

    private static boolean booleanValue(Map<String, Object> input, String key) {
        var value = input.get(key);
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        throw new IllegalArgumentException("input field must be boolean: " + key);
    }

    private static int boundedLimit(Map<String, Object> input, String key, int fallback, int maximum) {
        var value = input.get(key);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(key + " must be between 1 and " + maximum);
        }
        var parsed = InputNumbers.exactInt(number, key);
        if (parsed < 1 || parsed > maximum) throw new IllegalArgumentException(key + " must be between 1 and " + maximum);
        return parsed;
    }

    private static String optionalText(Map<String, Object> input, String key) {
        var value = input.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("input field must be a non-empty string: " + key);
        }
        return text;
    }

    private static Map<String, Object> entityProjection(Entity entity) {
        return Map.of("uuid", entity.getUUID().toString(),
                "type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),
                "name", entity.getName().getString(),
                "position", Map.of("x", entity.getX(), "y", entity.getY(), "z", entity.getZ()),
                "rotation", Map.of("yaw", entity.getYRot(), "pitch", entity.getXRot()),
                "alive", entity.isAlive(), "onGround", entity.onGround());
    }

    private CompletionStage<Map<String, Object>> serverCall(CancellationToken cancellation,
                                                              Function<MinecraftServer, Map<String, Object>> operation) {
        var current = server;
        if (!serverReady || current == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("logical server/world is not available"));
        }
        var result = new CompletableFuture<Map<String, Object>>();
        current.execute(() -> {
            try {
                cancellation.throwIfCancelled();
                result.complete(operation.apply(current));
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    private static Map<String, Object> describeCommand(CommandNode<CommandSourceStack> node) {
        var children = new ArrayList<Map<String, Object>>();
        for (var child : node.getChildren()) {
            children.add(describeCommand(child));
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("name", node.getName());
        result.put("kind", node instanceof ArgumentCommandNode<?, ?> ? "argument" : "literal");
        result.put("executable", node.getCommand() != null);
        result.put("children", children);
        if (node instanceof ArgumentCommandNode<?, ?> argument) {
            result.put("argumentType", argument.getType().getClass().getName());
        }
        return result;
    }

    private static ServerPlayer findPlayer(MinecraftServer server, String requested) {
        var players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) {
            return null;
        }
        if (requested == null || requested.isBlank()) {
            return players.getFirst();
        }
        try {
            var uuid = UUID.fromString(requested);
            return players.stream().filter(player -> player.getUUID().equals(uuid)).findFirst().orElse(null);
        } catch (IllegalArgumentException ignored) {
            return players.stream().filter(player -> player.getGameProfile().getName().equalsIgnoreCase(requested))
                    .findFirst().orElse(null);
        }
    }

    private static String text(Map<String, Object> input, String key) {
        var value = input.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("input field must be a non-empty string: " + key);
        }
        return text;
    }

    private static int number(Map<String, Object> input, String key) {
        var value = input.get(key);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("input field must be numeric: " + key);
        }
        return InputNumbers.exactInt(number, key);
    }

    private static int boundedSize(Map<String, Object> input, String key) {
        var value = input.get(key);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(key + " must be between 1 and 32");
        }
        var parsed = InputNumbers.exactInt(number, key);
        if (parsed < 1 || parsed > 32) throw new IllegalArgumentException(key + " must be between 1 and 32");
        return parsed;
    }

    private static int changeInteger(Map<?, ?> input, String key) {
        var value = input.get(key);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("change field must be numeric: " + key);
        }
        return InputNumbers.exactInt(number, key);
    }

    private static String changeText(Map<?, ?> input, String key) {
        var value = input.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("change field must be a non-empty string: " + key);
        }
        return text;
    }

    private record BlockChange(int x, int y, int z, String block) {
        private String position() {
            return x + ":" + y + ":" + z;
        }
    }

    private record PreparedChange(BlockChange source, BlockPos position, BlockState next, BlockState previous) {
    }

    private void publish(String event, Map<String, Object> payload, long gameTick) {
        var current = context;
        if (current != null) {
            current.eventSink().publish(event, payload, gameTick);
        }
    }

    interface ClientBridge {
        boolean available(String capability);

        CompletionStage<Map<String, Object>> invoke(String capability, InvocationContext invocation);
    }

    private static Environment environment() {
        return FMLEnvironment.dist == Dist.CLIENT ? Environment.CLIENT : Environment.DEDICATED_SERVER;
    }
}
