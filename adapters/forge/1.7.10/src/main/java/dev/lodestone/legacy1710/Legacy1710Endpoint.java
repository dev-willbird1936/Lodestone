// SPDX-License-Identifier: MIT
package dev.lodestone.legacy1710;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import dev.lodestone.legacyshared.LegacyBatchMutationRollback;
import dev.lodestone.legacyshared.LegacyTokenFile;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.gameevent.TickEvent.ServerTickEvent;
import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.world.WorldServer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Java 8-native control surface for Forge 1.7.10. */
public final class Legacy1710Endpoint implements AutoCloseable {
    private static final String GAME_VERSION = "1.7.10";
    private static final int MAX_BODY_BYTES = 1_048_576;
    private static final int MAX_CHANGES = 64;
    private static final int MAX_VOLUME = 4096;
    private static final long OPERATION_TIMEOUT_MS = 10_000L;

    private final MinecraftServer server;
    private final String token;
    private final int port;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ConcurrentLinkedQueue<FutureTask<Map<String, Object>>> mainThreadTasks =
            new ConcurrentLinkedQueue<FutureTask<Map<String, Object>>>();
    private HttpServer httpServer;

    Legacy1710Endpoint(MinecraftServer server) {
        this.server = server;
        this.port = integerProperty("lodestone.legacy.port", 37942);
        this.token = token();
    }

    void start() {
        try {
            httpServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
            httpServer.createContext("/health", new HealthHandler());
            httpServer.createContext("/invoke", new InvokeHandler());
            httpServer.setExecutor(executor);
            FMLCommonHandler.instance().bus().register(this);
            httpServer.start();
            System.out.println("[Lodestone] Legacy 1.7.10 MCP bridge listening on 127.0.0.1:" + port);
        } catch (IOException failure) {
            throw new IllegalStateException("unable to start Lodestone Forge 1.7.10 bridge", failure);
        }
    }

