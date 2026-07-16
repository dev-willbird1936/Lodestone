// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import dev.lodestone.adapter.InvocationContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Native-input actor for the creative wool-tree / survival zombie-defense goal.
 *
 * <p>Allowed setup commands are dispatched through an integrated-server source with output
 * suppressed. The tree itself is built and removed exclusively with vanilla key mappings. Combat
 * is never a fixed kill stage: every defensive response is gated by a fresh client-side zombie
 * visibility and distance observation.</p>
 */
final class NeoForgeWoolTreeZombieGoal {
    private static final int MAX_TOTAL_TICKS = 7_200;
    private static final double THREAT_OBSERVE_DISTANCE = 6.5;
    private static final double ATTACK_DISTANCE = 3.7;
    private static final String ZOMBIE_TAG = "lodestone_goal_zombie";

    private final InvocationContext invocation;
    private final CompletableFuture<Map<String, Object>> result;
    private final boolean suppressInGameMessages;
    private final NeoForgeGoalPolicy policy;
    private final LinkedHashSet<String> inputActions = new LinkedHashSet<>();
    private final List<String> setupCommands = new ArrayList<>();
    private final List<String> defenseDiagnostics = new ArrayList<>();
    private final List<Placement> placements = new ArrayList<>();
    private final List<BlockPos> mineTargets = new ArrayList<>();

    private Stage stage = Stage.WAIT_WORLD;
    private CompletableFuture<Void> pendingCommand;
    private int totalTicks;
    private int stageTicks;
    private int waitTicks;
    private int placementIndex;
    private int mineIndex;
    private int trunkLogsPlaced;
    private int woolLeavesPlaced;
    private int treeMinedBlocks;
    private int inGameMessagesEmitted;
    private int defensePolicyEvaluations;
    private int threatDetections;
    private int defensiveResponses;
    private int defensiveAttacks;
    private int navigationStuckTicks;
    private int placementClicks;
    private int restoreFlightTicks;
    private int lastFlightJumpEdgeTick = -1_000;
    private long worldGameTimeAtStart;
    private double navigationLastDistance = Double.POSITIVE_INFINITY;
    private BlockPos navigationDestination;
    private boolean flightEscapeActive;
    private double teleportDistance;
    private boolean freshWorld;
    private boolean creativeSetupMode;
    private boolean manualTreeBuilt;
    private boolean zombieSetupComplete;
    private boolean teleportedAway;
    private boolean diamondSwordEquipped;
    private boolean survivalMode;
    private boolean zombieObserved;
    private boolean threatActive;
    private boolean zombieKilledByReactiveDefense;
    private UUID observedZombieId;
    private BlockPos buildOrigin;
    private BlockPos zombiePosition;
    private BlockPos awayPosition;
    private BlockPos miningVantage;

    NeoForgeWoolTreeZombieGoal(InvocationContext invocation,
                               CompletableFuture<Map<String, Object>> result) {
        this.invocation = invocation;
        this.result = result;
        this.suppressInGameMessages = Boolean.TRUE.equals(
                invocation.request().input().get("suppressInGameMessages"));
        this.policy = NeoForgeGoalPolicy.from(invocation.request().input());
    }

    boolean done() {
        return result.isDone();
    }

    void tick(Minecraft client) {
        if (done()) return;
        try {
            invocation.cancellation().throwIfCancelled();
            if (++totalTicks > MAX_TOTAL_TICKS) {
                throw new IllegalStateException("wool-tree zombie-defense goal exceeded its bounded input budget");
            }
            if (stage == Stage.WAIT_WORLD && (!policy.allowBlockBreaking() || !policy.allowBlockPlacing())) {
                throw new IllegalStateException("wool-tree defense workflow requires block breaking and placing permissions");
            }
            if (waitTicks > 0) {
                waitTicks--;
                return;
            }
            stageTicks++;
            switch (stage) {
                case WAIT_WORLD -> waitForWorld(client);
                case SET_CREATIVE -> command(client, "gamemode creative @s", Stage.SILENCE_ADVANCEMENTS, 16);
                case SILENCE_ADVANCEMENTS -> command(client, "gamerule announceAdvancements false", Stage.GIVE_LOGS, 8);
                case GIVE_LOGS -> command(client, "give @s minecraft:oak_log 8", Stage.GIVE_WOOL, 12);
                case GIVE_WOOL -> command(client, "give @s minecraft:green_wool 32", Stage.ENABLE_FLIGHT, 16);
                case ENABLE_FLIGHT -> enableCreativeFlight(client);
                case SELECT_SITE -> selectSite(client);
                case BUILD_TREE -> buildTree(client);
                case VERIFY_BUILD -> verifyBuild(client);
                case SUMMON_ZOMBIE -> summonZombie(client);
                case EQUIP_ZOMBIE -> command(client,
                        "item replace entity @e[type=minecraft:zombie,tag=" + ZOMBIE_TAG
                                + ",sort=nearest,limit=1] armor.head with minecraft:carved_pumpkin",
                        Stage.TELEPORT_AWAY, 12);
                case TELEPORT_AWAY -> teleportAway(client);
                case CLEAR_INVENTORY -> command(client, "clear @s", Stage.GIVE_SWORD, 12);
                case GIVE_SWORD -> command(client, "give @s minecraft:diamond_sword 1", Stage.SET_SURVIVAL, 16);
                case SET_SURVIVAL -> command(client, "gamemode survival @s", Stage.WAIT_SURVIVAL, 20);
                case WAIT_SURVIVAL -> waitForSurvival(client);
                case MINE_WITH_DEFENSE -> mineWithDefense(client);
                case VERIFY -> verify(client);
                case COMPLETE_DELAY -> complete(client);
            }
        } catch (Throwable failure) {
            releaseInput(client);
            result.completeExceptionally(failure);
        }
    }

