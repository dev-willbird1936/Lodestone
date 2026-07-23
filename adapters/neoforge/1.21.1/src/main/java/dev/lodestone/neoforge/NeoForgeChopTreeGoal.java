// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import dev.lodestone.adapter.InvocationContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.BlockHitResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/**
 * Backs {@code minecraft.goal.gather.chop-tree}: locate the nearest matching tree with a directed
 * bounded scan (never wandering to search further), approach its trunk with the shared
 * {@link NeoForgeGotoMovement} engine, mine every connected trunk column bottom-to-top (handling a
 * dark-oak-style 2x2 trunk and leaves occluding the hit), then optionally collect nearby drops with
 * the shared {@link NeoForgeDropCollector} engine invoked directly - not as a nested goal actor,
 * since only one native goal actor may own client input at a time.
 */
final class NeoForgeChopTreeGoal {
    private static final int MAX_SCAN_VISITED = 65536;
    private static final int MAX_TRUNK_SEARCH_HEIGHT = 40;
    private static final int MINE_TIMEOUT_TICKS = 100;
    private static final int MAX_TARGET_FAILURES_PER_LOG = 3;
    private static final double COLLECT_RADIUS = 16.0;
    private static final double APPROACH_ARRIVE_RADIUS = 2.0;
    private static final float REACH = 5.0F;

    private enum Stage { LOCATE, APPROACH, MINE, COLLECT, DONE }

    private final InvocationContext invocation;
    private final CompletableFuture<Map<String, Object>> result;
    private final String species;
    private final int maxDistance;
    private final boolean collectDrops;
    private final int timeoutTicks;

    private Stage stage = Stage.LOCATE;
    private int ticks;
    private BlockPos treePosition;
    private List<BlockPos> trunkColumns = List.of();
    private int columnIndex;
    private int heightIndex;
    private List<BlockPos> currentColumnLogs = List.of();
    private NeoForgeGotoMovement movement;
    private int mineTicks;
    private int targetFailures;
    private int logsMined;
    private NeoForgeDropCollector collector;

    NeoForgeChopTreeGoal(InvocationContext invocation, CompletableFuture<Map<String, Object>> result) {
        this.invocation = invocation;
        this.result = result;
        var input = invocation.request().input();
        this.species = normalizeSpecies(input.get("species"));
        this.maxDistance = (int) clamp(numberOrDefault(input, "maxDistance", 32), 4, 64);
        this.collectDrops = !Boolean.FALSE.equals(input.get("collectDrops"));
        this.timeoutTicks = (int) clamp(numberOrDefault(input, "timeoutTicks", 2400), 100, 24000);
    }

    boolean done() {
        return result.isDone();
    }

    void tick(Minecraft client) {
        if (done()) return;
        try {
            invocation.cancellation().throwIfCancelled();
            if (++ticks > timeoutTicks) {
                complete(client, "timeout");
                return;
            }
            if (client.level == null || client.player == null || client.gameMode == null
                    || client.screen != null) {
                return;
            }
            if (client.gameMode.getPlayerMode() != GameType.SURVIVAL
                    && client.gameMode.getPlayerMode() != GameType.ADVENTURE) {
                throw new IllegalStateException("minecraft.goal.gather.chop-tree requires survival or adventure mode");
            }
            switch (stage) {
                case LOCATE -> tickLocate(client);
                case APPROACH -> tickApproach(client);
                case MINE -> tickMine(client);
                case COLLECT -> tickCollect(client);
                case DONE -> { }
            }
        } catch (Throwable failure) {
            releaseInput(client);
            result.completeExceptionally(failure);
        }
    }

    private void tickLocate(Minecraft client) {
        var level = client.level;
        var center = client.player.blockPosition();
        var matches = new ArrayList<BlockPos>();
        var scanned = 0;
        scanning:
        for (var y = center.getY() - maxDistance; y <= center.getY() + maxDistance; y++) {
            for (var x = center.getX() - maxDistance; x <= center.getX() + maxDistance; x++) {
                for (var z = center.getZ() - maxDistance; z <= center.getZ() + maxDistance; z++) {
                    if (++scanned > MAX_SCAN_VISITED) {
                        break scanning;
                    }
                    var pos = new BlockPos(x, y, z);
                    if (!level.isInWorldBounds(pos) || !level.isLoaded(pos)) continue;
                    var state = level.getBlockState(pos);
                    if (!state.is(BlockTags.LOGS)) continue;
                    if (species != null && !logMatchesSpecies(NeoForgeHardScript.blockId(state), species)) continue;
                    matches.add(pos.immutable());
                }
            }
        }
        if (matches.isEmpty()) {
            complete(client, "no-tree-found");
            return;
        }
        matches.sort(java.util.Comparator.comparingDouble((BlockPos pos) -> pos.distSqr(center)));
        var base = findColumnBase(level, matches.get(0));
        treePosition = base;
        trunkColumns = trunkColumnsAt(base, pos -> level.isLoaded(pos) && level.getBlockState(pos).is(BlockTags.LOGS));
        columnIndex = 0;
        stage = Stage.APPROACH;
    }

