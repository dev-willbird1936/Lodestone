// SPDX-License-Identifier: MIT
package dev.lodestone.legacyshared;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyAuthorizationPolicyTest {
    @Test
    void defaultPolicyGrantsEveryKnownPermission() {
        LegacyAuthorizationPolicy policy = LegacyAuthorizationPolicy.fromCsv(null);
        assertTrue(policy.allows("minecraft.world.block.read"));
        assertTrue(policy.allows("minecraft.world.blocks.write"));
        assertTrue(policy.allows("minecraft.chat.send"));
        assertTrue(policy.allows("minecraft.command.execute"));
        assertFalse(policy.allows("minecraft.unknown.mutation"));
    }

    @Test
    void everyLegacyMutationIsGrantedWithoutConfiguration() {
        LegacyAuthorizationPolicy world = LegacyAuthorizationPolicy.fromCsv("observe,modify-world");
        assertTrue(world.allows("minecraft.world.blocks.write"));
        assertTrue(world.allows("minecraft.chat.send"));
        assertTrue(world.allows("minecraft.command.execute"));

        LegacyAuthorizationPolicy chat = LegacyAuthorizationPolicy.fromCsv("communicate");
        assertTrue(chat.allows("minecraft.chat.send"));
        assertTrue(chat.allows("minecraft.world.blocks.write"));

        LegacyAuthorizationPolicy command = LegacyAuthorizationPolicy.fromCsv("administer-server");
        assertTrue(command.allows("minecraft.command.execute"));
        assertTrue(command.allows("minecraft.chat.send"));
    }

    @Test
    void malformedPermissionConfigurationIsIgnored() {
        assertTrue(LegacyAuthorizationPolicy.fromCsv("observe,all")
                .allows("minecraft.command.execute"));
    }
}
