// SPDX-License-Identifier: MIT
package dev.lodestone.fabric;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import dev.lodestone.adapter.InputLease;
import dev.lodestone.adapter.InputNumbers;
import dev.lodestone.adapter.InvocationContext;
import dev.lodestone.adapter.InvocationAttributes;
import dev.lodestone.adapter.UiBounds;
import dev.lodestone.adapter.UiContracts;
import dev.lodestone.adapter.UiLimits;
import dev.lodestone.adapter.UiNode;
import dev.lodestone.adapter.UiSelector;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
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
import java.util.concurrent.CompletionException;
import java.util.concurrent.ForkJoinPool;

/** Client-only bridge for Fabric 26.2 input, UI, and read primitives. */
public final class FabricClientController implements ClientModInitializer {
    private static final Bridge BRIDGE = new Bridge();
    private static boolean attached;
    private static Screen lastScreen;

    @Override
    public void onInitializeClient() {
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> LodestoneFabricMod.clientStopping());
        ClientTickEvents.END_CLIENT_TICK.register(FabricClientController::tick);
    }

    private static void tick(Minecraft client) {
        var availabilityChanged = BRIDGE.tick(client);
        var adapter = FabricAdapter.active();
        if (adapter == null) {
            return;
        }
        if (!attached) {
            adapter.attachClientBridge(BRIDGE);
            attached = true;
        }
        if (availabilityChanged || client.gui.screen() != lastScreen) {
            lastScreen = client.gui.screen();
            adapter.refreshClientState();
        }
    }

    private static final class Bridge implements FabricAdapter.ClientBridge {
        private final InputLease inputLease = new InputLease();
        private final Map<String, KeyMapping> ownedMappings = new LinkedHashMap<>();
        private final Set<String> leasedMappings = new LinkedHashSet<>();
        private boolean hasLevel;
        private boolean hasPlayer;
        private Screen tokenScreen;
        private boolean tokenInitialized;
        private String screenToken = UUID.randomUUID().toString();

        private boolean tick(Minecraft client) {
            updateScreenToken(client.gui.screen());
            var nextLevel = client.level != null;
            var nextPlayer = client.player != null;
            var changed = hasLevel != nextLevel || hasPlayer != nextPlayer;
            if (hasLevel && hasPlayer && !(nextLevel && nextPlayer)) {
                releaseAllOwned();
            }
            hasLevel = nextLevel;
            hasPlayer = nextPlayer;
            releaseLeased(inputLease.releaseExpired(monotonicMillis()));
            return changed;
        }

        @Override
        public boolean available(String capability) {
            return switch (capability) {
                case "minecraft.input.key.set", "minecraft.input.mouse.set",
                        "minecraft.input.release-all", "minecraft.ui.state.read",
                        "minecraft.registry.item.search", "minecraft.server.info.read",
                        "minecraft.client.screenshot.capture" -> true;
                case "minecraft.player.context.read", "minecraft.entity.nearby.read",
                        "minecraft.player.look" -> hasLevel && hasPlayer;
                case "minecraft.ui.key" -> {
                    var client = Minecraft.getInstance();
                    yield client.gui.screen() != null || client.level != null;
                }
                case "minecraft.ui.click", "minecraft.ui.text.insert" -> Minecraft.getInstance().gui.screen() != null;
                case "minecraft.inventory.container.read", "minecraft.inventory.container.click" ->
                        Minecraft.getInstance().gui.screen() instanceof AbstractContainerScreen<?>;
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
                    case "minecraft.input.key.set" -> setKey(invocation, false);
                    case "minecraft.input.mouse.set" -> setKey(invocation, true);
                    case "minecraft.input.release-all" -> releaseAll(invocation);
                    case "minecraft.player.look" -> look(invocation);
                    case "minecraft.inventory.container.read" -> containerRead();
                    case "minecraft.inventory.container.click" -> containerClick(invocation);
                    case "minecraft.ui.state.read" -> captureUi().toMap();
                    case "minecraft.ui.click" -> uiClick(invocation);
                    case "minecraft.ui.key" -> uiKey(invocation);
                    case "minecraft.ui.text.insert" -> uiText(invocation);
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
                // 26.2's item components are not bound before a world exists. Keep title-menu search
                // registry-only rather than invoking component-backed item naming APIs.
                var translationKey = id;
                var displayName = id;
                if (!id.toLowerCase(Locale.ROOT).contains(normalizedQuery)
                        && !translationKey.toLowerCase(Locale.ROOT).contains(normalizedQuery)
                        && !displayName.toLowerCase(Locale.ROOT).contains(normalizedQuery)) {
                    continue;
                }
                matches.add(new RegistryItem(
                        FabricReadPrimitiveSupport.boundedText(id, 256),
                        FabricReadPrimitiveSupport.boundedText(translationKey, 512),
                        FabricReadPrimitiveSupport.boundedText(displayName, 4096),
                        64,
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
            mapping.setDown(down);
            if (down) {
                ownedMappings.put(mapping.getName(), mapping);
            } else {
                ownedMappings.remove(mapping.getName());
            }
            leasedMappings.remove(mapping.getName());
            return Map.of("key", mapping.getName(), "down", down, "mouse", mouse);
        }

        private Map<String, Object> releaseAll(InvocationContext invocation) {
            invocation.cancellation().commitMutation();
            var released = new ArrayList<>(ownedMappings.keySet());
            releaseAllOwned();
            released.sort(String::compareTo);
            return Map.of("released", released, "count", released.size(),
                    "leaseGeneration", inputLease.generation());
        }

        private void releaseAllOwned() {
            releaseOwned(new ArrayList<>(ownedMappings.keySet()));
            inputLease.releaseAll();
            leasedMappings.clear();
        }

        private void releaseOwned(Collection<String> names) {
            for (var name : names) {
                var mapping = ownedMappings.remove(name);
                if (mapping != null) mapping.setDown(false);
            }
        }

        private void releaseLeased(Collection<String> names) {
            for (var name : names) {
                if (!leasedMappings.remove(name)) continue;
                var mapping = ownedMappings.remove(name);
                if (mapping != null) mapping.setDown(false);
            }
        }

        private static long monotonicMillis() {
            return System.nanoTime() / 1_000_000L;
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

        private UiSnapshot captureUi() {
            var client = Minecraft.getInstance();
            var screen = client.gui.screen();
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
                    nodes.clear();
                    causes.clear();
                    causes.add("opaque-screen");
                    opaque = true;
                }
            }
            var truncated = !causes.isEmpty();
            var coverage = opaque ? "opaque" : truncated ? "partial" : "complete";
            var causeList = List.copyOf(causes);
            var revision = UiContracts.revision(screenToken, screenClass, title, width, height,
                    nodes, coverage, truncated, causeList);
            return new UiSnapshot(screen, screenToken, revision, capturedAtTick, inWorld,
                    screenName, screenClass, title, width, height, client.getWindow().getGuiScale(),
                    coverage, truncated, causeList, List.copyOf(nodes));
        }

        private static void captureChildren(ContainerEventHandler container, List<Integer> parentPath, int depth,
                                            List<UiNode> nodes, Set<String> causes,
                                            Set<GuiEventListener> seen) {
            List<? extends GuiEventListener> children = List.copyOf(container.children());
            if (children.isEmpty()) return;
            if (depth > UiLimits.DEFAULT.maxDepth()) {
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
                if (child == null || !seen.add(child)) {
                    causes.add("unsupported-widget");
                    continue;
                }
                var path = new ArrayList<Integer>(parentPath.size() + 1);
                path.addAll(parentPath);
                path.add(index);
                nodes.add(projectNode(child, container, path, depth, causes));
                if (child instanceof ContainerEventHandler nested) {
                    captureChildren(nested, path, depth + 1, nodes, causes, seen);
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
                label = safeWidgetLabel(widget);
                if (widget.active && widget.visible && widget.getWidth() > 0 && widget.getHeight() > 0) {
                    actions.add("click");
                }
            } else if (!(child instanceof ContainerEventHandler)) {
                causes.add("unsupported-widget");
            }
            if (child instanceof EditBox editBox) {
                textLength = editBox.getValue().length();
                textPresent = !editBox.getValue().isEmpty();
                actions.add("text-insert");
            }
            return new UiNode(nodeId(path), path, depth, bounded(child.getClass().getName(), 256),
                    bounded(parent.getClass().getName(), 256), label, null, bounds, child.isFocused(),
                    active, visible, actions, textLength, textPresent);
        }

        private static String safeWidgetLabel(AbstractWidget widget) {
            var label = widget.getMessage().getString();
            if (widget instanceof EditBox editBox && !editBox.getValue().isEmpty()
                    && label.contains(editBox.getValue())) return "";
            return bounded(label, 4096);
        }

        private Map<String, Object> uiClick(InvocationContext invocation) {
            var input = invocation.request().input();
            var expectedToken = requiredText(input, "screenToken");
            var expectedRevision = requiredText(input, "snapshotRevision");
            var selector = UiSelector.from(input);
            var button = numberOrDefault(input, "button", 0);
            if (button < 0 || button > 8) throw new IllegalArgumentException("button must be between 0 and 8");
            var snapshot = captureUi();
            if (!snapshot.screenToken().equals(expectedToken)) throw new IllegalStateException("UI screen token is stale");
            if (!snapshot.snapshotRevision().equals(expectedRevision)) throw new IllegalStateException("UI snapshot revision is stale");
            if (snapshot.nativeScreen() == null) throw new IllegalStateException("no screen is open");
            UiNode node;
            double x;
            double y;
            if (selector.x() != null) {
                x = selector.x();
                y = selector.y();
                node = uniqueCoordinateNode(snapshot.widgets(), x, y);
            } else {
                node = selector.resolve(snapshot.widgets());
                x = node.bounds().centerX();
                y = node.bounds().centerY();
            }
            if (x < 0 || y < 0 || x >= snapshot.width() || y >= snapshot.height()) {
                throw new IllegalArgumentException("UI click coordinates are outside the guarded screen");
            }
            invocation.cancellation().commitMutation();
            var mouseEvent = new MouseButtonEvent(x, y, new MouseButtonInfo(button, 0));
            var pressed = snapshot.nativeScreen().mouseClicked(mouseEvent, false);
            var released = snapshot.nativeScreen().mouseReleased(mouseEvent);
            var handled = pressed || released;
            var result = new LinkedHashMap<String, Object>();
            result.put("handled", handled);
            result.put("x", x);
            result.put("y", y);
            result.put("screenToken", snapshot.screenToken());
            result.put("snapshotRevision", snapshot.snapshotRevision());
            if (node != null) result.put("nodeId", node.nodeId());
            return Map.copyOf(result);
        }

        private static UiNode uniqueCoordinateNode(List<UiNode> nodes, double x, double y) {
            UiNode match = null;
            for (var candidate : nodes) {
                var bounds = candidate.bounds();
                if (bounds == null || bounds.width() <= 0 || bounds.height() <= 0
                        || x < bounds.x() || y < bounds.y()
                        || x >= bounds.x() + bounds.width() || y >= bounds.y() + bounds.height()) continue;
                if (match != null) return null;
                match = candidate;
            }
            return match;
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
            var client = Minecraft.getInstance();
            var screen = client.gui.screen();
            var input = invocation.request().input();
            var key = number(input, "key");
            var scanCode = numberOrDefault(input, "scanCode", 0);
            var modifiers = numberOrDefault(input, "modifiers", 0);
            if (screen == null) {
                if (key == 256 && client.level != null) {
                    invocation.cancellation().commitMutation();
                    client.gui.setScreen(new PauseScreen(true));
                    return Map.of("handled", true, "openedPause", true);
                }
                if (client.level != null && client.player != null) {
                    invocation.cancellation().commitMutation();
                    KeyMapping.click(InputConstants.getKey(new KeyEvent(key, scanCode, modifiers)));
                    return Map.of("handled", true, "openedPause", false);
                }
                throw new IllegalStateException("no screen is open");
            }
            invocation.cancellation().commitMutation();
            var handled = screen.keyPressed(new KeyEvent(key, scanCode, modifiers));
            return Map.of("handled", handled, "openedPause", false);
        }

        private static Map<String, Object> containerRead() {
            var client = Minecraft.getInstance();
            requirePlayer();
            if (!(client.gui.screen() instanceof AbstractContainerScreen<?> screen)) {
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
            if (!(client.gui.screen() instanceof AbstractContainerScreen<?> screen) || client.gameMode == null) {
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
            var containerInput = ContainerInput.valueOf(text(input, "clickType", "PICKUP").toUpperCase(Locale.ROOT));
            invocation.cancellation().commitMutation();
            client.gameMode.handleContainerInput(screen.getMenu().containerId, slot, button, containerInput, player);
            return Map.of("containerId", screen.getMenu().containerId, "slot", slot,
                    "button", button, "clickType", containerInput.toString());
        }

        private static Map<String, Object> uiText(InvocationContext invocation) {
            var screen = Minecraft.getInstance().gui.screen();
            if (screen == null) throw new IllegalStateException("no screen is open");
            var value = text(invocation.request().input(), "text", null);
            if (value.length() > 4096) throw new IllegalArgumentException("text must be <=4096 characters");
            for (GuiEventListener child : screen.children()) {
                if (child instanceof EditBox editBox && child.isFocused()) {
                    invocation.cancellation().commitMutation();
                    editBox.insertText(value);
                    return Map.of("inserted", true, "length", value.length());
                }
            }
            throw new IllegalStateException("no focused text input is available");
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

        private static String bounded(String value, int maxLength) {
            if (value == null || value.length() <= maxLength) return value;
            return value.substring(0, maxLength);
        }

        private static String itemId(ItemStack stack) {
            return stack.isEmpty() ? "minecraft:air" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
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
            if (!(value instanceof Number number)) {
                throw new IllegalArgumentException("input field must be numeric: " + key);
            }
            return InputNumbers.exactInt(number, key);
        }

        private static int numberOrDefault(Map<String, Object> input, String key, int fallback) {
            return input.get(key) == null ? fallback : number(input, key);
        }

        private static float decimal(Map<String, Object> input, String key) {
            var value = input.get(key);
            if (!(value instanceof Number number)) {
                throw new IllegalArgumentException("input field must be numeric: " + key);
            }
            return number.floatValue();
        }
    }
}