    private void tickApproach(Minecraft client) {
        if (movement == null) movement = new NeoForgeGotoMovement(false, true);
        var target = trunkColumns.get(columnIndex);
        var outcome = movement.tick(client, client.player, target, APPROACH_ARRIVE_RADIUS);
        switch (outcome) {
            case ARRIVED -> {
                movement.releaseInput(client);
                movement = null;
                beginColumn(client.level, target);
            }
            case NO_ROUTE, MUTATION_FAILURE -> complete(client, "unreachable");
            case MOVING -> { }
        }
    }

    private void beginColumn(ClientLevel level, BlockPos base) {
        currentColumnLogs = verticalLogExtent(level, base);
        heightIndex = 0;
        mineTicks = 0;
        targetFailures = 0;
        stage = Stage.MINE;
    }

    private void tickMine(Minecraft client) {
        if (heightIndex >= currentColumnLogs.size()) {
            columnIndex++;
            if (columnIndex >= trunkColumns.size()) {
                if (collectDrops) {
                    stage = Stage.COLLECT;
                } else {
                    complete(client, "complete");
                }
            } else {
                stage = Stage.APPROACH;
            }
            return;
        }
        var player = client.player;
        var target = currentColumnLogs.get(heightIndex);
        var state = client.level.getBlockState(target);
        if (!state.is(BlockTags.LOGS)) {
            logsMined++;
            advancePastCurrentLog(client);
            return;
        }
        var hit = player.pick(REACH, 0.0F, false);
        if (hit instanceof BlockHitResult blockHit && blockHit.getBlockPos().equals(target)) {
            client.options.keyUp.setDown(false);
            client.options.keySprint.setDown(false);
            if (mineTicks == 0 || mineTicks % 5 == 1) KeyMapping.click(client.options.keyAttack.getKey());
            client.options.keyAttack.setDown(true);
            mineTicks++;
            if (mineTicks > MINE_TIMEOUT_TICKS) {
                client.options.keyAttack.setDown(false);
                advancePastCurrentLog(client);
            }
            return;
        }
        if (hit instanceof BlockHitResult blockHit
                && client.level.getBlockState(blockHit.getBlockPos()).is(BlockTags.LEAVES)) {
            if (mineTicks == 0 || mineTicks % 5 == 1) KeyMapping.click(client.options.keyAttack.getKey());
            client.options.keyAttack.setDown(true);
            mineTicks++;
            if (mineTicks > MINE_TIMEOUT_TICKS) {
                client.options.keyAttack.setDown(false);
                mineTicks = 0;
                targetFailures++;
            }
            return;
        }
        client.options.keyAttack.setDown(false);
        NeoForgeHardScript.aimAt(player, target);
        targetFailures++;
        if (targetFailures > MAX_TARGET_FAILURES_PER_LOG) {
            // Out of reach even after repositioning attempts - stop this column here rather than
            // spinning until the overall timeout.
            heightIndex = currentColumnLogs.size();
            mineTicks = 0;
            targetFailures = 0;
        }
    }

    private void advancePastCurrentLog(Minecraft client) {
        heightIndex++;
        mineTicks = 0;
        targetFailures = 0;
        client.options.keyAttack.setDown(false);
    }

    private void tickCollect(Minecraft client) {
        if (collector == null) collector = new NeoForgeDropCollector(COLLECT_RADIUS, null);
        if (collector.tick(client, client.player) == NeoForgeDropCollector.Outcome.DONE) {
            complete(client, "complete");
        }
    }

