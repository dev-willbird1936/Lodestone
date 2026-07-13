// SPDX-License-Identifier: MIT
package dev.lodestone.fabric;

import dev.lodestone.adapter.ArtifactReference;
import dev.lodestone.adapter.CancellationToken;
import dev.lodestone.adapter.InvocationContext;
import dev.lodestone.protocol.Availability;
import dev.lodestone.protocol.ProtocolVersion;
import dev.lodestone.protocol.RequestEnvelope;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.launch.FabricLauncher;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

final class FabricScreenshotAdapterTest {
    private static final String CAPABILITY = "minecraft.client.screenshot.capture";

    @BeforeAll
    static void installFabricTestLauncher() throws ReflectiveOperationException {
        if (FabricLauncherBase.getLauncher() != null) return;
        var launcher = (FabricLauncher) Proxy.newProxyInstance(
                FabricLauncher.class.getClassLoader(), new Class<?>[]{FabricLauncher.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getEnvironmentType" -> EnvType.CLIENT;
                    case "isDevelopment", "isClassLoaded" -> false;
                    case "getClassPath" -> java.util.List.of();
                    default -> null;
                });
        var launcherField = FabricLauncherBase.class.getDeclaredField("launcher");
        launcherField.setAccessible(true);
        launcherField.set(null, launcher);
    }

    @Test
    void advertisesScreenshotWithoutAWorldPlayerOrScreen() {
        var adapter = adapterReturning(Map.of());
        var descriptor = adapter.manifest().capabilities().stream()
                .filter(candidate -> candidate.id().equals(CAPABILITY)).findFirst().orElseThrow();
        assertEquals(Availability.AVAILABLE, descriptor.availability());
        assertFalse(descriptor.prerequisites().requiresWorld());
        assertFalse(descriptor.prerequisites().requiresPlayer());
        assertFalse(descriptor.prerequisites().requiresScreen());
    }

    @Test
    void delegatesCanonicalNestedArtifactMetadata() {
        var invoked = new AtomicReference<String>();
        var expected = screenshotResult();
        var adapter = adapterReturning(expected, invoked);
        var handler = adapter.handlers().get(CAPABILITY);
        assertNotNull(handler, "screenshot capture must have a native handler");
        assertEquals(expected, handler.invoke(invocation()).toCompletableFuture().join());
        assertEquals(CAPABILITY, invoked.get());
    }

    private static FabricAdapter adapterReturning(Map<String, Object> output) {
        return adapterReturning(output, new AtomicReference<>());
    }

    private static FabricAdapter adapterReturning(Map<String, Object> output, AtomicReference<String> invoked) {
        var adapter = new FabricAdapter();
        adapter.attachClientBridge(new FabricAdapter.ClientBridge() {
            @Override public boolean available(String capability) { return CAPABILITY.equals(capability); }
            @Override public java.util.concurrent.CompletionStage<Map<String, Object>> invoke(
                    String capability, InvocationContext context) {
                invoked.set(capability);
                return CompletableFuture.completedFuture(output);
            }
        });
        return adapter;
    }

    private static Map<String, Object> screenshotResult() {
        var digest = "a".repeat(64);
        var result = new LinkedHashMap<String, Object>();
        result.put("artifact", new ArtifactReference(
                "lodestone://artifacts/sha256/" + digest, "image/png", digest, 1024, 60_000).toMetadata());
        result.put("width", 1920);
        result.put("height", 1080);
        result.put("originalWidth", 2560);
        result.put("originalHeight", 1440);
        return Map.copyOf(result);
    }

    private static InvocationContext invocation() {
        var request = new RequestEnvelope(ProtocolVersion.CURRENT, "request-1", "session-1",
                CAPABILITY, "1.0", Map.of(), null, null, false);
        return new InvocationContext(request, CancellationToken.none(), Runnable::run, Map.of());
    }
}
