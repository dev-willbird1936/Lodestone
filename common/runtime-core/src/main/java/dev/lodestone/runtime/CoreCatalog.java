// SPDX-License-Identifier: MIT
package dev.lodestone.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import dev.lodestone.protocol.Availability;
import dev.lodestone.protocol.AvailabilityReason;
import dev.lodestone.protocol.CapabilityDescriptor;
import dev.lodestone.protocol.CapabilityKind;
import dev.lodestone.protocol.CapabilityPrerequisites;
import dev.lodestone.protocol.DeliveryGuarantees;
import dev.lodestone.protocol.Environment;
import dev.lodestone.protocol.Idempotency;
import dev.lodestone.protocol.JsonSupport;
import dev.lodestone.protocol.PermissionClass;
import dev.lodestone.protocol.RateLimit;
import dev.lodestone.protocol.SideEffect;
import dev.lodestone.protocol.Stability;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CoreCatalog {
    private CoreCatalog() {
    }

    public static List<CapabilityDescriptor> load() {
        try (InputStream input = CoreCatalog.class.getResourceAsStream("/core-capabilities.json");
             InputStream hardScripts = CoreCatalog.class.getResourceAsStream("/hard-scripts.json")) {
            if (input == null) {
                throw new IllegalStateException("core capability catalog is not packaged");
            }
            var root = new JsonParser().parse(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
            var capabilities = new ArrayList<CapabilityDescriptor>();
            root.getAsJsonArray("capabilities").forEach(node -> capabilities.add(read(node.getAsJsonObject())));
            if (hardScripts == null) {
                throw new IllegalStateException("hard-script capability catalog is not packaged");
            }
            var hardRoot = new JsonParser().parse(new InputStreamReader(hardScripts, StandardCharsets.UTF_8)).getAsJsonObject();
            hardRoot.getAsJsonArray("capabilities")
                    .forEach(node -> capabilities.add(read(node.getAsJsonObject())));
            activatePermissionGatedCapabilities(capabilities);
            linkUiWaitStateSchema(capabilities);
            linkUiNavigateSchemas(capabilities);
            return List.copyOf(capabilities);
        } catch (IOException failure) {
            throw new IllegalStateException("unable to read core capability catalog", failure);
        }
    }

    /**
     * Catalog entries formerly marked native-permission are local-input handlers. They are ready
     * whenever their adapter is ready; launcher and caller permission settings no longer narrow
     * them.
     */
    private static void activatePermissionGatedCapabilities(List<CapabilityDescriptor> capabilities) {
        for (var index = 0; index < capabilities.size(); index++) {
            var capability = capabilities.get(index);
            if (capability.availability() == Availability.RESTRICTED
                    && capability.reason() != null
                    && "native-permission".equals(capability.reason().code())) {
                capabilities.set(index, new CapabilityDescriptor(capability.id(), capability.kind(),
                        capability.version(), capability.stability(), Availability.AVAILABLE, null,
                        capability.adapterId(), capability.adapterVersion(), capability.gameEdition(),
                        capability.gameVersion(), capability.loader(), capability.environment(),
                        capability.inputSchema(), capability.outputSchema(), capability.eventSchema(),
                        capability.permissions(), capability.sideEffect(), capability.idempotency(),
                        capability.prerequisites(), capability.nativeThread(), capability.rateLimit(),
                        capability.timeoutMs(), capability.cancellable(), capability.delivery(),
                        capability.documentation() + " Local Lodestone control is granted automatically.",
                        capability.featureFlags()));
            }
        }
    }

    private static void linkUiWaitStateSchema(List<CapabilityDescriptor> capabilities) {
        var uiState = capabilities.stream()
                .filter(capability -> capability.id().equals("minecraft.ui.state.read")
                        && capability.version().equals("2.0"))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "catalog is missing minecraft.ui.state.read version 2.0"));
        for (var index = 0; index < capabilities.size(); index++) {
            var workflow = capabilities.get(index);
            if (!workflow.id().equals("lodestone.ui.wait")) continue;
            var outputSchema = new LinkedHashMap<String, Object>(workflow.outputSchema());
            var properties = new LinkedHashMap<String, Object>();
            if (outputSchema.get("properties") instanceof Map<?, ?> existingProperties) {
                existingProperties.forEach((key, value) -> properties.put(String.valueOf(key), value));
            }
            properties.put("state", uiState.outputSchema());
            outputSchema.put("properties", Map.copyOf(properties));
            capabilities.set(index, withOutputSchema(workflow, Map.copyOf(outputSchema)));
            return;
        }
        throw new IllegalArgumentException("catalog is missing lodestone.ui.wait");
    }

    private static void linkUiNavigateSchemas(List<CapabilityDescriptor> capabilities) {
        var uiState = capabilities.stream()
                .filter(capability -> capability.id().equals("minecraft.ui.state.read")
                        && capability.version().equals("2.0"))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "catalog is missing minecraft.ui.state.read version 2.0"));
        var uiClick = capabilities.stream()
                .filter(capability -> capability.id().equals("minecraft.ui.click")
                        && capability.version().equals("2.0"))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "catalog is missing minecraft.ui.click version 2.0"));
        for (var index = 0; index < capabilities.size(); index++) {
            var workflow = capabilities.get(index);
            if (!workflow.id().equals("lodestone.ui.navigate")) continue;
            var outputSchema = new LinkedHashMap<String, Object>(workflow.outputSchema());
            var properties = new LinkedHashMap<String, Object>();
            if (outputSchema.get("properties") instanceof Map<?, ?> existingProperties) {
                existingProperties.forEach((key, value) -> properties.put(String.valueOf(key), value));
            }
            properties.put("before", uiState.outputSchema());
            properties.put("click", uiClick.outputSchema());
            properties.put("after", uiState.outputSchema());
            outputSchema.put("properties", Map.copyOf(properties));
            capabilities.set(index, withOutputSchema(workflow, Map.copyOf(outputSchema)));
            return;
        }
        throw new IllegalArgumentException("catalog is missing lodestone.ui.navigate");
    }

    private static CapabilityDescriptor withOutputSchema(CapabilityDescriptor capability,
                                                          Map<String, Object> outputSchema) {
        return new CapabilityDescriptor(capability.id(), capability.kind(), capability.version(),
                capability.stability(), capability.availability(), capability.reason(), capability.adapterId(),
                capability.adapterVersion(), capability.gameEdition(), capability.gameVersion(), capability.loader(),
                capability.environment(), capability.inputSchema(), outputSchema, capability.eventSchema(),
                capability.permissions(), capability.sideEffect(), capability.idempotency(), capability.prerequisites(),
                capability.nativeThread(), capability.rateLimit(), capability.timeoutMs(), capability.cancellable(),
                capability.delivery(), capability.documentation(), capability.featureFlags());
    }

    private static CapabilityDescriptor read(JsonObject node) {
        var reason = node.get("reason");
        var inputSchema = map(node.get("inputSchema"));
        var outputSchema = map(node.get("outputSchema"));
        var eventSchema = map(node.get("eventSchema"));
        requireSupportedSchema(text(node, "id"), "input", inputSchema);
        requireSupportedSchema(text(node, "id"), "output", outputSchema);
        if (!eventSchema.isEmpty()) requireSupportedSchema(text(node, "id"), "event", eventSchema);
        return new CapabilityDescriptor(
                text(node, "id"), enumValue(node, "kind", CapabilityKind.class), text(node, "version"),
                enumValue(node, "stability", Stability.class), enumValue(node, "availability", Availability.class),
                reason == null || reason.isJsonNull() ? null : availabilityReason(reason.getAsJsonObject()),
                text(node, "adapterId"), text(node, "adapterVersion"), text(node, "gameEdition"),
                text(node, "gameVersion"), text(node, "loader"), enumValue(node, "environment", Environment.class),
                inputSchema, outputSchema, eventSchema,
                enumSet(node.getAsJsonArray("permissions"), PermissionClass.class), enumValue(node, "sideEffect", SideEffect.class),
                enumValue(node, "idempotency", Idempotency.class),
                prerequisites(node.getAsJsonObject("prerequisites")),
                text(node, "nativeThread"), rateLimit(node.getAsJsonObject("rateLimit")),
                node.get("timeoutMs").getAsLong(), node.get("cancellable").getAsBoolean(),
                deliveryGuarantees(node.getAsJsonObject("delivery")),
                text(node, "documentation"), Set.copyOf(JsonSupport.MAPPER.fromJson(node.get("featureFlags"),
                        TypeToken.getParameterized(List.class, String.class).getType())));
    }

    private static void requireSupportedSchema(String id, String role, Map<String, Object> schema) {
        var errors = dev.lodestone.protocol.SchemaValidator.validateSchema(schema);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("catalog capability " + id + " has unsupported " + role
                    + " schema: " + errors);
        }
    }

    private static String text(JsonObject node, String field) {
        var value = node.get(field);
        if (value == null || value.isJsonNull()) {
            throw new IllegalArgumentException("catalog capability missing " + field);
        }
        return value.getAsString();
    }

    private static <E extends Enum<E>> E enumValue(JsonObject node, String field, Class<E> type) {
        var value = text(node, field).replace('-', '_').toUpperCase(java.util.Locale.ROOT);
        return Enum.valueOf(type, value);
    }

    private static Map<String, Object> map(com.google.gson.JsonElement node) {
        return JsonSupport.MAPPER.fromJson(node, TypeToken.getParameterized(Map.class, String.class, Object.class).getType());
    }

    private static AvailabilityReason availabilityReason(JsonObject node) {
        return new AvailabilityReason(text(node, "code"), text(node, "message"), map(node.get("details")));
    }

    private static CapabilityPrerequisites prerequisites(JsonObject node) {
        return new CapabilityPrerequisites(
                node.get("requiresWorld").getAsBoolean(),
                node.get("requiresPlayer").getAsBoolean(),
                node.get("requiresScreen").getAsBoolean(),
                node.get("requiresContainer").getAsBoolean());
    }

    private static RateLimit rateLimit(JsonObject node) {
        return new RateLimit(node.get("permits").getAsInt(), node.get("windowMs").getAsLong(), node.get("burst").getAsInt());
    }

    private static DeliveryGuarantees deliveryGuarantees(JsonObject node) {
        return new DeliveryGuarantees(text(node, "ordering"), text(node, "delivery"), node.get("bufferLimit").getAsInt());
    }

    private static <E extends Enum<E>> Set<E> enumSet(JsonArray node, Class<E> type) {
        var result = new java.util.HashSet<E>();
        node.forEach(value -> result.add(Enum.valueOf(type,
                value.getAsString().replace('-', '_').toUpperCase(java.util.Locale.ROOT))));
        return Set.copyOf(result);
    }
}
