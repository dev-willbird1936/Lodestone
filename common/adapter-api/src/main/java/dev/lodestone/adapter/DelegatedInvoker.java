// SPDX-License-Identifier: MIT
package dev.lodestone.adapter;

import dev.lodestone.protocol.ResultEnvelope;

import java.util.Map;
import java.util.concurrent.CompletionStage;

/**
 * Invokes one runtime capability as a child step of the current capability invocation.
 * Implementations preserve the caller, deadline, authorization, rate limiting, audit trail,
 * cancellation, and irreversible-mutation boundary of the parent invocation.
 * A step ID identifies one logical occurrence. Repeated non-idempotent work and polling iterations
 * use deterministic monotonic IDs such as {@code poll.0}, {@code poll.1}; timestamps and random IDs
 * make safe parent retries impossible.
 */
@FunctionalInterface
public interface DelegatedInvoker {
    CompletionStage<ResultEnvelope> invoke(String stepId, String capability,
                                           String capabilityVersion, Map<String, Object> input);
}
