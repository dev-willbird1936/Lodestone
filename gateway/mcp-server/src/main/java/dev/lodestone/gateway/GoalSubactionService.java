// SPDX-License-Identifier: MIT
package dev.lodestone.gateway;

import dev.lodestone.protocol.ProtocolVersion;
import dev.lodestone.protocol.RequestEnvelope;
import dev.lodestone.protocol.ResultEnvelope;
import dev.lodestone.runtime.AuthorizationPolicy;
import dev.lodestone.runtime.LodestoneRuntime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Executes one model-planned script segment through the same verified capability boundary as MCP. */
final class GoalSubactionService {
    static final int MAX_ACTIONS = 64;
    static final long DEFAULT_DURATION_MS = 120_000L;
    static final long MAX_DURATION_MS = 600_000L;

    private final LodestoneRuntime runtime;

    GoalSubactionService(LodestoneRuntime runtime) {
        this.runtime = runtime;
    }

    Map<String, Object> execute(List<Subaction> actions, long maxDurationMs, boolean stopOnError,
                                String callerSessionId, AuthorizationPolicy authorization) {
        if (actions == null || actions.isEmpty()) {
            throw new IllegalArgumentException("actions must contain at least one subaction");
        }
        if (actions.size() > MAX_ACTIONS) {
            throw new IllegalArgumentException("actions must contain at most " + MAX_ACTIONS + " subactions");
        }
        if (maxDurationMs < 100L || maxDurationMs > MAX_DURATION_MS) {
            throw new IllegalArgumentException("maxDurationMs must be between 100 and " + MAX_DURATION_MS);
        }
        actions.forEach(GoalSubactionService::validate);

        var startedNanos = System.nanoTime();
        var deadlineEpochMs = System.currentTimeMillis() + maxDurationMs;
        var results = new ArrayList<Map<String, Object>>();
        String stoppedReason = null;
        var executed = 0;

        for (var index = 0; index < actions.size(); index++) {
            if (System.currentTimeMillis() >= deadlineEpochMs) {
                stoppedReason = "batch-duration-exhausted-before-action-" + (index + 1);
                break;
            }
            var action = actions.get(index);
            var requestId = UUID.randomUUID().toString();
            var request = new RequestEnvelope(ProtocolVersion.CURRENT, requestId, runtime.sessionId(),
                    action.capability(), action.capabilityVersion(), action.input(), deadlineEpochMs,
                    action.idempotencyKey() == null ? null : callerSessionId + ":batch:" + action.idempotencyKey(),
                    action.dryRun());
            var result = runtime.invoke(request, callerSessionId, authorization).join();
            executed++;
            var row = new LinkedHashMap<String, Object>();
            row.put("index", index);
            row.put("capability", action.capability());
            row.put("result", result);
            results.add(Map.copyOf(row));
            if (stopOnError && result.status() != ResultEnvelope.Status.OK) {
                stoppedReason = "subaction-" + (index + 1) + "-returned-" + result.status();
                break;
            }
        }

        var output = new LinkedHashMap<String, Object>();
        output.put("requested", actions.size());
        output.put("executed", executed);
        output.put("complete", executed == actions.size() && stoppedReason == null);
        output.put("stoppedReason", stoppedReason == null ? "none" : stoppedReason);
        output.put("elapsedMs", (System.nanoTime() - startedNanos) / 1_000_000L);
        output.put("results", List.copyOf(results));
        return Map.copyOf(output);
    }

    private static void validate(Subaction action) {
        if (action == null || action.capability() == null || action.capability().isBlank()) {
            throw new IllegalArgumentException("every subaction requires a capability");
        }
        GoalCapabilityPolicy.requireModelPrimitive(action.capability());
        if (action.capability().startsWith("minecraft.event.")) {
            throw new IllegalArgumentException("event capabilities must use the session-owned MCP event tools");
        }
    }

    record Subaction(String capability, String capabilityVersion, Map<String, Object> input,
                     String idempotencyKey, boolean dryRun) {
        Subaction {
            input = input == null ? Map.of() : Map.copyOf(input);
        }
    }
}
