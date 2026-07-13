// SPDX-License-Identifier: MIT
package dev.lodestone.folia;

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
import dev.lodestone.runtime.CoreCatalog;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Folia 1.21.4 adapter with explicit global, region, and entity scheduling. */
public final class FoliaAdapter implements LodestoneAdapter {
    public static final String ADAPTER_ID = "lodestone.folia.mc1_21_4";
    private static final String DIMENSION = "minecraft:overworld";
    private static final String PLAYER_COMMAND_CAPABILITY = "minecraft.player.command.execute";
    private static final Set<String> IMPLEMENTED = Set.of(
            "minecraft.command.execute", PLAYER_COMMAND_CAPABILITY, "minecraft.player.state.read",
            "minecraft.world.block.read", "minecraft.world.blocks.read",
            "minecraft.world.region.scan", "minecraft.world.blocks.write",
            "minecraft.inventory.read", "minecraft.chat.send");

    private final Plugin plugin;
    private final AdapterDescriptor descriptor = new AdapterDescriptor(
            ADAPTER_ID, "0.1.0", "minecraft-java", "1.21.4", "folia", Environment.DEDICATED_SERVER);
    private volatile AdapterContext context;
    private volatile World overworld;
    private volatile boolean ready;
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();

    public FoliaAdapter(Plugin plugin) {
        this.plugin = plugin;
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
        handlers.put("minecraft.command.execute", this::executeCommand);
        handlers.put(PLAYER_COMMAND_CAPABILITY, this::executePlayerCommand);
        handlers.put("minecraft.player.state.read", this::readPlayerState);
        handlers.put("minecraft.world.block.read", this::readBlock);
        handlers.put("minecraft.world.blocks.read", this::readBlocks);
        handlers.put("minecraft.world.region.scan", this::scanRegion);
        handlers.put("minecraft.world.blocks.write", this::writeBlocks);
        handlers.put("minecraft.inventory.read", this::readInventory);
        handlers.put("minecraft.chat.send", this::sendChat);
        return Map.copyOf(handlers);
    }

    @Override
    public void start(AdapterContext context) {
        this.context = context;
        refreshWorld();
    }

    @Override
    public AdapterHealth health() {
        return ready
                ? new AdapterHealth(AdapterHealth.State.READY, "Folia server is available", null)
                : new AdapterHealth(AdapterHealth.State.NO_WORLD, "Folia has no loaded overworld", null);
    }

    public void publishEvent(String event, Map<String, Object> payload) {
        var current = context;
        if (current != null) {
            current.eventSink().publish(event, payload == null ? Map.of() : Map.copyOf(payload), -1);
        }
    }

    @Override
    public void close() {
        ready = false;
        overworld = null;
        var failure = new IllegalStateException("Folia adapter is shutting down");
        pending.forEach(future -> fail(future, failure));
        pending.clear();
    }

    /** Refresh the world reference after Folia loads or unloads the overworld. */
    public void refreshWorld() {
        var next = Bukkit.getWorlds().stream()
                .filter(world -> DIMENSION.equals(world.getKey().toString()))
                .findFirst().orElse(null);
        overworld = next;
        ready = next != null;
    }

    public void markWorldUnloaded(World world) {
        if (world != null && DIMENSION.equals(world.getKey().toString())) {
            overworld = null;
            ready = false;
        }
    }

    private CapabilityDescriptor adapt(CapabilityDescriptor capability) {
        if (!IMPLEMENTED.contains(capability.id())) {
            return capability.forAdapter(descriptor, Availability.UNAVAILABLE,
                    new AvailabilityReason("not-implemented",
                            "This Folia 1.21.4 adapter does not implement the operation yet.",
                            Map.of("adapter", ADAPTER_ID)));
        }
        if (!ready) {
            return capability.forAdapter(descriptor, Availability.DEGRADED,
                    new AvailabilityReason("server-not-ready", "Folia has no ready overworld.", Map.of()));
        }
        var availability = capability.availability() == Availability.RESTRICTED
                ? Availability.RESTRICTED : Availability.AVAILABLE;
        return capability.forAdapter(descriptor, availability,
                availability == Availability.RESTRICTED ? capability.reason() : null);
    }

