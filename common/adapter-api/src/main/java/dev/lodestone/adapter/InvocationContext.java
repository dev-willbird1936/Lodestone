// SPDX-License-Identifier: MIT
package dev.lodestone.adapter;

import dev.lodestone.protocol.RequestEnvelope;

import java.util.Map;
import java.util.concurrent.Executor;

public record InvocationContext(RequestEnvelope request, CancellationToken cancellation,
                                Executor gameExecutor, Map<String, Object> attributes) {
    public InvocationContext {
        if (request == null || cancellation == null || gameExecutor == null) {
            throw new IllegalArgumentException("invocation context dependencies are required");
        }
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
