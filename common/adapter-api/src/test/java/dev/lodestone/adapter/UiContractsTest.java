// SPDX-License-Identifier: MIT
package dev.lodestone.adapter;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class UiContractsTest {
    private static UiNode node(String id, List<Integer> path, String label, boolean active) {
        return new UiNode(id, path, path.size() - 1, "button", "screen", label, label,
                new UiBounds(10, 20, 100, 20), false, active, true, Set.of("click"), null, null);
    }

    @Test
    void selectorRequiresExactlyOneTargetAndRejectsAmbiguousLabels() {
        assertThrows(IllegalArgumentException.class, () -> UiSelector.from(Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> UiSelector.from(Map.of("nodeId", "n0", "label", "Play")));
        var nodes = List.of(node("n0", List.of(0), "Play", true), node("n1", List.of(1), "Play", true));
        assertThrows(IllegalArgumentException.class, () -> UiSelector.from(Map.of("label", "Play")).resolve(nodes));
        assertEquals("n1", UiSelector.from(Map.of("path", List.of(1))).resolve(nodes).nodeId());
    }

    @Test
    void selectorRejectsInactiveAndOversizedPaths() {
        assertThrows(IllegalStateException.class,
                () -> UiSelector.from(Map.of("nodeId", "n0"))
                        .resolve(List.of(node("n0", List.of(0), "Disabled", false))));
        assertThrows(IllegalArgumentException.class,
                () -> UiSelector.from(Map.of("path", List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9))));
    }

    @Test
    void revisionChangesWithSemanticState() {
        var first = List.of(node("n0", List.of(0), "Play", true));
        var second = List.of(node("n0", List.of(0), "Play", false));
        var revision = UiContracts.revision("screen-a", "TitleScreen", "Minecraft", 320, 240,
                first, "complete", false);
        assertEquals(revision, UiContracts.revision("screen-a", "TitleScreen", "Minecraft", 320, 240,
                first, "complete", false));
        assertNotEquals(revision, UiContracts.revision("screen-a", "TitleScreen", "Minecraft", 320, 240,
                second, "complete", false));
    }

    @Test
    void revisionIsDelimiterSafeOrderStableAndIncludesTruncationCauses() {
        var firstActions = new LinkedHashSet<>(List.of("click", "focus"));
        var secondActions = new LinkedHashSet<>(List.of("focus", "click"));
        var first = new UiNode("n|0", List.of(0), 0, "button", "screen", "A|B", "C",
                new UiBounds(1, 2, 3, 4), false, true, true, firstActions, null, null);
        var second = new UiNode("n|0", List.of(0), 0, "button", "screen", "A|B", "C",
                new UiBounds(1, 2, 3, 4), false, true, true, secondActions, null, null);
        var left = UiContracts.revision("token", "Screen", "x\ny", 10, 20,
                List.of(first), "partial", true, List.of("node-limit", "depth-limit"));
        var right = UiContracts.revision("token", "Screen", "x\ny", 10, 20,
                List.of(second), "partial", true, List.of("depth-limit", "node-limit"));
        assertEquals(left, right);
        assertNotEquals(left, UiContracts.revision("token", "Screen", "x\ny", 10, 20,
                List.of(second), "partial", true, List.of("depth-limit")));
        assertNotEquals(
                UiContracts.revision("a", "bc", "d", 1, 2, List.of(), "complete", false),
                UiContracts.revision("ab", "c", "d", 1, 2, List.of(), "complete", false));
    }
}
