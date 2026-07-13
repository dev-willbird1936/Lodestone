// SPDX-License-Identifier: MIT
package dev.lodestone.folia;

import dev.lodestone.adapter.AdapterContext;
import dev.lodestone.adapter.CancellationToken;
import dev.lodestone.adapter.InvocationContext;
import dev.lodestone.protocol.Availability;
import dev.lodestone.protocol.CapabilityPrerequisites;
import dev.lodestone.protocol.PermissionClass;
import dev.lodestone.protocol.ProtocolVersion;
import dev.lodestone.protocol.RequestEnvelope;
import dev.lodestone.protocol.SideEffect;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FoliaAdapterPlayerCommandTest {
    private static final String CAPABILITY = "minecraft.player.command.execute";
    private static final ServerState SERVER = new ServerState();
    private static final ScheduledTask SCHEDULED_TASK = proxy(ScheduledTask.class, Map.of());
    private FoliaAdapter adapter;

    @BeforeAll
    static void installServer() throws Exception {
        var serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        serverField.set(null, serverProxy(SERVER));
    }

    @BeforeEach
    void setUp() {
        SERVER.reset();
        adapter = new FoliaAdapter(proxy(Plugin.class, Map.of()));
        adapter.start(new AdapterContext("folia-test", Runnable::run, (event, payload, tick) -> { }));
    }

    @Test
    void manifestDeclaresSharedRestrictedContract() {
        var capability = adapter.manifest().capabilities().stream()
                .filter(candidate -> CAPABILITY.equals(candidate.id()))
                .findFirst()
                .orElseThrow();

        assertEquals("1.0", capability.version());
        assertEquals(Availability.RESTRICTED, capability.availability());
        assertEquals(Set.of(PermissionClass.ADMINISTER_SERVER), capability.permissions());
        assertEquals(SideEffect.ADMINISTER_SERVER, capability.sideEffect());
        assertEquals(new CapabilityPrerequisites(true, true, false, false), capability.prerequisites());
        var inputProperties = object(capability.inputSchema().get("properties"));
        assertEquals(32767, number(object(inputProperties.get("command")).get("maxLength")));
        var capture = object(object(inputProperties.get("capture")).get("properties"));
        assertEquals(2000, number(object(capture.get("windowMs")).get("maximum")));
        assertEquals(64, number(object(capture.get("maxMessages")).get("maximum")));
        assertEquals(65536, number(object(capture.get("maxBytes")).get("maximum")));
    }

    @Test
    void dispatchWaitsForEntitySchedulerAndPreservesWorldEditSlash() {
        var player = SERVER.addPlayer("Alex", "00000000-0000-0000-0000-000000000001");
        player.mode = EntityMode.DEFER;
        var token = new RecordingToken(SERVER.events);

        var stage = invokeStage(Map.of(
                "player", Map.of("uuid", player.uuid.toString()),
                "command", "//pos1 10,64,10",
                "capture", Map.of("enabled", true, "windowMs", 500, "maxMessages", 12, "maxBytes", 4096)),
                token);

        assertFalse(stage.isDone());
        assertEquals(List.of("global"), SERVER.events);
        player.runPending();
        var result = stage.join();
        assertEquals(List.of("global", "entity", "commit", "dispatch"), SERVER.events);
        assertEquals("entity", SERVER.dispatchPhase);
        assertEquals(player.player, SERVER.sender);
        assertEquals("/pos1 10,64,10", SERVER.command);
        assertEquals(Map.of("uuid", player.uuid.toString(), "name", "Alex"), result.get("actor"));
        assertEquals(true, result.get("dispatched"));
        assertEquals("dispatch-confirmed", result.get("certainty"));
        assertEquals(1, result.get("result"));
        assertEquals(List.of(), result.get("messages"));
        assertEquals(Map.of("complete", false, "truncated", false, "windowMs", 500,
                "maxMessages", 12, "maxBytes", 4096), result.get("capture"));
        assertTrue(token.committed);
    }

    @Test
    void exactActorFailuresNeverReachEntitySchedulerOrCommit() {
        SERVER.addPlayer("Alice", "00000000-0000-0000-0000-000000000002");
        SERVER.addPlayer("ALICE", "00000000-0000-0000-0000-000000000003");
        var token = new RecordingToken(SERVER.events);

        var failure = invokeFailure(Map.of("player", Map.of("name", "alice"), "command", "/help"), token);

        assertInstanceOf(IllegalArgumentException.class, failure);
        assertTrue(failure.getMessage().contains("ambiguous"));
        assertFalse(token.committed);
        assertEquals(List.of("global"), SERVER.events);
    }

    @Test
    void selectorAndCaptureValidationFailBeforeScheduling() {
        var token = new RecordingToken(SERVER.events);

        var paddedUuid = invokeFailure(Map.of("player", Map.of(
                "uuid", " 00000000-0000-0000-0000-000000000004 "), "command", "/help"), token);
        assertTrue(paddedUuid.getMessage().contains("valid UUID"));
        var badCapture = invokeFailure(Map.of("player", Map.of("name", "Alex"), "command", "/help",
                "capture", Map.of("windowMs", 2001)), token);
        assertTrue(badCapture.getMessage().contains("windowMs"));
        assertEquals(List.of(), SERVER.events);
        assertFalse(token.committed);
    }

    @Test
    void logoutAfterGlobalResolutionFailsBeforeCommit() {
        var player = SERVER.addPlayer("Alex", "00000000-0000-0000-0000-000000000005");
        player.mode = EntityMode.DEFER;
        var token = new RecordingToken(SERVER.events);
        var stage = invokeStage(Map.of("player", Map.of("name", "Alex"), "command", "/help"), token);

        player.online = false;
        player.runPending();
        var failure = completionFailure(stage);

        assertInstanceOf(IllegalArgumentException.class, failure);
        assertTrue(failure.getMessage().contains("online"));
        assertFalse(token.committed);
        assertEquals(List.of("global", "entity"), SERVER.events);
    }

    @Test
    void alreadyRetiredEntitySchedulerFailsWithoutHangingOrCommit() {
        var player = SERVER.addPlayer("Alex", "00000000-0000-0000-0000-000000000006");
        player.mode = EntityMode.RETURN_NULL;
        var token = new RecordingToken(SERVER.events);

        var failure = invokeFailure(Map.of("player", Map.of("name", "Alex"), "command", "/help"), token);

        assertInstanceOf(IllegalStateException.class, failure);
        assertTrue(failure.getMessage().contains("retired"));
        assertFalse(token.committed);
        assertEquals(List.of("global"), SERVER.events);
    }

    @Test
    void retirementAfterSchedulingCompletesExceptionallyWithoutCommit() {
        var player = SERVER.addPlayer("Alex", "00000000-0000-0000-0000-000000000007");
        player.mode = EntityMode.DEFER;
        var token = new RecordingToken(SERVER.events);
        var stage = invokeStage(Map.of("player", Map.of("name", "Alex"), "command", "/help"), token);

        player.retirePending();
        var failure = completionFailure(stage);

        assertInstanceOf(IllegalStateException.class, failure);
        assertTrue(failure.getMessage().contains("retired"));
        assertFalse(token.committed);
        assertEquals(List.of("global"), SERVER.events);
    }

    @Test
    void cancellationAfterSchedulingPreventsDispatch() {
        var player = SERVER.addPlayer("Alex", "00000000-0000-0000-0000-000000000008");
        player.mode = EntityMode.DEFER;
        var token = new RecordingToken(SERVER.events);
        var stage = invokeStage(Map.of("player", Map.of("name", "Alex"), "command", "/help"), token);

        token.cancelled = true;
        player.runPending();
        var failure = completionFailure(stage);

        assertInstanceOf(CancellationToken.CancellationException.class, failure);
        assertFalse(token.committed);
        assertEquals(List.of("global", "entity"), SERVER.events);
    }

    @Test
    void closeAfterSchedulingMakesLaterEntityCallbackHarmless() {
        var player = SERVER.addPlayer("Alex", "00000000-0000-0000-0000-000000000009");
        player.mode = EntityMode.DEFER;
        var token = new RecordingToken(SERVER.events);
        var stage = invokeStage(Map.of("player", Map.of("name", "Alex"), "command", "/help"), token);

        adapter.close();
        assertInstanceOf(IllegalStateException.class, completionFailure(stage));
        player.runPending();

        assertFalse(token.committed);
        assertEquals(List.of("global", "entity"), SERVER.events);
        assertEquals(null, SERVER.command);
    }

    @Test
    void closeRacingCommittedEntityCallbackCannotReplaceItsSuccess() throws Exception {
        var player = SERVER.addPlayer("Alex", "00000000-0000-0000-0000-000000000012");
        player.mode = EntityMode.DEFER;
        var token = new BlockingCommitToken(SERVER.events);
        var stage = invokeStage(Map.of("player", Map.of("name", "Alex"), "command", "/help"), token);
        var entityThread = new Thread(player::runPending, "folia-command-entity-test");
        var closeThread = new Thread(adapter::close, "folia-command-close-test");

        entityThread.start();
        try {
            assertTrue(token.entered.await(5, TimeUnit.SECONDS));
            closeThread.start();
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (closeThread.getState() != Thread.State.BLOCKED && closeThread.isAlive()
                    && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertEquals(Thread.State.BLOCKED, closeThread.getState());
        } finally {
            token.release.countDown();
        }
        entityThread.join(5_000);
        closeThread.join(5_000);

        assertFalse(entityThread.isAlive());
        assertFalse(closeThread.isAlive());
        assertEquals(true, stage.join().get("dispatched"));
        assertEquals(List.of("global", "entity", "commit", "dispatch"), SERVER.events);
    }

    @Test
    void reentrantCloseDuringDispatchCannotReplaceCommittedResult() {
        SERVER.addPlayer("Alex", "00000000-0000-0000-0000-000000000013");
        var token = new RecordingToken(SERVER.events);
        SERVER.dispatchAction = adapter::close;

        var result = invokeStage(Map.of("player", Map.of("name", "Alex"), "command", "/help"), token).join();

        assertTrue(token.committed);
        assertEquals(true, result.get("dispatched"));
        assertEquals("dispatch-confirmed", result.get("certainty"));
        assertEquals(List.of("global", "entity", "commit", "dispatch"), SERVER.events);
    }

    @Test
    void dispatcherFalseIsReportedAsNotDispatchedAndCaptureDefaultsAreHonest() {
        SERVER.addPlayer("Alex", "00000000-0000-0000-0000-000000000010");
        SERVER.dispatchResult = false;

        var result = invokeStage(Map.of("player", Map.of("name", "alex"), "command", "help"),
                new RecordingToken(SERVER.events)).join();

        assertEquals(false, result.get("dispatched"));
        assertEquals("not-dispatched", result.get("certainty"));
        assertEquals(0, result.get("result"));
        assertEquals(Map.of("complete", false, "truncated", false, "windowMs", 0,
                "maxMessages", 0, "maxBytes", 0), result.get("capture"));
    }

    @Test
    void dispatchExceptionOccursOnlyAfterCommit() {
        SERVER.addPlayer("Alex", "00000000-0000-0000-0000-000000000011");
        SERVER.dispatchFailure = new IllegalStateException("dispatch exploded");
        var token = new RecordingToken(SERVER.events);

        var failure = invokeFailure(Map.of("player", Map.of("name", "Alex"), "command", "/help"), token);

        assertEquals(SERVER.dispatchFailure, failure);
        assertTrue(token.committed);
        assertEquals(List.of("global", "entity", "commit", "dispatch"), SERVER.events);
    }

    private CompletableFuture<Map<String, Object>> invokeStage(Map<String, Object> input, CancellationToken token) {
        var handler = adapter.handlers().get(CAPABILITY);
        assertNotNull(handler);
        return handler.invoke(context(input, token)).toCompletableFuture();
    }

    private Throwable invokeFailure(Map<String, Object> input, CancellationToken token) {
        try {
            return completionFailure(invokeStage(input, token));
        } catch (IllegalArgumentException failure) {
            return failure;
        }
    }

    private static Throwable completionFailure(CompletableFuture<?> stage) {
        var failure = assertThrows(CompletionException.class, stage::join);
        return failure.getCause();
    }

    private static InvocationContext context(Map<String, Object> input, CancellationToken token) {
        return new InvocationContext(new RequestEnvelope(ProtocolVersion.CURRENT, UUID.randomUUID().toString(),
                "folia-test", CAPABILITY, "1.0", input, System.currentTimeMillis() + 5_000,
                null, false), token, Runnable::run, Map.of());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    private static int number(Object value) {
        return assertInstanceOf(Number.class, value).intValue();
    }

    private static Server serverProxy(ServerState state) {
        var global = globalScheduler(state);
        var world = proxy(World.class, Map.of("getKey", new NamespacedKey("minecraft", "overworld")));
        return (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[]{Server.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getOnlinePlayers" -> List.copyOf(state.players);
                    case "getWorlds" -> List.of(world);
                    case "getGlobalRegionScheduler" -> global;
                    case "dispatchCommand" -> {
                        state.sender = (CommandSender) arguments[0];
                        state.command = String.valueOf(arguments[1]);
                        state.dispatchPhase = state.phase;
                        state.events.add("dispatch");
                        if (state.dispatchAction != null) state.dispatchAction.run();
                        if (state.dispatchFailure != null) throw state.dispatchFailure;
                        yield state.dispatchResult;
                    }
                    case "toString" -> "FoliaAdapterPlayerCommandTest.Server";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    @SuppressWarnings("unchecked")
    private static GlobalRegionScheduler globalScheduler(ServerState state) {
        return (GlobalRegionScheduler) Proxy.newProxyInstance(GlobalRegionScheduler.class.getClassLoader(),
                new Class<?>[]{GlobalRegionScheduler.class}, (proxy, method, arguments) -> {
                    if ("run".equals(method.getName())) {
                        state.events.add("global");
                        var previous = state.phase;
                        state.phase = "global";
                        try {
                            ((Consumer<ScheduledTask>) arguments[1]).accept(SCHEDULED_TASK);
                        } finally {
                            state.phase = previous;
                        }
                        return SCHEDULED_TASK;
                    }
                    return defaultProxyMethod(proxy, method.getName(), arguments, method.getReturnType());
                });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Map<String, Object> values) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "toString" -> type.getSimpleName() + values;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> values.containsKey(method.getName())
                            ? values.get(method.getName()) : defaultValue(method.getReturnType());
                });
    }

    private static Object defaultProxyMethod(Object proxy, String name, Object[] arguments, Class<?> returnType) {
        return switch (name) {
            case "toString" -> proxy.getClass().getInterfaces()[0].getSimpleName();
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == arguments[0];
            default -> defaultValue(returnType);
        };
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        return 0D;
    }

    private enum EntityMode { IMMEDIATE, DEFER, RETURN_NULL }

    private static final class PlayerState {
        private final ServerState server;
        private final String name;
        private final UUID uuid;
        private final Player player;
        private final EntityScheduler scheduler;
        private EntityMode mode = EntityMode.IMMEDIATE;
        private boolean online = true;
        private Consumer<ScheduledTask> pendingTask;
        private Runnable pendingRetired;

        private PlayerState(ServerState server, String name, UUID uuid) {
            this.server = server;
            this.name = name;
            this.uuid = uuid;
            this.scheduler = entityScheduler(this);
            this.player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                    (proxy, method, arguments) -> switch (method.getName()) {
                        case "getName" -> this.name;
                        case "getUniqueId" -> this.uuid;
                        case "isOnline" -> this.online;
                        case "getScheduler" -> this.scheduler;
                        case "toString" -> "Player[" + this.name + "]";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == arguments[0];
                        default -> defaultValue(method.getReturnType());
                    });
        }

        @SuppressWarnings("unchecked")
        private static EntityScheduler entityScheduler(PlayerState state) {
            return (EntityScheduler) Proxy.newProxyInstance(EntityScheduler.class.getClassLoader(),
                    new Class<?>[]{EntityScheduler.class}, (proxy, method, arguments) -> {
                        if ("run".equals(method.getName())) {
                            var task = (Consumer<ScheduledTask>) arguments[1];
                            var retired = (Runnable) arguments[2];
                            if (state.mode == EntityMode.RETURN_NULL) return null;
                            if (state.mode == EntityMode.DEFER) {
                                state.pendingTask = task;
                                state.pendingRetired = retired;
                                return SCHEDULED_TASK;
                            }
                            state.execute(task);
                            return SCHEDULED_TASK;
                        }
                        return defaultProxyMethod(proxy, method.getName(), arguments, method.getReturnType());
                    });
        }

        private void runPending() {
            assertNotNull(pendingTask);
            var task = pendingTask;
            pendingTask = null;
            pendingRetired = null;
            execute(task);
        }

        private void retirePending() {
            assertNotNull(pendingRetired);
            var retired = pendingRetired;
            pendingTask = null;
            pendingRetired = null;
            retired.run();
        }

        private void execute(Consumer<ScheduledTask> task) {
            server.events.add("entity");
            var previous = server.phase;
            server.phase = "entity";
            try {
                task.accept(SCHEDULED_TASK);
            } finally {
                server.phase = previous;
            }
        }
    }

    private static final class ServerState {
        private final Collection<Player> players = new ArrayList<>();
        private final List<String> events = new ArrayList<>();
        private boolean dispatchResult = true;
        private Runnable dispatchAction;
        private RuntimeException dispatchFailure;
        private CommandSender sender;
        private String command;
        private String phase;
        private String dispatchPhase;

        private PlayerState addPlayer(String name, String uuid) {
            var state = new PlayerState(this, name, UUID.fromString(uuid));
            players.add(state.player);
            return state;
        }

        private void reset() {
            players.clear();
            events.clear();
            dispatchResult = true;
            dispatchAction = null;
            dispatchFailure = null;
            sender = null;
            command = null;
            phase = null;
            dispatchPhase = null;
        }
    }

    private static final class RecordingToken implements CancellationToken {
        private final List<String> events;
        private boolean cancelled;
        private boolean committed;

        private RecordingToken(List<String> events) {
            this.events = events;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public void commitMutation() {
            throwIfCancelled();
            committed = true;
            events.add("commit");
        }
    }

    private static final class BlockingCommitToken implements CancellationToken {
        private final List<String> events;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        private BlockingCommitToken(List<String> events) {
            this.events = events;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public void commitMutation() {
            events.add("commit");
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("timed out waiting to release commit boundary");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("commit boundary interrupted", interrupted);
            }
        }
    }
}
