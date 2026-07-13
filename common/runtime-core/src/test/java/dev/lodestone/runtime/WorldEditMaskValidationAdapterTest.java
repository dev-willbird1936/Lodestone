// SPDX-License-Identifier: MIT
package dev.lodestone.runtime;

import dev.lodestone.protocol.Availability;
import dev.lodestone.protocol.CapabilityKind;
import dev.lodestone.protocol.PermissionClass;
import dev.lodestone.protocol.ProtocolVersion;
import dev.lodestone.protocol.RequestEnvelope;
import dev.lodestone.protocol.ResultEnvelope;
import dev.lodestone.protocol.SideEffect;
import dev.lodestone.protocol.Stability;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WorldEditMaskValidationAdapterTest {
    private static final String CAPABILITY = "lodestone.worldedit.mask.validate";

    @Test
    void runtimePublishesAnExperimentalPureLocalOnlyContract() {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var capability = runtime.capabilities(CAPABILITY).stream()
                    .filter(candidate -> candidate.id().equals(CAPABILITY))
                    .findFirst().orElseThrow();
            assertEquals(Availability.AVAILABLE, capability.availability());
            assertEquals(Stability.EXPERIMENTAL, capability.stability());
            assertEquals(CapabilityKind.QUERY, capability.kind());
            assertEquals(Set.of(PermissionClass.OBSERVE), capability.permissions());
            assertEquals(SideEffect.NONE, capability.sideEffect());
            assertFalse(capability.prerequisites().requiresWorld());
            assertFalse(capability.prerequisites().requiresPlayer());
            assertTrue(capability.featureFlags().contains("server-validation-required"));
        }
    }

    @Test
    void everyPinnedWorldEditBuiltInAndAliasIsStructurallyRecognized() throws Exception {
        var masks = List.of("#existing", "#solid", "#fullcube", "#air",
                "#region", "#sel", "#selection", "#dregion", "#dsel", "#dselection",
                "#clipboard", "#surface", "#exposed");
        for (var mask : masks) {
            var result = invokeFresh(Map.of("mask", mask));
            assertEquals(ResultEnvelope.Status.OK, result.status(), () -> mask + ": " + result);
            assertEquals(true, result.output().get("valid"), mask);
            assertEquals("recognized-grammar", result.output().get("verification"), mask);
            assertEquals(mask, result.output().get("normalizedMask"), mask);
            assertTrue(list(result, "recognizedKinds").contains("builtin"), mask);
        }
    }

    @Test
    void prefixesIntersectionsListsStatesTagsAndBiomesAreBoundedAndHonest() throws Exception {
        var raw = "  !dirt,stone[axis=y]   <#air ##minecraft:wool $minecraft:plains "
                + "^[waterlogged=false] ^=[axis=y]  ";
        var result = invokeFresh(Map.of("mask", raw));
        assertEquals(ResultEnvelope.Status.OK, result.status(), result::toString);
        assertEquals(true, result.output().get("locallyAccepted"));
        assertEquals("recognized-grammar", result.output().get("verification"));
        assertEquals("!dirt,stone[axis=y] <#air ##minecraft:wool $minecraft:plains "
                + "^[waterlogged=false] ^=[axis=y]", result.output().get("normalizedMask"));
        assertEquals(false, result.output().get("semanticValidationPerformed"));
        assertEquals(true, result.output().get("serverValidationRequired"));
        assertEquals(true, result.output().get("bounded"));
        assertTrue(list(result, "recognizedKinds").containsAll(List.of(
                "intersection", "negation", "offset-below", "block-list", "block-state",
                "builtin", "tag", "biome", "state-mask", "exact-state-mask")));
        assertEquals(List.of("REGISTRY_NOT_VALIDATED"), diagnosticCodes(result, "warnings"));
    }

    @Test
    void pluginMasksAndSafeExpressionEnvelopeRemainLexicalOnly() throws Exception {
        var plugin = invokeFresh(Map.of("mask", "#custom_mask"));
        assertEquals(ResultEnvelope.Status.OK, plugin.status());
        assertEquals(true, plugin.output().get("valid"));
        assertEquals("lexical-only", plugin.output().get("verification"));
        assertEquals(List.of("PLUGIN_MASK_REQUIRES_SERVER"), diagnosticCodes(plugin, "warnings"));

        var expression = invokeFresh(Map.of("mask", "=sqrt(x*x+z*z)<max(10, 20)"));
        assertEquals(ResultEnvelope.Status.OK, expression.status(), expression::toString);
        assertEquals(true, expression.output().get("valid"));
        assertEquals("lexical-only", expression.output().get("verification"));
        assertEquals(List.of("EXPRESSION_NOT_EVALUATED"), diagnosticCodes(expression, "warnings"));
        assertTrue(list(expression, "recognizedKinds").contains("expression"));
    }

    @Test
    void malformedOrUnsafeContentReturnsSuccessfulStructuredInvalidResults() throws Exception {
        var invalidMasks = List.of(
                "   ", "stone\n", "\"stone\"", "stöne", "stone[", "stone,,dirt",
                "stone[a=1,a=2]", "%101", "%1.5", "%+1", "!", "#custom[arg]",
                "=x=1", "=while(x)", "=x++", "=x;1", "=random()", "=x<<1",
                "//replace stone dirt");
        for (var mask : invalidMasks) {
            var result = invokeFresh(Map.of("mask", mask));
            assertEquals(ResultEnvelope.Status.OK, result.status(), () -> mask + ": " + result);
            assertEquals(false, result.output().get("valid"), mask);
            assertEquals(false, result.output().get("locallyAccepted"), mask);
            assertEquals("invalid", result.output().get("verification"), mask);
            assertFalse(result.output().containsKey("normalizedMask"), mask);
            assertFalse(diagnosticCodes(result, "errors").isEmpty(), mask);
        }
    }

    @Test
    void schemaViolationsStayProtocolErrorsWhileContentFailuresDoNot() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            for (var input : List.<Map<String, Object>>of(Map.of(), Map.of("mask", ""),
                    Map.of("mask", "a".repeat(4097)), Map.of("mask", 42))) {
                var result = invoke(runtime, input);
                assertEquals(ResultEnvelope.Status.ERROR, result.status(), result::toString);
                assertEquals("INVALID_INPUT", result.error().code());
                assertFalse(result.error().retryable());
            }
        }

        var spaces = invokeFresh(Map.of("mask", "    "));
        assertEquals(ResultEnvelope.Status.OK, spaces.status());
        assertEquals(false, spaces.output().get("valid"));
    }

    @Test
    void hardComplexityBoundsAndMaximumInputNeverEscapeAsAdapterFailures() throws Exception {
        var maximumIdentifier = invokeFresh(Map.of("mask", "a".repeat(4096)));
        assertEquals(ResultEnvelope.Status.OK, maximumIdentifier.status(), maximumIdentifier::toString);
        assertEquals(true, maximumIdentifier.output().get("valid"));

        var tooManyTerms = invokeFresh(Map.of("mask", "a ".repeat(256) + "a"));
        assertStructuredInvalid(tooManyTerms, "TOO_MANY_TERMS");

        var tooManyPrefixes = invokeFresh(Map.of("mask", "!".repeat(65) + "stone"));
        assertStructuredInvalid(tooManyPrefixes, "TOO_MANY_PREFIXES");

        var expression = new StringBuilder("=");
        for (var index = 0; index < 256; index++) expression.append("x+");
        expression.append('x');
        assertStructuredInvalid(invokeFresh(Map.of("mask", expression.toString())), "EXPRESSION_TOO_COMPLEX");

        var deep = "=" + "(".repeat(33) + "x" + ")".repeat(33);
        assertStructuredInvalid(invokeFresh(Map.of("mask", deep)), "COMPLEXITY_LIMIT");
    }

    @Test
    void normalizationIsIdempotentAndFeatureOrderingIsStable() throws Exception {
        var first = invokeFresh(Map.of("mask", "  !stone   <#air  "));
        assertEquals(ResultEnvelope.Status.OK, first.status());
        var normalized = String.valueOf(first.output().get("normalizedMask"));
        var second = invokeFresh(Map.of("mask", normalized));
        assertEquals(normalized, second.output().get("normalizedMask"));
        assertEquals(first.output().get("recognizedKinds"), second.output().get("recognizedKinds"));
        assertEquals(first.output().get("features"), second.output().get("features"));
        assertNull(first.error());
    }

    private static void assertStructuredInvalid(ResultEnvelope result, String expectedCode) {
        assertEquals(ResultEnvelope.Status.OK, result.status(), result::toString);
        assertEquals(false, result.output().get("valid"));
        assertTrue(diagnosticCodes(result, "errors").contains(expectedCode), result::toString);
    }

    private static ResultEnvelope invokeFresh(Map<String, Object> input) throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            return invoke(runtime, input);
        }
    }

    private static ResultEnvelope invoke(LodestoneRuntime runtime, Map<String, Object> input) throws Exception {
        var request = new RequestEnvelope(ProtocolVersion.CURRENT, "mask-" + UUID.randomUUID(),
                runtime.sessionId(), CAPABILITY, "1.0", input, null, null, false);
        return runtime.invoke(request).get(2, TimeUnit.SECONDS);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(ResultEnvelope result, String key) {
        return (List<Object>) result.output().get(key);
    }

    @SuppressWarnings("unchecked")
    private static List<String> diagnosticCodes(ResultEnvelope result, String key) {
        return ((List<Map<String, Object>>) result.output().get(key)).stream()
                .map(diagnostic -> String.valueOf(diagnostic.get("code")))
                .toList();
    }
}
