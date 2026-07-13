// SPDX-License-Identifier: MIT
package dev.lodestone.adapter;

public interface CancellationToken {
    boolean isCancelled();

    default void throwIfCancelled() {
        if (isCancelled()) {
            throw new CancellationException();
        }
    }

    /**
     * Atomically crosses the cancellation boundary for a mutation. Reversible batches call this
     * after their final write and roll back if cancellation won. Irreversible operations call it
     * immediately before dispatch. Runtime-backed tokens then report a post-commit deadline as an
     * indeterminate outcome instead of falsely claiming that no side effect occurred.
     */
    default void commitMutation() {
        throwIfCancelled();
    }

    static CancellationToken none() {
        return () -> false;
    }

    final class CancellationException extends RuntimeException {
        public CancellationException() {
            super("capability invocation cancelled");
        }
    }
}
