// SPDX-License-Identifier: MIT
package dev.lodestone.legacyshared;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LegacyBatchMutationRollbackTest {
    @Test
    void nativeFailureRestoresEarlierAndFailingChangesInReverseOrder() {
        final ArrayList<String> values = new ArrayList<String>(Arrays.asList("old-0", "old-1", "old-2"));
        assertThrows(IllegalStateException.class, () -> LegacyBatchMutationRollback.apply(
                Arrays.asList(0, 1, 2), ignored -> true,
                index -> {
                    values.set(index, "new-" + index);
                    if (index == 1) throw new IllegalStateException("native failure");
                },
                index -> values.set(index, "old-" + index),
                () -> { }));
        assertEquals(Arrays.asList("old-0", "old-1", "old-2"), values);
    }

    @Test
    void expiredFinalCommitRestoresEveryChange() {
        final ArrayList<String> values = new ArrayList<String>(Arrays.asList("old-0", "old-1"));
        assertThrows(IllegalStateException.class, () -> LegacyBatchMutationRollback.apply(
                Arrays.asList(0, 1), ignored -> true,
                index -> values.set(index, "new-" + index),
                index -> values.set(index, "old-" + index),
                () -> { throw new IllegalStateException("deadline expired"); }));
        assertEquals(Arrays.asList("old-0", "old-1"), values);
    }
}
