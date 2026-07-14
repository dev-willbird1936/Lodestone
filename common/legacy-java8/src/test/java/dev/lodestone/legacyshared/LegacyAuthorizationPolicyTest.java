// SPDX-License-Identifier: MIT
package dev.lodestone.legacyshared;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyAuthorizationPolicyTest {
    @Test
    void defaultPolicyIsObserveOnlyAndNeverGrantsMutations() {
        LegacyAuthorizationPolicy policy = LegacyAuthorizationPolicy.fromCsv(null);
        assertTrue(policy.allows("minecraft.world.block.read"));
        assertFalse(policy.allows("minecraft.world.blocks.write"));
        assertFalse(policy.allows("minecraft.chat.send"));
        assertFalse(policy.allows("minecraft.command.execute"));
        assertFalse(policy.allows("minecraft.unknown.mutation"));
    }

    @Test
    void everyLegacyMutationNeedsItsOwnExplicitPermission() {
        LegacyAuthorizationPolicy world = LegacyAuthorizationPolicy.fromCsv("observe,modify-world");
        assertTrue(world.allows("minecraft.world.blocks.write"));
        assertFalse(world.allows("minecraft.chat.send"));
        assertFalse(world.allows("minecraft.command.execute"));

        LegacyAuthorizationPolicy chat = LegacyAuthorizationPolicy.fromCsv("communicate");
        assertTrue(chat.allows("minecraft.chat.send"));
        assertFalse(chat.allows("minecraft.world.blocks.write"));

        LegacyAuthorizationPolicy command = LegacyAuthorizationPolicy.fromCsv("administer-server");
        assertTrue(command.allows("minecraft.command.execute"));
        assertFalse(command.allows("minecraft.chat.send"));
    }

    @Test
    void malformedPermissionConfigurationFailsClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> LegacyAuthorizationPolicy.fromCsv("observe,all"));
    }
}
