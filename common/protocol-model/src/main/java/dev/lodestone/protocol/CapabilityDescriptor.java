// SPDX-License-Identifier: MIT
package dev.lodestone.protocol;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public record CapabilityDescriptor(
        String id,
        CapabilityKind kind,
        String version,
        Stability stability,
        Availability availability,
        AvailabilityReason reason,
        String adapterId,
        String adapterVersion,
        String gameEdition,
        String gameVersion,
        String loader,
        Environment environment,
        Map<String, Object> inputSchema,
        Map<String, Object> outputSchema,
        Map<String, Object> eventSchema,
        Set<PermissionClass> permissions,
        SideEffect sideEffect,
        Idempotency idempotency,
        CapabilityPrerequisites prerequisites,
        String nativeThread,
        RateLimit rateLimit,
        long timeoutMs,
        boolean cancellable,
        DeliveryGuarantees delivery,
        String documentation,
        Set<String> featureFlags) {
    private static final Pattern ID = Pattern.compile("^[a-z][a-z0-9]*(\\.[a-z0-9][a-z0-9-]*)+$");

    public CapabilityDescriptor {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("invalid capability id: " + id);
        }
        Objects.requireNonNull(kind, "kind");
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
        Objects.requireNonNull(stability, "stability");
        Objects.requireNonNull(availability, "availability");
        if (availability == Availability.AVAILABLE && reason != null) {
            throw new IllegalArgumentException("available capability must not have an unavailable reason");
        }
        if (availability != Availability.AVAILABLE && reason == null) {
            throw new IllegalArgumentException("non-available capability requires a structured reason");
        }
        requireText(adapterId, "adapterId");
        requireText(adapterVersion, "adapterVersion");
        requireText(gameEdition, "gameEdition");
        requireText(gameVersion, "gameVersion");
        requireText(loader, "loader");
        Objects.requireNonNull(environment, "environment");
        inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
        outputSchema = outputSchema == null ? Map.of() : Map.copyOf(outputSchema);
        eventSchema = eventSchema == null ? Map.of() : Map.copyOf(eventSchema);
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
        Objects.requireNonNull(sideEffect, "sideEffect");
        if (permissions.isEmpty()) {
            throw new IllegalArgumentException("capability permissions must not be empty");
        }
        if (sideEffect != SideEffect.NONE
                && !permissions.contains(PermissionClass.valueOf(sideEffect.name()))) {
            throw new IllegalArgumentException("capability side effect " + sideEffect
                    + " requires permission " + PermissionClass.valueOf(sideEffect.name()));
        }
        Objects.requireNonNull(idempotency, "idempotency");
        Objects.requireNonNull(prerequisites, "prerequisites");
        requireText(nativeThread, "nativeThread");
        Objects.requireNonNull(rateLimit, "rateLimit");
        if (timeoutMs < 1) {
            throw new IllegalArgumentException("timeoutMs must be positive");
        }
        Objects.requireNonNull(delivery, "delivery");
        requireText(documentation, "documentation");
        featureFlags = featureFlags == null ? Set.of() : Set.copyOf(featureFlags);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    public boolean isMutation() {
        return sideEffect != SideEffect.NONE;
    }

    public boolean allows(PermissionClass permission) {
        return permissions.contains(permission);
    }

    /** Create an adapter-local contract variant without changing identity or authorization. */
    public CapabilityDescriptor withInputSchema(Map<String, Object> nextInputSchema) {
        return new CapabilityDescriptor(id, kind, version, stability, availability, reason,
                adapterId, adapterVersion, gameEdition, gameVersion, loader, environment,
                nextInputSchema, outputSchema, eventSchema, permissions, sideEffect, idempotency,
                prerequisites, nativeThread, rateLimit, timeoutMs, cancellable, delivery,
                documentation, featureFlags);
    }

    public CapabilityDescriptor forAdapter(AdapterDescriptor adapter, Availability nextAvailability,
                                           AvailabilityReason nextReason) {
        return new CapabilityDescriptor(id, kind, version, stability, nextAvailability, nextReason,
                adapter.id(), adapter.version(), adapter.gameEdition(), adapter.gameVersion(), adapter.loader(),
                adapter.environment(), inputSchema, outputSchema, eventSchema, permissions, sideEffect, idempotency,
                prerequisites, nativeThread, rateLimit, timeoutMs, cancellable, delivery, documentation, featureFlags);
    }

}
