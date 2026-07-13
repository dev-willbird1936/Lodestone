// SPDX-License-Identifier: MIT
package dev.lodestone.runtime;

import dev.lodestone.protocol.Availability;
import dev.lodestone.protocol.ProtocolVersion;
import dev.lodestone.protocol.RequestEnvelope;
import dev.lodestone.protocol.ResultEnvelope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GeometryAdapterTest {
    private static final String CAPABILITY = "lodestone.geometry.calculate";

    @Test
    void runtimePublishesAnAlwaysAvailablePureGeometryCapability() {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var capability = runtime.capabilities(CAPABILITY).stream()
                    .filter(candidate -> candidate.id().equals(CAPABILITY))
                    .findFirst().orElseThrow();
            assertEquals(Availability.AVAILABLE, capability.availability());
            assertEquals("1.0", capability.version());
        }
    }

    @Test
    void circleUsesDeterministicBresenhamCoordinatesAndAsciiPreview() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var result = invoke(runtime, Map.of("shape", "circle", "radius", 2));
            assertEquals(ResultEnvelope.Status.OK, result.status(), result::toString);
            assertEquals(12, number(result.output(), "blocksCount"));
            assertEquals(2, number(result.output(), "coordinateDimensions"));
            assertEquals(true, result.output().get("bounded"));
            @SuppressWarnings("unchecked")
            var coordinates = (List<List<Number>>) result.output().get("coordinates");
            assertEquals(List.of(-2.0, -1.0), coordinates.get(0));
            assertEquals(List.of(2.0, 1.0), coordinates.get(coordinates.size() - 1));
            assertTrue(result.output().get("asciiPreview").toString().contains("+"));
        }
    }

    @Test
    void filledSphereAndArchExposeStructuredThreeDimensionalCoordinates() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var sphere = invoke(runtime, Map.of("shape", "sphere", "radius", 1, "hollow", false));
            assertEquals(ResultEnvelope.Status.OK, sphere.status(), sphere::toString);
            assertEquals(7, number(sphere.output(), "blocksCount"));
            assertEquals(3, number(sphere.output(), "coordinateDimensions"));
            assertEquals("//sphere <block> 1", sphere.output().get("worldEditCommand"));

            var arch = invoke(runtime, Map.of("shape", "arch", "width", 6, "height", 4, "depth", 2));
            assertEquals(ResultEnvelope.Status.OK, arch.status(), arch::toString);
            assertEquals(3, number(arch.output(), "coordinateDimensions"));
            assertTrue(number(arch.output(), "blocksCount") > 0);
        }
    }

    @Test
    void oddThreeQuarterDomeMatchesPythonFloorDivisionAndDegenerateEllipseIsDefined() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var dome = invoke(runtime, Map.of("shape", "dome", "radius", 1,
                    "style", "three_quarter"));
            assertEquals(ResultEnvelope.Status.OK, dome.status(), dome::toString);
            assertEquals(18, number(dome.output(), "blocksCount"));
            @SuppressWarnings("unchecked")
            var domeCoordinates = (List<List<Number>>) dome.output().get("coordinates");
            assertTrue(domeCoordinates.contains(List.of(-1.0, -1.0, 0.0)));

            var ellipse = invoke(runtime, Map.of("shape", "ellipse", "width", 1,
                    "height", 3, "filled", true));
            assertEquals(ResultEnvelope.Status.OK, ellipse.status(), ellipse::toString);
            assertEquals(3, number(ellipse.output(), "blocksCount"));
            @SuppressWarnings("unchecked")
            var ellipseCoordinates = (List<List<Number>>) ellipse.output().get("coordinates");
            assertEquals(List.of(List.of(0.0, -1.0), List.of(0.0, 0.0), List.of(0.0, 1.0)),
                    ellipseCoordinates);
        }
    }

    @Test
    void shapeSpecificRequirementsAndResourceCapsFailAtTheSchemaBoundary() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var missingRadius = invoke(runtime, Map.of("shape", "circle"));
            assertEquals(ResultEnvelope.Status.ERROR, missingRadius.status());
            assertEquals("INVALID_INPUT", missingRadius.error().code());
            assertFalse(missingRadius.error().retryable());

            var oversized = invoke(runtime, Map.of("shape", "sphere", "radius", 100, "hollow", false));
            assertEquals(ResultEnvelope.Status.ERROR, oversized.status());
            assertEquals("INVALID_INPUT", oversized.error().code());
            assertFalse(oversized.error().retryable());

            var widthOneArch = invoke(runtime, Map.of("shape", "arch", "width", 1, "height", 4));
            assertEquals(ResultEnvelope.Status.ERROR, widthOneArch.status());
            assertEquals("INVALID_INPUT", widthOneArch.error().code());
        }
    }

    @Test
    void cancellationInterruptsTheLargestAdmittedCalculation() {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var request = new RequestEnvelope(ProtocolVersion.CURRENT,
                    "geometry-cancel-" + java.util.UUID.randomUUID(), runtime.sessionId(), CAPABILITY, "1.0",
                    Map.of("shape", "sphere", "radius", 25, "hollow", false), null, null, false);
            var invocation = runtime.invoke(request);
            assertTrue(invocation.cancel(false));
            assertTrue(invocation.isCancelled());
        }
    }

    private static ResultEnvelope invoke(LodestoneRuntime runtime, Map<String, Object> input) throws Exception {
        var request = new RequestEnvelope(ProtocolVersion.CURRENT, "geometry-" + java.util.UUID.randomUUID(),
                runtime.sessionId(), CAPABILITY, "1.0", input, null, null, false);
        return runtime.invoke(request).get(2, TimeUnit.SECONDS);
    }

    private static int number(Map<String, Object> output, String key) {
        return ((Number) output.get(key)).intValue();
    }
}
