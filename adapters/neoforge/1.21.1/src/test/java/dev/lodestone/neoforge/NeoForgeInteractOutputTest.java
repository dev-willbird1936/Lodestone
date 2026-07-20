// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import dev.lodestone.protocol.SchemaValidator;
import dev.lodestone.runtime.CoreCatalog;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for a live-caught death loop: {@code minecraft.player.interact}'s output used
 * to unconditionally include an "intelligence" field (an echo of an internal tool-prerequisite
 * guard hint) that is declared on several goal capabilities' output schemas
 * (e.g. minecraft.goal.survival.wooden-axe-tree) but not on interact's. Because interact's
 * catalog outputSchema is exactly {@code {action, queued}} with {@code additionalProperties:false},
 * every interact call - including every attack used while mining a tree - failed output schema
 * validation. Since interact always commits its mutation before returning, this surfaced as a
 * repeating CAPABILITY_QUARANTINED -> minecraft.session.reconcile -> retry -> fail-again loop
 * that burned most of a live run's turn budget (see
 * verification/evidence/goal-orchestrator-milestone1/trace-dccbf274aa43.jsonl, turns 19/22/26/29).
 *
 * <p>{@link NeoForgeClientController#interactOutput} cannot itself trigger this bug via a
 * different code path per call (it always builds a fresh {@code Map.of(...)}; there is no shared
 * mutable state to leak across invocations), so calling it twice with different actions is
 * sufficient to prove the fix is not action-dependent, while still exercising the exact
 * production method that used to leak the field.
 */
final class NeoForgeInteractOutputTest {
    @Test
    void interactOutputOnlyEverContainsTheCatalogsDeclaredFields() {
        var capability = CoreCatalog.load().stream()
                .filter(candidate -> candidate.id().equals("minecraft.player.interact"))
                .findFirst().orElseThrow();

        var attackOutput = NeoForgeClientController.interactOutput("attack");
        var useOutput = NeoForgeClientController.interactOutput("use");

        assertEquals(Set.of("action", "queued"), attackOutput.keySet(),
                "interact output must never carry fields the catalog does not declare, "
                        + "such as the leaked 'intelligence' hint");
        assertEquals(Set.of("action", "queued"), useOutput.keySet());

        assertTrue(SchemaValidator.validate(capability.outputSchema(), attackOutput).isEmpty(),
                "attack output must satisfy minecraft.player.interact's declared outputSchema");
        assertTrue(SchemaValidator.validate(capability.outputSchema(), useOutput).isEmpty(),
                "use output must satisfy minecraft.player.interact's declared outputSchema");
    }

    @Test
    void catalogDeclaresInteractOutputAsExactlyActionAndQueuedWithNoAdditionalProperties() {
        var capability = CoreCatalog.load().stream()
                .filter(candidate -> candidate.id().equals("minecraft.player.interact"))
                .findFirst().orElseThrow();

        @SuppressWarnings("unchecked")
        var properties = (java.util.Map<String, Object>) capability.outputSchema().get("properties");
        assertEquals(Set.of("action", "queued"), properties.keySet());
        assertEquals(Boolean.FALSE, capability.outputSchema().get("additionalProperties"));

        // "intelligence" is a legitimate output field on several goal capabilities but was never
        // declared for interact - confirms the field genuinely does not belong on this
        // capability's output rather than the catalog simply being out of date.
        assertTrue(SchemaValidator.validate(capability.outputSchema(),
                java.util.Map.of("action", "attack", "queued", true, "intelligence", "guarded-v1"))
                .stream().anyMatch(violation -> violation.contains("intelligence")));
    }
}