    private void waitForWorld(Minecraft client) {
        if (client.level == null || client.player == null || client.gameMode == null || client.screen != null) return;
        if (stageTicks < 40) return;
        worldGameTimeAtStart = client.level.getGameTime();
        freshWorld = worldGameTimeAtStart < 12_000L;
        announce(client, "Fresh world ready; beginning creative manual-build setup");
        transition(Stage.SET_CREATIVE, 0);
    }

    private void selectSite(Minecraft client) {
        var player = requirePlayer(client);
        creativeSetupMode = client.gameMode != null && client.gameMode.getPlayerMode() == GameType.CREATIVE;
        if (!creativeSetupMode) {
            if (stageTicks > 120) throw new IllegalStateException("creative setup mode was not observed");
            return;
        }
        var logs = hotbarSlot(player, Items.OAK_LOG);
        var wool = hotbarSlot(player, Items.GREEN_WOOL);
        if (logs < 0 || wool < 0) {
            if (stageTicks > 120) throw new IllegalStateException("command-granted build materials were not observed");
            return;
        }
        buildOrigin = findBuildSite(client);
        if (buildOrigin == null) throw new IllegalStateException("no nearby input-buildable tree site was found");
        createPlacements(client);
        announce(client, "Building a three-log tree with nine green-wool leaves using normal place input");
        transition(Stage.BUILD_TREE, 10);
    }

    private void enableCreativeFlight(Minecraft client) {
        var player = requirePlayer(client);
        if (client.gameMode == null || client.gameMode.getPlayerMode() != GameType.CREATIVE) {
            if (stageTicks > 120) throw new IllegalStateException("creative mode unavailable for input flight");
            return;
        }
        if (player.getAbilities().flying) {
            client.options.keyJump.setDown(false);
            lastFlightJumpEdgeTick = totalTicks;
            inputActions.add("move:creative-flight-enabled-by-double-jump");
            transition(Stage.SELECT_SITE, 8);
            return;
        }
        var phase = stageTicks % 10;
        client.options.keyJump.setDown(phase == 1 || phase == 4);
        if (phase == 1 || phase == 4) inputActions.add("move:key.jump-flight-toggle-pulse");
        if (stageTicks > 160) throw new IllegalStateException("normal double-jump input did not enable creative flight");
    }

    private void createPlacements(Minecraft client) {
        placements.clear();
        var base = buildOrigin;
        placements.add(placement(client, base, base.below(), Direction.UP, Items.OAK_LOG));
        placements.add(placement(client, base.above(), base, Direction.UP, Items.OAK_LOG));
        placements.add(placement(client, base.above(2), base.above(), Direction.UP, Items.OAK_LOG));
        for (var direction : Direction.Plane.HORIZONTAL) {
            placements.add(placement(client, base.above().relative(direction), base.above(), direction,
                    Items.GREEN_WOOL));
        }
        for (var direction : Direction.Plane.HORIZONTAL) {
            placements.add(placement(client, base.above(2).relative(direction), base.above(2), direction,
                    Items.GREEN_WOOL));
        }
        placements.add(placement(client, base.above(3), base.above(2), Direction.UP, Items.GREEN_WOOL));
        mineTargets.clear();
        placements.stream().filter(value -> value.item() == Items.GREEN_WOOL)
                .map(Placement::target).forEach(mineTargets::add);
        mineTargets.add(base.above(2));
        mineTargets.add(base.above());
        mineTargets.add(base);
    }

    private Placement placement(Minecraft client, BlockPos target, BlockPos support,
                                Direction face, Item item) {
        var desired = face.getAxis().isHorizontal()
                ? target.relative(face, 2)
                : support.relative(Direction.SOUTH, 3);
        var vantage = findFlightVantage(client, support, face);
        if (vantage == null) throw new IllegalStateException(
                "no collision-free normal-input flight vantage for " + target + " via " + support + "/" + face);
        return new Placement(target.immutable(), support.immutable(), face, item, vantage.immutable());
    }