    private CompletionStage<Map<String, Object>> executeCommand(InvocationContext invocation) {
        var command = text(invocation.request().input(), "command");
        return globalCall(invocation.cancellation(), server -> {
            var normalized = command.startsWith("/") ? command.substring(1) : command;
            invocation.cancellation().commitMutation();
            var executed = server.dispatchCommand(server.getConsoleSender(), normalized);
            return Map.of("executed", executed, "command", normalized);
        });
    }

    private CompletionStage<Map<String, Object>> executePlayerCommand(InvocationContext invocation) {
        var input = invocation.request().input();
        var selector = playerSelector(input.get("player"));
        var command = text(input, "command");
        if (command.length() > 32767) {
            throw new IllegalArgumentException("command must be <=32767 characters");
        }
        var dispatchedCommand = command.startsWith("/") ? command.substring(1) : command;
        if (dispatchedCommand.isBlank()) {
            throw new IllegalArgumentException("command must contain a command after its leading slash");
        }
        var capture = captureRequest(input.get("capture"));
        return playerCommandCall(invocation, selector, dispatchedCommand, capture);
    }

    private CompletionStage<Map<String, Object>> readPlayerState(InvocationContext invocation) {
        return entityCall(invocation, player -> {
            var location = player.getLocation();
            return Map.of("uuid", player.getUniqueId().toString(), "name", player.getName(),
                    "position", Map.of("x", location.getX(), "y", location.getY(), "z", location.getZ()),
                    "rotation", Map.of("yaw", location.getYaw(), "pitch", location.getPitch()),
                    "dimension", player.getWorld().getKey().toString(), "health", player.getHealth(),
                    "food", player.getFoodLevel(), "selectedSlot", player.getInventory().getHeldItemSlot());
        });
    }

    private CompletionStage<Map<String, Object>> readBlock(InvocationContext invocation) {
        var input = invocation.request().input();
        requireOverworld(input);
        var origin = coordinates(input);
        return regionCall(invocation.cancellation(), origin[0], origin[1], origin[2], world -> {
            var loaded = world.isChunkLoaded(origin[0] >> 4, origin[2] >> 4);
            if (!loaded) {
                return Map.of("position", position(origin[0], origin[1], origin[2]),
                        "dimension", world.getKey().toString(), "block", "lodestone:unloaded", "loaded", false);
            }
            var block = world.getBlockAt(origin[0], origin[1], origin[2]);
            return Map.of("position", position(origin[0], origin[1], origin[2]),
                    "dimension", world.getKey().toString(), "block", block.getType().getKey().toString(),
                    "state", block.getBlockData().getAsString(), "air", block.getType().isAir(), "loaded", true);
        });
    }

    private CompletionStage<Map<String, Object>> readBlocks(InvocationContext invocation) {
        var input = invocation.request().input();
        var batch = readBlockBatch(invocation);
        return batch.thenApply(blocks -> Map.of("dimension", DIMENSION,
                "origin", position(integer(input, "x"), integer(input, "y"), integer(input, "z")),
                "size", Map.of("x", boundedSize(input, "sizeX"), "y", boundedSize(input, "sizeY"),
                        "z", boundedSize(input, "sizeZ")), "count", blocks.size(), "blocks", blocks));
    }

    private CompletionStage<Map<String, Object>> scanRegion(InvocationContext invocation) {
        var input = invocation.request().input();
        return readBlockBatch(invocation).thenApply(blocks -> {
            var counts = new LinkedHashMap<String, Integer>();
            var loaded = 0;
            var unloaded = 0;
            for (var block : blocks) {
                if (Boolean.FALSE.equals(block.get("loaded"))) {
                    unloaded++;
                } else {
                    loaded++;
                    counts.merge(String.valueOf(block.get("block")), 1, Integer::sum);
                }
            }
            return Map.of("dimension", DIMENSION,
                    "origin", position(integer(input, "x"), integer(input, "y"), integer(input, "z")),
                    "size", Map.of("x", boundedSize(input, "sizeX"), "y", boundedSize(input, "sizeY"),
                            "z", boundedSize(input, "sizeZ")),
                    "totalCells", blocks.size(), "loadedCells", loaded, "unloadedCells", unloaded,
                    "blockCounts", counts);
        });
    }

