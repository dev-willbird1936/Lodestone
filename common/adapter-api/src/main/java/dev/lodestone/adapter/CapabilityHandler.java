// SPDX-License-Identifier: MIT
package dev.lodestone.adapter;

import java.util.Map;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface CapabilityHandler {
    CompletionStage<Map<String, Object>> invoke(InvocationContext context);
}