    private void buildTree(Minecraft client) {
        if (placementIndex >= placements.size()) {
            transition(Stage.VERIFY_BUILD, 20);
            return;
        }
        var player = requirePlayer(client);
        if (client.gameMode != null && client.gameMode.getPlayerMode() == GameType.CREATIVE
                && !player.getAbilities().flying) {
            var phase = ++restoreFlightTicks % 10;
            client.options.keyJump.setDown(phase == 1 || phase == 4);
            if (phase == 1 || phase == 4) inputActions.add("move:key.jump-restore-creative-flight");
            if (restoreFlightTicks > 160) throw new IllegalStateException(
                    "normal double-jump input could not restore creative flight during build");
            return;
        }
        restoreFlightTicks = 0;
        var placement = placements.get(placementIndex);
        if (isExpectedPlacedBlock(client, placement)) {
            stopAttack(client);
            if (placement.item() == Items.OAK_LOG) trunkLogsPlaced++;
            else woolLeavesPlaced++;
            placementIndex++;
            placementClicks = 0;
            stageTicks = 0;
            waitTicks = 5;
            return;
        }
        if (!client.level.getBlockState(placement.target()).isAir())
            throw new IllegalStateException("manual placement target became obstructed: " + placement.target());
        if (player.getBoundingBox().intersects(new AABB(placement.target()))) {
            stopMovement(client);
            lookAt(player, Vec3.atCenterOf(placement.target()));
            client.options.keyDown.setDown(true);
            inputActions.add("place:key.retreat-from-target:" + placement.target());
            if (stageTicks > 900) throw new IllegalStateException(
                    "normal backward input could not leave placement target " + placement.target());
            return;
        }
        client.options.keyDown.setDown(false);
        lookAt(player, facePoint(placement.support(), placement.face()));
        var currentHit = player.pick(5.0, 0.0F, false);
        var safelyAimedFromCurrentPosition = !player.getBoundingBox().intersects(new AABB(placement.target()))
                && currentHit instanceof BlockHitResult currentBlockHit
                && currentBlockHit.getBlockPos().equals(placement.support())
                && currentBlockHit.getDirection() == placement.face();
        if (!safelyAimedFromCurrentPosition
                && !navigateTo(client, placement.vantage(), 0.8, "tree placement")) {
            if (stageTicks > 900) throw new IllegalStateException("could not reach placement vantage "
                    + placement.vantage() + "; player=" + player.blockPosition() + ", flying="
                    + player.getAbilities().flying + ", eyeY=" + rounded(player.getEyeY()));
            return;
        }
        stopMovement(client);
        if (player.getBoundingBox().intersects(new AABB(placement.target()))) {
            navigateTo(client, placement.vantage(), 0.2, "placement collision correction");
            if (stageTicks > 900) throw new IllegalStateException(
                    "normal-input placement could not leave target collision volume " + placement.target()
                            + "; player=" + player.blockPosition() + ", vantage=" + placement.vantage());
            return;
        }
        stopAttack(client);
        var slot = hotbarSlot(player, placement.item());
        if (slot < 0) throw new IllegalStateException("build item left hotbar: " + placement.item());
        selectHotbar(client, slot);
        if (!player.getMainHandItem().is(placement.item())) {
            if (stageTicks > 600) throw new IllegalStateException(
                    "normal hotbar input did not equip build item " + placement.item());
            return;
        }
        if (placement.face() == Direction.UP
                && player.getEyeY() <= placement.support().getY() + 1.02) {
            stopAttack(client);
            client.options.keyUse.setDown(false);
            client.options.keyJump.setDown(true);
            inputActions.add("place:key.jump-for-upper-face:" + placement.target());
            return;
        }
        client.options.keyJump.setDown(false);
        lookAt(player, facePoint(placement.support(), placement.face()));
        var hit = player.pick(5.0, 0.0F, false);
        var aimed = hit instanceof BlockHitResult blockHit
                && blockHit.getBlockPos().equals(placement.support())
                && blockHit.getDirection() == placement.face();
        var hitDescription = hit instanceof BlockHitResult blockHit
                ? blockHit.getBlockPos() + "/" + blockHit.getDirection() : hit.getType().toString();
        if (aimed && stageTicks % 20 == 1) {
            KeyMapping.click(client.options.keyUse.getKey());
            inputActions.add("place:key.use:" + placement.item() + ":" + placement.target());
            placementClicks++;
        }
        if (placementClicks > 12 || stageTicks > 600) {
            throw new IllegalStateException("normal use input could not place " + placement.item()
                    + " at " + placement.target() + "; aimed=" + aimed + ", hit=" + hitDescription
                    + ", expected=" + placement.support() + "/" + placement.face()
                    + ", player=" + player.blockPosition() + ", eyeY=" + rounded(player.getEyeY())
                    + ", held=" + player.getMainHandItem().getItem());
        }
    }

    private void verifyBuild(Minecraft client) {
        var actualLogs = placements.stream().filter(value -> value.item() == Items.OAK_LOG)
                .filter(value -> isExpectedPlacedBlock(client, value)).count();
        var actualWool = placements.stream().filter(value -> value.item() == Items.GREEN_WOOL)
                .filter(value -> isExpectedPlacedBlock(client, value)).count();
        manualTreeBuilt = actualLogs == 3 && actualWool == 9 && trunkLogsPlaced == 3 && woolLeavesPlaced == 9;
        if (!manualTreeBuilt) {
            throw new IllegalStateException("manual tree readback failed: logs=" + actualLogs + ", wool=" + actualWool);
        }
        zombiePosition = nearestStandable(client, buildOrigin.relative(Direction.EAST, 4), 3);
        if (zombiePosition == null) throw new IllegalStateException("no nearby standable zombie setup position");
        announce(client, "Manual wool tree complete; setting up nearby zombie with silent commands");
        transition(Stage.SUMMON_ZOMBIE, 10);
    }

