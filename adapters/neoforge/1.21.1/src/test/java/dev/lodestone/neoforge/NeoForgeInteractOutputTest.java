// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import dev.lodestone.protocol.SchemaValidator;
import dev.lodestone.runtime.CoreCatalog;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for a live-caught death loop: {@code minecraft.player.interact}'s output used
 * to unconditionally include an "intelligence" field (an echo of an internal tool-prerequisite
 * guard hint) that is declared on several goal capabilities' output schemas
 * (e.g. minecraft.goal.survival.wooden-axe-tree) but not on interact's. Because interact's
 * catalog outputSchema only ever declared {@code action}/{@code queued} with
 * {@code additionalProperties:false}, every interact call - including every attack used while
 * mining a tree - failed output schema validation. Since interact always commits its mutation
 * before returning, this surfaced as a repeating CAPABILITY_QUARANTINED ->
 * minecraft.session.reconcile -> retry -> fail-again loop that burned most of a live run's turn
 * budget (see verification/evidence/goal-orchestrator-milestone1/trace-dccbf274aa43.jsonl, turns
 * 19/22/26/29).
 *
 * <p>Also guards a second, related live-caught bug: an "attack" that fell back to a momentary
 * click (no real multi-tick {@link NeoForgeAttackHold} ever engaged) used to return the exact
 * same {@code {"action":"attack","queued":true}} as a genuine block-breaking hold, so a model had
 * no way to tell a real attack from a no-op swing without polling world state - see
 * verification/evidence/adhoc-goal11-mine-tree-run9/, where all 6 attack calls in a 60-turn run
 * resolved in ~10ms and the hold never once engaged. {@code held}/{@code targetKind}/
 * {@code target} are additive-only (optional, not in the schema's {@code required} list) so every
 * existing caller and every "use"/"pick" output - which still return the original
 * {@code {action, queued}} shape - stays schema-valid.
 *
 * <p>{@link NeoForgeClientController#interactOutput} cannot itself trigger the "intelligence" leak
 * via a different code path per call (it always builds a fresh immutable map; there is no shared
 * mutable state to leak across invocations), so calling each overload with different arguments is
 * sufficient to prove the fix is not argument-dependent, while still exercising the exact
 * production methods {@link NeoForgeClientController} and {@link NeoForgeAttackHold} call.
 */
final class NeoForgeInteractOutputTest {
    @Test
    void plainInteractOutputOnlyEverContainsTheCatalogsOriginalFields() {
        var capability = interactCapability();

        var attackOutput = NeoForgeClientController.interactOutput("attack");
        var useOutput = NeoForgeClientController.interactOutput("use");

        assertEquals(Set.of("action", "queued"), attackOutput.keySet(),
                "the plain interactOutput(action) overload used by use/pick must never carry "
                        + "fields the catalog does not declare, such as the leaked 'intelligence' hint");
        assertEquals(Set.of("action", "queued"), useOutput.keySet());

        assertTrue(SchemaValidator.validate(capability.outputSchema(), attackOutput).isEmpty(),
                "attack output must satisfy minecraft.player.interact's declared outputSchema");
        assertTrue(SchemaValidator.validate(capability.outputSchema(), useOutput).isEmpty(),
                "use output must satisfy minecraft.player.interact's declared outputSchema");
    }

    @Test
    void momentaryAttackOutputReportsHeldFalseWithTheResolvedTargetKind() {
        var capability = interactCapability();

        var entityOutput = NeoForgeClientController.interactOutput("attack", false, "entity");
        var noneOutput = NeoForgeClientController.interactOutput("attack", false, "none");

        assertEquals(Set.of("action", "queued", "held", "targetKind"), entityOutput.keySet(),
                "an attack that resolved against an entity must report held=false and no target "
                        + "block position, since no hold ever starts for an entity");
        assertEquals(Boolean.FALSE, entityOutput.get("held"));
        assertEquals("entity", entityOutput.get("targetKind"));
        assertEquals(Set.of("action", "queued", "held", "targetKind"), noneOutput.keySet());
        assertEquals("none", noneOutput.get("targetKind"));

        assertTrue(SchemaValidator.validate(capability.outputSchema(), entityOutput).isEmpty(),
                "entity-target attack output must satisfy the catalog's outputSchema");
        assertTrue(SchemaValidator.validate(capability.outputSchema(), noneOutput).isEmpty(),
                "no-target attack output must satisfy the catalog's outputSchema");
    }

    @Test
    void blockAttackOutputReportsTheTargetPositionAndBlockIdRegardlessOfWhetherAHoldEngaged() {
        var capability = interactCapability();
        var target = new BlockPos(10, 64, -3);

        var momentaryOutput = NeoForgeClientController.interactOutput("attack", false, target, "minecraft:short_grass");
        var heldOutput = NeoForgeClientController.interactOutput("attack", true, target, "minecraft:oak_log");

        assertEquals(Set.of("action", "queued", "held", "targetKind", "target"), momentaryOutput.keySet(),
                "a block target - held or not - must report its position and block id");
        assertEquals(Boolean.FALSE, momentaryOutput.get("held"));
        assertEquals(Boolean.TRUE, heldOutput.get("held"));
        assertEquals("block", momentaryOutput.get("targetKind"));
        assertEquals("block", heldOutput.get("targetKind"));

        @SuppressWarnings("unchecked")
        var momentaryTarget = (java.util.Map<String, Object>) momentaryOutput.get("target");
        assertEquals(10, momentaryTarget.get("x"));
        assertEquals(64, momentaryTarget.get("y"));
        assertEquals(-3, momentaryTarget.get("z"));
        assertEquals("minecraft:short_grass", momentaryTarget.get("block"));

        assertTrue(SchemaValidator.validate(capability.outputSchema(), momentaryOutput).isEmpty(),
                "a momentary click that happened to hit a block must still satisfy the outputSchema");
        assertTrue(SchemaValidator.validate(capability.outputSchema(), heldOutput).isEmpty(),
                "a genuine held-attack break must satisfy the outputSchema");
    }

    @Test
    void catalogDeclaresInteractOutputFieldsWithOnlyActionAndQueuedRequired() {
        var capability = interactCapability();

        @SuppressWarnings("unchecked")
        var properties = (java.util.Map<String, Object>) capability.outputSchema().get("properties");
        assertEquals(Set.of("action", "queued", "held", "targetKind", "target"), properties.keySet(),
                "held/targetKind/target must be declared additively alongside the original "
                        + "action/queued fields, not replacing them");
        assertEquals(java.util.List.of("action", "queued"), capability.outputSchema().get("required"),
                "held/targetKind/target must stay optional so use/pick output - and any older "
                        + "caller expecting only {action, queued} - remains schema-valid");
        assertEquals(Boolean.FALSE, capability.outputSchema().get("additionalProperties"));

        // "intelligence" is a legitimate output field on several goal capabilities but was never
        // declared for interact - confirms the field genuinely does not belong on this
        // capability's output rather than the catalog simply being out of date.
        assertTrue(SchemaValidator.validate(capability.outputSchema(),
                java.util.Map.of("action", "attack", "queued", true, "intelligence", "guarded-v1"))
                .stream().anyMatch(violation -> violation.contains("intelligence")));
    }

    private static dev.lodestone.protocol.CapabilityDescriptor interactCapability() {
        return CoreCatalog.load().stream()
                .filter(candidate -> candidate.id().equals("minecraft.player.interact"))
                .findFirst().orElseThrow();
    }
}
