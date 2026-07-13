// SPDX-License-Identifier: MIT
package dev.lodestone.adapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Transactional batch primitive for native-world mutations. */
public final class BatchMutationRollback {
    private BatchMutationRollback() {
    }

    /**
     * Applies a prepared batch and restores every mutation already applied if cancellation or a native
     * mutation failure interrupts the batch. The mutation is recorded before the native call so a call
     * that changes state and then throws is also restored.
     */
    public static <T> int apply(CancellationToken cancellation, List<T> changes,
                                Predicate<T> shouldApply, Consumer<T> apply, Consumer<T> restore) {
        Objects.requireNonNull(cancellation, "cancellation");
        Objects.requireNonNull(changes, "changes");
        Objects.requireNonNull(shouldApply, "shouldApply");
        Objects.requireNonNull(apply, "apply");
        Objects.requireNonNull(restore, "restore");
        var applied = new ArrayList<T>();
        try {
            var changed = 0;
            for (var change : changes) {
                cancellation.throwIfCancelled();
                if (!shouldApply.test(change)) {
                    continue;
                }
                applied.add(change);
                apply.accept(change);
                changed++;
            }
            if (changed > 0) {
                cancellation.commitMutation();
            } else {
                cancellation.throwIfCancelled();
            }
            return changed;
        } catch (RuntimeException failure) {
            restore(applied, restore, failure);
            throw failure;
        }
    }

    /** Restores a previously applied batch in reverse order, preserving the original failure. */
    public static <T> void restore(List<T> applied, Consumer<T> restore, RuntimeException failure) {
        for (var index = applied.size() - 1; index >= 0; index--) {
            try {
                restore.accept(applied.get(index));
            } catch (RuntimeException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
        }
    }
}