    private void summonZombie(Minecraft client) {
        var command = String.format(Locale.ROOT,
                "summon minecraft:zombie %.1f %d %.1f {Tags:[\"%s\"],PersistenceRequired:1b}",
                zombiePosition.getX() + 0.5, zombiePosition.getY(), zombiePosition.getZ() + 0.5, ZOMBIE_TAG);
        command(client, command, Stage.EQUIP_ZOMBIE, 20);
    }

    private void teleportAway(Minecraft client) {
        if (awayPosition == null) {
            awayPosition = findRouteAwareAwayPosition(client);
            if (awayPosition == null) throw new IllegalStateException("no bounded teleport-away position found");
            teleportDistance = Math.sqrt(buildOrigin.distSqr(awayPosition));
            if (teleportDistance < 6.0 || teleportDistance > 14.0) {
                throw new IllegalStateException("teleport-away distance was outside bounded goal range: " + teleportDistance);
            }
        }
        var command = String.format(Locale.ROOT, "tp @s %.1f %d %.1f",
                awayPosition.getX() + 0.5, awayPosition.getY(), awayPosition.getZ() + 0.5);
        command(client, command, Stage.CLEAR_INVENTORY, 16);
        if (stage == Stage.CLEAR_INVENTORY) teleportedAway = true;
    }

    private void waitForSurvival(Minecraft client) {
        var player = requirePlayer(client);
        survivalMode = client.gameMode != null && client.gameMode.getPlayerMode() == GameType.SURVIVAL;
        var swordSlot = hotbarSlot(player, Items.DIAMOND_SWORD);
        if (!survivalMode || swordSlot < 0) {
            if (stageTicks > 160) throw new IllegalStateException("survival mode or command-granted diamond sword not observed");
            return;
        }
        selectHotbar(client, swordSlot);
        diamondSwordEquipped = player.getMainHandItem().is(Items.DIAMOND_SWORD)
                || player.getInventory().selected == swordSlot;
        var zombie = findObservedZombie(client);
        if (zombie == null) {
            if (stageTicks > 200) throw new IllegalStateException("setup zombie was not observed by client");
            return;
        }
        zombieSetupComplete = true;
        zombieObserved = true;
        observedZombieId = zombie.getUUID();
        defenseDiagnostics.add("setup-observed:zombie=" + observedZombieId
                + ",distance=" + rounded(player.distanceTo(zombie)));
        announce(client, "Survival ready with diamond sword; mining while evaluating live zombie threat");
        transition(Stage.MINE_WITH_DEFENSE, 20);
    }

    private void mineWithDefense(Minecraft client) {
        var player = requirePlayer(client);
        if (!player.isAlive() || player.getHealth() <= 0.0F) {
            throw new IllegalStateException("player died during reactive tree mining");
        }
        var swordSlot = hotbarSlot(player, Items.DIAMOND_SWORD);
        if (swordSlot < 0) throw new IllegalStateException("diamond sword was lost during survival gameplay");
        selectHotbar(client, swordSlot);
        diamondSwordEquipped = true;

        if (evaluateDefense(client)) return;
        if (mineIndex >= mineTargets.size()) {
            stopAttack(client);
            transition(Stage.VERIFY, 20);
            return;
        }
        var target = mineTargets.get(mineIndex);
        if (client.level.getBlockState(target).isAir()) {
            stopAttack(client);
            treeMinedBlocks++;
            mineIndex++;
            stageTicks = 0;
            waitTicks = 4;
            return;
        }
        if (miningVantage == null) throw new IllegalStateException("route-aware mining vantage was not retained");
        if (!navigateTo(client, miningVantage, 1.3, "tree mining")) {
            stopAttack(client);
            if (stageTicks > 900) throw new IllegalStateException("could not approach tree mining target " + target
                    + "; player=" + player.blockPosition() + ", vantage=" + miningVantage);
            return;
        }
        stopMovement(client);
        lookAt(player, Vec3.atCenterOf(target));
        client.options.keyAttack.setDown(true);
        inputActions.add("mine:key.attack-held-with-diamond-sword:" + target);
        if (stageTicks > 900) throw new IllegalStateException("normal attack input could not mine tree block " + target);
    }

    /** Returns true only while a fresh observed threat is controlling this tick. */
    private boolean evaluateDefense(Minecraft client) {
        var player = requirePlayer(client);
        defensePolicyEvaluations++;
        var zombie = findObservedZombie(client);
        if (zombie == null || !zombie.isAlive()) {
            if (observedZombieId != null && defensiveAttacks > 0) zombieKilledByReactiveDefense = true;
            threatActive = false;
            stopDefensiveMovement(client);
            return false;
        }
        zombieObserved = true;
        observedZombieId = zombie.getUUID();
        var distance = player.distanceTo(zombie);
        var visible = player.hasLineOfSight(zombie);
        if (defensePolicyEvaluations == 1 || defensePolicyEvaluations % 20 == 0) {
            defenseDiagnostics.add("evaluation=" + defensePolicyEvaluations + ",zombieVisible=" + visible
                    + ",distance=" + rounded(distance) + ",action="
                    + (visible && distance <= THREAT_OBSERVE_DISTANCE ? "defend" : "continue-mining"));
        }
        if (!visible || distance > THREAT_OBSERVE_DISTANCE) {
            threatActive = false;
            stopDefensiveMovement(client);
            return false;
        }
        stopAttack(client);
        if (!threatActive) {
            threatActive = true;
            threatDetections++;
            defenseDiagnostics.add("threat-detected:distance=" + rounded(distance) + ",visible=true");
        }
        lookAt(player, zombie.getEyePosition());
        defensiveResponses++;
        inputActions.add("defense:face-observed-zombie");
        if (distance < 2.5) {
            client.options.keyDown.setDown(true);
            inputActions.add("defense:key.back-held");
        } else {
            client.options.keyDown.setDown(false);
        }
        if (distance <= ATTACK_DISTANCE && player.getAttackStrengthScale(0.0F) >= 0.85F) {
            KeyMapping.click(client.options.keyAttack.getKey());
            defensiveAttacks++;
            inputActions.add("defense:key.attack-reactive");
            defenseDiagnostics.add("reactive-attack=" + defensiveAttacks + ",distance=" + rounded(distance));
        }
        return true;
    }

