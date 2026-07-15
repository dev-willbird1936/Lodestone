// SPDX-License-Identifier: MIT
package dev.lodestone.goal;

import dev.lodestone.protocol.ResultEnvelope;

import java.util.Map;

@FunctionalInterface
public interface GoalInvoker {
    ResultEnvelope invoke(String capability, String capabilityVersion, Map<String, Object> input, boolean dryRun);

    /** Invokes with the remaining goal budget. Runtime-backed invokers propagate this bound. */
    default ResultEnvelope invoke(String capability, String capabilityVersion, Map<String, Object> input,
                                  boolean dryRun, long timeoutMs) {
        return invoke(capability, capabilityVersion, input, dryRun);
    }
}
