// SPDX-License-Identifier: MIT
package dev.lodestone.adapter;

import java.util.Map;

@FunctionalInterface
public interface EventSink {
    void publish(String event, Map<String, Object> payload, long gameTick);
}
