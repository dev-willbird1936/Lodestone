// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import dev.lodestone.adapter.InvocationContext;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Client-thread contract for a native goal that can pause at safe phase boundaries. */
interface NeoForgeResumableGoal {
    boolean done();

    boolean paused();

    String continuationToken();

    void resume(InvocationContext invocation, CompletableFuture<Map<String, Object>> result);
}
