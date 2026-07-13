// SPDX-License-Identifier: MIT
package dev.lodestone.fabric;

import dev.lodestone.adapter.CancellationToken;
import dev.lodestone.adapter.InvocationContext;
import dev.lodestone.protocol.Availability;
import dev.lodestone.protocol.ProtocolVersion;
import dev.lodestone.protocol.RequestEnvelope;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.launch.FabricLauncher;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

final class FabricAdapterReadPrimitivesTest {
    @BeforeAll
    static void installFabricTestLauncher() {
        if (FabricLauncherBase.getLauncher() != null) {
            return;
        }
        var launcher = (FabricLauncher) Proxy.newProxyInstance(
                FabricLauncher.class.getClassLoader(),
                new Class<?>[]{FabricLauncher.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getEnvironmentType" -> EnvType.CLIENT;
                    case "isDevelopment", "isClassLoaded" -> false;
                    case "getClassPath" -> java.util.List.of();
                    default -> null;
                });
        FabricLauncherBase.setLauncher(launcher);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "minecraft.registry.item.search",
            "minecraft.server.info.read",
            "minecraft.player.context.read",
            "minecraft.entity.nearby.read"
    })
    void advertisesRegistersAndDelegatesReadPrimitive(String capability) {
        var invoked = new AtomicReference<String>();
        var adapter = new FabricAdapter();
        adapter.attachClientBridge(new FabricAdapter.ClientBridge() {
            @Override
            public boolean available(String candidate) {
                return true;
            }

            @Override
            public java.util.concurrent.CompletionStage<Map<String, Object>> invoke(
                    String candidate, InvocationContext invocation) {
                invoked.set(candidate);
                return CompletableFuture.completedFuture(Map.of("capability", candidate));
            }
        });

        var descriptor = adapter.manifest().capabilities().stream()
                .filter(candidate -> candidate.id().equals(capability))
                .findFirst()
                .orElseThrow();
        assertEquals(Availability.AVAILABLE, descriptor.availability());

        var handler = adapter.handlers().get(capability);
        assertNotNull(handler, "read primitive must have a native handler");
        var output = handler.invoke(invocation(capability)).toCompletableFuture().join();
        assertEquals(capability, invoked.get());
        assertEquals(capability, output.get("capability"));
    }

    private static InvocationContext invocation(String capability) {
        var request = new RequestEnvelope(
                ProtocolVersion.CURRENT,
                "request-1",
                "session-1",
                capability,
                "1.0",
                Map.of(),
                null,
                null,
                false);
        return new InvocationContext(request, CancellationToken.none(), Runnable::run, Map.of());
    }
}
