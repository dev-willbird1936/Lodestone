// SPDX-License-Identifier: MIT
package dev.lodestone.runtime;

import com.google.gson.JsonParser;
import dev.lodestone.protocol.ProtocolVersion;
import dev.lodestone.protocol.RequestEnvelope;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AuditResourceIsolationTest {
    @Test
    void remoteAuditReadsUseExactCallerTraceDespiteRequestIdCollision() {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var callerA = "caller-a";
            var callerB = "caller-b";
            var sharedRequestId = "shared-request-id";

            runtime.invoke(request(runtime, sharedRequestId,
                    "lodestone.system.health", Map.of()), callerA).join();
            runtime.invoke(request(runtime, sharedRequestId,
                    "lodestone.system.capabilities.list", Map.of()), callerB).join();

            var auditA = runtime.readResource("lodestone://audit", callerA);
            assertTrue(auditA.contains(sharedRequestId));
            assertTrue(auditA.contains("lodestone.system.health"));
            assertFalse(auditA.contains("lodestone.system.capabilities.list"));

            var traceA = runtime.readResource("lodestone://audit/trace", callerA);
            assertTrue(traceA.contains("\"callerSessionId\":\"caller-a\""));
            assertFalse(traceA.contains("caller-b"));
            assertTrue(traceA.contains("lodestone.system.health"));
            assertFalse(traceA.contains("lodestone.system.capabilities.list"));

            var trustedTrace = runtime.readResource("lodestone://audit/trace");
            assertTrue(trustedTrace.contains("caller-a"));
            assertTrue(trustedTrace.contains("caller-b"));
            assertThrows(IllegalArgumentException.class,
                    () -> runtime.readResource("lodestone://audit", " "));

            var parsed = JsonParser.parseString(auditA).getAsJsonObject();
            assertEquals(1, parsed.getAsJsonArray("records").size());
        }
    }

    private static RequestEnvelope request(LodestoneRuntime runtime, String requestId,
                                           String capability, Map<String, Object> input) {
        return new RequestEnvelope(ProtocolVersion.CURRENT, requestId, runtime.sessionId(), capability,
                "1.0", input, null, null, false);
    }
}
