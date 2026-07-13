// SPDX-License-Identifier: MIT
package dev.lodestone.adapter;

import dev.lodestone.protocol.ResultEnvelope;

/** Raised when a delegated child capability returns any non-OK terminal result. */
public final class DelegatedInvocationException extends RuntimeException {
    private final ResultEnvelope result;

    public DelegatedInvocationException(ResultEnvelope result) {
        super(message(result));
        if (result == null || result.status() == ResultEnvelope.Status.OK) {
            throw new IllegalArgumentException("a delegated failure result is required");
        }
        this.result = result;
    }

    public ResultEnvelope result() {
        return result;
    }

    private static String message(ResultEnvelope result) {
        if (result == null) return "delegated capability failed without a result";
        if (result.error() == null) return "delegated capability ended with status " + result.status();
        return "delegated capability failed [" + result.error().code() + "]: " + result.error().message();
    }
}
