// SPDX-License-Identifier: MIT
package dev.lodestone.legacyshared;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Java 8-compatible transactional batch primitive for legacy native hosts. */
public final class LegacyBatchMutationRollback {
    private LegacyBatchMutationRollback() {
    }

    public static <T> int apply(List<T> changes, Predicate<T> shouldApply, Consumer<T> apply,
                                Consumer<T> restore, Runnable commitVerifier) {
        Objects.requireNonNull(changes, "changes");
        Objects.requireNonNull(shouldApply, "shouldApply");
        Objects.requireNonNull(apply, "apply");
        Objects.requireNonNull(restore, "restore");
        Objects.requireNonNull(commitVerifier, "commitVerifier");
        List<T> applied = new ArrayList<T>();
        try {
            int changed = 0;
            for (T change : changes) {
                if (!shouldApply.test(change)) continue;
                applied.add(change);
                apply.accept(change);
                changed++;
            }
            commitVerifier.run();
            return changed;
        } catch (RuntimeException failure) {
            restore(applied, restore, failure);
            throw failure;
        }
    }

    public static <T> void restore(List<T> applied, Consumer<T> restore, RuntimeException failure) {
        for (int index = applied.size() - 1; index >= 0; index--) {
            try {
                restore.accept(applied.get(index));
            } catch (RuntimeException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
        }
    }
}
