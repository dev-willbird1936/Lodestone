// SPDX-License-Identifier: MIT
package dev.lodestone.forge;

import com.mojang.blaze3d.platform.InputConstants;
import dev.lodestone.adapter.InputNumbers;
import dev.lodestone.adapter.UiBounds;
import dev.lodestone.adapter.UiContracts;
import dev.lodestone.adapter.UiLimits;
import dev.lodestone.adapter.UiNode;
import dev.lodestone.adapter.UiSelector;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Physical-client-only MCP bridge. Registered through DistExecutor by the common mod class. */
public final class ForgeClientController {
    private static final ClientBridgeImpl BRIDGE = new ClientBridgeImpl();
    private static boolean attached;
    private static boolean lastWorld;
    private static boolean lastScreen;
    private static long clientTick;

    private ForgeClientController() {
    }

    public static void register() {
        MinecraftForge.EVENT_BUS.addListener(ForgeClientController::onClientTick);
    }

    private static void onClientTick(TickEvent.ClientTickEvent.Post event) {
        clientTick++;
        var adapter = ForgeAdapter.active();
        if (adapter == null) return;
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

    private static final class ClientBridgeImpl implements ForgeAdapter.ClientBridge {
        private Screen tokenScreen;
        private boolean tokenInitialized;
        private long screenSequence;
        private String currentScreenToken = "forge121-0";

        @Override
        public boolean available(String capability) {
            var client = Minecraft.getInstance();
            return switch (capability) {
                case "minecraft.ui.state.read" -> true;
                case "minecraft.ui.key" -> client.screen != null || client.level != null;
                case "minecraft.ui.click" -> client.screen != null;
                case "minecraft.inventory.container.read" -> client.screen instanceof AbstractContainerScreen<?>
                        && client.level != null && client.player != null;
                case "minecraft.inventory.container.click" -> client.screen instanceof AbstractContainerScreen<?>
                        && client.level != null && client.player != null && client.gameMode != null;
                default -> client.level != null && client.player != null;
            };
        }

        @Override
        public CompletionStage<Map<String, Object>> invoke(String capability,
                                                           dev.lodestone.adapter.InvocationContext invocation) {
            return onClientThread(() -> {
                invocation.cancellation().throwIfCancelled();
                return switch (capability) {
                    case "minecraft.player.state.read" -> playerState();
                    case "minecraft.player.look" -> look(invocation);
                    case "minecraft.inventory.container.read" -> containerRead();
                    case "minecraft.inventory.container.click" -> containerClick(invocation);
                    case "minecraft.ui.state.read" -> uiState();
                    case "minecraft.ui.click" -> uiClick(invocation);
                    case "minecraft.ui.key" -> uiKey(invocation);
                    default -> throw new IllegalArgumentException("unsupported Forge 1.21.1 client capability: " + capability);
                };
            });
        }

        private void observeScreen(Screen screen) {
            screenToken(screen);
        }

        private Map<String, Object> playerState() {
            var player = requirePlayer();
            return Map.of("uuid", player.getUUID().toString(), "name", player.getGameProfile().getName(),
                    "position", Map.of("x", player.getX(), "y", player.getY(), "z", player.getZ()),
                    "rotation", Map.of("yaw", player.getYRot(), "pitch", player.getXRot()),
                    "dimension", player.level().dimension().location().toString(),
                    "health", player.getHealth(), "food", player.getFoodData().getFoodLevel(),
                    "selectedSlot", player.getInventory().selected);
        }

        private Map<String, Object> look(dev.lodestone.adapter.InvocationContext invocation) {
            var player = requirePlayer();
            var input = invocation.request().input();
            var yaw = finiteNumber(input, "yaw");
            var pitch = finiteNumber(input, "pitch");
            if (pitch < -90 || pitch > 90 || yaw < -3600 || yaw > 3600) {
                throw new IllegalArgumentException("look rotation is outside safe bounds");
            }
            invocation.cancellation().commitMutation();
            player.setYRot((float) yaw);
            player.setXRot((float) pitch);
            player.setYHeadRot((float) yaw);
            return Map.of("yaw", player.getYRot(), "pitch", player.getXRot());
        }

        private Map<String, Object> uiState() {
            return captureUi().toMap();
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
                slots.add(Map.of("slot", index, "item", itemId(stack),
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
            var clickTypeName = input.get("clickType") == null ? "PICKUP" : text(input, "clickType");
            var clickType = ClickType.valueOf(clickTypeName.toUpperCase(java.util.Locale.ROOT));
            invocation.cancellation().commitMutation();
            client.gameMode.handleInventoryMouseClick(screen.getMenu().containerId, slot, button, clickType, player);
            return Map.of("containerId", screen.getMenu().containerId, "slot", slot,
                    "button", button, "clickType", clickType.toString());
        }

        private Map<String, Object> uiClick(dev.lodestone.adapter.InvocationContext invocation) {
            var input = invocation.request().input();
            var expectedToken = text(input, "screenToken");
            var expectedRevision = text(input, "snapshotRevision");
            var button = numberOrDefault(input, "button", 0);
            if (button < 0 || button > 2) throw new IllegalArgumentException("button must be between 0 and 2");
            var selector = UiSelector.from(input);
            var snapshot = captureUi();
            if (snapshot.screen() == null) throw new IllegalStateException("no screen is open");
            if (!snapshot.screenToken().equals(expectedToken)) throw new IllegalStateException("UI screen token is stale");
            if (!snapshot.snapshotRevision().equals(expectedRevision)) throw new IllegalStateException("UI snapshot revision is stale");
            var node = selector.resolve(snapshot.nodes());
            var x = node == null ? selector.x() : node.bounds().centerX();
            var y = node == null ? selector.y() : node.bounds().centerY();
            if (x < 0 || y < 0 || x >= snapshot.width() || y >= snapshot.height()) {
                throw new IllegalArgumentException("UI click coordinates are outside the guarded screen");
            }
            invocation.cancellation().commitMutation();
            var handled = snapshot.screen().mouseClicked(x, y, button);
            var released = snapshot.screen().mouseReleased(x, y, button);
            var result = new LinkedHashMap<String, Object>();
            result.put("handled", handled || released);
            result.put("x", x);
            result.put("y", y);
            result.put("screenToken", snapshot.screenToken());
            result.put("snapshotRevision", snapshot.snapshotRevision());
            if (node != null) result.put("nodeId", node.nodeId());
            return Map.copyOf(result);
        }

        private Map<String, Object> uiKey(dev.lodestone.adapter.InvocationContext invocation) {
            var client = Minecraft.getInstance();
            var screen = client.screen;
            var input = invocation.request().input();
            var key = number(input, "key");
            var scanCode = numberOrDefault(input, "scanCode", 0);
            var modifiers = numberOrDefault(input, "modifiers", 0);
            if (screen == null) {
                if (key == 256 && client.level != null) {
                    invocation.cancellation().commitMutation();
                    client.setScreen(new PauseScreen(true));
                    return Map.of("handled", true, "openedPause", true);
                }
                if (client.level != null && client.player != null) {
                    invocation.cancellation().commitMutation();
                    KeyMapping.click(InputConstants.getKey(key, scanCode));
                    return Map.of("handled", true, "openedPause", false);
                }
                throw new IllegalStateException("no screen is open");
            }
            invocation.cancellation().commitMutation();
            var handled = screen.keyPressed(key, scanCode, modifiers);
            return Map.of("handled", handled, "openedPause", false);
        }

        private static String itemId(ItemStack stack) {
            return stack.isEmpty() ? "minecraft:air" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
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
                var revision = UiContracts.revision(token, "", "", width, height, List.of(), "complete", false, List.of());
                return new UiSnapshot(null, false, token, revision, clientTick, inWorld, "", "", "", width, height,
                        guiScale, "complete", false, List.of(), List.of());
            }
            var token = screenToken(screen);
            var nodes = new ArrayList<UiNode>();
            var causes = new LinkedHashSet<String>();
            var seen = new IdentityHashMap<GuiEventListener, Boolean>();
            seen.put(screen, Boolean.TRUE);
            traverseChildren(screen, token, List.of(), 0, screen.getClass().getName(), seen, nodes, causes);
            if (nodes.isEmpty()) causes.add("opaque-screen");
            var truncated = !causes.isEmpty();
            var coverage = causes.contains("opaque-screen") ? "opaque" : truncated ? "partial" : "complete";
            var screenClass = screen.getClass().getName();
            var screenName = screen.getClass().getSimpleName().isEmpty() ? screenClass : screen.getClass().getSimpleName();
            var title = safeText(screen.getTitle().getString());
            var revision = UiContracts.revision(token, screenClass, title, width, height, nodes, coverage, truncated,
                    List.copyOf(causes));
            return new UiSnapshot(screen, true, token, revision, clientTick, inWorld, screenName, screenClass, title,
                    width, height, guiScale, coverage, truncated, List.copyOf(causes), List.copyOf(nodes));
        }

        private boolean traverseChildren(ContainerEventHandler parent, String token, List<Integer> prefix,
                                         int depth, String parentType,
                                         IdentityHashMap<GuiEventListener, Boolean> seen,
                                         List<UiNode> nodes, Set<String> causes) {
            var children = parent.children();
            var childCount = Math.min(children.size(), UiLimits.DEFAULT.maxChildren());
            if (children.size() > UiLimits.DEFAULT.maxChildren()) causes.add("child-limit");
            for (var index = 0; index < childCount; index++) {
                var child = children.get(index);
                if (child == null || seen.put(child, Boolean.TRUE) != null) continue;
                if (nodes.size() >= UiLimits.DEFAULT.maxNodes()) {
                    causes.add("node-limit");
                    return false;
                }
                var path = new ArrayList<>(prefix);
                path.add(index);
                nodes.add(projectNode(child, token, path, depth, parentType, causes));
                if (child instanceof ContainerEventHandler container) {
                    if (depth >= UiLimits.DEFAULT.maxDepth()) {
                        if (!container.children().isEmpty()) causes.add("depth-limit");
                    } else if (!traverseChildren(container, token, path, depth + 1, child.getClass().getName(),
                            seen, nodes, causes)) {
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
                if (!(listener instanceof ContainerEventHandler)) causes.add("unsupported-widget");
                return new UiNode(nodeId, path, depth, listener.getClass().getName(), parentType,
                        null, null, null, listener.isFocused(), null, null, Set.of(), null, null);
            }
            var label = safeText(widget.getMessage().getString());
            var bounds = new UiBounds(widget.getX(), widget.getY(), Math.max(0, widget.getWidth()), Math.max(0, widget.getHeight()));
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
                currentScreenToken = "forge121-" + Long.toUnsignedString(++screenSequence, 36);
            }
            return currentScreenToken;
        }

        private static String safeText(String value) {
            if (value == null || value.isEmpty()) return "";
            return value.length() <= 512 ? value : value.substring(0, 512);
        }

        private static LocalPlayer requirePlayer() {
            var client = Minecraft.getInstance();
            if (client.player == null || client.level == null) {
                throw new IllegalStateException("client player/world is unavailable");
            }
            return client.player;
        }

        private static Screen requireScreen() {
            var screen = Minecraft.getInstance().screen;
            if (screen == null) throw new IllegalStateException("no screen is open");
            return screen;
        }

        private static String text(Map<String, Object> input, String key) {
            var value = input.get(key);
            if (!(value instanceof String string) || string.isBlank()) {
                throw new IllegalArgumentException("input field must be a non-empty string: " + key);
            }
            return string;
        }

        private static int number(Map<String, Object> input, String key) {
            var value = input.get(key);
            if (!(value instanceof Number number)) throw new IllegalArgumentException("input field must be numeric: " + key);
            return InputNumbers.exactInt(number, key);
        }

        private static int numberOrDefault(Map<String, Object> input, String key, int fallback) {
            return input.get(key) == null ? fallback : number(input, key);
        }

        private static double finiteNumber(Map<String, Object> input, String key) {
            var value = input.get(key);
            if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
                throw new IllegalArgumentException("input field must be a finite number: " + key);
            }
            return number.doubleValue();
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
    }
}
