// SPDX-License-Identifier: MIT
package dev.lodestone.adapter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Comparator;

/** Deterministic snapshot revision helpers shared by exact-version client adapters. */
public final class UiContracts {
    private UiContracts() {
    }

    public static String revision(String screenToken, String screenClass, String title,
                                  int width, int height, List<UiNode> nodes,
                                  String coverage, boolean truncated) {
        return revision(screenToken, screenClass, title, width, height, nodes, coverage, truncated, List.of());
    }

    public static String revision(String screenToken, String screenClass, String title,
                                  int width, int height, List<UiNode> nodes,
                                  String coverage, boolean truncated,
                                  List<String> truncationCauses) {
        var canonical = new StringBuilder(4096);
        field(canonical, screenToken);
        field(canonical, screenClass);
        field(canonical, title);
        field(canonical, width);
        field(canonical, height);
        field(canonical, coverage);
        field(canonical, truncated);
        var causes = truncationCauses == null ? List.<String>of()
                : truncationCauses.stream().sorted().toList();
        field(canonical, causes.size());
        causes.forEach(cause -> field(canonical, cause));
        field(canonical, nodes.size());
        for (var node : nodes) {
            field(canonical, node.nodeId());
            field(canonical, node.path().size());
            node.path().forEach(index -> field(canonical, index));
            field(canonical, node.depth());
            field(canonical, node.type());
            field(canonical, node.parentType());
            field(canonical, node.label());
            field(canonical, node.narration());
            if (node.bounds() == null) {
                field(canonical, null);
            } else {
                field(canonical, node.bounds().x());
                field(canonical, node.bounds().y());
                field(canonical, node.bounds().width());
                field(canonical, node.bounds().height());
            }
            field(canonical, node.focused());
            field(canonical, node.active());
            field(canonical, node.visible());
            var actions = node.actions().stream().sorted(Comparator.naturalOrder()).toList();
            field(canonical, actions.size());
            actions.forEach(action -> field(canonical, action));
            field(canonical, node.textLength());
            field(canonical, node.textPresent());
        }
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }

    private static void field(StringBuilder target, Object value) {
        if (value == null) {
            target.append("-1:");
            return;
        }
        var text = String.valueOf(value);
        target.append(text.length()).append(':').append(text);
    }
}
