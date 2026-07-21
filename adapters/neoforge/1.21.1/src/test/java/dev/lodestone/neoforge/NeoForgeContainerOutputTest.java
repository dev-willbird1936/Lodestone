// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import dev.lodestone.protocol.CapabilityDescriptor;
import dev.lodestone.protocol.SchemaValidator;
import dev.lodestone.runtime.CoreCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Closes an audit-flagged test gap: prior to this test, neither {@code
 * minecraft.inventory.container.read} nor {@code minecraft.inventory.container.click}'s real
 * handler-shaped output was ever validated against the catalog's loaded outputSchema, unlike
 * {@code minecraft.player.interact} (see {@link NeoForgeInteractOutputTest}).
 *
 * <p>Also guards the additive {@code carried} field added to {@code
 * minecraft.inventory.container.read}'s output - the cursor stack read from {@code
 * AbstractContainerMenu#getCarried()} - staying optional (not in the catalog's {@code required}
 * list) and additionalProperties-safe, so every existing caller expecting only {@code
 * {open, containerId, revision, slots}} remains schema-valid.
 *
 * <p>{@link NeoForgeClientController#containerSlotOutput}, {@link
 * NeoForgeClientController#containerCarriedOutput}, {@link
 * NeoForgeClientController#containerReadOutput}, and {@link
 * NeoForgeClientController#containerClickOutput} are pure static helpers - like {@link
 * NeoForgeClientController#interactOutput} - so these output shapes can be exercised directly
 * without a live {@code ClientLevel}/{@code Player}.
 */
final class NeoForgeContainerOutputTest {
    @Test
    void containerReadOutputWithoutACarriedStackSatisfiesTheCatalogSchema() {
        var capability = containerReadCapability();
        var slots = List.of(
                NeoForgeClientController.containerSlotOutput(0, "minecraft:air", 0, 64, true),
                NeoForgeClientController.containerSlotOutput(1, "minecraft:diamond", 3, 64, false));
        var carried = NeoForgeClientController.containerCarriedOutput("minecraft:air", 0, true);
        var output = NeoForgeClientController.containerReadOutput(5, 12, slots, carried);

        assertEquals(Set.of("open", "containerId", "revision", "slots", "carried"), output.keySet());
        assertEquals(true, output.get("open"));
        assertEquals(5, output.get("containerId"));
        assertEquals(12, output.get("revision"));

        @SuppressWarnings("unchecked")
        var carriedOut = (Map<String, Object>) output.get("carried");
        assertEquals(Set.of("item", "count", "empty"), carriedOut.keySet(),
                "carried must mirror the slot entry shape minus slot/maxCount");
        assertEquals("minecraft:air", carriedOut.get("item"));
        assertEquals(0, carriedOut.get("count"));
        assertEquals(true, carriedOut.get("empty"));

        assertTrue(SchemaValidator.validate(capability.outputSchema(), output).isEmpty(),
                "container read output with an empty (not-carrying) cursor must satisfy the catalog's outputSchema");
    }

    @Test
    void containerReadOutputWithACarriedStackSatisfiesTheCatalogSchema() {
        var capability = containerReadCapability();
        var slots = List.of(
                NeoForgeClientController.containerSlotOutput(0, "minecraft:iron_pickaxe", 1, 1, false));
        var carried = NeoForgeClientController.containerCarriedOutput("minecraft:cobblestone", 42, false);
        var output = NeoForgeClientController.containerReadOutput(1, 3, slots, carried);

        @SuppressWarnings("unchecked")
        var carriedOut = (Map<String, Object>) output.get("carried");
        assertEquals("minecraft:cobblestone", carriedOut.get("item"));
        assertEquals(42, carriedOut.get("count"));
        assertEquals(false, carriedOut.get("empty"));

        assertTrue(SchemaValidator.validate(capability.outputSchema(), output).isEmpty(),
                "container read output with a genuinely carried stack must satisfy the catalog's outputSchema");
    }

    @Test
    void catalogDeclaresCarriedAdditivelyWithoutMakingItRequired() {
        var capability = containerReadCapability();

        @SuppressWarnings("unchecked")
        var properties = (Map<String, Object>) capability.outputSchema().get("properties");
        assertEquals(Set.of("open", "containerId", "revision", "slots", "carried"), properties.keySet(),
                "carried must be declared additively alongside the original open/containerId/revision/slots fields");
        assertEquals(List.of("open", "containerId", "revision", "slots"), capability.outputSchema().get("required"),
                "carried must stay optional so any older caller expecting only the original fields remains schema-valid");
        assertEquals(Boolean.FALSE, capability.outputSchema().get("additionalProperties"));
    }

    @Test
    void containerClickOutputSatisfiesTheCatalogSchema() {
        var capability = containerClickCapability();
        var output = NeoForgeClientController.containerClickOutput(7, 4, 0, "PICKUP");

        assertEquals(Set.of("containerId", "slot", "button", "clickType"), output.keySet());
        assertEquals(7, output.get("containerId"));
        assertEquals(4, output.get("slot"));
        assertEquals(0, output.get("button"));
        assertEquals("PICKUP", output.get("clickType"));

        assertTrue(SchemaValidator.validate(capability.outputSchema(), output).isEmpty(),
                "container click output must satisfy the catalog's outputSchema");
    }

    private static CapabilityDescriptor containerReadCapability() {
        return CoreCatalog.load().stream()
                .filter(candidate -> candidate.id().equals("minecraft.inventory.container.read"))
                .findFirst().orElseThrow();
    }

    private static CapabilityDescriptor containerClickCapability() {
        return CoreCatalog.load().stream()
                .filter(candidate -> candidate.id().equals("minecraft.inventory.container.click"))
                .findFirst().orElseThrow();
    }
}
