// SPDX-License-Identifier: MIT
package dev.lodestone.adapter;

import java.util.List;

/** Stable keys supplied by the runtime in {@link InvocationContext#attributes()}. */
public final class InvocationAttributes {
    public static final String CALLER_SESSION_ID = "lodestone.callerSessionId";
    public static final String DELEGATED_INVOKER = "lodestone.delegatedInvoker";
    public static final String DELEGATION_PATH = "lodestone.delegationPath";

    private InvocationAttributes() {
    }

    public static DelegatedInvoker requireDelegatedInvoker(InvocationContext context) {
        var value = context.attributes().get(DELEGATED_INVOKER);
        if (value instanceof DelegatedInvoker invoker) return invoker;
        throw new IllegalStateException("runtime did not supply a delegated invoker");
    }

    @SuppressWarnings("unchecked")
    public static List<String> delegationPath(InvocationContext context) {
        var value = context.attributes().get(DELEGATION_PATH);
        return value instanceof List<?> path ? (List<String>) path : List.of();
    }
}
