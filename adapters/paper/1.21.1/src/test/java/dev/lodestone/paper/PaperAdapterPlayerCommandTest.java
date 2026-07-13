// SPDX-License-Identifier: MIT
package dev.lodestone.paper;

import dev.lodestone.adapter.AdapterContext;
import dev.lodestone.adapter.CancellationToken;
import dev.lodestone.adapter.InvocationContext;
import dev.lodestone.protocol.CapabilityPrerequisites;
import dev.lodestone.protocol.PermissionClass;
import dev.lodestone.protocol.ProtocolVersion;
import dev.lodestone.protocol.RequestEnvelope;
import dev.lodestone.protocol.SideEffect;
import org.bukkit.Bukkit;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperAdapterPlayerCommandTest {
    private static final String CAPABILITY = "minecraft.player.command.execute";
    private static final ServerState SERVER = new ServerState();
    private PaperAdapter adapter;

    @BeforeAll
    static void installServer() throws Exception {
        var serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        serverField.set(null, serverProxy(SERVER));
    }

    @BeforeEach
    void setUp() {
        SERVER.reset();
        adapter = new PaperAdapter(proxy(Plugin.class, Map.of()));
        adapter.start(new AdapterContext("paper-test", Runnable::run, (event, payload, tick) -> { }));
    }

    @Test
    void manifestDeclaresSharedPlayerCommandContract() {
        var capability = adapter.manifest().capabilities().stream()
                .filter(candidate -> CAPABILITY.equals(candidate.id()))
                .findFirst()
                .orElseThrow();

        assertEquals("1.0", capability.version());
        assertEquals(Set.of(PermissionClass.ADMINISTER_SERVER), capability.permissions());
        assertEquals(SideEffect.ADMINISTER_SERVER, capability.sideEffect());
        assertEquals(new CapabilityPrerequisites(true, true, false, false), capability.prerequisites());

        var input = capability.inputSchema();
        assertEquals(List.of("player", "command"), input.get("required"));
        var inputProperties = object(input.get("properties"));
        var player = object(inputProperties.get("player"));
        assertEquals(List.of(Map.of("required", List.of("uuid")), Map.of("required", List.of("name"))),
                player.get("anyOf"));
        assertEquals(32767, number(object(inputProperties.get("command")).get("maxLength")));
        var captureProperties = object(object(inputProperties.get("capture")).get("properties"));
        assertEquals(2000, number(object(captureProperties.get("windowMs")).get("maximum")));
        assertEquals(64, number(object(captureProperties.get("maxMessages")).get("maximum")));
        assertEquals(65536, number(object(captureProperties.get("maxBytes")).get("maximum")));

        var outputProperties = object(capability.outputSchema().get("properties"));
        assertEquals(List.of("dispatch-confirmed", "not-dispatched"),
                object(outputProperties.get("certainty")).get("enum"));
        var messages = object(outputProperties.get("messages"));
        assertEquals(64, number(messages.get("maxItems")));
        assertEquals(4096, number(object(messages.get("items")).get("maxLength")));
    }

    @Test
    void dispatchesAsExactUuidActorAndPreservesWorldEditSlash() {
        var player = player("Alex", UUID.fromString("00000000-0000-0000-0000-000000000001"));
        SERVER.players.add(player);
        var token = new RecordingToken(SERVER.events);

        var result = invoke(Map.of(
                "player", Map.of("uuid", player.getUniqueId().toString()),
                "command", "//pos1 10,64,10",
                "capture", Map.of("enabled", true, "windowMs", 500, "maxMessages", 12, "maxBytes", 4096)),
                token);

        assertEquals(List.of("commit", "dispatch"), SERVER.events);
        assertEquals(player, SERVER.sender);
        assertEquals("/pos1 10,64,10", SERVER.command);
        assertEquals(Map.of("uuid", player.getUniqueId().toString(), "name", "Alex"), result.get("actor"));
        assertEquals("/pos1 10,64,10", result.get("command"));
        assertEquals(true, result.get("dispatched"));
        assertEquals("dispatch-confirmed", result.get("certainty"));
        assertEquals(1, result.get("result"));
        assertEquals(List.of(), result.get("messages"));
        assertEquals(Map.of("complete", false, "truncated", false, "windowMs", 500,
                "maxMessages", 12, "maxBytes", 4096), result.get("capture"));
    }

    @Test
    void exactNameResolutionRejectsPartialNames() {
        SERVER.players.add(player("Alice", UUID.fromString("00000000-0000-0000-0000-000000000002")));
        var token = new RecordingToken(SERVER.events);

        var failure = invokeFailure(Map.of("player", Map.of("name", "Ali"), "command", "/help"), token);

        assertTrue(failure.getMessage().contains("online player"));
        assertFalse(token.committed);
        assertEquals(List.of(), SERVER.events);
    }

    @Test
    void exactNameResolutionRejectsPaddedNames() {
        SERVER.players.add(player("Alex", UUID.fromString("00000000-0000-0000-0000-000000000007")));
        var token = new RecordingToken(SERVER.events);

        var failure = invokeFailure(Map.of("player", Map.of("name", " Alex "), "command", "/help"), token);

        assertTrue(failure.getMessage().contains("exact name"));
        assertFalse(token.committed);
        assertEquals(List.of(), SERVER.events);
    }

    @Test
    void uuidResolutionRejectsPaddedUuids() {
        var uuid = UUID.fromString("00000000-0000-0000-0000-000000000008");
        SERVER.players.add(player("Alex", uuid));
        var token = new RecordingToken(SERVER.events);

        var failure = invokeFailure(Map.of("player", Map.of("uuid", " " + uuid + " "),
                "command", "/help"), token);

        assertTrue(failure.getMessage().contains("valid UUID"));
        assertFalse(token.committed);
        assertEquals(List.of(), SERVER.events);
    }

    @Test
    void duplicateExactNamesAreRejectedBeforeCommit() {
        SERVER.players.add(player("Alex", UUID.fromString("00000000-0000-0000-0000-000000000009")));
        SERVER.players.add(player("ALEX", UUID.fromString("00000000-0000-0000-0000-000000000010")));
        var token = new RecordingToken(SERVER.events);

        var failure = invokeFailure(Map.of("player", Map.of("name", "alex"), "command", "/help"), token);

        assertTrue(failure.getMessage().contains("ambiguous"));
        assertFalse(token.committed);
        assertEquals(List.of(), SERVER.events);
    }

    @Test
    void conflictingUuidAndNameFailBeforeCommit() {
        var first = player("First", UUID.fromString("00000000-0000-0000-0000-000000000003"));
        var second = player("Second", UUID.fromString("00000000-0000-0000-0000-000000000004"));
        SERVER.players.add(first);
        SERVER.players.add(second);
        var token = new RecordingToken(SERVER.events);

        var failure = invokeFailure(Map.of("player", Map.of(
                "uuid", first.getUniqueId().toString(), "name", second.getName()), "command", "/help"), token);

        assertTrue(failure.getMessage().contains("same online player"));
        assertFalse(token.committed);
        assertEquals(List.of(), SERVER.events);
    }

    @Test
    void emptyActorFailsBeforeCommit() {
        var token = new RecordingToken(SERVER.events);

        var failure = invokeFailure(Map.of("player", Map.of(), "command", "/help"), token);

        assertTrue(failure.getMessage().contains("uuid or name"));
        assertFalse(token.committed);
        assertEquals(List.of(), SERVER.events);
    }

    @Test
    void captureBoundsFailBeforeCommit() {
        var player = player("Alex", UUID.fromString("00000000-0000-0000-0000-000000000005"));
        SERVER.players.add(player);
        var token = new RecordingToken(SERVER.events);

        var failure = invokeFailure(Map.of(
                "player", Map.of("name", "Alex"), "command", "/help",
                "capture", Map.of("windowMs", 2001)), token);

        assertTrue(failure.getMessage().contains("windowMs"));
        assertFalse(token.committed);
        assertEquals(List.of(), SERVER.events);
    }

    @Test
    void dispatcherFalseIsReportedAsNotDispatchedWithZeroResult() {
        var player = player("Alex", UUID.fromString("00000000-0000-0000-0000-000000000006"));
        SERVER.players.add(player);
        SERVER.dispatchResult = false;

        var result = invoke(Map.of("player", Map.of("name", "alex"), "command", "help"),
                new RecordingToken(SERVER.events));

        assertEquals(false, result.get("dispatched"));
        assertEquals("not-dispatched", result.get("certainty"));
        assertEquals(0, result.get("result"));
        assertEquals("help", result.get("command"));
    }

    private Map<String, Object> invoke(Map<String, Object> input, CancellationToken token) {
        var handler = adapter.handlers().get(CAPABILITY);
        assertNotNull(handler);
        return handler.invoke(context(input, token)).toCompletableFuture().join();
    }

    private Throwable invokeFailure(Map<String, Object> input, CancellationToken token) {
        var handler = adapter.handlers().get(CAPABILITY);
        assertNotNull(handler);
        try {
            var stage = handler.invoke(context(input, token));
            var failure = assertThrows(CompletionException.class,
                    () -> stage.toCompletableFuture().join());
            return assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        } catch (IllegalArgumentException failure) {
            return failure;
        }
    }

    private static InvocationContext context(Map<String, Object> input, CancellationToken token) {
        return new InvocationContext(new RequestEnvelope(ProtocolVersion.CURRENT, UUID.randomUUID().toString(),
                "paper-test", CAPABILITY, "1.0", input, System.currentTimeMillis() + 5_000,
                null, false), token, Runnable::run, Map.of());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    private static int number(Object value) {
        return assertInstanceOf(Number.class, value).intValue();
    }

    private static Player player(String name, UUID uuid) {
        return proxy(Player.class, Map.of("getName", name, "getUniqueId", uuid));
    }

    private static Server serverProxy(ServerState state) {
        return (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[]{Server.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getOnlinePlayers" -> List.copyOf(state.players);
                    case "getWorlds" -> List.of(proxy(World.class, Map.of()));
                    case "isPrimaryThread" -> true;
                    case "dispatchCommand" -> {
                        state.sender = (CommandSender) arguments[0];
                        state.command = String.valueOf(arguments[1]);
                        state.events.add("dispatch");
                        yield state.dispatchResult;
                    }
                    case "toString" -> "PaperAdapterPlayerCommandTest.Server";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> defaultValue(method.getReturnType());
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

    private static final class ServerState {
        private final Collection<Player> players = new ArrayList<>();
        private final List<String> events = new ArrayList<>();
        private boolean dispatchResult = true;
        private CommandSender sender;
        private String command;

        private void reset() {
            players.clear();
            events.clear();
            dispatchResult = true;
            sender = null;
            command = null;
        }
    }

    private static final class RecordingToken implements CancellationToken {
        private final List<String> events;
        private boolean committed;

        private RecordingToken(List<String> events) {
            this.events = events;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public void commitMutation() {
            committed = true;
            events.add("commit");
        }
    }
}
