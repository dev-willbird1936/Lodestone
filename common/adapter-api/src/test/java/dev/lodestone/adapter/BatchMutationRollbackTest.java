// SPDX-License-Identifier: MIT
package dev.lodestone.adapter;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BatchMutationRollbackTest {
    @Test
    void cancellationRestoresEveryMutationAlreadyAppliedInReverseOrder() {
        var values = new ArrayList<>(List.of("old-0", "old-1", "old-2"));
        var cancelled = new AtomicBoolean();

        assertThrows(CancellationToken.CancellationException.class, () ->
                BatchMutationRollback.apply(cancelled::get, List.of(0, 1, 2),
                        ignored -> true,
                        index -> {
                            values.set(index, "new-" + index);
                            if (index == 1) cancelled.set(true);
                        },
                        index -> values.set(index, "old-" + index)));

        assertEquals(List.of("old-0", "old-1", "old-2"), values);
    }

    @Test
    void cancellationAfterFinalNativeMutationStillRestoresTheWholeBatch() {
        var values = new ArrayList<>(List.of("old-0", "old-1", "old-2"));
        var cancelled = new AtomicBoolean();

        assertThrows(CancellationToken.CancellationException.class, () ->
                BatchMutationRollback.apply(cancelled::get, List.of(0, 1, 2),
                        ignored -> true,
                        index -> {
                            values.set(index, "new-" + index);
                            if (index == 2) cancelled.set(true);
                        },
                        index -> values.set(index, "old-" + index)));

        assertEquals(List.of("old-0", "old-1", "old-2"), values);
    }

    @Test
    void mutationFailureIsPreservedAndRollbackFailuresAreSuppressed() {
        var values = new ArrayList<>(List.of("old-0", "old-1"));
        var failure = assertThrows(IllegalStateException.class, () ->
                BatchMutationRollback.apply(CancellationToken.none(), List.of(0, 1),
                        ignored -> true,
                        index -> {
                            values.set(index, "new-" + index);
                            if (index == 1) throw new IllegalStateException("native failure");
                        },
                        index -> {
                            if (index == 1) throw new IllegalStateException("rollback failure");
                            values.set(index, "old-" + index);
                        }));

        assertEquals("native failure", failure.getMessage());
        assertEquals(1, failure.getSuppressed().length);
        assertEquals("rollback failure", failure.getSuppressed()[0].getMessage());
        assertEquals("old-0", values.get(0));
    }
}