    private void complete(Minecraft client, String reason) {
        releaseInput(client);
        var logsCollected = new LinkedHashMap<String, Object>();
        var saplingsCollected = 0;
        if (collector != null) {
            for (var entry : collector.collectedDelta(client.player).entrySet()) {
                var id = entry.getKey();
                if (id.contains("sapling")) {
                    saplingsCollected += entry.getValue();
                } else if (id.endsWith("_log") || id.endsWith("_wood")) {
                    logsCollected.put(id, entry.getValue());
                }
            }
        }
        var output = new LinkedHashMap<String, Object>();
        output.put("logsMined", logsMined);
        output.put("logsCollected", Map.copyOf(logsCollected));
        output.put("saplingsCollected", saplingsCollected);
        if (treePosition != null) output.put("treePosition", NeoForgeGotoMovement.positionMap(treePosition));
        output.put("reason", reason);
        result.complete(Map.copyOf(output));
        stage = Stage.DONE;
    }

    private void releaseInput(Minecraft client) {
        if (movement != null) movement.releaseInput(client);
        if (collector != null) collector.releaseInput(client);
        client.options.keyAttack.setDown(false);
        client.options.keyUp.setDown(false);
        client.options.keySprint.setDown(false);
    }

    private static BlockPos findColumnBase(ClientLevel level, BlockPos anyLogInColumn) {
        var current = anyLogInColumn;
        var steps = 0;
        while (steps < MAX_TRUNK_SEARCH_HEIGHT && level.isLoaded(current.below())
                && level.getBlockState(current.below()).is(BlockTags.LOGS)) {
            current = current.below();
            steps++;
        }
        return current;
    }

    private static List<BlockPos> verticalLogExtent(ClientLevel level, BlockPos base) {
        var logs = new ArrayList<BlockPos>();
        var current = base;
        var steps = 0;
        while (steps < MAX_TRUNK_SEARCH_HEIGHT && level.isLoaded(current) && level.getBlockState(current).is(BlockTags.LOGS)) {
            logs.add(current.immutable());
            current = current.above();
            steps++;
        }
        return logs;
    }

    /**
     * Every 2x2 square of positions at {@code origin}'s height that contains {@code origin} as one
     * corner, tested via {@code isLog} until one square matches fully (a dark-oak-style 2x2 trunk),
     * else a single-column result of just {@code origin}. Package-private and pure (given a plain
     * predicate) for direct testing.
     */
    static List<BlockPos> trunkColumnsAt(BlockPos origin, Predicate<BlockPos> isLog) {
        var squares = List.of(
                new int[][]{{0, 0}, {-1, 0}, {0, -1}, {-1, -1}},
                new int[][]{{0, 0}, {1, 0}, {0, -1}, {1, -1}},
                new int[][]{{0, 0}, {-1, 0}, {0, 1}, {-1, 1}},
                new int[][]{{0, 0}, {1, 0}, {0, 1}, {1, 1}});
        for (var square : squares) {
            var positions = new ArrayList<BlockPos>(4);
            for (var offset : square) positions.add(origin.offset(offset[0], 0, offset[1]));
            if (positions.stream().allMatch(isLog)) return List.copyOf(positions);
        }
        return List.of(origin);
    }

    /** True when {@code blockId} names a log/wood block of {@code species} (e.g. "oak" matches
     * minecraft:oak_log, minecraft:stripped_oak_log, minecraft:oak_wood, and the stripped wood
     * variant), or when {@code species} is {@code null}/blank (match any species). Compares the
     * exact block name after the namespace, not a bare substring - "oak" must not match
     * "dark_oak_log". Package-private and pure for direct testing. */
    static boolean logMatchesSpecies(String blockId, String species) {
        if (species == null || species.isBlank()) return true;
        var id = blockId.trim().toLowerCase(Locale.ROOT);
        var colon = id.indexOf(':');
        var name = colon >= 0 ? id.substring(colon + 1) : id;
        var normalized = species.trim().toLowerCase(Locale.ROOT);
        return name.equals(normalized + "_log") || name.equals("stripped_" + normalized + "_log")
                || name.equals(normalized + "_wood") || name.equals("stripped_" + normalized + "_wood");
    }

    private static String normalizeSpecies(Object value) {
        if (value == null) return null;
        var text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        return text.isBlank() ? null : text;
    }

    private static double numberOrDefault(Map<String, Object> input, String key, double fallback) {
        return input.get(key) instanceof Number number ? number.doubleValue() : fallback;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
