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
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
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
    public static void onClientTick(ClientTickEvent.Pre event) {
        clientTick++;
        BRIDGE.releaseExpiredInput(monotonicMillis());
        BRIDGE.tickPlayerAlerts();
        BRIDGE.tickSurvivalTreeGoal();
        BRIDGE.tickWoolTreeZombieGoal();
        BRIDGE.tickNetherGoal();
        BRIDGE.tickNavigationGoal();
        BRIDGE.tickGotoGoal();
        BRIDGE.tickCombatGoal();
        BRIDGE.tickSpawnGauntletGoal();
        BRIDGE.tickStoneToolsetGoal();
        BRIDGE.tickHardScript();
        BRIDGE.tickAttackHold();
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
        BRIDGE.cancelHardScript("player clone or respawn");
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
        BRIDGE.recordPhysicalKey(event.getKey(), event.getScanCode(), event.getAction());
        publish("minecraft.input.key.received", Map.of(
                "key", event.getKey(), "scanCode", event.getScanCode(),
                "action", event.getAction(), "modifiers", event.getModifiers()));
    }

    @SubscribeEvent
    public static void onMouseButton(InputEvent.MouseButton.Post event) {
        BRIDGE.recordPhysicalMouse(event.getButton(), event.getAction());
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

    /**
     * Builds the {@code minecraft.player.interact} output map for "use"/"pick" and for any
     * "attack" the caller does not have target information for. The catalog declares this
     * capability's {@code outputSchema} with {@code additionalProperties:false} - any key it does
     * not declare fails output schema validation and, because interact always commits its
     * mutation before returning, surfaces to callers as {@code OUTCOME_INDETERMINATE} rather than
     * a clean error (see the leaked "intelligence" output field this guarded against, regression
     * tested by {@code NeoForgeInteractOutputTest}). Kept as its own package-private method
     * (instead of inlined in {@link ClientBridgeImpl#interactKeyAsync} or
     * {@link NeoForgeAttackHold}) so it can be exercised directly by a regression test without
     * requiring a live Minecraft client.
     */
    static Map<String, Object> interactOutput(String action) {
        return Map.of("action", action, "queued", true);
    }

    /**
     * Builds interact's output for an "attack" that resolved against an entity or hit nothing -
     * {@code held} is always {@code false} here since neither case ever starts a
     * {@link NeoForgeAttackHold}, and there is no block position to report as {@code target}.
     */
    static Map<String, Object> interactOutput(String action, boolean held, String targetKind) {
        return Map.of("action", action, "queued", true, "held", held, "targetKind", targetKind);
    }

    /**
     * Builds interact's output for an "attack" that resolved against a block - {@code held}
     * distinguishes a genuine multi-tick {@link NeoForgeAttackHold} break from a momentary click
     * that happened to hit an instant-break block, an unbreakable block, or any block in a game
     * mode (e.g. creative) where {@link ClientBridgeImpl#heldAttackTarget} never starts a hold.
     * {@code target} is always {@code block} for this overload, so it always carries the position
     * and block id the caller aimed at.
     */
    static Map<String, Object> interactOutput(String action, boolean held, BlockPos target, String blockId) {
        var output = new LinkedHashMap<String, Object>();
        output.put("action", action);
        output.put("queued", true);
        output.put("held", held);
        output.put("targetKind", "block");
        output.put("target", Map.of("x", target.getX(), "y", target.getY(), "z", target.getZ(), "block", blockId));
        return Map.copyOf(output);
    }

    /**
     * Builds a single entry of {@code minecraft.inventory.container.read}'s {@code slots} array.
     * Kept as a pure static helper (like {@link #interactOutput}) so container read's output
     * shape can be exercised directly by a regression test without requiring a live
     * {@code ClientLevel}/{@code Player} - see {@code NeoForgeContainerOutputTest}.
     */
    static Map<String, Object> containerSlotOutput(int slot, String itemId, int count, int maxCount, boolean empty) {
        return Map.of("slot", slot, "item", itemId, "count", count, "maxCount", maxCount, "empty", empty);
    }

    /**
     * Builds the additive-optional {@code carried} field of {@code minecraft.inventory.container.read}'s
     * output - the cursor stack, read from {@code AbstractContainerMenu#getCarried()}. Mirrors
     * {@link #containerSlotOutput}'s shape minus {@code slot}/{@code maxCount}, which do not apply
     * to a stack that is not sitting in any slot. Always populated (including when the cursor is
     * empty) rather than omitted, exactly like every slot entry already reports its own emptiness
     * instead of being left out of the array.
     */
    static Map<String, Object> containerCarriedOutput(String itemId, int count, boolean empty) {
        return Map.of("item", itemId, "count", count, "empty", empty);
    }

    /**
     * Builds {@code minecraft.inventory.container.read}'s full output from already-projected
     * slot/carried entries. Kept separate from {@link #containerSlotOutput}/
     * {@link #containerCarriedOutput} so a test can assemble a whole output without needing a
     * real {@code AbstractContainerMenu}.
     */
    static Map<String, Object> containerReadOutput(int containerId, int revision,
                                                    List<Map<String, Object>> slots, Map<String, Object> carried) {
        var output = new LinkedHashMap<String, Object>();
        output.put("open", true);
        output.put("containerId", containerId);
        output.put("revision", revision);
        output.put("slots", List.copyOf(slots));
        output.put("carried", carried);
        return Map.copyOf(output);
    }

    /**
     * Builds {@code minecraft.inventory.container.click}'s output. Kept as a pure static helper
     * (like {@link #interactOutput}) so it can be exercised directly by a regression test without
     * requiring a live {@code ClientLevel}/{@code Player}.
     */
    static Map<String, Object> containerClickOutput(int containerId, int slot, int button, String clickType) {
        return Map.of("containerId", containerId, "slot", slot, "button", button, "clickType", clickType);
    }

    private static final class ClientBridgeImpl implements NeoForgeAdapter.ClientBridge {
        private final ArrayDeque<Map<String, Object>> chat = new ArrayDeque<>();
        private final InputLease movementLease = new InputLease();
        private final Map<String, KeyMapping> ownedMappings = new LinkedHashMap<>();
        private final Set<String> directlyOwnedMappings = new LinkedHashSet<>();
        private final Set<String> leasedMappings = new LinkedHashSet<>();
        /** Physical keys currently held by the player, kept separate from agent-owned leases. */
        private final Set<String> physicallyHeldMappings = new LinkedHashSet<>();
        private Screen tokenScreen;
        private boolean tokenInitialized;
        private long screenSequence;
        private String currentScreenToken = "neo121-0";
        private NeoForgeSurvivalTreeGoal survivalTreeGoal;
        private NeoForgeWoolTreeZombieGoal woolTreeZombieGoal;
        private NeoForgeNetherGoal netherGoal;
        private NeoForgeNavigationGoal navigationGoal;
        private NeoForgeCombatGoal combatGoal;
        private NeoForgeSpawnGauntletGoal spawnGauntletGoal;
        private NeoForgeStoneToolsetGoal stoneToolsetGoal;
        private NeoForgeGotoGoal gotoGoal;
        /** Backs a single in-flight minecraft.player.interact "attack" held against a breakable
         * block - see {@link NeoForgeAttackHold}. Not a native goal actor, but it still owns
         * keyAttack across real ticks exactly like one, so a new hold refuses to start while a
         * native goal actor is already running (see anyNativeGoalActorRunning()) - the reverse
         * ordering can't race in practice since every mutating minecraft.* capability, including
         * every native goal's start call, shares one runtime-enforced ordering queue
         * (LodestoneRuntime.SHARED_MUTATION_ORDER), so a goal-start invocation cannot even begin
         * running until this hold's own future has resolved. */
        private NeoForgeAttackHold attackHold;
        /** Exactly one deterministic hard script may own attack/use input at a time. */
        private NeoForgeHardScript hardScript;
        /** Not a goal actor - ticks unconditionally, every tick, before any goal actor - see
         * {@link #tickPlayerAlerts()}. */
        private final NeoForgePlayerAlerts playerAlerts = new NeoForgePlayerAlerts();

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

        private record HeldAttackTarget(BlockPos position, double progressPerTick, String blockId) { }

        @Override
        public boolean available(String capability) {
            var client = Minecraft.getInstance();
            return switch (capability) {
                case "minecraft.registry.item.search", "minecraft.server.info.read",
                        "minecraft.client.screenshot.capture",
                        "minecraft.input.key.set", "minecraft.input.mouse.set", "minecraft.input.release-all",
                        "minecraft.script.current.cancel",
                        "minecraft.ui.state.read",
                        "minecraft.chat.read", "minecraft.goal.survival.wooden-axe-tree",
                        "minecraft.goal.creative.wool-tree-zombie-defense",
                        "minecraft.goal.survival.reach-nether",
                        "minecraft.goal.navigation.safe-waypoint",
                        "minecraft.goal.combat.attack-nearest",
                        "minecraft.goal.survival.spawn-gauntlet",
                        "minecraft.goal.survival.stone-toolset",
                        "minecraft.goal.move.goto" -> true;
                case "minecraft.world.heightmap.read", "minecraft.world.light.analyze" -> client.level != null;
                case "minecraft.ui.click", "minecraft.ui.text.insert",
                        "minecraft.inventory.container.read", "minecraft.inventory.container.click" -> client.screen != null;
                case "minecraft.ui.key", "minecraft.ui.screen.close" -> client.screen != null
                        || (client.level != null && client.player != null);
                case "minecraft.ui.inventory.open" -> client.level != null && client.player != null;
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
            if ("minecraft.goal.survival.spawn-gauntlet".equals(capability)) {
                return startSpawnGauntletGoal(invocation);
            }
            if ("minecraft.goal.survival.stone-toolset".equals(capability)) {
                return startStoneToolsetGoal(invocation);
            }
            if ("minecraft.goal.move.goto".equals(capability)) {
                return startGotoGoal(invocation);
            }
            if ("minecraft.player.interact".equals(capability)) {
                return interactKeyAsync(invocation);
            }
            if (capability.equals("minecraft.player.block.mine")) return startMineBlock(invocation);
            if (capability.equals("minecraft.player.target-block.mine")) return startMineTargetBlock(invocation);
            if (capability.equals("minecraft.player.block.place")) return startPlaceBlock(invocation);
            if (capability.equals("minecraft.player.target-block.place")) return startPlaceTargetBlock(invocation);
            if (capability.equals("minecraft.ui.inventory.open")) return startOpenInventory(invocation);
            if (capability.equals("minecraft.ui.screen.close")) return startCloseScreen(invocation);
            return onClientThread(() -> {
                invocation.cancellation().throwIfCancelled();
                return switch (capability) {
                case "minecraft.registry.item.search" -> searchItems(invocation);
                case "minecraft.server.info.read" -> serverInfo(invocation);
                case "minecraft.client.screenshot.capture" -> screenshot(invocation);
                case "minecraft.input.key.set" -> setKey(invocation, false);
                case "minecraft.input.mouse.set" -> setKey(invocation, true);
                case "minecraft.input.release-all" -> releaseAllInput(invocation);
                case "minecraft.script.current.cancel" -> cancelCurrentScript();
                case "minecraft.player.state.read" -> playerState(invocation);
                case "minecraft.player.context.read" -> playerContext(invocation);
                case "minecraft.player.crosshair.read" -> queryCrosshair(invocation);
                case "minecraft.world.block.find" -> findBlock(invocation);
                case "minecraft.player.block.look-at" -> lookAtBlock(invocation);
                case "minecraft.inventory.hotbar.select-item" -> selectItem(invocation);
                case "minecraft.world.heightmap.read", "minecraft.world.light.analyze" ->
                        worldAnalysis(capability, invocation);
                case "minecraft.player.look" -> look(invocation);
                case "minecraft.player.move" -> move(invocation);
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

        @Override
        public CompletionStage<Map<String, Object>> reconcileSession() {
            return onClientThread(this::quiesceAndObserve);
        }

        /**
         * Backs minecraft.session.reconcile's quiesce+re-observe step (see
         * LodestoneAdapter#reconcileSession()). Force-stops every native goal actor this bridge
         * might still be driving - nulling a goal's field here is exactly what stops its next
         * tickXxxGoal() from doing anything further, see e.g. tickNavigationGoal() above - releases
         * every input this bridge could still be holding down, then reports fresh player state so
         * the caller can re-plan against reality instead of stale assumptions.
         * <p>
         * Every action here is idempotent and safe to run even when there was nothing active to
         * stop (nulling an already-null field, releasing an already-released key, clearing an
         * already-empty lease set), so this always reports "quiesced": true if it completes at all;
         * onClientThread() converts any thrown failure (e.g. no Minecraft instance) into a failed
         * CompletionStage, which is exactly the "reconcile failed, do not clear the quarantine"
         * signal the caller needs.
         */
        private Map<String, Object> quiesceAndObserve() {
            var stoppedGoalActors = new ArrayList<String>();
            if (survivalTreeGoal != null && !survivalTreeGoal.done()) stoppedGoalActors.add("survivalTreeGoal");
            if (woolTreeZombieGoal != null && !woolTreeZombieGoal.done()) stoppedGoalActors.add("woolTreeZombieGoal");
            if (netherGoal != null && !netherGoal.done()) stoppedGoalActors.add("netherGoal");
            if (navigationGoal != null && !navigationGoal.done()) stoppedGoalActors.add("navigationGoal");
            if (combatGoal != null && !combatGoal.done()) stoppedGoalActors.add("combatGoal");
            if (spawnGauntletGoal != null && !spawnGauntletGoal.done()) stoppedGoalActors.add("spawnGauntletGoal");
            if (stoneToolsetGoal != null && !stoneToolsetGoal.done()) stoppedGoalActors.add("stoneToolsetGoal");
            if (gotoGoal != null && !gotoGoal.done()) stoppedGoalActors.add("gotoGoal");
            if (attackHold != null && !attackHold.done()) {
                stoppedGoalActors.add("attackHold");
                attackHold.fail(new IllegalStateException(
                        "held attack was stopped by a session reconcile"));
            }
            if (hardScript != null && !hardScript.done()) {
                stoppedGoalActors.add("hardScript:" + hardScript.id());
                hardScript.cancel("hard script stopped by session reconcile");
            }
            survivalTreeGoal = null;
            woolTreeZombieGoal = null;
            netherGoal = null;
            navigationGoal = null;
            combatGoal = null;
            spawnGauntletGoal = null;
            stoneToolsetGoal = null;
            gotoGoal = null;
            attackHold = null;
            hardScript = null;

            var client = Minecraft.getInstance();
            // Goal actors (e.g. NeoForgeNavigationGoal) drive movement/interaction by setting these
            // raw KeyMapping states directly on client.options, bypassing this bridge's own
            // ownedMappings/leasedMappings bookkeeping entirely - see NeoForgeNavigationGoal's own
            // releaseInput(client), whose exact key set this mirrors. releaseAllInput() below does
            // NOT cover these, so they must be force-released independently here.
            client.options.keyUp.setDown(false);
            client.options.keySprint.setDown(false);
            client.options.keyJump.setDown(false);
            client.options.keyShift.setDown(false);
            client.options.keyAttack.setDown(false);
            client.options.keyUse.setDown(false);
            var releaseResult = releaseAllInput();

            var observation = new LinkedHashMap<String, Object>();
            observation.put("stoppedGoalActors", List.copyOf(stoppedGoalActors));
            observation.put("inputRelease", releaseResult);
            var player = client.player;
            var level = client.level;
            if (player != null && level != null) {
                observation.put("playerObserved", true);
                observation.put("position", position(player.getX(), player.getY(), player.getZ()));
                observation.put("blockPosition", blockPosition(player.blockPosition()));
                observation.put("health", player.getHealth());
                observation.put("dimension", level.dimension().location().toString());
                observation.put("gameMode", client.gameMode == null || client.gameMode.getPlayerMode() == null
                        ? "unknown" : client.gameMode.getPlayerMode().getName());
                observation.put("heldItem", itemId(player.getMainHandItem()));
            } else {
                observation.put("playerObserved", false);
            }
            observation.put("quiesced", true);
            return Map.copyOf(observation);
        }

        private CompletionStage<Map<String, Object>> startSurvivalTreeGoal(
                dev.lodestone.adapter.InvocationContext invocation) {
            var result = new CompletableFuture<Map<String, Object>>();
            Minecraft.getInstance().execute(() -> {
                try {
                    if (combatGoal != null && !combatGoal.done()) {
                        throw new IllegalStateException("a native Minecraft goal actor is already running");
                    }
                    if (spawnGauntletGoal != null && !spawnGauntletGoal.done()) {
                        throw new IllegalStateException("a native Minecraft goal actor is already running");
                    }
                    if (stoneToolsetGoal != null && !stoneToolsetGoal.done()) {
                        throw new IllegalStateException("a native Minecraft goal actor is already running");
                    }
                    if (gotoGoal != null && !gotoGoal.done()) {
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

        /** Advances {@link NeoForgePlayerAlerts} and republishes any detected alert through the same
         * {@link #publish(String, Map)} path every other client event uses. Deliberately not a goal
         * actor: it ticks unconditionally (no mutual-exclusion gate) and never owns input. */
        private void tickPlayerAlerts() {
            playerAlerts.tick(Minecraft.getInstance(), NeoForgeClientController::publish);
        }

        /** The most recent death position observed this session, or {@code null} if the player has
         * not died - reserved for a future respawn-recover competency. */
        Map<String, Object> lastDeathPosition() {
            return playerAlerts.lastDeathPosition();
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
                    if (spawnGauntletGoal != null && !spawnGauntletGoal.done()) {
                        throw new IllegalStateException("a native Minecraft goal actor is already running");
                    }
                    if (stoneToolsetGoal != null && !stoneToolsetGoal.done()) {
                        throw new IllegalStateException("a native Minecraft goal actor is already running");
                    }
                    if (gotoGoal != null && !gotoGoal.done()) {
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
                    if (spawnGauntletGoal != null && !spawnGauntletGoal.done()) {
                        throw new IllegalStateException("a native Minecraft goal actor is already running");
                    }
                    if (stoneToolsetGoal != null && !stoneToolsetGoal.done()) {
                        throw new IllegalStateException("a native Minecraft goal actor is already running");
                    }
                    if (gotoGoal != null && !gotoGoal.done()) {
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
                    if (spawnGauntletGoal != null && !spawnGauntletGoal.done()) {
                        throw new IllegalStateException("a native Minecraft goal actor is already running");
                    }
                    if (stoneToolsetGoal != null && !stoneToolsetGoal.done()) {
                        throw new IllegalStateException("a native Minecraft goal actor is already running");
                    }
                    if (gotoGoal != null && !gotoGoal.done()) {
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
                    if (spawnGauntletGoal != null && !spawnGauntletGoal.done()) {
                        throw new IllegalStateException("a native Minecraft goal actor is already running");
                    }
                    if (stoneToolsetGoal != null && !stoneToolsetGoal.done()) {
                        throw new IllegalStateException("a native Minecraft goal actor is already running");
                    }
                    if (gotoGoal != null && !gotoGoal.done()) {
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

        private CompletionStage<Map<String, Object>> startSpawnGauntletGoal(
                dev.lodestone.adapter.InvocationContext invocation) {
            var result = new CompletableFuture<Map<String, Object>>();
            Minecraft.getInstance().execute(() -> {
                try {
                    if ((survivalTreeGoal != null && !survivalTreeGoal.done())
                            || (woolTreeZombieGoal != null && !woolTreeZombieGoal.done())
                            || (netherGoal != null && !netherGoal.done())
                            || (navigationGoal != null && !navigationGoal.done())
                            || (combatGoal != null && !combatGoal.done())
                            || (spawnGauntletGoal != null && !spawnGauntletGoal.done())
                            || (stoneToolsetGoal != null && !stoneToolsetGoal.done())
                            || (gotoGoal != null && !gotoGoal.done())) {
                        throw new IllegalStateException("a native Minecraft goal actor is already running");
                    }
                    invocation.cancellation().commitMutation();
                    spawnGauntletGoal = new NeoForgeSpawnGauntletGoal(invocation, result);
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            });
            return result;
        }

        private void tickSpawnGauntletGoal() {
            var current = spawnGauntletGoal;
            if (current == null) return;
            current.tick(Minecraft.getInstance());
            if (current.done()) spawnGauntletGoal = null;
        }

        private CompletionStage<Map<String, Object>> startStoneToolsetGoal(
                dev.lodestone.adapter.InvocationContext invocation) {
            var result = new CompletableFuture<Map<String, Object>>();
            Minecraft.getInstance().execute(() -> {
                try {
                    if ((survivalTreeGoal != null && !survivalTreeGoal.done())
                            || (woolTreeZombieGoal != null && !woolTreeZombieGoal.done())
                            || (netherGoal != null && !netherGoal.done())
                            || (navigationGoal != null && !navigationGoal.done())
                            || (combatGoal != null && !combatGoal.done())
                            || (spawnGauntletGoal != null && !spawnGauntletGoal.done())
                            || (gotoGoal != null && !gotoGoal.done())) {
                        throw new IllegalStateException("a native Minecraft goal actor is already running");
                    }
                    invocation.cancellation().commitMutation();
                    var token = String.valueOf(invocation.request().input().getOrDefault("continuationToken", ""));
                    if (!token.isBlank()) {
                        if (stoneToolsetGoal == null || stoneToolsetGoal.done() || !stoneToolsetGoal.paused()
                                || !stoneToolsetGoal.continuationToken().equals(token)) {
                            throw new IllegalStateException("stone toolset continuation token is stale or unknown");
                        }
                        stoneToolsetGoal.resume(invocation, result);
                    } else {
                        if (stoneToolsetGoal != null && !stoneToolsetGoal.done()) {
                            throw new IllegalStateException("a stone toolset goal is already running");
                        }
                        stoneToolsetGoal = new NeoForgeStoneToolsetGoal(invocation, result);
                    }
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            });
            return result;
        }

        private void tickStoneToolsetGoal() {
            var current = stoneToolsetGoal;
            if (current == null) return;
            current.tick(Minecraft.getInstance());
            if (current.done()) stoneToolsetGoal = null;
        }

        private CompletionStage<Map<String, Object>> startGotoGoal(
                dev.lodestone.adapter.InvocationContext invocation) {
            var result = new CompletableFuture<Map<String, Object>>();
            Minecraft.getInstance().execute(() -> {
                try {
                    if (anyNativeGoalActorRunning()) {
                        throw new IllegalStateException("a native Minecraft goal actor is already running");
                    }
                    invocation.cancellation().commitMutation();
                    gotoGoal = new NeoForgeGotoGoal(invocation, result);
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            });
            return result;
        }

        private void tickGotoGoal() {
            var current = gotoGoal;
            if (current == null) return;
            current.tick(Minecraft.getInstance());
            if (current.done()) gotoGoal = null;
        }

        private void tickAttackHold() {
            var current = attackHold;
            if (current == null) return;
            current.tick(Minecraft.getInstance());
            if (current.done()) attackHold = null;
        }

        private void tickHardScript() {
            var current = hardScript;
            if (current == null) return;
            current.tick(Minecraft.getInstance());
            if (current.done()) hardScript = null;
        }

        private void cancelHardScript(String reason) {
            if (hardScript != null && !hardScript.done()) {
                hardScript.cancel(reason);
                hardScript = null;
            }
        }

        private Map<String, Object> cancelCurrentScript() {
            var current = hardScript;
            if (current == null || current.done()) {
                return Map.of("cancelled", false, "mutationDispatched", false,
                        "inputReleased", List.of(), "reconcileRequired", false);
            }
            var dispatched = current.mutationDispatched();
            var id = current.id();
            var kind = current.kind();
            current.cancel("cancelled by minecraft.script.current.cancel");
            hardScript = null;
            return Map.of("cancelled", true, "mutationDispatched", dispatched,
                    "inputReleased", List.of("key.attack", "key.use"),
                    "reconcileRequired", dispatched, "scriptId", id, "scriptKind", kind);
        }

        private boolean anyNativeGoalActorRunning() {
            return (survivalTreeGoal != null && !survivalTreeGoal.done())
                    || (woolTreeZombieGoal != null && !woolTreeZombieGoal.done())
                    || (netherGoal != null && !netherGoal.done())
                    || (navigationGoal != null && !navigationGoal.done())
                    || (combatGoal != null && !combatGoal.done())
                    || (spawnGauntletGoal != null && !spawnGauntletGoal.done())
                    || (stoneToolsetGoal != null && !stoneToolsetGoal.done())
                    || (gotoGoal != null && !gotoGoal.done());
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
            cancelHardScript("all controlled input released");
            var released = new ArrayList<String>();
            var preserved = new ArrayList<String>();
            for (var entry : ownedMappings.entrySet()) {
                // Never synthesize a release over a real player-held key. The next physical
                // release event will update the mapping normally after the agent lease ends.
                if (physicallyHeldMappings.contains(entry.getKey())) {
                    preserved.add(entry.getKey());
                } else {
                    entry.getValue().setDown(false);
                    released.add(entry.getKey());
                }
            }
            ownedMappings.clear();
            directlyOwnedMappings.clear();
            leasedMappings.clear();
            movementLease.releaseAll();
            released.sort(String::compareTo);
            preserved.sort(String::compareTo);
            return Map.of("released", released, "preservedPhysicalInput", preserved, "count", released.size(),
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
                    if (!physicallyHeldMappings.contains(name)) mapping.setDown(false);
                    ownedMappings.remove(name);
                }
            }
        }

        private void recordPhysicalKey(int key, int scanCode, int action) {
            var pressed = action != 0;
            var inputKey = InputConstants.getKey(key, scanCode);
            var mappings = Minecraft.getInstance().options.keyMappings;
            if (pressed && hardScript != null && !hardScript.done()) {
                cancelHardScript("user keyboard input");
            }
            for (var mapping : mappings) {
                if (!mapping.getKey().equals(inputKey)) continue;
                if (pressed) physicallyHeldMappings.add(mapping.getName());
                else physicallyHeldMappings.remove(mapping.getName());
            }
        }

        private void recordPhysicalMouse(int button, int action) {
            if (action != 0 && hardScript != null && !hardScript.done()) {
                cancelHardScript("user mouse input");
            }
            var key = InputConstants.Type.MOUSE.getOrCreate(button);
            for (var mapping : Minecraft.getInstance().options.keyMappings) {
                if (!mapping.getKey().equals(key)) continue;
                if (action != 0) physicallyHeldMappings.add(mapping.getName());
                else physicallyHeldMappings.remove(mapping.getName());
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

        private Map<String, Object> queryCrosshair(dev.lodestone.adapter.InvocationContext invocation) {
            invocation.cancellation().throwIfCancelled();
            var player = requirePlayer();
            var target = raycastTarget(player, player.blockInteractionRange());
            var output = new LinkedHashMap<String, Object>(target);
            if ("block".equals(target.get("kind"))) {
                var pos = new BlockPos(((Number) ((Map<?, ?>) target.get("blockPosition")).get("x")).intValue(),
                        ((Number) ((Map<?, ?>) target.get("blockPosition")).get("y")).intValue(),
                        ((Number) ((Map<?, ?>) target.get("blockPosition")).get("z")).intValue());
                var state = requireLevel().getBlockState(pos);
                output.put("blockFingerprint", NeoForgeHardScript.blockFingerprint(
                        requireLevel().dimension().location().toString(), pos, state,
                        String.valueOf(target.get("face"))));
            }
            return Map.copyOf(output);
        }

        private Map<String, Object> findBlock(dev.lodestone.adapter.InvocationContext invocation) {
            var input = input(invocation);
            var requested = text(input, "block", null).trim().toLowerCase(Locale.ROOT);
            if (!requested.contains(":")) requested = "minecraft:" + requested;
            var radius = numberOrDefault(input, "maxDistance", 16);
            var maxVisited = numberOrDefault(input, "maxVisited", 32768);
            if (radius < 1 || radius > 32 || maxVisited < 1 || maxVisited > 65536) {
                throw new IllegalArgumentException("maxDistance must be 1..32 and maxVisited must be 1..65536");
            }
            var player = requirePlayer();
            var center = player.blockPosition();
            var level = requireLevel();
            var matches = new ArrayList<BlockPos>();
            var scanned = 0;
            var truncated = false;
            for (var y = center.getY() - radius; y <= center.getY() + radius && !truncated; y++) {
                for (var x = center.getX() - radius; x <= center.getX() + radius && !truncated; x++) {
                    for (var z = center.getZ() - radius; z <= center.getZ() + radius; z++) {
                        if (++scanned > maxVisited) {
                            truncated = true;
                            break;
                        }
                        invocation.cancellation().throwIfCancelled();
                        var pos = new BlockPos(x, y, z);
                        if (!level.isInWorldBounds(pos) || !level.isLoaded(pos)) continue;
                        var state = level.getBlockState(pos);
                        if (requested.equals(NeoForgeHardScript.blockId(state))) matches.add(pos.immutable());
                    }
                }
            }
            matches.sort(Comparator.comparingDouble((BlockPos pos) -> pos.distSqr(center))
                    .thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getX)
                    .thenComparingInt(BlockPos::getZ));
            var output = new LinkedHashMap<String, Object>();
            output.put("found", !matches.isEmpty());
            output.put("scanned", Math.min(scanned, maxVisited));
            output.put("truncated", truncated);
            if (!matches.isEmpty()) output.put("target", NeoForgeHardScript.snapshot(
                    level.dimension().location().toString(), matches.get(0), level.getBlockState(matches.get(0))));
            return Map.copyOf(output);
        }

        private Map<String, Object> lookAtBlock(dev.lodestone.adapter.InvocationContext invocation) {
            var input = input(invocation);
            var target = new BlockPos(number(input, "x"), number(input, "y"), number(input, "z"));
            var level = requireLevel();
            if (!level.isInWorldBounds(target) || !level.isLoaded(target)) {
                throw new IllegalArgumentException("target block is outside loaded client world");
            }
            var state = level.getBlockState(target);
            var expected = input.get("blockFingerprint");
            if (expected != null && !expected.equals(NeoForgeHardScript.blockFingerprint(
                    level.dimension().location().toString(), target, state, null))) {
                throw new IllegalStateException("TARGET_CHANGED before look-at dispatch");
            }
            var player = requirePlayer();
            invocation.cancellation().commitMutation();
            NeoForgeHardScript.aimAt(player, target);
            return Map.of("rotation", Map.of("yaw", player.getYRot(), "pitch", player.getXRot()),
                    "target", queryCrosshair(invocation));
        }

        private Map<String, Object> selectItem(dev.lodestone.adapter.InvocationContext invocation) {
            var input = input(invocation);
            var item = text(input, "item", null).trim().toLowerCase(Locale.ROOT);
            if (!item.contains(":")) item = "minecraft:" + item;
            var player = requirePlayer();
            var preferred = input.get("preferredSlot") == null ? -1 : number(input, "preferredSlot");
            if (preferred < -1 || preferred > 8) throw new IllegalArgumentException("preferredSlot must be 0..8");
            var slot = chooseHotbarSlot(player, item, preferred);
            if (slot < 0) throw new IllegalArgumentException("item is not present in the hotbar: " + item);
            var changed = player.getInventory().selected != slot;
            invocation.cancellation().commitMutation();
            player.getInventory().selected = slot;
            var stack = player.getInventory().getItem(slot);
            return Map.of("selectedSlot", slot, "item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                    "count", stack.getCount(), "changed", changed);
        }

        private CompletionStage<Map<String, Object>> startMineBlock(dev.lodestone.adapter.InvocationContext invocation) {
            var result = new CompletableFuture<Map<String, Object>>();
            Minecraft.getInstance().execute(() -> {
                try {
                    var player = requirePlayer();
                    var hit = player.pick((float) player.blockInteractionRange(), 0.0F, false);
                    if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
                        throw new IllegalStateException("crosshair is not on a block within interaction range");
                    }
                    startMine(invocation, result, blockHit.getBlockPos());
                } catch (Throwable failure) { result.completeExceptionally(failure); }
            });
            return result;
        }

        private CompletionStage<Map<String, Object>> startMineTargetBlock(dev.lodestone.adapter.InvocationContext invocation) {
            var result = new CompletableFuture<Map<String, Object>>();
            Minecraft.getInstance().execute(() -> {
                try {
                    var found = findBlock(invocation);
                    if (!Boolean.TRUE.equals(found.get("found"))) throw new IllegalStateException("target block was not found");
                    var targetMap = (Map<?, ?>) ((Map<?, ?>) found.get("target")).get("position");
                    var pos = new BlockPos(((Number) targetMap.get("x")).intValue(), ((Number) targetMap.get("y")).intValue(),
                            ((Number) targetMap.get("z")).intValue());
                    startMine(invocation, result, pos);
                } catch (Throwable failure) { result.completeExceptionally(failure); }
            });
            return result;
        }

        private void startMine(dev.lodestone.adapter.InvocationContext invocation,
                               CompletableFuture<Map<String, Object>> result, BlockPos target) {
            var client = Minecraft.getInstance();
            if (client.screen != null) throw new IllegalStateException("hard scripts cannot run with a screen open");
            if (hardScript != null && !hardScript.done() || attackHold != null && !attackHold.done()
                    || anyNativeGoalActorRunning()) throw new IllegalStateException("another client actor is already running");
            var state = requireLevel().getBlockState(target);
            if (state.isAir()) throw new IllegalStateException("target block is empty");
            var player = requirePlayer();
            var reach = player.blockInteractionRange();
            if (player.getEyePosition().distanceToSqr(Vec3.atCenterOf(target)) > reach * reach) {
                throw new IllegalStateException("target block is outside the player's live interaction range");
            }
            hardScript = NeoForgeHardScript.mine("mine-" + clientTick, invocation, result, target,
                    NeoForgeHardScript.blockFingerprint(requireLevel().dimension().location().toString(), target, state, null),
                    requireLevel().dimension().location().toString(), NeoForgeHardScript.blockId(state), 300);
        }

        private CompletionStage<Map<String, Object>> startPlaceBlock(dev.lodestone.adapter.InvocationContext invocation) {
            var result = new CompletableFuture<Map<String, Object>>();
            Minecraft.getInstance().execute(() -> {
                try {
                    var player = requirePlayer();
                    var hit = player.pick((float) player.blockInteractionRange(), 0.0F, false);
                    if (!(hit instanceof BlockHitResult blockHit)) throw new IllegalStateException("crosshair is not on a support block");
                    startPlace(invocation, result, blockHit.getBlockPos().relative(blockHit.getDirection()),
                            blockHit.getBlockPos(), blockHit.getDirection());
                } catch (Throwable failure) { result.completeExceptionally(failure); }
            });
            return result;
        }

        private CompletionStage<Map<String, Object>> startPlaceTargetBlock(dev.lodestone.adapter.InvocationContext invocation) {
            var result = new CompletableFuture<Map<String, Object>>();
            Minecraft.getInstance().execute(() -> {
                try {
                    var input = input(invocation);
                    var target = new BlockPos(number(input, "x"), number(input, "y"), number(input, "z"));
                    var face = input.get("face") == null ? Direction.UP : Direction.byName(text(input, "face", null));
                    if (face == null) throw new IllegalArgumentException("face is invalid");
                    startPlace(invocation, result, target, target.relative(face.getOpposite()), face);
                } catch (Throwable failure) { result.completeExceptionally(failure); }
            });
            return result;
        }

        private void startPlace(dev.lodestone.adapter.InvocationContext invocation,
                                CompletableFuture<Map<String, Object>> result, BlockPos target,
                                BlockPos support, Direction face) {
            var client = Minecraft.getInstance();
            if (client.screen != null) throw new IllegalStateException("hard scripts cannot run with a screen open");
            if (hardScript != null && !hardScript.done() || attackHold != null && !attackHold.done()
                    || anyNativeGoalActorRunning()) throw new IllegalStateException("another client actor is already running");
            var level = requireLevel();
            if (!level.isInWorldBounds(target) || !level.isLoaded(target) || !level.isLoaded(support)) {
                throw new IllegalStateException("placement target or support is not loaded");
            }
            var input = input(invocation);
            var item = text(input, "item", null).trim().toLowerCase(Locale.ROOT);
            if (!item.contains(":")) item = "minecraft:" + item;
            var player = requirePlayer();
            var slot = chooseHotbarSlot(player, item, -1);
            if (slot < 0) throw new IllegalArgumentException("block item is not present in the hotbar: " + item);
            var state = level.getBlockState(target);
            if (!state.isAir() && !state.canBeReplaced()) throw new IllegalStateException("placement target is occupied");
            player.getInventory().selected = slot;
            hardScript = NeoForgeHardScript.place("place-" + clientTick, invocation, result, target, support, face,
                    NeoForgeHardScript.blockFingerprint(level.dimension().location().toString(), target, state, null),
                    level.dimension().location().toString(), item, slot, 160);
        }

        private CompletionStage<Map<String, Object>> startOpenInventory(dev.lodestone.adapter.InvocationContext invocation) {
            var result = new CompletableFuture<Map<String, Object>>();
            Minecraft.getInstance().execute(() -> {
                try {
                    var timeoutMs = boundedScreenTimeoutMs(invocation);
                    var client = Minecraft.getInstance();
                    if (client.level == null || client.player == null) {
                        throw new IllegalStateException("client player/world is unavailable");
                    }
                    if (client.screen instanceof InventoryScreen) {
                        var snapshot = captureUi();
                        result.complete(Map.of("opened", true, "alreadyOpen", true,
                                "screenClass", client.screen.getClass().getName(),
                                "screenToken", snapshot.screenToken(),
                                "snapshotRevision", snapshot.snapshotRevision()));
                        return;
                    }
                    if (client.screen != null) {
                        throw new IllegalStateException("OTHER_SCREEN_OPEN: " + client.screen.getClass().getName()
                                + " is open; close it before opening the inventory");
                    }
                    if (hardScript != null && !hardScript.done() || attackHold != null && !attackHold.done()
                            || anyNativeGoalActorRunning()) {
                        throw new IllegalStateException("another client actor is already running");
                    }
                    hardScript = NeoForgeHardScript.openScreen("open-inventory-" + clientTick, invocation, result,
                            Math.max(1, timeoutMs / 50), this::uiTokenFields);
                } catch (Throwable failure) { result.completeExceptionally(failure); }
            });
            return result;
        }

        private CompletionStage<Map<String, Object>> startCloseScreen(dev.lodestone.adapter.InvocationContext invocation) {
            var result = new CompletableFuture<Map<String, Object>>();
            Minecraft.getInstance().execute(() -> {
                try {
                    var timeoutMs = boundedScreenTimeoutMs(invocation);
                    var client = Minecraft.getInstance();
                    if (client.screen == null) {
                        result.complete(Map.of("closed", true, "alreadyClosed", true, "beforeScreenClass", "",
                                "afterInWorld", client.level != null && client.player != null));
                        return;
                    }
                    if (hardScript != null && !hardScript.done() || attackHold != null && !attackHold.done()
                            || anyNativeGoalActorRunning()) {
                        throw new IllegalStateException("another client actor is already running");
                    }
                    var beforeScreen = client.screen;
                    hardScript = NeoForgeHardScript.closeScreen("close-screen-" + clientTick, invocation, result,
                            Math.max(1, timeoutMs / 50), beforeScreen, beforeScreen.getClass().getName());
                } catch (Throwable failure) { result.completeExceptionally(failure); }
            });
            return result;
        }

        private static int boundedScreenTimeoutMs(dev.lodestone.adapter.InvocationContext invocation) {
            var timeoutMs = numberOrDefault(input(invocation), "timeoutMs", 3000);
            if (timeoutMs < 250 || timeoutMs > 10000) {
                throw new IllegalArgumentException("timeoutMs must be between 250 and 10000");
            }
            return timeoutMs;
        }

        /** Cheap ui-state fields worth including in minecraft.ui.inventory.open's success output so
         * a caller can immediately follow up with minecraft.ui.click without a separate
         * minecraft.ui.state.read round-trip. */
        private Map<String, Object> uiTokenFields() {
            var snapshot = captureUi();
            return Map.of("screenToken", snapshot.screenToken(), "snapshotRevision", snapshot.snapshotRevision());
        }

        private static int chooseHotbarSlot(LocalPlayer player, String item, int preferred) {
            if (matches(player.getInventory().getItem(player.getInventory().selected), item)) return player.getInventory().selected;
            if (preferred >= 0 && matches(player.getInventory().getItem(preferred), item)) return preferred;
            for (var slot = 0; slot < 9; slot++) if (matches(player.getInventory().getItem(slot), item)) return slot;
            return -1;
        }

        private static boolean matches(ItemStack stack, String item) {
            return !stack.isEmpty() && item.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
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
            if (input.containsKey("intelligence") || input.containsKey("safety")) {
                var policy = NeoForgeGoalPolicy.from(input);
                if (client.gameMode != null && client.gameMode.getPlayerMode() == GameType.SURVIVAL
                        && policy.fallProtectionEnabled() && forward > 0
                        && NeoForgeGoalActionGuard.unsafeForwardDrop(client, client.player)) {
                    throw new IllegalStateException("goal safety blocked movement toward an unsafe forward drop");
                }
                durationMs = Math.min(durationMs, policy.highSafety() ? 250 : 500);
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

        /**
         * minecraft.player.interact's "attack" case must hold the attack key down across real
         * client ticks when it is targeting a block with nonzero hardness - a single momentary
         * {@code KeyMapping.click(...)} never sets {@code isDown()}, so vanilla's destroy-progress
         * accumulation is never driven and the attack silently no-ops (see
         * verification/evidence/goal-orchestrator-milestone1/trace-23da1de26e5c.jsonl turn 35/36,
         * where an attack on a leaves block reported {@code {"queued":true}} yet the identical
         * block was still there on the very next observation). Every other block-breaking code
         * path in this package already holds the key across ticks - see e.g.
         * NeoForgeNavigationGoal.executeMineStep - so this dispatches to {@link NeoForgeAttackHold},
         * which mirrors that exact convention instead of the single click below.
         * <p>
         * "use", "pick", and "attack" against anything that is not a breakable block (an entity, no
         * target in range, an instant-break block, or an unbreakable one) are unaffected: those stay
         * exactly the momentary single-click behavior this replaced, resolved synchronously within
         * this one client-thread callback.
         */
        private CompletionStage<Map<String, Object>> interactKeyAsync(dev.lodestone.adapter.InvocationContext invocation) {
            var result = new CompletableFuture<Map<String, Object>>();
            Minecraft.getInstance().execute(() -> {
                try {
                    invocation.cancellation().throwIfCancelled();
                    var client = Minecraft.getInstance();
                    // Vanilla suspends keybind handling while a screen is open, so a "queued":true
                    // response here would be a lie: the queued click cannot actually fire until the
                    // screen closes, and then fires dangerously late against whatever the player is
                    // looking at at that later moment. Fail fast, before commitMutation() below, so
                    // this surfaces as a clean error instead of an OUTCOME_INDETERMINATE quarantine -
                    // see LodestoneRuntime's mutationCommitted() gating. Checked for all three actions
                    // (use/attack/pick); minecraft.ui.* is the correct path while a screen is open.
                    if (client.screen != null) {
                        throw new IllegalStateException("minecraft.player.interact is unavailable "
                                + "while a screen is open (" + client.screen.getClass().getName()
                                + "); vanilla suspends keybind handling while a screen is open, so "
                                + "the interaction cannot be queued safely - use minecraft.ui.* to "
                                + "interact with the open screen instead");
                    }
                    var player = requirePlayer();
                    var arguments = input(invocation);
                    var action = text(arguments, "action", "use");
                    var intelligence = text(arguments, "intelligence", "").trim().toLowerCase(Locale.ROOT);
                    if ("attack".equals(action) && !intelligence.isBlank() && !"raw".equals(intelligence)
                            && !"lowest".equals(intelligence) && !"raw-v1".equals(intelligence)
                            && client.gameMode != null && client.gameMode.getPlayerMode() == GameType.SURVIVAL) {
                        var blockedBlock = NeoForgeGoalActionGuard.toolRequiredAttackTarget(client.level, player);
                        if (blockedBlock != null) {
                            throw new IllegalStateException("intelligent attack refused tool-required block " + blockedBlock
                                    + "; acquire and equip the prerequisite tool first");
                        }
                    }
                    if ("attack".equals(action)) {
                        var heldTarget = heldAttackTarget(client, player);
                        if (heldTarget != null) {
                            if (attackHold != null && !attackHold.done()) {
                                throw new IllegalStateException("a held attack is already in progress");
                            }
                            if (anyNativeGoalActorRunning()) {
                                throw new IllegalStateException("a native Minecraft goal actor is already running");
                            }
                            invocation.cancellation().commitMutation();
                            attackHold = new NeoForgeAttackHold(invocation, result,
                                    heldTarget.position(), heldTarget.progressPerTick(), heldTarget.blockId());
                            return;
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
                    // NOTE: the "intelligence" hint read above is intentionally not echoed into the
                    // output - minecraft.player.interact's catalog outputSchema does not declare (nor
                    // does this capability's inputSchema accept) an "intelligence" field. Returning it
                    // here used to fail every single interact call's output validation - see
                    // NeoForgeClientController.interactOutput. "use" and "pick" stay the plain
                    // {action, queued} shape below; only "attack" additionally reports what it hit, so
                    // a caller can tell a genuine block-breaking swing apart from a no-op one (see
                    // momentaryAttackOutput).
                    result.complete("attack".equals(action) ? momentaryAttackOutput(client) : interactOutput(action));
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            });
            return result;
        }

        /**
         * Returns the block a held attack should target, or {@code null} when "attack" should stay
         * the old momentary single click instead: no block in range (a miss, or an entity is
         * targeted - entity attacks are already instantaneous per swing and need no holding),
         * survival/adventure block-breaking is not active, the block is already air/fluid, it is
         * unbreakable ({@code progressPerTick <= 0}, e.g. bedrock), or it is an instant-break block
         * ({@code progressPerTick >= 1.0}, e.g. tall grass) that a single click already destroys in
         * one tick just as before.
         */
        private HeldAttackTarget heldAttackTarget(Minecraft client, LocalPlayer player) {
            if (client.level == null || client.gameMode == null) return null;
            var mode = client.gameMode.getPlayerMode();
            if (mode != GameType.SURVIVAL && mode != GameType.ADVENTURE) return null;
            var hit = player.pick((float) player.blockInteractionRange(), 0.0F, false);
            if (!(hit instanceof BlockHitResult blockHit)) return null;
            var pos = blockHit.getBlockPos();
            var state = client.level.getBlockState(pos);
            if (state.isAir()) return null;
            var progressPerTick = state.getDestroyProgress(player, client.level, pos);
            if (progressPerTick <= 0.0 || progressPerTick >= 1.0) return null;
            var blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            return new HeldAttackTarget(pos.immutable(), progressPerTick, blockId);
        }

        /**
         * Builds the output for an "attack" that {@link #heldAttackTarget} declined to hold -
         * either it never resolved to a block at all (a miss or an entity), or it did but the
         * block does not need holding (creative mode, an instant-break block, or an unbreakable
         * one), so the plain {@link KeyMapping#click} above already ran. Reads
         * {@link Minecraft#hitResult}, the exact field vanilla's own left-click handling
         * ({@code Minecraft#startAttack}) switches on to choose between
         * {@code gameMode.attack(entity)} and {@code gameMode.startDestroyBlock(block)} - it is
         * recomputed every client frame regardless of what this method does, so reading it here is
         * cheap and authoritative instead of running a second, possibly-inconsistent raycast.
         */
        private Map<String, Object> momentaryAttackOutput(Minecraft client) {
            var hit = client.hitResult;
            if (hit instanceof EntityHitResult) {
                return interactOutput("attack", false, "entity");
            }
            if (hit instanceof BlockHitResult blockHit && blockHit.getType() == HitResult.Type.BLOCK
                    && client.level != null) {
                var pos = blockHit.getBlockPos();
                var blockId = BuiltInRegistries.BLOCK.getKey(client.level.getBlockState(pos).getBlock()).toString();
                return interactOutput("attack", false, pos, blockId);
            }
            return interactOutput("attack", false, "none");
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
                slots.add(containerSlotOutput(index, itemId(stack), stack.getCount(),
                        stack.getMaxStackSize(), stack.isEmpty()));
            }
            var carried = menu.getCarried();
            return containerReadOutput(menu.containerId, menu.getStateId(), slots,
                    containerCarriedOutput(itemId(carried), carried.getCount(), carried.isEmpty()));
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
            return containerClickOutput(screen.getMenu().containerId, slot, button, clickType.toString());
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