    private void verify(Minecraft client) {
        releaseInput(client);
        var player = requirePlayer(client);
        var remaining = (int) mineTargets.stream()
                .filter(position -> !client.level.getBlockState(position).isAir()).count();
        var fullTreeMined = remaining == 0 && treeMinedBlocks == mineTargets.size();
        var reactiveDefenseEvaluated = defensePolicyEvaluations > 0;
        if (!fullTreeMined || !reactiveDefenseEvaluated || threatDetections < 1
                || defensiveResponses < 1 || defensiveAttacks < 1 || !player.isAlive()
                || !player.getMainHandItem().is(Items.DIAMOND_SWORD)) {
            throw new IllegalStateException("terminal defense/tree predicates failed: remaining=" + remaining
                    + ", evaluations=" + defensePolicyEvaluations + ", threats=" + threatDetections
                    + ", responses=" + defensiveResponses + ", attacks=" + defensiveAttacks
                    + ", alive=" + player.isAlive() + ", held=" + player.getMainHandItem().getItem());
        }
        announce(client, "Tree cleared after live reactive zombie defense");
        transition(Stage.COMPLETE_DELAY, 80);
    }

    private void complete(Minecraft client) {
        releaseInput(client);
        var player = requirePlayer(client);
        var remaining = (int) mineTargets.stream()
                .filter(position -> !client.level.getBlockState(position).isAir()).count();
        result.complete(Map.ofEntries(
                Map.entry("freshWorld", freshWorld),
                Map.entry("creativeSetupMode", creativeSetupMode),
                Map.entry("manualTreeBuilt", manualTreeBuilt),
                Map.entry("manualPlacementInputOnly", true),
                Map.entry("trunkLogsPlaced", trunkLogsPlaced),
                Map.entry("woolLeavesPlaced", woolLeavesPlaced),
                Map.entry("manuallyPlacedBlocks", placements.size()),
                Map.entry("zombieSetupComplete", zombieSetupComplete),
                Map.entry("teleportedAway", teleportedAway),
                Map.entry("teleportDistance", teleportDistance),
                Map.entry("diamondSwordEquipped", player.getMainHandItem().is(Items.DIAMOND_SWORD)),
                Map.entry("survivalMode", client.gameMode != null && client.gameMode.getPlayerMode() == GameType.SURVIVAL),
                Map.entry("zombieObserved", zombieObserved),
                Map.entry("reactiveDefenseEvaluated", defensePolicyEvaluations > 0),
                Map.entry("defensePolicyEvaluations", defensePolicyEvaluations),
                Map.entry("threatDetections", threatDetections),
                Map.entry("defensiveResponses", defensiveResponses),
                Map.entry("defensiveAttacks", defensiveAttacks),
                Map.entry("zombieKilledByReactiveDefense", zombieKilledByReactiveDefense),
                Map.entry("unconditionalKillRoutine", false),
                Map.entry("treeInitialBlocks", mineTargets.size()),
                Map.entry("treeMinedBlocks", treeMinedBlocks),
                Map.entry("treeRemainingBlocks", remaining),
                Map.entry("fullTreeMined", remaining == 0 && treeMinedBlocks == mineTargets.size()),
                Map.entry("playerAlive", player.isAlive() && player.getHealth() > 0.0F),
                Map.entry("healthAtEnd", player.getHealth()),
                Map.entry("intelligence", policy.intelligence().id()),
                Map.entry("safety", policy.safety().id()),
                Map.entry("policyMode", policy.mode()),
                Map.entry("toolPrerequisiteGuard", policy.toolPrerequisiteGuardEnabled()),
                Map.entry("observation", policy.observation()), Map.entry("combatPolicy", policy.combatPolicy()),
                Map.entry("allowBlockBreaking", policy.allowBlockBreaking()),
                Map.entry("allowBlockPlacing", policy.allowBlockPlacing()),
                Map.entry("setupCommandsUsed", !setupCommands.isEmpty()),
                Map.entry("setupCommandCount", setupCommands.size()),
                Map.entry("setupCommands", List.copyOf(setupCommands)),
                Map.entry("commandFeedbackSuppressed", true),
                Map.entry("suppressInGameMessages", suppressInGameMessages),
                Map.entry("inGameMessagesEmitted", inGameMessagesEmitted),
                Map.entry("directMutationUsed", false),
                Map.entry("defenseDiagnostics", List.copyOf(defenseDiagnostics)),
                Map.entry("inputActions", List.copyOf(inputActions)),
                Map.entry("buildOrigin", position(buildOrigin)),
                Map.entry("awayPosition", position(awayPosition))));
    }