    private CompletionStage<List<Map<String, Object>>> readBlockBatch(InvocationContext invocation) {
        var input = invocation.request().input();
        requireOverworld(input);
        var x = integer(input, "x");
        var y = integer(input, "y");
        var z = integer(input, "z");
        var sizeX = boundedSize(input, "sizeX");
        var sizeY = boundedSize(input, "sizeY");
        var sizeZ = boundedSize(input, "sizeZ");
        InputNumbers.requireRegionBounds(x, y, z, sizeX, sizeY, sizeZ);
        requireVolume(sizeX, sizeY, sizeZ);
        var byChunk = new LinkedHashMap<Long, List<BatchCell>>();
        var index = 0;
        for (var dy = 0; dy < sizeY; dy++) for (var dz = 0; dz < sizeZ; dz++) for (var dx = 0; dx < sizeX; dx++) {
            var px = x + dx; var py = y + dy; var pz = z + dz;
            var chunkKey = (((long) px >> 4) << 32) ^ ((long) pz >> 4 & 0xffffffffL);
            byChunk.computeIfAbsent(chunkKey, ignored -> new ArrayList<>())
                    .add(new BatchCell(index++, px, py, pz));
        }
        var totalCells = index;
        var futures = new ArrayList<CompletableFuture<List<IndexedBlock>>>();
        for (var cells : byChunk.values()) {
            var anchor = cells.getFirst();
            futures.add(regionCall(invocation.cancellation(), anchor.x(), anchor.y(), anchor.z(), world -> {
                var blocks = new ArrayList<IndexedBlock>(cells.size());
                for (var cell : cells) {
                    invocation.cancellation().throwIfCancelled();
                    var loaded = world.isChunkLoaded(cell.x() >> 4, cell.z() >> 4);
                    blocks.add(new IndexedBlock(cell.index(), Map.of("position", position(cell.x(), cell.y(), cell.z()),
                            "block", loaded ? world.getBlockAt(cell.x(), cell.y(), cell.z()).getType().getKey().toString()
                                    : "lodestone:unloaded",
                            "loaded", loaded)));
                }
                return blocks;
            }));
        }
        var all = CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
        return all.thenApply(ignored -> {
            var ordered = new ArrayList<Map<String, Object>>(totalCells);
            for (var i = 0; i < totalCells; i++) ordered.add(null);
            for (var future : futures) {
                for (var block : future.join()) ordered.set(block.index(), block.value());
            }
            return List.copyOf(ordered);
        });
    }

    private CompletionStage<Map<String, Object>> writeBlocks(InvocationContext invocation) {
        var input = invocation.request().input();
        requireOverworld(input);
        var changes = parseChanges(input);
        var dryRun = Boolean.TRUE.equals(input.get("dryRun"));
        var first = changes.getFirst();
        var chunkX = first.x() >> 4;
        var chunkZ = first.z() >> 4;
        if (changes.stream().anyMatch(change -> (change.x() >> 4) != chunkX || (change.z() >> 4) != chunkZ)) {
            throw new IllegalArgumentException("Folia block writes must stay within one chunk per request");
        }
        var validation = globalCall(invocation.cancellation(), server -> {
            for (var change : changes) {
                invocation.cancellation().throwIfCancelled();
                blockData(change.block());
            }
            return Map.of("validated", true);
        });
        return validation.thenCompose(ignored -> regionCall(invocation.cancellation(), first.x(), first.y(), first.z(), world -> {
            var prepared = new ArrayList<PreparedChange>(changes.size());
            for (var change : changes) {
                var next = blockData(change.block());
                if (!world.isChunkLoaded(change.x() >> 4, change.z() >> 4)) {
                    throw new IllegalArgumentException("block position is not in a loaded chunk: " + change.position());
                }
                var block = world.getBlockAt(change.x(), change.y(), change.z());
                if (block.getState() instanceof org.bukkit.block.TileState) {
                    throw new IllegalArgumentException("block-entity writes require NBT-safe mutation support: " + change.position());
                }
                prepared.add(new PreparedChange(change, block, next,
                        block.getBlockData().getMaterial().getKey().toString(), block.getBlockData().getAsString()));
            }
            invocation.cancellation().throwIfCancelled();
            var results = new ArrayList<Map<String, Object>>(prepared.size());
            var changed = 0;
            var appliedChanges = new ArrayList<PreparedChange>();
            try {
                for (var change : prepared) {
                    invocation.cancellation().throwIfCancelled();
                    var isChanged = !change.block().getBlockData().matches(change.next());
                    if (!dryRun && isChanged) {
                        appliedChanges.add(change);
                        change.block().setBlockData(change.next(), true);
                        changed++;
                    }
                    results.add(Map.of("position", position(change.source().x(), change.source().y(), change.source().z()),
                            "requestedBlock", change.source().block(), "previousBlock", change.previous(),
                            "previousState", change.previousState(), "changed", isChanged, "applied", !dryRun));
                }
                if (!dryRun && changed > 0) invocation.cancellation().commitMutation();
                else invocation.cancellation().throwIfCancelled();
            } catch (RuntimeException failure) {
                if (!dryRun) dev.lodestone.adapter.BatchMutationRollback.restore(appliedChanges, rollback ->
                        rollback.block().setBlockData(Bukkit.createBlockData(rollback.previousState()), true), failure);
                throw failure;
            }
            return Map.of("dimension", DIMENSION, "dryRun", dryRun, "validated", true,
                    "requestedCount", prepared.size(), "changedCount", changed, "changes", results);
        }));
    }