    @Override
    public void close() {
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
        }
        FMLCommonHandler.instance().bus().unregister(this);
        for (FutureTask<Map<String, Object>> task : mainThreadTasks) task.cancel(false);
        mainThreadTasks.clear();
        executor.shutdownNow();
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        FutureTask<Map<String, Object>> task;
        int processed = 0;
        while (processed++ < 64 && (task = mainThreadTasks.poll()) != null) task.run();
    }

    private final class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!authorized(exchange)) {
                respond(exchange, 401, error("UNAUTHORIZED", "invalid bridge token"));
                return;
            }
            respond(exchange, 200, success(map("ready", server != null, "gameVersion", GAME_VERSION, "loader", "forge")));
        }
    }

    private final class InvokeHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 405, error("METHOD_NOT_ALLOWED", "POST is required"));
                return;
            }
            if (!authorized(exchange)) {
                respond(exchange, 401, error("UNAUTHORIZED", "invalid bridge token"));
                return;
            }
            Future<Map<String, Object>> future = null;
            try {
                JsonElement parsed = new JsonParser().parse(new String(readBody(exchange), StandardCharsets.UTF_8));
                if (!parsed.isJsonObject()) throw new IllegalArgumentException("request must be a JSON object");
                JsonObject request = parsed.getAsJsonObject();
                final String capability = requiredText(request, "capability");
                final JsonObject input = request.has("input") && request.get("input").isJsonObject()
                        ? request.getAsJsonObject("input") : new JsonObject();
                final long deadline = request.has("deadlineEpochMs") && request.get("deadlineEpochMs").isJsonPrimitive()
                        ? request.get("deadlineEpochMs").getAsLong() : System.currentTimeMillis() + OPERATION_TIMEOUT_MS;
                future = schedule(new Callable<Map<String, Object>>() {
                    @Override
                    public Map<String, Object> call() {
                        if (System.currentTimeMillis() >= deadline) {
                            throw new IllegalStateException("legacy game operation deadline expired");
                        }
                        return invoke(capability, input, deadline);
                    }
                });
                respond(exchange, 200, success(future.get(OPERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)));
            } catch (java.util.concurrent.TimeoutException failure) {
                if (future != null) future.cancel(false);
                respond(exchange, 504, error("TIMEOUT", "legacy game operation exceeded its deadline"));
            } catch (Exception failure) {
                respond(exchange, 400, error("ADAPTER_FAILURE", message(failure)));
            }
        }
    }

    private Map<String, Object> invoke(String capability, JsonObject input, long deadline) {
        if ("minecraft.command.execute".equals(capability)) return executeCommand(input);
        if ("minecraft.player.state.read".equals(capability)) return readPlayer(input);
        if ("minecraft.world.block.read".equals(capability)) return readBlock(input);
        if ("minecraft.world.blocks.read".equals(capability)) return readBlocks(input);
        if ("minecraft.world.region.scan".equals(capability)) return scanRegion(input);
        if ("minecraft.world.blocks.write".equals(capability)) return writeBlocks(input, deadline);
        if ("minecraft.entity.list".equals(capability)) return listEntities(input);
        if ("minecraft.inventory.read".equals(capability)) return readInventory(input);
        if ("minecraft.chat.send".equals(capability)) return sendChat(input);
        throw new IllegalArgumentException("capability is unavailable on the Forge 1.7.10 bridge: " + capability);
    }

    private Map<String, Object> executeCommand(JsonObject input) {
        String command = text(input, "command", 32_768);
        String normalized = command.startsWith("/") ? command.substring(1) : command;
        int result = server.getCommandManager().executeCommand(server, normalized);
        return map("executed", true, "command", normalized, "result", result);
    }

    private Map<String, Object> readPlayer(JsonObject input) {
        EntityPlayerMP player = findPlayer(optionalText(input, "player"));
        if (player == null) throw new IllegalStateException("no matching server player is available");
        return map("uuid", player.getUniqueID().toString(), "name", player.getCommandSenderName(),
                "position", map("x", player.posX, "y", player.posY, "z", player.posZ),
                "rotation", map("yaw", player.rotationYaw, "pitch", player.rotationPitch),
                "dimension", "minecraft:overworld", "health", player.getHealth(),
                "food", player.getFoodStats().getFoodLevel());
    }

    private Map<String, Object> readBlock(JsonObject input) {
        WorldServer world = world(input);
        int x = coordinate(input, "x"); int y = height(input, "y"); int z = coordinate(input, "z");
        if (!world.blockExists(x, y, z)) return blockResult(x, y, z, "lodestone:unloaded", false);
        return blockResult(x, y, z, blockId(world.getBlock(x, y, z)), true);
    }

    private Map<String, Object> readBlocks(JsonObject input) {
        WorldServer world = world(input);
        int x = coordinate(input, "x"); int y = height(input, "y"); int z = coordinate(input, "z");
        int sizeX = size(input, "sizeX"); int sizeY = size(input, "sizeY"); int sizeZ = size(input, "sizeZ");
        validateVolume(x, y, z, sizeX, sizeY, sizeZ);
        List<Map<String, Object>> blocks = new ArrayList<Map<String, Object>>(sizeX * sizeY * sizeZ);
        for (int dy = 0; dy < sizeY; dy++) for (int dz = 0; dz < sizeZ; dz++) for (int dx = 0; dx < sizeX; dx++) {
            int px = x + dx; int py = y + dy; int pz = z + dz;
            blocks.add(world.blockExists(px, py, pz) ? blockResult(px, py, pz, blockId(world.getBlock(px, py, pz)), true)
                    : blockResult(px, py, pz, "lodestone:unloaded", false));
        }
        return map("dimension", "minecraft:overworld", "origin", xyz(x, y, z),
                "size", xyz(sizeX, sizeY, sizeZ), "count", blocks.size(), "blocks", blocks);
    }

    private Map<String, Object> scanRegion(JsonObject input) {
        Map<String, Object> read = readBlocks(input);
        List<?> blocks = (List<?>) read.get("blocks");
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        int loaded = 0; int unloaded = 0;
        for (Object value : blocks) {
            Map<?, ?> block = (Map<?, ?>) value;
            if (Boolean.TRUE.equals(block.get("loaded"))) {
                loaded++;
                String id = String.valueOf(block.get("block"));
                Integer count = counts.get(id);
                counts.put(id, count == null ? 1 : count + 1);
            } else {
                unloaded++;
            }
        }
        return map("dimension", "minecraft:overworld", "origin", read.get("origin"), "size", read.get("size"),
                "totalCells", blocks.size(), "loadedCells", loaded, "unloadedCells", unloaded, "blockCounts", counts);
    }

    private Map<String, Object> writeBlocks(JsonObject input, final long deadline) {
        if (!input.has("changes") || !input.get("changes").isJsonArray()) throw new IllegalArgumentException("changes must be an array");
        JsonArray raw = input.getAsJsonArray("changes");
        if (raw.size() < 1 || raw.size() > MAX_CHANGES) throw new IllegalArgumentException("changes must contain 1-64 entries");
        boolean dryRun = input.has("dryRun") && input.get("dryRun").getAsBoolean();
        WorldServer world = world(input);
        List<PreparedChange> prepared = new ArrayList<PreparedChange>(raw.size());
        Set<String> positions = new HashSet<String>();
        for (JsonElement element : raw) {
            JsonObject change = element.getAsJsonObject();
            int x = coordinate(change, "x"); int y = height(change, "y"); int z = coordinate(change, "z");
            String id = text(change, "block", 256);
            String key = x + ":" + y + ":" + z;
            if (!positions.add(key)) throw new IllegalArgumentException("change positions must be unique");
            world.getChunkFromChunkCoords(x >> 4, z >> 4);
            if (world.getTileEntity(x, y, z) != null) throw new IllegalArgumentException(
                    "block-entity writes require NBT-safe mutation support: " + key);
            Block block = Block.getBlockFromName(id);
            if (block == null) throw new IllegalArgumentException("unknown block id: " + id);
            Block previous = world.getBlock(x, y, z);
            prepared.add(new PreparedChange(x, y, z, id, block, previous, world.getBlockMetadata(x, y, z), key));
        }
        List<Map<String, Object>> results = new ArrayList<Map<String, Object>>(prepared.size());
        int changedCount = dryRun ? 0 : LegacyBatchMutationRollback.apply(prepared,
                change -> change.previous != change.next || change.previousMetadata != 0,
                change -> {
                if (!world.setBlock(change.x, change.y, change.z, change.next, 0, 3)) {
                    throw new IllegalStateException("native block write was rejected at " + change.key);
                }
                },
                change -> {
                    if (!world.setBlock(change.x, change.y, change.z, change.previous, change.previousMetadata, 3)) {
                        throw new IllegalStateException("rollback was rejected at " + change.key);
                    }
                },
                () -> requireDeadline(deadline));
        if (dryRun) requireDeadline(deadline);
        for (PreparedChange change : prepared) {
            boolean changed = change.previous != change.next || change.previousMetadata != 0;
            results.add(map("position", xyz(change.x, change.y, change.z), "requestedBlock", change.requested,
                    "previousBlock", blockId(change.previous), "changed", changed, "applied", !dryRun));
        }
        return map("dimension", "minecraft:overworld", "dryRun", dryRun, "validated", true,
                "requestedCount", prepared.size(), "changedCount", dryRun ? 0 : changedCount, "changes", results);
    }

    private Map<String, Object> listEntities(JsonObject input) {
        WorldServer world = world(input);
        int limit = input.has("limit") ? integer(input, "limit") : 128;
        if (limit < 1 || limit > 256) throw new IllegalArgumentException("limit must be between 1 and 256");
        List<Map<String, Object>> entities = new ArrayList<Map<String, Object>>();
        for (Object value : world.loadedEntityList) {
            if (entities.size() >= limit) break;
            Entity entity = (Entity) value;
            entities.add(map("uuid", entity.getUniqueID().toString(), "type", String.valueOf(net.minecraft.entity.EntityList.getEntityString(entity)),
                    "name", entity.getCommandSenderName(), "position", map("x", entity.posX, "y", entity.posY, "z", entity.posZ),
                    "alive", !entity.isDead));
        }
        return map("dimension", "minecraft:overworld", "limit", limit, "truncated", entities.size() >= limit, "entities", entities);
    }

    private Map<String, Object> readInventory(JsonObject input) {
        EntityPlayerMP player = findPlayer(optionalText(input, "player"));
        if (player == null) throw new IllegalStateException("no matching server player is available");
        List<Map<String, Object>> slots = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < player.inventory.getSizeInventory(); i++) {
            ItemStack stack = player.inventory.getStackInSlot(i);
            boolean empty = stack == null || stack.stackSize < 1;
            String item = empty ? "minecraft:air" : String.valueOf(Item.itemRegistry.getNameForObject(stack.getItem()));
            slots.add(map("slot", i, "item", item, "count", empty ? 0 : stack.stackSize, "empty", empty));
        }
        return map("player", map("uuid", player.getUniqueID().toString(), "name", player.getCommandSenderName()),
                "selectedSlot", player.inventory.currentItem, "slots", slots);
    }

    private Map<String, Object> sendChat(JsonObject input) {
        String message = text(input, "message", 256);
        server.getConfigurationManager().sendChatMsg(new ChatComponentText(message));
        return map("sent", true, "message", message, "recipientCount", server.getConfigurationManager().playerEntityList.size());
    }

    private WorldServer world(JsonObject input) {
        if (input.has("dimension") && !"minecraft:overworld".equals(input.get("dimension").getAsString())) {
            throw new IllegalArgumentException("only minecraft:overworld is supported by this bridge");
        }
        return server.worldServerForDimension(0);
    }

    private EntityPlayerMP findPlayer(String requested) {
        List<?> players = server.getConfigurationManager().playerEntityList;
        if (players.isEmpty()) return null;
        if (requested == null || requested.length() == 0) return (EntityPlayerMP) players.get(0);
        try {
            UUID uuid = UUID.fromString(requested);
            for (Object value : players) {
                EntityPlayerMP player = (EntityPlayerMP) value;
                if (uuid.equals(player.getUniqueID())) return player;
            }
        } catch (IllegalArgumentException ignored) {
            for (Object value : players) {
                EntityPlayerMP player = (EntityPlayerMP) value;
                if (player.getCommandSenderName().equalsIgnoreCase(requested)) return player;
            }
        }
        return null;
    }

    private static Map<String, Object> blockResult(int x, int y, int z, String block, boolean loaded) {
        return map("position", xyz(x, y, z), "dimension", "minecraft:overworld", "block", block,
                "air", "minecraft:air".equals(block), "loaded", loaded);
    }

    private static String blockId(Block block) {
        Object id = Block.blockRegistry.getNameForObject(block);
        return id == null ? "minecraft:air" : String.valueOf(id);
    }

    private static void validateVolume(int x, int y, int z, int sx, int sy, int sz) {
        if ((long) sx * sy * sz > MAX_VOLUME) throw new IllegalArgumentException("volume must contain at most 4096 cells");
        if ((long) x + sx - 1 > Integer.MAX_VALUE || (long) z + sz - 1 > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("region exceeds the 32-bit coordinate range");
        }
        if (y + sy - 1 > 255) throw new IllegalArgumentException("region exceeds the legacy world height");
    }

    private static int size(JsonObject input, String key) {
        int value = integer(input, key);
        if (value < 1 || value > 32) throw new IllegalArgumentException(key + " must be between 1 and 32");
        return value;
    }

    private static int coordinate(JsonObject input, String key) { return integer(input, key); }

    private static int height(JsonObject input, String key) {
        int value = integer(input, key);
        if (value < 0 || value > 255) throw new IllegalArgumentException(key + " must be between 0 and 255");
        return value;
    }

    private static int integer(JsonObject input, String key) {
        if (!input.has(key) || !input.get(key).isJsonPrimitive()) throw new IllegalArgumentException(key + " must be an integer");
        double value = input.get(key).getAsDouble();
        if (value != Math.rint(value) || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw new IllegalArgumentException(key + " must be a 32-bit integer");
        return (int) value;
    }

    private static String text(JsonObject input, String key, int maximum) {
        String value = requiredText(input, key);
        if (value.length() > maximum) throw new IllegalArgumentException(key + " is too long");
        return value;
    }

    private static String optionalText(JsonObject input, String key) { return input.has(key) ? input.get(key).getAsString() : null; }

    private static String requiredText(JsonObject input, String key) {
        if (!input.has(key) || !input.get(key).isJsonPrimitive()) throw new IllegalArgumentException(key + " must be a string");
        String value = input.get(key).getAsString();
        if (value.length() == 0) throw new IllegalArgumentException(key + " must not be empty");
        return value;
    }

    private static int integerProperty(String key, int fallback) {
        try { return Integer.parseInt(System.getProperty(key, Integer.toString(fallback))); }
        catch (NumberFormatException failure) { throw new IllegalArgumentException(key + " must be an integer", failure); }
    }

    private Future<Map<String, Object>> schedule(Callable<Map<String, Object>> callable) {
        FutureTask<Map<String, Object>> task = new FutureTask<Map<String, Object>>(callable);
        mainThreadTasks.add(task);
        return task;
    }

    private static byte[] readBody(HttpExchange exchange) throws IOException {
        Headers headers = exchange.getRequestHeaders();
        String length = headers.getFirst("Content-length");
        if (length != null && Long.parseLong(length) > MAX_BODY_BYTES) throw new IOException("request body is too large");
        InputStream input = exchange.getRequestBody();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192]; int total = 0; int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > MAX_BODY_BYTES) throw new IOException("request body is too large");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private boolean authorized(HttpExchange exchange) {
        String supplied = exchange.getRequestHeaders().getFirst("X-Lodestone-Token");
        return supplied != null && token.equals(supplied);
    }

    private static void respond(HttpExchange exchange, int status, Map<String, Object> body) throws IOException {
        byte[] bytes = new com.google.gson.Gson().toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        OutputStream output = exchange.getResponseBody();
        try { output.write(bytes); } finally { output.close(); }
    }

    private static Map<String, Object> success(Map<String, Object> value) { return map("ok", true, "result", value); }
    private static Map<String, Object> error(String code, String message) { return map("ok", false, "error", map("code", code, "message", message)); }
    private static String message(Throwable failure) { Throwable current = failure; while (current.getCause() != null) current = current.getCause(); return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage(); }
    private static Map<String, Object> xyz(int x, int y, int z) { return map("x", x, "y", y, "z", z); }
    private static Map<String, Object> map(Object... values) { Map<String, Object> result = new LinkedHashMap<String, Object>(); for (int i = 0; i < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]); return result; }

    private static void requireDeadline(long deadline) {
        if (System.currentTimeMillis() >= deadline) throw new IllegalStateException("legacy game operation deadline expired");
    }

    private static String token() {
        String configured = System.getProperty("lodestone.legacy.token", System.getenv("LODESTONE_LEGACY_TOKEN"));
        if (configured != null && configured.trim().length() > 0) return configured.trim();
        File config = Loader.instance().getConfigDir();
        Path path = new File(config, "lodestone-legacy.token").toPath();
        try {
            byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
            String generated = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            return LegacyTokenFile.readOrCreate(path, generated);
        } catch (IOException failure) {
            throw new IllegalStateException("unable to create Lodestone legacy token", failure);
        }
    }

    private static final class PreparedChange {
        private final int x; private final int y; private final int z;
        private final String requested; private final Block next; private final Block previous;
        private final int previousMetadata; private final String key;
        private PreparedChange(int x, int y, int z, String requested, Block next, Block previous,
                               int previousMetadata, String key) {
            this.x = x; this.y = y; this.z = z; this.requested = requested; this.next = next;
            this.previous = previous; this.previousMetadata = previousMetadata; this.key = key;
        }
    }
}