    private void command(Minecraft client, String command, Stage next, int delay) {
        if (pendingCommand == null) {
            var server = client.getSingleplayerServer();
            if (server == null) throw new IllegalStateException("goal setup commands require integrated server");
            var clientPlayerId = requirePlayer(client).getUUID();
            pendingCommand = new CompletableFuture<>();
            setupCommands.add(command);
            server.execute(() -> {
                try {
                    var serverPlayer = server.getPlayerList().getPlayer(clientPlayerId);
                    if (serverPlayer == null) throw new IllegalStateException("no matching integrated-server player for silent command source");
                    var source = serverPlayer.createCommandSourceStack()
                            .withPermission(4).withSuppressedOutput();
                    var parsed = server.getCommands().getDispatcher().parse(command, source);
                    server.getCommands().performCommand(parsed, command);
                    pendingCommand.complete(null);
                } catch (Throwable failure) {
                    pendingCommand.completeExceptionally(failure);
                }
            });
            return;
        }
        if (!pendingCommand.isDone()) return;
        pendingCommand.join();
        pendingCommand = null;
        transition(next, delay);
    }

    private BlockPos findBuildSite(Minecraft client) {
        var player = requirePlayer(client);
        var center = player.blockPosition();
        for (var radius = 3; radius <= 20; radius++) {
            for (var dx = -radius; dx <= radius; dx++) {
                for (var dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    var base = standableInColumn(client, center.getX() + dx, center.getZ() + dz,
                            center.getY(), 6);
                    if (base == null || Math.abs(base.getY() - center.getY()) > 5) continue;
                    var clear = true;
                    for (var placementY = 0; placementY <= 3; placementY++) {
                        if (!client.level.getBlockState(base.above(placementY)).isAir()) clear = false;
                    }
                    for (var direction : Direction.Plane.HORIZONTAL) {
                        if (!client.level.getBlockState(base.above().relative(direction)).isAir()
                                || !client.level.getBlockState(base.above(2).relative(direction)).isAir()) clear = false;
                    }
                    if (clear && allPlacementVantagesAvailable(client, base)
                            && player.position().distanceTo(Vec3.atCenterOf(base)) <= 24.0) return base;
                }
            }
        }
        return null;
    }