    private CompletionStage<Map<String, Object>> readInventory(InvocationContext invocation) {
        return entityCall(invocation, player -> {
            var slots = new ArrayList<Map<String, Object>>();
            var contents = player.getInventory().getContents();
            for (var index = 0; index < contents.length; index++) {
                invocation.cancellation().throwIfCancelled();
                slots.add(item(index, contents[index]));
            }
            return Map.of("player", Map.of("uuid", player.getUniqueId().toString(), "name", player.getName()),
                    "selectedSlot", player.getInventory().getHeldItemSlot(), "slots", slots);
        });
    }

    private CompletionStage<Map<String, Object>> sendChat(InvocationContext invocation) {
        var message = text(invocation.request().input(), "message");
        if (message.length() > 256) throw new IllegalArgumentException("message must be <=256 characters");
        return globalCall(invocation.cancellation(), server -> {
            invocation.cancellation().commitMutation();
            var recipients = server.broadcastMessage(message);
            return Map.of("sent", true, "message", message, "recipientCount", recipients);
        });
    }

    private CompletionStage<Map<String, Object>> globalCall(CancellationToken cancellation,
                                                              Function<Server, Map<String, Object>> operation) {
        var result = new CompletableFuture<Map<String, Object>>();
        pending.add(result);
        if (!ready) return fail(result, new IllegalStateException("Folia server is not ready"));
        try {
            var scheduled = Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
                try {
                    if (!ready) throw new IllegalStateException("Folia adapter is shutting down");
                    cancellation.throwIfCancelled();
                    result.complete(operation.apply(Bukkit.getServer()));
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                } finally {
                    pending.remove(result);
                }
            });
            if (scheduled == null) return fail(result, new IllegalStateException("Folia global scheduler retired"));
        } catch (Throwable failure) {
            return fail(result, failure);
        }
        return result;
    }

    private <T> CompletableFuture<T> regionCall(CancellationToken cancellation, int x, int y, int z,
                                                 Function<World, T> operation) {
        var result = new CompletableFuture<T>();
        pending.add(result);
        var world = overworld;
        if (!ready || world == null) return fail(result, new IllegalStateException("Folia world is not ready"));
        try {
            var scheduled = Bukkit.getRegionScheduler().run(plugin, world, x >> 4, z >> 4, task -> {
                try {
                    if (!ready) throw new IllegalStateException("Folia adapter is shutting down");
                    cancellation.throwIfCancelled();
                    result.complete(operation.apply(world));
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                } finally {
                    pending.remove(result);
                }
            });
            if (scheduled == null) return fail(result, new IllegalStateException("Folia region scheduler unavailable"));
        } catch (Throwable failure) {
            return fail(result, failure);
        }
        return result;
    }

    private <T> CompletableFuture<T> entityCall(InvocationContext invocation, Function<Player, T> operation) {
        var result = new CompletableFuture<T>();
        pending.add(result);
        if (!ready) return fail(result, new IllegalStateException("Folia server is not ready"));
        try {
            var scheduled = Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
                try {
                    var player = findPlayer(Bukkit.getServer(), invocation.request().input().get("player"));
                    if (player == null) throw new IllegalStateException("no matching online player is available");
                    var entityTask = player.getScheduler().run(plugin, entity -> {
                        try {
                            if (!ready) throw new IllegalStateException("Folia adapter is shutting down");
                            invocation.cancellation().throwIfCancelled();
                            result.complete(operation.apply(player));
                        } catch (Throwable failure) {
                            result.completeExceptionally(failure);
                        } finally {
                            pending.remove(result);
                        }
                    }, () -> fail(result, new IllegalStateException("player entity scheduler retired")));
                    if (entityTask == null) fail(result, new IllegalStateException("player entity scheduler retired"));
                } catch (Throwable failure) {
                    fail(result, failure);
                }
            });
            if (scheduled == null) return fail(result, new IllegalStateException("Folia global scheduler retired"));
        } catch (Throwable failure) {
            return fail(result, failure);
        }
        return result;
    }

    private CompletableFuture<Map<String, Object>> playerCommandCall(
            InvocationContext invocation, PlayerSelector selector, String command, CaptureRequest capture) {
        var result = new CompletableFuture<Map<String, Object>>();
        pending.add(result);
        if (!ready) return fail(result, new IllegalStateException("Folia server is not ready"));
        try {
            var scheduled = Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
                if (result.isDone()) return;
                try {
                    if (!ready) throw new IllegalStateException("Folia adapter is shutting down");
                    invocation.cancellation().throwIfCancelled();
                    var server = Bukkit.getServer();
                    var player = resolveExactOnlinePlayer(server, selector);
                    var entityTask = player.getScheduler().run(plugin, entity ->
                            completePlayerCommand(result, invocation, server, player, selector, command, capture),
                            () -> failRetired(invocation, result));
                    if (entityTask == null) {
                        fail(result, new IllegalStateException("player entity scheduler retired"));
                    }
                } catch (Throwable failure) {
                    fail(result, failure);
                }
            });
            if (scheduled == null) return fail(result, new IllegalStateException("Folia global scheduler retired"));
        } catch (Throwable failure) {
            return fail(result, failure);
        }
        return result;
    }

    private void completePlayerCommand(CompletableFuture<Map<String, Object>> result,
                                       InvocationContext invocation, Server server, Player player,
                                       PlayerSelector selector, String command, CaptureRequest capture) {
        synchronized (result) {
            if (result.isDone()) {
                pending.remove(result);
                return;
            }
            try {
                if (!ready) throw new IllegalStateException("Folia adapter is shutting down");
                invocation.cancellation().throwIfCancelled();
                if (!player.isOnline()) {
                    throw new IllegalArgumentException("selected player is no longer online");
                }
                var actorUuid = player.getUniqueId();
                var actorName = player.getName();
                if ((selector.uuid() != null && !selector.uuid().equals(actorUuid))
                        || (selector.name() != null && !selector.name().equalsIgnoreCase(actorName))) {
                    throw new IllegalArgumentException("selected online player no longer matches the exact actor");
                }
                invocation.cancellation().commitMutation();
                pending.remove(result);
                var handled = server.dispatchCommand(player, command);
                result.complete(Map.of(
                        "actor", Map.of("uuid", actorUuid.toString(), "name", actorName),
                        "command", command,
                        "dispatched", handled,
                        "certainty", handled ? "dispatch-confirmed" : "not-dispatched",
                        "result", handled ? 1 : 0,
                        "messages", List.of(),
                        "capture", Map.of(
                                "complete", false,
                                "truncated", false,
                                "windowMs", capture.windowMs(),
                                "maxMessages", capture.maxMessages(),
                                "maxBytes", capture.maxBytes())));
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            } finally {
                pending.remove(result);
            }
        }
    }

    private void failRetired(InvocationContext invocation, CompletableFuture<?> result) {
        var failure = new IllegalStateException("player entity scheduler retired");
        try {
            invocation.gameExecutor().execute(() -> fail(result, failure));
        } catch (Throwable rejected) {
            fail(result, failure);
        }
    }

    private <T> CompletableFuture<T> fail(CompletableFuture<T> future, Throwable failure) {
        synchronized (future) {
            pending.remove(future);
            future.completeExceptionally(failure);
        }
        return future;
    }

    private static Player findPlayer(Server server, Object requested) {
        var players = server.getOnlinePlayers();
        if (players.isEmpty()) return null;
        if (requested == null || String.valueOf(requested).isBlank()) return players.iterator().next();
        var value = String.valueOf(requested);
        try {
            var uuid = UUID.fromString(value);
            var player = server.getPlayer(uuid);
            if (player != null) return player;
        } catch (IllegalArgumentException ignored) {
        }
        return server.getPlayerExact(value);
    }

    private static PlayerSelector playerSelector(Object requested) {
        if (!(requested instanceof Map<?, ?> player)) {
            throw new IllegalArgumentException("player must be an object containing uuid and/or name");
        }
        for (var key : player.keySet()) {
            if (!"uuid".equals(String.valueOf(key)) && !"name".equals(String.valueOf(key))) {
                throw new IllegalArgumentException("player supports only uuid and name");
            }
        }
        var uuidText = optionalText(player, "uuid", 64);
        var name = optionalText(player, "name", 256);
        if (uuidText == null && name == null) {
            throw new IllegalArgumentException("player must contain uuid or name");
        }
        UUID uuid = null;
        if (uuidText != null) {
            try {
                uuid = UUID.fromString(uuidText);
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("player uuid must be a valid UUID", invalid);
            }
        }
        return new PlayerSelector(uuid, name);
    }

    private static Player resolveExactOnlinePlayer(Server server, PlayerSelector selector) {
        var uuidMatches = new ArrayList<Player>();
        var nameMatches = new ArrayList<Player>();
        for (var candidate : server.getOnlinePlayers()) {
            if (selector.uuid() != null && selector.uuid().equals(candidate.getUniqueId())) {
                uuidMatches.add(candidate);
            }
            if (selector.name() != null && selector.name().equalsIgnoreCase(candidate.getName())) {
                nameMatches.add(candidate);
            }
        }
        if (selector.uuid() != null && uuidMatches.isEmpty()) {
            throw new IllegalArgumentException("no matching online player exists for uuid");
        }
        if (selector.name() != null && nameMatches.isEmpty()) {
            throw new IllegalArgumentException("no matching online player exists for exact name");
        }
        if (uuidMatches.size() > 1 || nameMatches.size() > 1) {
            throw new IllegalArgumentException("exact player selector is ambiguous among online players");
        }
        var uuidMatch = uuidMatches.isEmpty() ? null : uuidMatches.getFirst();
        var nameMatch = nameMatches.isEmpty() ? null : nameMatches.getFirst();
        if (uuidMatch != null && nameMatch != null
                && !uuidMatch.getUniqueId().equals(nameMatch.getUniqueId())) {
            throw new IllegalArgumentException("player uuid and name must identify the same online player");
        }
        return uuidMatch != null ? uuidMatch : nameMatch;
    }

    private static CaptureRequest captureRequest(Object requested) {
        if (requested == null) {
            return new CaptureRequest(0, 0, 0);
        }
        if (!(requested instanceof Map<?, ?> capture)) {
            throw new IllegalArgumentException("capture must be an object");
        }
        for (var key : capture.keySet()) {
            if (!Set.of("enabled", "windowMs", "maxMessages", "maxBytes").contains(String.valueOf(key))) {
                throw new IllegalArgumentException("unknown capture field: " + key);
            }
        }
        var enabledValue = capture.get("enabled");
        if (enabledValue != null && !(enabledValue instanceof Boolean)) {
            throw new IllegalArgumentException("capture enabled must be boolean");
        }
        var enabled = enabledValue == null || Boolean.TRUE.equals(enabledValue);
        return new CaptureRequest(
                optionalBoundedInt(capture, "windowMs", enabled ? 500 : 0, 0, 2000),
                optionalBoundedInt(capture, "maxMessages", enabled ? 64 : 0, 0, 64),
                optionalBoundedInt(capture, "maxBytes", enabled ? 65536 : 0, 0, 65536));
    }

    private static String optionalText(Map<?, ?> input, String key, int maximumLength) {
        var value = input.get(key);
        if (value == null) return null;
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("player " + key + " must be a non-empty string");
        }
        if (text.length() > maximumLength) {
            throw new IllegalArgumentException("player " + key + " must be <=" + maximumLength + " characters");
        }
        return text;
    }

    private static int optionalBoundedInt(Map<?, ?> input, String key, int defaultValue, int minimum, int maximum) {
        var value = input.get(key);
        if (value == null) return defaultValue;
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("capture " + key + " must be numeric");
        }
        var parsed = InputNumbers.exactInt(number, "capture." + key);
        if (parsed < minimum || parsed > maximum) {
            throw new IllegalArgumentException("capture " + key + " must be between " + minimum + " and " + maximum);
        }
        return parsed;
    }

    private record BatchCell(int index, int x, int y, int z) {
    }

    private record IndexedBlock(int index, Map<String, Object> value) {
    }

    private static Map<String, Object> item(int slot, ItemStack stack) {
        return Map.of("slot", slot, "item", stack == null || stack.getType().isAir()
                ? "minecraft:air" : stack.getType().getKey().toString(), "count", stack == null ? 0 : stack.getAmount());
    }

    private static Map<String, Object> position(int x, int y, int z) {
        return Map.of("x", x, "y", y, "z", z);
    }

    private static int[] coordinates(Map<String, Object> input) {
        return new int[]{integer(input, "x"), integer(input, "y"), integer(input, "z")};
    }

    private static void requireOverworld(Map<String, Object> input) {
        var dimension = input.get("dimension");
        if (dimension != null && !"minecraft:overworld".equals(String.valueOf(dimension))) {
            throw new IllegalArgumentException("only minecraft:overworld is supported by this adapter slice");
        }
    }

    private static int boundedSize(Map<String, Object> input, String key) {
        var value = integer(input, key);
        if (value < 1 || value > 32) throw new IllegalArgumentException(key + " must be between 1 and 32");
        return value;
    }

    private static void requireVolume(int x, int y, int z) {
        if ((long) x * y * z > 4096) throw new IllegalArgumentException("block volume must contain at most 4096 cells");
    }

    private static List<BlockChange> parseChanges(Map<String, Object> input) {
        if (!(input.get("changes") instanceof List<?> values) || values.isEmpty() || values.size() > 64) {
            throw new IllegalArgumentException("changes must contain between 1 and 64 entries");
        }
        var result = new ArrayList<BlockChange>(); var positions = new HashSet<String>();
        for (var value : values) {
            if (!(value instanceof Map<?, ?> change)) throw new IllegalArgumentException("each change must be an object");
            var parsed = new BlockChange(changeInteger(change, "x"), changeInteger(change, "y"),
                    changeInteger(change, "z"), changeText(change, "block"));
            if (parsed.block().length() > 256 || !positions.add(parsed.position())) {
                throw new IllegalArgumentException("block IDs must be <=256 characters and positions unique");
            }
            result.add(parsed);
        }
        return result;
    }

    private static BlockData blockData(String id) {
        var normalized = id.startsWith("minecraft:") ? id : "minecraft:" + id;
        try {
            return Bukkit.createBlockData(normalized);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("unknown block ID: " + id, failure);
        }
    }

    private static int integer(Map<String, Object> input, String key) {
        return changeInteger(input, key);
    }

    private static int changeInteger(Map<?, ?> input, String key) {
        var value = input.get(key);
        if (!(value instanceof Number number)) throw new IllegalArgumentException("input field must be numeric: " + key);
        return InputNumbers.exactInt(number, key);
    }

    private static String text(Map<String, Object> input, String key) {
        return changeText(input, key);
    }

    private static String changeText(Map<?, ?> input, String key) {
        var value = input.get(key);
        if (!(value instanceof String string) || string.isBlank()) {
            throw new IllegalArgumentException("input field must be a non-empty string: " + key);
        }
        return string;
    }

    private record BlockChange(int x, int y, int z, String block) {
        private String position() { return x + ":" + y + ":" + z; }
    }

    private record PreparedChange(BlockChange source, Block block, BlockData next, String previous, String previousState) {
    }

    private record PlayerSelector(UUID uuid, String name) {
    }

    private record CaptureRequest(int windowMs, int maxMessages, int maxBytes) {
    }
}