    private BlockPos nearestStandable(Minecraft client, BlockPos desired, int radius) {
        BlockPos best = null;
        var bestDistance = Double.POSITIVE_INFINITY;
        for (var dx = -radius; dx <= radius; dx++) {
            for (var dz = -radius; dz <= radius; dz++) {
                var x = desired.getX() + dx;
                var z = desired.getZ() + dz;
                var candidate = standableInColumn(client, x, z, desired.getY(), 7);
                if (candidate == null) continue;
                var distance = candidate.distSqr(desired);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = candidate;
                }
            }
        }
        return best;
    }

    private BlockPos findRouteAwareAwayPosition(Minecraft client) {
        var firstFace = placements.stream().filter(placement -> placement.item() == Items.GREEN_WOOL)
                .findFirst().map(Placement::face).orElse(Direction.SOUTH);
        var directions = new ArrayList<Direction>();
        directions.add(firstFace);
        for (var direction : List.of(Direction.SOUTH, Direction.WEST, Direction.NORTH, Direction.EAST)) {
            if (direction != firstFace) directions.add(direction);
        }
        for (var direction : directions) {
            var nearTree = standableInColumn(client, buildOrigin.relative(direction, 3).getX(),
                    buildOrigin.relative(direction, 3).getZ(), buildOrigin.getY(), 3);
            if (nearTree == null || nearTree.getCenter().distanceTo(Vec3.atCenterOf(buildOrigin)) > 5.0) continue;
            for (var radius = 10; radius >= 6; radius--) {
                var desired = buildOrigin.relative(direction, radius);
                var away = standableInColumn(client, desired.getX(), desired.getZ(), nearTree.getY(), 4);
                if (away == null) continue;
                var distance = Math.sqrt(buildOrigin.distSqr(away));
                if (distance < 6.0 || distance > 14.0 || !groundRouteClear(client, away, nearTree)) continue;
                miningVantage = nearTree.immutable();
                return away.immutable();
            }
        }
        return null;
    }

    private static boolean groundRouteClear(Minecraft client, BlockPos from, BlockPos to) {
        var dx = to.getX() - from.getX();
        var dz = to.getZ() - from.getZ();
        var steps = Math.max(Math.abs(dx), Math.abs(dz));
        var previousY = from.getY();
        for (var step = 1; step <= steps; step++) {
            var progress = step / (double) steps;
            var x = (int) Math.round(from.getX() + dx * progress);
            var z = (int) Math.round(from.getZ() + dz * progress);
            var feet = standableInColumn(client, x, z, previousY, 2);
            if (feet == null || Math.abs(feet.getY() - previousY) > 1) return false;
            previousY = feet.getY();
        }
        return Math.abs(previousY - to.getY()) <= 1;
    }

    private boolean allPlacementVantagesAvailable(Minecraft client, BlockPos base) {
        if (findFlightVantage(client, base.below(), Direction.UP) == null
                || findFlightVantage(client, base, Direction.UP) == null
                || findFlightVantage(client, base.above(), Direction.UP) == null
                || findFlightVantage(client, base.above(2), Direction.UP) == null) return false;
        for (var direction : Direction.Plane.HORIZONTAL) {
            if (findFlightVantage(client, base.above(), direction) == null
                    || findFlightVantage(client, base.above(2), direction) == null) return false;
        }
        return true;
    }

    private BlockPos findFlightVantage(Minecraft client, BlockPos support, Direction face) {
        var directions = face.getAxis().isHorizontal()
                ? List.of(face) : List.of(Direction.SOUTH, Direction.NORTH, Direction.EAST, Direction.WEST);
        var aim = facePoint(support, face);
        for (var direction : directions) {
            for (var distance : List.of(2, 1, 3)) {
                var horizontal = support.relative(direction, distance);
                for (var yOffset : List.of(1, 2, 0, 3, -1)) {
                    var candidate = new BlockPos(horizontal.getX(), support.getY() + yOffset,
                            horizontal.getZ());
                    var eye = new Vec3(candidate.getX() + 0.5, candidate.getY() + 1.62,
                            candidate.getZ() + 0.5);
                    if (flightSpaceClear(client, candidate) && eye.distanceTo(aim) <= 4.3) return candidate;
                }
            }
        }
        return null;
    }

    private static boolean flightSpaceClear(Minecraft client, BlockPos feet) {
        return client.level.getBlockState(feet).getCollisionShape(client.level, feet).isEmpty()
                && client.level.getBlockState(feet.above()).getCollisionShape(client.level, feet.above()).isEmpty();
    }

    private static boolean standable(Minecraft client, BlockPos feet) {
        return !client.level.getBlockState(feet.below()).getCollisionShape(client.level, feet.below()).isEmpty()
                && client.level.getBlockState(feet).getCollisionShape(client.level, feet).isEmpty()
                && client.level.getBlockState(feet.above()).getCollisionShape(client.level, feet.above()).isEmpty();
    }

    private static BlockPos standableInColumn(Minecraft client, int x, int z, int referenceY,
                                               int verticalRadius) {
        for (var distance = 0; distance <= verticalRadius; distance++) {
            var above = new BlockPos(x, referenceY + distance, z);
            if (standable(client, above)) return above;
            if (distance > 0) {
                var below = new BlockPos(x, referenceY - distance, z);
                if (standable(client, below)) return below;
            }
        }
        return null;
    }

    private boolean navigateTo(Minecraft client, BlockPos target, double tolerance, String label) {
        var player = requirePlayer(client);
        if (!target.equals(navigationDestination)) {
            navigationDestination = target.immutable();
            navigationLastDistance = Double.POSITIVE_INFINITY;
            navigationStuckTicks = 0;
            flightEscapeActive = false;
        }
        var dx = target.getX() + 0.5 - player.getX();
        var dy = target.getY() + 0.1 - player.getY();
        var dz = target.getZ() + 0.5 - player.getZ();
        var horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        var distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        var flying = player.getAbilities().flying;
        if (horizontalDistance <= tolerance && Math.abs(dy) <= (flying ? 0.9 : 2.5)) {
            stopMovement(client);
            navigationLastDistance = Double.POSITIVE_INFINITY;
            navigationStuckTicks = 0;
            flightEscapeActive = false;
            return true;
        }
        var yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        player.setYRot(yaw);
        player.setYHeadRot(yaw);
        player.setXRot(8.0F);
        if (flying && navigationStuckTicks > 18 && horizontalDistance > tolerance) {
            flightEscapeActive = true;
        }
        if (horizontalDistance <= tolerance) flightEscapeActive = false;
        var cruise = flying && horizontalDistance > tolerance
                && (horizontalDistance > 3.0 || flightEscapeActive);
        var ascend = flying && ((cruise && player.getY() < target.getY() + 3.0)
                || (!cruise && dy > 0.55));
        var descend = flying && !cruise && dy < -0.55;
        client.options.keyUp.setDown(horizontalDistance > tolerance);
        client.options.keySprint.setDown(horizontalDistance > 4.0);
        var detouring = navigationStuckTicks > 18;
        var detourLeft = detouring && (navigationStuckTicks / 30) % 2 == 0;
        client.options.keyLeft.setDown(!flying && detourLeft);
        client.options.keyRight.setDown(!flying && detouring && !detourLeft);
        var jumpWasDown = client.options.keyJump.isDown();
        var guardedFlightJump = ascend && (jumpWasDown || totalTicks - lastFlightJumpEdgeTick >= 8);
        if (guardedFlightJump && !jumpWasDown) lastFlightJumpEdgeTick = totalTicks;
        client.options.keyJump.setDown(flying ? guardedFlightJump
                : player.onGround() && (navigationStuckTicks > 6 || stageTicks % 30 < 3));
        client.options.keyShift.setDown(descend);
        inputActions.add("move:key.forward:" + label);
        if (ascend) inputActions.add("move:key.jump-flight-ascent:" + label);
        if (descend) inputActions.add("move:key.sneak-flight-descent:" + label);
        if (!flying && detouring) inputActions.add("move:key.strafe-detour:" + label);
        if (distance < navigationLastDistance - 0.04) navigationStuckTicks = 0;
        else navigationStuckTicks++;
        navigationLastDistance = distance;
        if (navigationStuckTicks > 240) {
            throw new IllegalStateException("normal-input navigation stuck during " + label + " at "
                    + player.blockPosition() + "; target=" + target + ", distance=" + rounded(distance)
                    + ", dy=" + rounded(dy) + ", flying=" + flying);
        }
        return false;
    }

    private Zombie findObservedZombie(Minecraft client) {
        if (client.level == null || client.player == null) return null;
        return client.level.getEntitiesOfClass(Zombie.class, client.player.getBoundingBox().inflate(48.0)).stream()
                .filter(Zombie::isAlive)
                .filter(zombie -> observedZombieId == null || zombie.getUUID().equals(observedZombieId))
                .min(Comparator.comparingDouble(client.player::distanceToSqr)).orElseGet(() ->
                        observedZombieId == null ? null : client.level.getEntitiesOfClass(
                                Zombie.class, client.player.getBoundingBox().inflate(48.0)).stream()
                                .filter(Zombie::isAlive)
                                .min(Comparator.comparingDouble(client.player::distanceToSqr)).orElse(null));
    }

    private static boolean isExpectedPlacedBlock(Minecraft client, Placement placement) {
        var state = client.level.getBlockState(placement.target());
        return placement.item() == Items.OAK_LOG ? state.is(Blocks.OAK_LOG) : state.is(Blocks.GREEN_WOOL);
    }

    private static int hotbarSlot(LocalPlayer player, Item item) {
        for (var slot = 0; slot < 9; slot++) {
            if (player.getInventory().getItem(slot).is(item)) return slot;
        }
        return -1;
    }

    private void selectHotbar(Minecraft client, int slot) {
        if (client.player.getInventory().selected == slot) return;
        KeyMapping.click(client.options.keyHotbarSlots[slot].getKey());
        inputActions.add("select:hotbar-key-" + (slot + 1));
    }

    private static Vec3 facePoint(BlockPos support, Direction face) {
        return Vec3.atCenterOf(support).add(face.getStepX() * 0.49,
                face.getStepY() * 0.49, face.getStepZ() * 0.49);
    }

    private static void lookAt(LocalPlayer player, Vec3 target) {
        var eye = player.getEyePosition();
        var dx = target.x - eye.x;
        var dy = target.y - eye.y;
        var dz = target.z - eye.z;
        var horizontal = Math.sqrt(dx * dx + dz * dz);
        var yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        var pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        player.setYRot(yaw);
        player.setYHeadRot(yaw);
        player.setXRot(Math.max(-89.0F, Math.min(89.0F, pitch)));
    }

    private void transition(Stage next, int delay) {
        stage = next;
        stageTicks = 0;
        waitTicks = delay;
        navigationLastDistance = Double.POSITIVE_INFINITY;
        navigationStuckTicks = 0;
        navigationDestination = null;
        flightEscapeActive = false;
    }

    private void announce(Minecraft client, String message) {
        if (suppressInGameMessages) return;
        var text = Component.literal("[Lodestone Goal] " + message);
        if (client.player != null) client.player.displayClientMessage(text, true);
        client.gui.getChat().addMessage(text);
        inGameMessagesEmitted += 2;
    }

    private static void stopMovement(Minecraft client) {
        client.options.keyUp.setDown(false);
        client.options.keySprint.setDown(false);
        client.options.keyJump.setDown(false);
        client.options.keyLeft.setDown(false);
        client.options.keyRight.setDown(false);
        client.options.keyShift.setDown(false);
    }

    private static void stopDefensiveMovement(Minecraft client) {
        client.options.keyDown.setDown(false);
    }

    private static void stopAttack(Minecraft client) {
        client.options.keyAttack.setDown(false);
    }

    private static void releaseInput(Minecraft client) {
        stopMovement(client);
        stopDefensiveMovement(client);
        stopAttack(client);
        client.options.keyUse.setDown(false);
    }

    private static LocalPlayer requirePlayer(Minecraft client) {
        if (client.player == null || client.level == null) {
            throw new IllegalStateException("client player/world unavailable");
        }
        return client.player;
    }

    private static Map<String, Object> position(BlockPos value) {
        return Map.of("x", value.getX(), "y", value.getY(), "z", value.getZ());
    }

    private static double rounded(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private record Placement(BlockPos target, BlockPos support, Direction face, Item item,
                             BlockPos vantage) {
    }

    private enum Stage {
        WAIT_WORLD,
        SET_CREATIVE,
        SILENCE_ADVANCEMENTS,
        GIVE_LOGS,
        GIVE_WOOL,
        ENABLE_FLIGHT,
        SELECT_SITE,
        BUILD_TREE,
        VERIFY_BUILD,
        SUMMON_ZOMBIE,
        EQUIP_ZOMBIE,
        TELEPORT_AWAY,
        CLEAR_INVENTORY,
        GIVE_SWORD,
        SET_SURVIVAL,
        WAIT_SURVIVAL,
        MINE_WITH_DEFENSE,
        VERIFY,
        COMPLETE_DELAY
    }
}
