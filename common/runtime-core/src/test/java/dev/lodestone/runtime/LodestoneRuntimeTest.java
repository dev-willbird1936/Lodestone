// SPDX-License-Identifier: MIT
package dev.lodestone.runtime;

import dev.lodestone.adapter.CapabilityHandler;
import dev.lodestone.adapter.AdapterHealth;
import dev.lodestone.adapter.InvocationAttributes;
import dev.lodestone.adapter.LodestoneAdapter;
import dev.lodestone.protocol.AdapterDescriptor;
import dev.lodestone.protocol.Availability;
import dev.lodestone.protocol.AvailabilityReason;
import dev.lodestone.protocol.CapabilityDescriptor;
import dev.lodestone.protocol.CapabilityKind;
import dev.lodestone.protocol.CapabilityManifest;
import dev.lodestone.protocol.CapabilityPrerequisites;
import dev.lodestone.protocol.DeliveryGuarantees;
import dev.lodestone.protocol.Environment;
import dev.lodestone.protocol.Idempotency;
import dev.lodestone.protocol.PermissionClass;
import dev.lodestone.protocol.RateLimit;
import dev.lodestone.protocol.RequestEnvelope;
import dev.lodestone.protocol.ResultEnvelope;
import dev.lodestone.protocol.SideEffect;
import dev.lodestone.protocol.Stability;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LodestoneRuntimeTest {
    @Test
    void exposesTheFullCatalogBeforeANativeAdapterExists() {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            assertTrue(runtime.capabilities(null).size() >= 25);
            var worldRead = runtime.capabilities("minecraft.world.block.read").get(0);
            assertEquals(Availability.UNAVAILABLE, worldRead.availability());
            assertEquals("no-native-adapter", worldRead.reason().code());
            assertTrue(runtime.capabilities(null).stream()
                    .filter(capability -> capability.id().startsWith("minecraft."))
                    .filter(capability -> !capability.id().startsWith("minecraft.event."))
                    // minecraft.session.reconcile is a SystemAdapter-level capability like
                    // minecraft.event.* (runtime bookkeeping, not native-adapter-dependent) - it
                    // is deliberately AVAILABLE with no native adapter, so it must clear the
                    // recovery quarantine even before/without one ever attaching.
                    .filter(capability -> !capability.id().equals("minecraft.session.reconcile"))
                    .allMatch(capability -> capability.availability() == Availability.UNAVAILABLE
                            && capability.reason() != null
                            && "no-native-adapter".equals(capability.reason().code())));
            assertEquals("no-adapter", runtime.health().state());
        }
    }

    @Test
    void rejectsRestrictedCapabilitiesWithoutHandlers() {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.fromCsv("control-player"))) {
            var descriptor = capability("test.handlerless-restricted", Availability.RESTRICTED,
                    Set.of(PermissionClass.CONTROL_PLAYER), SideEffect.CONTROL_PLAYER,
                    new AvailabilityReason("permission-required", "control-player permission is required", Map.of()));
            var adapterDescriptor = new AdapterDescriptor("test.handlerless-adapter", "1.0.0",
                    "minecraft", "test", "test", Environment.REMOTE);
            var adapter = new LodestoneAdapter() {
                @Override
                public AdapterDescriptor descriptor() {
                    return adapterDescriptor;
                }

                @Override
                public CapabilityManifest manifest() {
                    return new CapabilityManifest(adapterDescriptor, java.util.List.of(descriptor));
                }

                @Override
                public Map<String, CapabilityHandler> handlers() {
                    return Map.of();
                }
            };

            var failure = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> runtime.registerAdapter(adapter));
            assertTrue(failure.getMessage().contains("invocable capability has no handler"));
        }
    }

    @Test
    void exposesCapabilityGetAndSearchAsWorkingSystemOperations() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            assertEquals(Availability.AVAILABLE,
                    runtime.capabilities("lodestone.system.capabilities.get").get(0).availability());
            var get = runtime.invoke(request(runtime, "lodestone.system.capabilities.get",
                    Map.of("id", "minecraft.command.discover"))).get(1, TimeUnit.SECONDS);
            assertEquals(ResultEnvelope.Status.OK, get.status());
            assertEquals(true, get.output().get("found"));

            var search = runtime.invoke(request(runtime, "lodestone.system.capabilities.search",
                    Map.of("query", "inventory"))).get(1, TimeUnit.SECONDS);
            assertEquals(ResultEnvelope.Status.OK, search.status());
            assertTrue(((java.util.List<?>) search.output().get("capabilities")).size() >= 1);
        }
    }

    @Test
    void permissionsNeverBlockAMutationWhenAHandlerIsPresent() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var descriptor = capability("test.mutation", Availability.AVAILABLE, Set.of(PermissionClass.MODIFY_WORLD),
                    SideEffect.MODIFY_WORLD, null);
            runtime.registerAdapter(adapter(descriptor, context -> CompletableFuture.completedFuture(Map.of("changed", true))));
            var result = runtime.invoke(request(runtime, descriptor.id(), Map.of())).get(1, TimeUnit.SECONDS);
            assertEquals(ResultEnvelope.Status.OK, result.status());
            assertEquals(true, result.output().get("changed"));
        }
    }

    @Test
    void deadlineCancelsTheInvocation() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var descriptor = capability("test.never-completes", Availability.AVAILABLE,
                    Set.of(PermissionClass.OBSERVE), SideEffect.NONE, null);
            runtime.registerAdapter(adapter(descriptor, context -> new CompletableFuture<>()));
            var result = runtime.invoke(new RequestEnvelope("1.0", "deadline", runtime.sessionId(),
                    descriptor.id(), "1.0", Map.of(), System.currentTimeMillis() + 80, null, false))
                    .get(1, TimeUnit.SECONDS);
            assertEquals(ResultEnvelope.Status.TIMED_OUT, result.status());
            assertEquals("DEADLINE_EXCEEDED", result.error().code());
        }
    }

    @Test
    void handlerReceivesTheRuntimeDefaultDeadline() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var descriptor = capability("test.default-deadline", Availability.AVAILABLE,
                    Set.of(PermissionClass.OBSERVE), SideEffect.NONE, null);
            var observed = new java.util.concurrent.atomic.AtomicLong();
            runtime.registerAdapter(adapter(descriptor, context -> {
                observed.set(context.request().deadlineEpochMs());
                return CompletableFuture.completedFuture(Map.of());
            }));
            var before = System.currentTimeMillis();

            var result = runtime.invoke(request(runtime, descriptor.id(), Map.of())).get(1, TimeUnit.SECONDS);

            assertEquals(ResultEnvelope.Status.OK, result.status());
            assertTrue(observed.get() >= before);
            assertTrue(observed.get() <= before + descriptor.timeoutMs() + 1_000);
        }
    }

    @Test
    void committedMutationDeadlineReportsIndeterminateOutcomeInsteadOfFalseTimeout() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.fromCsv("modify-world"))) {
            var descriptor = capability("test.committed-mutation", Availability.AVAILABLE,
                    Set.of(PermissionClass.MODIFY_WORLD), SideEffect.MODIFY_WORLD, null);
            var committed = new java.util.concurrent.CountDownLatch(1);
            var nativeCompletion = new CompletableFuture<Map<String, Object>>();
            runtime.registerAdapter(adapter(descriptor, context -> {
                context.cancellation().commitMutation();
                committed.countDown();
                return nativeCompletion;
            }));

            var result = runtime.invoke(new RequestEnvelope("1.0", "committed", runtime.sessionId(),
                    descriptor.id(), "1.0", Map.of(), System.currentTimeMillis() + 2_000, null, false));
            assertTrue(committed.await(5, TimeUnit.SECONDS));
            var terminal = result.get(5, TimeUnit.SECONDS);

            assertEquals(ResultEnvelope.Status.ERROR, terminal.status());
            assertEquals("OUTCOME_INDETERMINATE", terminal.error().code());
            assertEquals(false, terminal.error().retryable());
            assertEquals(true, terminal.error().details().get("mutationCommitted"));
            nativeCompletion.complete(Map.of("committed", true));
        }
    }

    @Test
    void failureAfterIrreversibleCommitReportsIndeterminateOutcome() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.fromCsv("modify-world"))) {
            var descriptor = capability("test.committed-failure", Availability.AVAILABLE,
                    Set.of(PermissionClass.MODIFY_WORLD), SideEffect.MODIFY_WORLD, null);
            runtime.registerAdapter(adapter(descriptor, context -> {
                context.cancellation().commitMutation();
                throw new IllegalStateException("native acknowledgement was lost");
            }));

            var result = runtime.invoke(request(runtime, descriptor.id(), Map.of())).get(1, TimeUnit.SECONDS);

            assertEquals(ResultEnvelope.Status.ERROR, result.status());
            assertEquals("OUTCOME_INDETERMINATE", result.error().code());
            assertEquals(false, result.error().retryable());

            var retry = runtime.invoke(new RequestEnvelope("1.0", "committed-failure-retry", runtime.sessionId(),
                            descriptor.id(), "1.0", Map.of(), null, null, false))
                    .get(1, TimeUnit.SECONDS);
            assertEquals("CAPABILITY_QUARANTINED", retry.error().code());
            assertFalse(retry.error().retryable());
        }
    }

    @Test
    void invalidMutationAcknowledgementIsIndeterminateAndQuarantined() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.fromCsv("modify-world"))) {
            var descriptor = capability("minecraft.test.invalid-mutation-output", Availability.AVAILABLE,
                    Set.of(PermissionClass.MODIFY_WORLD), SideEffect.MODIFY_WORLD, null,
                    Map.of("type", "object"),
                    Map.of("type", "object", "properties", Map.of("changed", Map.of("type", "boolean")),
                            "required", List.of("changed"), "additionalProperties", false),
                    new RateLimit(100, 1000, 100));
            runtime.registerAdapter(adapter(descriptor, context -> {
                context.cancellation().commitMutation();
                return CompletableFuture.completedFuture(Map.of("unexpected", true));
            }));

            var result = runtime.invoke(request(runtime, descriptor.id(), Map.of()), "caller-a")
                    .get(1, TimeUnit.SECONDS);
            var retry = runtime.invoke(new RequestEnvelope("1.0", "invalid-output-retry", runtime.sessionId(),
                            descriptor.id(), "1.0", Map.of(), null, null, false), "caller-b")
                    .get(1, TimeUnit.SECONDS);

            assertEquals("OUTCOME_INDETERMINATE", result.error().code());
            assertEquals(true, result.error().details().get("mutationCommitted"));
            assertEquals("CAPABILITY_QUARANTINED", retry.error().code());
        }
    }

    @Test
    void queuedMutationCannotPassAnIndeterminatePredecessor() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.fromCsv("modify-world"))) {
            var descriptor = capability("test.queued-after-commit", Availability.AVAILABLE,
                    Set.of(PermissionClass.MODIFY_WORLD), SideEffect.MODIFY_WORLD, null,
                    Map.of("type", "object"), Map.of("type", "object"), new RateLimit(100, 1000, 100));
            var firstCompletion = new CompletableFuture<Map<String, Object>>();
            var calls = new AtomicInteger();
            runtime.registerAdapter(adapter(descriptor, context -> {
                if (calls.incrementAndGet() == 1) {
                    context.cancellation().commitMutation();
                    return firstCompletion;
                }
                return CompletableFuture.completedFuture(Map.of("unsafe", true));
            }));

            var first = runtime.invoke(new RequestEnvelope("1.0", "queued-first", runtime.sessionId(),
                    descriptor.id(), "1.0", Map.of(), null, null, false));
            var second = runtime.invoke(new RequestEnvelope("1.0", "queued-second", runtime.sessionId(),
                    descriptor.id(), "1.0", Map.of(), null, null, false));
            firstCompletion.completeExceptionally(new IllegalStateException("acknowledgement lost"));

            assertEquals("OUTCOME_INDETERMINATE", first.get(1, TimeUnit.SECONDS).error().code());
            assertEquals("CAPABILITY_QUARANTINED", second.get(1, TimeUnit.SECONDS).error().code());
            assertEquals(1, calls.get());
        }
    }

    @Test
    void timedOutQueuedMutationDoesNotReleaseItsSuccessorBeforeThePredecessorTerminates() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.fromCsv("modify-world"))) {
            var descriptor = capability("minecraft.test.queued-timeout-order", Availability.AVAILABLE,
                    Set.of(PermissionClass.MODIFY_WORLD), SideEffect.MODIFY_WORLD, null,
                    Map.of("type", "object"), Map.of("type", "object"), new RateLimit(100, 1000, 100));
            var firstCompletion = new CompletableFuture<Map<String, Object>>();
            var firstStarted = new CompletableFuture<Void>();
            var thirdStarted = new CompletableFuture<Void>();
            var calls = new AtomicInteger();
            runtime.registerAdapter(adapter(descriptor, context -> {
                var call = calls.incrementAndGet();
                if (call == 1) {
                    firstStarted.complete(null);
                    return firstCompletion;
                }
                if (call == 2) {
                    thirdStarted.complete(null);
                }
                return CompletableFuture.completedFuture(Map.of());
            }));

            var first = runtime.invoke(new RequestEnvelope("1.0", "queue-a", runtime.sessionId(),
                    descriptor.id(), "1.0", Map.of(), null, null, false));
            firstStarted.get(1, TimeUnit.SECONDS);
            var second = runtime.invoke(new RequestEnvelope("1.0", "queue-b", runtime.sessionId(),
                    descriptor.id(), "1.0", Map.of(), System.currentTimeMillis() + 50, null, false));

            assertEquals("DEADLINE_EXCEEDED", second.get(1, TimeUnit.SECONDS).error().code());
            var third = runtime.invoke(new RequestEnvelope("1.0", "queue-c", runtime.sessionId(),
                    descriptor.id(), "1.0", Map.of(), null, null, false));
            assertThrows(java.util.concurrent.TimeoutException.class,
                    () -> thirdStarted.get(200, TimeUnit.MILLISECONDS));
            assertEquals(1, calls.get());

            firstCompletion.complete(Map.of());
            assertEquals(ResultEnvelope.Status.OK, first.get(1, TimeUnit.SECONDS).status());
            assertEquals(ResultEnvelope.Status.OK, third.get(1, TimeUnit.SECONDS).status());
            assertEquals(2, calls.get());
        }
    }

    @Test
    void reportsNoWorldUntilTheNativeAdapterHasAWorld() {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var descriptor = capability("test.no-world", Availability.AVAILABLE,
                    Set.of(PermissionClass.OBSERVE), SideEffect.NONE, null);
            runtime.registerAdapter(new LodestoneAdapter() {
                @Override
                public AdapterDescriptor descriptor() {
                    return new AdapterDescriptor("test.no-world-adapter", "1.0.0", "minecraft", "test", "test", Environment.REMOTE);
                }

                @Override
                public CapabilityManifest manifest() {
                    return new CapabilityManifest(descriptor(), java.util.List.of(descriptor));
                }

                @Override
                public Map<String, CapabilityHandler> handlers() {
                    return Map.of(descriptor.id(), context -> CompletableFuture.completedFuture(Map.of()));
                }

                @Override
                public AdapterHealth health() {
                    return new AdapterHealth(AdapterHealth.State.NO_WORLD, "world is not loaded", Instant.now());
                }
            });
            assertEquals("no-world", runtime.health().state());
        }
    }

    @Test
    void startsAnAdapterBeforeCapturingItsManifest() {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var capabilityDescriptor = capability("test.lifecycle", Availability.DEGRADED,
                    Set.of(PermissionClass.OBSERVE), SideEffect.NONE,
                    new AvailabilityReason("not-started", "adapter has not started", Map.of()));
            var adapterDescriptor = new AdapterDescriptor("test.lifecycle-adapter", "1.0.0", "minecraft", "test", "test", Environment.REMOTE);
            var adapter = new LodestoneAdapter() {
                private boolean started;

                @Override
                public AdapterDescriptor descriptor() {
                    return adapterDescriptor;
                }

                @Override
                public void start(dev.lodestone.adapter.AdapterContext context) {
                    started = true;
                }

                @Override
                public CapabilityManifest manifest() {
                    var effective = started ? capabilityDescriptor.forAdapter(adapterDescriptor, Availability.AVAILABLE, null) : capabilityDescriptor;
                    return new CapabilityManifest(descriptor(), java.util.List.of(effective));
                }

                @Override
                public Map<String, CapabilityHandler> handlers() {
                    return Map.of(capabilityDescriptor.id(), context -> CompletableFuture.completedFuture(Map.of()));
                }
            };
            runtime.registerAdapter(adapter);
            assertEquals(Availability.AVAILABLE,
                    runtime.capabilities("test.lifecycle").get(0).availability());
        }
    }

    @Test
    void refreshesAnAdapterAtomicallyWithoutSelfCollisions() {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var descriptor = capability("test.refresh", Availability.AVAILABLE,
                    Set.of(PermissionClass.OBSERVE), SideEffect.NONE, null);
            var adapter = adapter(descriptor, context -> CompletableFuture.completedFuture(Map.of()));
            runtime.registerAdapter(adapter);
            runtime.refreshAdapter(adapter);
            assertEquals(Availability.AVAILABLE,
                    runtime.capabilities("test.refresh").get(0).availability());
        }
    }

    @Test
    void timedOutLateCompletionDoesNotCreateAFalseSuccessAudit() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var descriptor = capability("test.late-completion", Availability.AVAILABLE,
                    Set.of(PermissionClass.OBSERVE), SideEffect.NONE, null);
            var completion = new CompletableFuture<Map<String, Object>>();
            runtime.registerAdapter(adapter(descriptor, context -> completion));
            var result = runtime.invoke(new RequestEnvelope("1.0", "late", runtime.sessionId(),
                    descriptor.id(), "1.0", Map.of(), System.currentTimeMillis() + 50, null, false))
                    .get(1, TimeUnit.SECONDS);
            assertEquals(ResultEnvelope.Status.TIMED_OUT, result.status());
            completion.complete(Map.of("late", true));
            Thread.sleep(50);
            assertEquals(1, runtime.audit().size());
            assertEquals("timed-out", runtime.audit().get(0).outcome());
        }
    }

    @Test
    void timedOutInvocationQuarantinesItsCapabilityWithoutPoisoningTheCaller() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var descriptor = capability("test.quarantine", Availability.AVAILABLE,
                    Set.of(PermissionClass.OBSERVE), SideEffect.NONE, null,
                    Map.of("type", "object"), Map.of("type", "object"), new RateLimit(100, 1000, 100));
            var completion = new CompletableFuture<Map<String, Object>>();
            var started = new java.util.concurrent.CountDownLatch(1);
            runtime.registerAdapter(adapter(descriptor, context -> {
                started.countDown();
                return completion;
            }));

            var firstInvocation = runtime.invoke(new RequestEnvelope("1.0", "quarantine-first", runtime.sessionId(),
                    descriptor.id(), "1.0", Map.of(), System.currentTimeMillis() + 2_000, null, false));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            var first = firstInvocation.get(5, TimeUnit.SECONDS);
            assertEquals(ResultEnvelope.Status.TIMED_OUT, first.status());

            var second = runtime.invoke(new RequestEnvelope("1.0", "quarantine-second", runtime.sessionId(),
                    descriptor.id(), "1.0", Map.of(), System.currentTimeMillis() + 1_000, null, false))
                    .get(1, TimeUnit.SECONDS);
            assertEquals("CAPABILITY_QUARANTINED", second.error().code());

            completion.complete(Map.of("late", true));

            ResultEnvelope third;
            var clearDeadline = System.currentTimeMillis() + 1_000;
            var attempt = 0;
            do {
                third = runtime.invoke(new RequestEnvelope("1.0", "quarantine-third-" + attempt++, runtime.sessionId(),
                        descriptor.id(), "1.0", Map.of(), System.currentTimeMillis() + 1_000, null, false))
                        .get(1, TimeUnit.SECONDS);
                if (third.status() != ResultEnvelope.Status.OK) Thread.sleep(10);
            } while (third.status() != ResultEnvelope.Status.OK && System.currentTimeMillis() < clearDeadline);
            assertEquals(ResultEnvelope.Status.OK, third.status());
        }
    }

    @Test
    void closeCompletesCommittedInvocationAsIndeterminate() throws Exception {
        var runtime = new LodestoneRuntime(AuthorizationPolicy.fromCsv("modify-world"));
        try {
            var descriptor = capability("test.close-committed", Availability.AVAILABLE,
                    Set.of(PermissionClass.MODIFY_WORLD), SideEffect.MODIFY_WORLD, null);
            var started = new java.util.concurrent.CountDownLatch(1);
            runtime.registerAdapter(adapter(descriptor, context -> {
                context.cancellation().commitMutation();
                started.countDown();
                return new CompletableFuture<>();
            }));

            var result = runtime.invoke(new RequestEnvelope("1.0", "close-committed", runtime.sessionId(),
                    descriptor.id(), "1.0", Map.of(), System.currentTimeMillis() + 60_000, null, false));
            assertTrue(started.await(1, TimeUnit.SECONDS));
            runtime.close();

            var terminal = result.get(1, TimeUnit.SECONDS);
            assertEquals(ResultEnvelope.Status.ERROR, terminal.status());
            assertEquals("OUTCOME_INDETERMINATE", terminal.error().code());
            assertFalse(terminal.error().retryable());
        } finally {
            runtime.close();
        }
    }

    @Test
    void transportCallerKeysIsolateRateLimits() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var descriptor = capability("test.caller-rate-limit", Availability.AVAILABLE,
                    Set.of(PermissionClass.OBSERVE), SideEffect.NONE, null,
                    Map.of("type", "object"), Map.of("type", "object"), new RateLimit(1, 60_000, 1));
            runtime.registerAdapter(adapter(descriptor, context -> CompletableFuture.completedFuture(Map.of())));

            var first = runtime.invoke(request(runtime, descriptor.id(), Map.of()), "caller-a")
                    .get(1, TimeUnit.SECONDS);
            var second = runtime.invoke(request(runtime, descriptor.id(), Map.of()), "caller-b")
                    .get(1, TimeUnit.SECONDS);

            assertEquals(ResultEnvelope.Status.OK, first.status());
            assertEquals(ResultEnvelope.Status.OK, second.status());
        }
    }

    @Test
    void hungReadForOneCallerDoesNotBlockAnotherCaller() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var descriptor = capability("minecraft.test.caller-scoped-read-order", Availability.AVAILABLE,
                    Set.of(PermissionClass.OBSERVE), SideEffect.NONE, null,
                    Map.of("type", "object"), Map.of("type", "object"),
                    new RateLimit(100, 1000, 100));
            var firstStarted = new CompletableFuture<Void>();
            var firstCompletion = new CompletableFuture<Map<String, Object>>();
            runtime.registerAdapter(adapter(descriptor, context -> {
                var caller = context.attributes().get(InvocationAttributes.CALLER_SESSION_ID);
                if ("caller-a".equals(caller)) {
                    firstStarted.complete(null);
                    return firstCompletion;
                }
                return CompletableFuture.completedFuture(Map.of("caller", caller));
            }));

            var first = runtime.invoke(request(runtime, descriptor.id(), Map.of()), "caller-a");
            firstStarted.get(1, TimeUnit.SECONDS);
            var second = runtime.invoke(request(runtime, descriptor.id(), Map.of()), "caller-b")
                    .get(1, TimeUnit.SECONDS);

            assertEquals(ResultEnvelope.Status.OK, second.status());
            assertEquals("caller-b", second.output().get("caller"));
            assertFalse(first.isDone());
            firstCompletion.complete(Map.of("caller", "caller-a"));
            assertEquals(ResultEnvelope.Status.OK, first.get(1, TimeUnit.SECONDS).status());
        }
    }

    @Test
    void delegatedInvocationPreservesCallerDeadlineAndPath() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var parent = capability("lodestone.test.workflow-parent", Availability.AVAILABLE,
                    Set.of(PermissionClass.OBSERVE), SideEffect.NONE, null);
            var child = capability("minecraft.test.workflow-child", Availability.AVAILABLE,
                    Set.of(PermissionClass.OBSERVE), SideEffect.NONE, null);
            var deadline = System.currentTimeMillis() + 5_000;
            var parentIdempotencyKey = "p".repeat(256);
            var childVerified = new AtomicBoolean();
            runtime.registerAdapter(adapter(List.of(parent, child), Map.of(
                    parent.id(), context -> InvocationAttributes.requireDelegatedInvoker(context)
                            .invoke("read", child.id(), "1.0", Map.of())
                            .thenApply(result -> Map.of("childStatus", result.status().name())),
                    child.id(), context -> {
                        childVerified.set("transport-caller".equals(context.attributes().get(
                                        InvocationAttributes.CALLER_SESSION_ID))
                                && deadline == context.request().deadlineEpochMs()
                                && context.request().idempotencyKey() != null
                                && context.request().idempotencyKey().startsWith("delegated:")
                                && context.request().idempotencyKey().length() <= 256
                                && InvocationAttributes.delegationPath(context).equals(
                                        List.of(parent.id(), child.id())));
                        return CompletableFuture.completedFuture(Map.of());
                    })));

            var result = runtime.invoke(new RequestEnvelope("1.0", "workflow", runtime.sessionId(),
                    parent.id(), "1.0", Map.of(), deadline, parentIdempotencyKey, false), "transport-caller")
                    .get(2, TimeUnit.SECONDS);

            assertEquals(ResultEnvelope.Status.OK, result.status());
            assertTrue(childVerified.get());
            assertTrue(runtime.audit().stream().anyMatch(record -> child.id().equals(record.capability())));
            assertTrue(runtime.auditTrace().stream().anyMatch(record ->
                    child.id().equals(record.capability())
                            && "transport-caller".equals(record.callerSessionId())
                            && record.delegationPath().equals(List.of(parent.id(), child.id()))));
        }
    }

    @Test
    void delegatedInvocationRejectsTargetsOutsideTheNativeBoundary() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var descriptor = capability("lodestone.test.workflow-cycle", Availability.AVAILABLE,
                    Set.of(PermissionClass.OBSERVE), SideEffect.NONE, null);
            runtime.registerAdapter(adapter(descriptor, context ->
                    InvocationAttributes.requireDelegatedInvoker(context)
                            .invoke("cycle", descriptor.id(), "1.0", Map.of())
                            .thenApply(result -> Map.of())));

            var result = runtime.invoke(request(runtime, descriptor.id(), Map.of())).get(1, TimeUnit.SECONDS);

            assertEquals(ResultEnvelope.Status.ERROR, result.status());
            assertEquals("DELEGATION_BOUNDARY_VIOLATION", result.error().code());
            assertTrue(result.error().message().contains("delegation boundary"));
        }
    }

    @Test
    void delegatedMutationMarksParentTimeoutIndeterminate() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.fromCsv("control-player"))) {
            var parent = capability("lodestone.test.mutating-workflow", Availability.AVAILABLE,
                    Set.of(PermissionClass.CONTROL_PLAYER), SideEffect.CONTROL_PLAYER, null);
            var child = capability("minecraft.test.mutating-child", Availability.AVAILABLE,
                    Set.of(PermissionClass.CONTROL_PLAYER), SideEffect.CONTROL_PLAYER, null);
            var committed = new java.util.concurrent.CountDownLatch(1);
            runtime.registerAdapter(adapter(List.of(parent, child), Map.of(
                    parent.id(), context -> InvocationAttributes.requireDelegatedInvoker(context)
                            .invoke("mutate", child.id(), "1.0", Map.of())
                            .thenApply(result -> Map.of()),
                    child.id(), context -> {
                        context.cancellation().commitMutation();
                        committed.countDown();
                        return new CompletableFuture<>();
                    })));

            var invocation = runtime.invoke(new RequestEnvelope("1.0", "mutating-workflow", runtime.sessionId(),
                    parent.id(), "1.0", Map.of(), System.currentTimeMillis() + 2_000, null, false));
            assertTrue(committed.await(5, TimeUnit.SECONDS));
            var result = invocation.get(5, TimeUnit.SECONDS);

            assertEquals(ResultEnvelope.Status.ERROR, result.status());
            assertEquals("OUTCOME_INDETERMINATE", result.error().code());
            assertFalse(result.error().retryable());
        }
    }

    @Test
    void parentSuccessWaitsForIgnoredDelegatedChildTermination() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var parent = capability("lodestone.test.ignored-child", Availability.AVAILABLE,
                    Set.of(PermissionClass.OBSERVE), SideEffect.NONE, null);
            var child = capability("minecraft.test.ignored-child", Availability.AVAILABLE,
                    Set.of(PermissionClass.OBSERVE), SideEffect.NONE, null);
            var childCompletion = new CompletableFuture<Map<String, Object>>();
            var childStarted = new java.util.concurrent.CountDownLatch(1);
            runtime.registerAdapter(adapter(List.of(parent, child), Map.of(
                    parent.id(), context -> {
                        InvocationAttributes.requireDelegatedInvoker(context)
                                .invoke("child.0", child.id(), "1.0", Map.of());
                        return CompletableFuture.completedFuture(Map.of("parentReturned", true));
                    },
                    child.id(), context -> {
                        childStarted.countDown();
                        return childCompletion;
                    })));

            var result = runtime.invoke(request(runtime, parent.id(), Map.of()));
            assertTrue(childStarted.await(1, TimeUnit.SECONDS));
            assertFalse(result.isDone());

            childCompletion.complete(Map.of("child", true));
            assertEquals(ResultEnvelope.Status.OK, result.get(1, TimeUnit.SECONDS).status());
        }
    }

    @Test
    void ignoredDelegatedFailureFailsParentWithTheChildError() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var parent = capability("lodestone.test.ignored-failure", Availability.AVAILABLE,
                    Set.of(PermissionClass.OBSERVE), SideEffect.NONE, null);
            var child = capability("minecraft.test.ignored-failure", Availability.AVAILABLE,
                    Set.of(PermissionClass.OBSERVE), SideEffect.NONE, null);
            runtime.registerAdapter(adapter(List.of(parent, child), Map.of(
                    parent.id(), context -> {
                        InvocationAttributes.requireDelegatedInvoker(context)
                                .invoke("child.0", child.id(), "1.0", Map.of());
                        return CompletableFuture.completedFuture(Map.of());
                    },
                    child.id(), context -> CompletableFuture.failedFuture(
                            new IllegalStateException("child failed exactly")))));

            var result = runtime.invoke(request(runtime, parent.id(), Map.of())).get(1, TimeUnit.SECONDS);

            assertEquals(ResultEnvelope.Status.ERROR, result.status());
            assertEquals("ADAPTER_FAILURE", result.error().code());
            assertTrue(result.error().message().contains("child failed exactly"));
        }
    }

    @Test
    void parentCancellationBeforeDelegatedCommitPreventsDispatch() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.fromCsv("control-player"))) {
            var parent = capability("lodestone.test.cancel-before-commit", Availability.AVAILABLE,
                    Set.of(PermissionClass.CONTROL_PLAYER), SideEffect.CONTROL_PLAYER, null);
            var child = capability("minecraft.test.cancel-before-commit", Availability.AVAILABLE,
                    Set.of(PermissionClass.CONTROL_PLAYER), SideEffect.CONTROL_PLAYER, null);
            var childToken = new java.util.concurrent.atomic.AtomicReference<dev.lodestone.adapter.CancellationToken>();
            var childCompletion = new CompletableFuture<Map<String, Object>>();
            var childStarted = new java.util.concurrent.CountDownLatch(1);
            runtime.registerAdapter(adapter(List.of(parent, child), Map.of(
                    parent.id(), context -> {
                        InvocationAttributes.requireDelegatedInvoker(context)
                                .invoke("child.0", child.id(), "1.0", Map.of());
                        return CompletableFuture.completedFuture(Map.of());
                    },
                    child.id(), context -> {
                        childToken.set(context.cancellation());
                        childStarted.countDown();
                        return childCompletion;
                    })));

            var result = runtime.invoke(request(runtime, parent.id(), Map.of()));
            assertTrue(childStarted.await(1, TimeUnit.SECONDS));
            assertTrue(result.cancel(false));
            assertTrue(result.isCancelled());
            assertTrue(childToken.get().isCancelled());
            org.junit.jupiter.api.Assertions.assertThrows(
                    dev.lodestone.adapter.CancellationToken.CancellationException.class,
                    () -> childToken.get().commitMutation());
            childCompletion.completeExceptionally(new IllegalStateException("cancelled test cleanup"));
        }
    }

    @Test
    void delegatedCommitBeforeParentCancellationMarksParentIndeterminate() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.fromCsv("control-player"))) {
            var parent = capability("lodestone.test.commit-before-cancel", Availability.AVAILABLE,
                    Set.of(PermissionClass.CONTROL_PLAYER), SideEffect.CONTROL_PLAYER, null);
            var child = capability("minecraft.test.commit-before-cancel", Availability.AVAILABLE,
                    Set.of(PermissionClass.CONTROL_PLAYER), SideEffect.CONTROL_PLAYER, null);
            var childToken = new java.util.concurrent.atomic.AtomicReference<dev.lodestone.adapter.CancellationToken>();
            var childCompletion = new CompletableFuture<Map<String, Object>>();
            var committed = new java.util.concurrent.CountDownLatch(1);
            runtime.registerAdapter(adapter(List.of(parent, child), Map.of(
                    parent.id(), context -> {
                        InvocationAttributes.requireDelegatedInvoker(context)
                                .invoke("child.0", child.id(), "1.0", Map.of());
                        return CompletableFuture.completedFuture(Map.of());
                    },
                    child.id(), context -> {
                        childToken.set(context.cancellation());
                        context.cancellation().commitMutation();
                        committed.countDown();
                        return childCompletion;
                    })));

            var result = runtime.invoke(new RequestEnvelope("1.0", "commit-before-cancel", runtime.sessionId(),
                    parent.id(), "1.0", Map.of(), System.currentTimeMillis() + 2_000, null, false));
            assertTrue(committed.await(1, TimeUnit.SECONDS));
            assertFalse(result.cancel(false));
            assertTrue(childToken.get().isCancelled());
            childCompletion.completeExceptionally(new IllegalStateException("native acknowledgement lost"));

            var terminal = result.get(1, TimeUnit.SECONDS);
            assertEquals("OUTCOME_INDETERMINATE", terminal.error().code());
            assertFalse(terminal.error().retryable());
        }
    }

    @Test
    void lateSuccessCannotClearIndeterminateMutationForAnotherCaller() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.fromCsv("modify-world"))) {
            var descriptor = capability("minecraft.test.cross-caller-indeterminate", Availability.AVAILABLE,
                    Set.of(PermissionClass.MODIFY_WORLD), SideEffect.MODIFY_WORLD, null,
                    Map.of("type", "object"), Map.of("type", "object"), new RateLimit(100, 1000, 100));
            var completion = new CompletableFuture<Map<String, Object>>();
            var committed = new java.util.concurrent.CountDownLatch(1);
            runtime.registerAdapter(adapter(descriptor, context -> {
                context.cancellation().commitMutation();
                committed.countDown();
                return completion;
            }));

            var firstInvocation = runtime.invoke(new RequestEnvelope("1.0", "cross-caller-first", runtime.sessionId(),
                    descriptor.id(), "1.0", Map.of(), System.currentTimeMillis() + 2_000, null, false), "caller-a");
            assertTrue(committed.await(5, TimeUnit.SECONDS));
            var first = firstInvocation.get(5, TimeUnit.SECONDS);
            assertEquals("OUTCOME_INDETERMINATE", first.error().code());
            completion.complete(Map.of("late", true));

            var second = runtime.invoke(new RequestEnvelope("1.0", "cross-caller-second", runtime.sessionId(),
                    descriptor.id(), "1.0", Map.of(), System.currentTimeMillis() + 1_000, null, false), "caller-b")
                    .get(1, TimeUnit.SECONDS);
            assertEquals("CAPABILITY_QUARANTINED", second.error().code());
            assertFalse(second.error().retryable());
        }
    }

    // --- minecraft.session.reconcile / CAPABILITY_QUARANTINED recovery -------------------------
    // See LodestoneRuntime's class-level SHARED_MUTATION_ORDER/activeQuarantine()/
    // putIndeterminateQuarantine() and the minecraft.session.reconcile handler for the mechanism
    // these tests cover: every mutating minecraft.* capability shares one ordering key, so one
    // capability's indeterminate outcome blocks every other one, and the only sanctioned recovery
    // is an explicit, audited minecraft.session.reconcile call that only clears the quarantine once
    // an adapter confirms it actually quiesced residual activity.

    @Test
    void indeterminateOutcomeOnOneMutatingCapabilityQuarantinesADifferentMutatingCapabilityToo() throws Exception {
        // This locks in, as an explicit, intentional, regression-tested contract (previously there
        // was no test either confirming or denying this), the exact cross-capability blocking
        // behavior discovered live against a real NeoForge client this session: capability A never
        // itself gets retried here - a genuinely DIFFERENT capability B is blocked by A's failure,
        // because both are minecraft.* mutating capabilities and therefore share
        // SHARED_MUTATION_ORDER. This is deliberate (see the class-level comment referenced above),
        // not an accident of the shared-key implementation, and must not be "fixed" by narrowing
        // the quarantine to per-capability scope.
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.fromCsv("control-player"))) {
            var capabilityA = capability("minecraft.test.cross-capability-a", Availability.AVAILABLE,
                    Set.of(PermissionClass.CONTROL_PLAYER), SideEffect.CONTROL_PLAYER, null);
            var capabilityB = capability("minecraft.test.cross-capability-b", Availability.AVAILABLE,
                    Set.of(PermissionClass.CONTROL_PLAYER), SideEffect.CONTROL_PLAYER, null);
            runtime.registerAdapter(adapter(List.of(capabilityA, capabilityB), Map.of(
                    capabilityA.id(), context -> {
                        context.cancellation().commitMutation();
                        throw new IllegalStateException("capability A's native acknowledgement was lost");
                    },
                    capabilityB.id(), context -> CompletableFuture.completedFuture(Map.of("ok", true)))));

            var failedA = runtime.invoke(request(runtime, capabilityA.id(), Map.of())).get(1, TimeUnit.SECONDS);
            assertEquals("OUTCOME_INDETERMINATE", failedA.error().code());

            var blockedB = runtime.invoke(request(runtime, capabilityB.id(), Map.of()), "caller-b")
                    .get(1, TimeUnit.SECONDS);
            assertEquals("CAPABILITY_QUARANTINED", blockedB.error().code());
            assertFalse(blockedB.error().retryable());
        }
    }

    @Test
    void reconcileClearsTheSharedQuarantineWhenTheAdapterConfirmsQuiescence() throws Exception {
        // minecraft.session.reconcile itself requires BOTH observe and control-player (see its
        // catalog entry) - control-player alone (sufficient for the plain mutating test
        // capabilities below) is not enough to invoke reconcile itself.
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.fromCsv("observe,control-player"))) {
            var failingCapability = capability("minecraft.test.reconcile-clears-a", Availability.AVAILABLE,
                    Set.of(PermissionClass.CONTROL_PLAYER), SideEffect.CONTROL_PLAYER, null);
            var otherCapability = capability("minecraft.test.reconcile-clears-b", Availability.AVAILABLE,
                    Set.of(PermissionClass.CONTROL_PLAYER), SideEffect.CONTROL_PLAYER, null);
            runtime.registerAdapter(reconcilingAdapter(List.of(failingCapability, otherCapability), Map.of(
                    failingCapability.id(), context -> {
                        context.cancellation().commitMutation();
                        throw new IllegalStateException("native acknowledgement was lost");
                    },
                    otherCapability.id(), context -> CompletableFuture.completedFuture(Map.of("ok", true))),
                    () -> CompletableFuture.completedFuture(Map.of(
                            "quiesced", true, "stoppedGoalActors", List.of("navigationGoal")))));

            runtime.invoke(request(runtime, failingCapability.id(), Map.of())).get(1, TimeUnit.SECONDS);
            var stillBlocked = runtime.invoke(request(runtime, otherCapability.id(), Map.of()), "caller-before-reconcile")
                    .get(1, TimeUnit.SECONDS);
            assertEquals("CAPABILITY_QUARANTINED", stillBlocked.error().code());

            var reconcile = runtime.invoke(request(runtime, "minecraft.session.reconcile", Map.of()))
                    .get(1, TimeUnit.SECONDS);
            assertEquals(ResultEnvelope.Status.OK, reconcile.status());
            assertEquals(true, reconcile.output().get("quarantinePresent"));
            assertEquals(true, reconcile.output().get("cleared"));
            assertEquals(true, reconcile.output().get("quiesced"));

            var recovered = runtime.invoke(request(runtime, otherCapability.id(), Map.of()), "caller-after-reconcile")
                    .get(1, TimeUnit.SECONDS);
            assertEquals(ResultEnvelope.Status.OK, recovered.status());
        }
    }

    @Test
    void reconcileLeavesTheSharedQuarantineInPlaceWhenTheAdapterCannotConfirmQuiescence() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.fromCsv("observe,control-player"))) {
            var failingCapability = capability("minecraft.test.reconcile-partial-a", Availability.AVAILABLE,
                    Set.of(PermissionClass.CONTROL_PLAYER), SideEffect.CONTROL_PLAYER, null);
            var otherCapability = capability("minecraft.test.reconcile-partial-b", Availability.AVAILABLE,
                    Set.of(PermissionClass.CONTROL_PLAYER), SideEffect.CONTROL_PLAYER, null);
            runtime.registerAdapter(reconcilingAdapter(List.of(failingCapability, otherCapability), Map.of(
                    failingCapability.id(), context -> {
                        context.cancellation().commitMutation();
                        throw new IllegalStateException("native acknowledgement was lost");
                    },
                    otherCapability.id(), context -> CompletableFuture.completedFuture(Map.of("ok", true))),
                    () -> CompletableFuture.completedFuture(Map.of(
                            "quiesced", false, "reason", "adapter could not confirm the client stopped moving"))));

            runtime.invoke(request(runtime, failingCapability.id(), Map.of())).get(1, TimeUnit.SECONDS);

            var reconcile = runtime.invoke(request(runtime, "minecraft.session.reconcile", Map.of()))
                    .get(1, TimeUnit.SECONDS);
            assertEquals(ResultEnvelope.Status.OK, reconcile.status());
            assertEquals(true, reconcile.output().get("quarantinePresent"));
            // Partial success (quiesce reported false) must NOT clear the quarantine - this is the
            // exact "don't clear on partial success" contract the reconcile capability exists to
            // uphold, distinct from and stricter than simply "the call didn't throw".
            assertEquals(false, reconcile.output().get("cleared"));

            var stillBlocked = runtime.invoke(request(runtime, otherCapability.id(), Map.of()), "caller-still-blocked")
                    .get(1, TimeUnit.SECONDS);
            assertEquals("CAPABILITY_QUARANTINED", stillBlocked.error().code());
            assertFalse(stillBlocked.error().retryable());
        }
    }

    @Test
    void reconcileClearRecordsAnAuditEntryReferencingTheQuarantiningInvocation() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.fromCsv("observe,control-player"))) {
            var failingCapability = capability("minecraft.test.reconcile-audit", Availability.AVAILABLE,
                    Set.of(PermissionClass.CONTROL_PLAYER), SideEffect.CONTROL_PLAYER, null);
            runtime.registerAdapter(reconcilingAdapter(List.of(failingCapability), Map.of(
                    failingCapability.id(), context -> {
                        context.cancellation().commitMutation();
                        throw new IllegalStateException("native acknowledgement was lost");
                    }),
                    () -> CompletableFuture.completedFuture(Map.of("quiesced", true))));

            assertTrue(runtime.quarantineClearAuditSnapshot().isEmpty());

            runtime.invoke(request(runtime, failingCapability.id(), Map.of())).get(1, TimeUnit.SECONDS);
            var reconcile = runtime.invoke(request(runtime, "minecraft.session.reconcile", Map.of()))
                    .get(1, TimeUnit.SECONDS);
            var clearedOwnerId = (String) reconcile.output().get("quarantineOwnerId");
            assertTrue(clearedOwnerId != null && !clearedOwnerId.isBlank());

            var auditSnapshot = runtime.quarantineClearAuditSnapshot();
            assertEquals(1, auditSnapshot.size());
            assertEquals(clearedOwnerId, auditSnapshot.get(0).get("clearedQuarantineOwnerId"));
            assertTrue(auditSnapshot.get(0).get("requestId") instanceof String requestId && !requestId.isBlank());
            assertTrue(auditSnapshot.get(0).get("occurredAt") instanceof String occurredAt && !occurredAt.isBlank());

            // A second reconcile call with nothing left to clear must not add another audit entry.
            runtime.invoke(request(runtime, "minecraft.session.reconcile", Map.of())).get(1, TimeUnit.SECONDS);
            assertEquals(1, runtime.quarantineClearAuditSnapshot().size());
        }
    }

    @Test
    void callerSpecificGrantDoesNotRestrictTheProcessPolicy() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.fromCsv("control-player"))) {
            var descriptor = capability("minecraft.test.caller-grant", Availability.AVAILABLE,
                    Set.of(PermissionClass.CONTROL_PLAYER), SideEffect.CONTROL_PLAYER, null);
            runtime.registerAdapter(adapter(descriptor,
                    context -> CompletableFuture.completedFuture(Map.of("allowed", true))));

            var denied = runtime.invoke(request(runtime, descriptor.id(), Map.of()), "caller",
                    AuthorizationPolicy.observeOnly()).get(1, TimeUnit.SECONDS);
            var allowed = runtime.invoke(new RequestEnvelope("1.0", "authorized", runtime.sessionId(),
                            descriptor.id(), "1.0", Map.of(), null, null, false), "caller-2",
                    AuthorizationPolicy.fromCsv("control-player")).get(1, TimeUnit.SECONDS);

            assertEquals(ResultEnvelope.Status.OK, denied.status());
            assertEquals(ResultEnvelope.Status.OK, allowed.status());
        }
    }

    @Test
    void repeatedDelegatedReadStepObservesFreshState() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var parent = capability("lodestone.test.fresh-poll", Availability.AVAILABLE,
                    Set.of(PermissionClass.OBSERVE), SideEffect.NONE, null);
            var child = capability("minecraft.test.fresh-poll", Availability.AVAILABLE,
                    Set.of(PermissionClass.OBSERVE), SideEffect.NONE, null);
            var calls = new AtomicInteger();
            runtime.registerAdapter(adapter(List.of(parent, child), Map.of(
                    parent.id(), context -> {
                        var invoker = InvocationAttributes.requireDelegatedInvoker(context);
                        return invoker.invoke("poll.0", child.id(), "1.0", Map.of())
                                .thenCompose(first -> invoker.invoke("poll.0", child.id(), "1.0", Map.of()))
                                .thenApply(second -> Map.of("calls", calls.get()));
                    },
                    child.id(), context -> CompletableFuture.completedFuture(Map.of(
                            "value", calls.incrementAndGet())))));

            var result = runtime.invoke(new RequestEnvelope("1.0", "fresh-poll", runtime.sessionId(),
                    parent.id(), "1.0", Map.of(), null, "stable-parent", false)).get(1, TimeUnit.SECONDS);

            assertEquals(ResultEnvelope.Status.OK, result.status());
            assertEquals(2, calls.get());
        }
    }

    @Test
    void repeatedMutatingLogicalStepWithoutParentKeyDispatchesOnce() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.fromCsv("control-player"))) {
            var parent = capability("lodestone.test.once-step", Availability.AVAILABLE,
                    Set.of(PermissionClass.CONTROL_PLAYER), SideEffect.CONTROL_PLAYER, null);
            var child = capability("minecraft.test.once-step", Availability.AVAILABLE,
                    Set.of(PermissionClass.CONTROL_PLAYER), SideEffect.CONTROL_PLAYER, null);
            var calls = new AtomicInteger();
            runtime.registerAdapter(adapter(List.of(parent, child), Map.of(
                    parent.id(), context -> {
                        var invoker = InvocationAttributes.requireDelegatedInvoker(context);
                        return invoker.invoke("mutate.0", child.id(), "1.0", Map.of())
                                .thenCompose(first -> invoker.invoke("mutate.0", child.id(), "1.0", Map.of()))
                                .thenApply(second -> Map.of("calls", calls.get()));
                    },
                    child.id(), context -> {
                        calls.incrementAndGet();
                        context.cancellation().commitMutation();
                        return CompletableFuture.completedFuture(Map.of("changed", true));
                    })));

            var result = runtime.invoke(request(runtime, parent.id(), Map.of())).get(1, TimeUnit.SECONDS);

            assertEquals(ResultEnvelope.Status.OK, result.status());
            assertEquals(1, calls.get());
        }
    }

    @Test
    void delegatedIdempotencySeparatesDifferentParentCapabilities() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.fromCsv("control-player"))) {
            var parentA = capability("lodestone.test.parent-a", Availability.AVAILABLE,
                    Set.of(PermissionClass.CONTROL_PLAYER), SideEffect.CONTROL_PLAYER, null);
            var parentB = capability("lodestone.test.parent-b", Availability.AVAILABLE,
                    Set.of(PermissionClass.CONTROL_PLAYER), SideEffect.CONTROL_PLAYER, null);
            var child = capability("minecraft.test.parent-key-child", Availability.AVAILABLE,
                    Set.of(PermissionClass.CONTROL_PLAYER), SideEffect.CONTROL_PLAYER, null);
            var calls = new AtomicInteger();
            CapabilityHandler workflow = context -> InvocationAttributes.requireDelegatedInvoker(context)
                    .invoke("mutate.0", child.id(), "1.0", Map.of())
                    .thenApply(ignored -> Map.of());
            runtime.registerAdapter(adapter(List.of(parentA, parentB, child), Map.of(
                    parentA.id(), workflow,
                    parentB.id(), workflow,
                    child.id(), context -> {
                        calls.incrementAndGet();
                        context.cancellation().commitMutation();
                        return CompletableFuture.completedFuture(Map.of());
                    })));

            var first = runtime.invoke(new RequestEnvelope("1.0", "parent-a", runtime.sessionId(), parentA.id(),
                    "1.0", Map.of(), null, "same-parent-key", false)).get(1, TimeUnit.SECONDS);
            var second = runtime.invoke(new RequestEnvelope("1.0", "parent-b", runtime.sessionId(), parentB.id(),
                    "1.0", Map.of(), null, "same-parent-key", false)).get(1, TimeUnit.SECONDS);

            assertEquals(ResultEnvelope.Status.OK, first.status());
            assertEquals(ResultEnvelope.Status.OK, second.status());
            assertEquals(2, calls.get());
        }
    }

    @Test
    void externalIdempotencyUsesDelimiterSafeTypedKeys() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var descriptor = capability("minecraft.test.typed-idempotency-key", Availability.AVAILABLE,
                    Set.of(PermissionClass.OBSERVE), SideEffect.NONE, null,
                    Map.of("type", "object"), Map.of("type", "object"), new RateLimit(100, 1000, 100));
            var calls = new AtomicInteger();
            runtime.registerAdapter(adapter(descriptor, context -> {
                calls.incrementAndGet();
                return CompletableFuture.completedFuture(Map.of());
            }));
            var capability = descriptor.id();
            var callerA = "a";
            var keyA = "b:" + capability + ":c";
            var callerB = "a:" + capability + ":b";
            var keyB = "c";

            var first = runtime.invoke(new RequestEnvelope("1.0", "typed-a", runtime.sessionId(), capability,
                    "1.0", Map.of(), null, keyA, false), callerA).get(1, TimeUnit.SECONDS);
            var second = runtime.invoke(new RequestEnvelope("1.0", "typed-b", runtime.sessionId(), capability,
                    "1.0", Map.of(), null, keyB, false), callerB).get(1, TimeUnit.SECONDS);

            assertEquals(ResultEnvelope.Status.OK, first.status());
            assertEquals(ResultEnvelope.Status.OK, second.status());
            assertEquals(2, calls.get());
        }
    }

    @Test
    void earlierDeadlineIdempotentObserverTimesOutWithoutCancellingOriginal() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var descriptor = capability("minecraft.test.idempotent-deadline", Availability.AVAILABLE,
                    Set.of(PermissionClass.OBSERVE), SideEffect.NONE, null,
                    Map.of("type", "object"), Map.of("type", "object"), new RateLimit(100, 1000, 100));
            var calls = new AtomicInteger();
            var completion = new CompletableFuture<Map<String, Object>>();
            runtime.registerAdapter(adapter(descriptor, context -> {
                calls.incrementAndGet();
                return completion;
            }));

            var first = runtime.invoke(new RequestEnvelope("1.0", "original", runtime.sessionId(), descriptor.id(),
                    "1.0", Map.of(), System.currentTimeMillis() + 2_000, "same-key", false));
            var observer = runtime.invoke(new RequestEnvelope("1.0", "observer", runtime.sessionId(), descriptor.id(),
                    "1.0", Map.of(), System.currentTimeMillis() + 80, "same-key", false));

            var observerResult = observer.get(1, TimeUnit.SECONDS);
            assertEquals(ResultEnvelope.Status.TIMED_OUT, observerResult.status());
            assertEquals("IDEMPOTENT_RESULT_PENDING", observerResult.error().code());
            assertFalse(first.isDone());
            completion.complete(Map.of("done", true));
            assertEquals(ResultEnvelope.Status.OK, first.get(1, TimeUnit.SECONDS).status());
            assertEquals(1, calls.get());
        }
    }

    @Test
    void schedulerDelayCannotTurnAnExpiredIdempotentReplayIntoLateSuccess() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var descriptor = capability("minecraft.test.idempotent-monotonic-replay", Availability.AVAILABLE,
                    Set.of(PermissionClass.OBSERVE), SideEffect.NONE, null,
                    Map.of("type", "object"), Map.of("type", "object"),
                    new RateLimit(100, 1000, 100));
            var completion = new CompletableFuture<Map<String, Object>>();
            runtime.registerAdapter(adapter(descriptor, context -> completion));
            var original = runtime.invoke(new RequestEnvelope("1.0", "monotonic-original", runtime.sessionId(),
                    descriptor.id(), "1.0", Map.of(), System.currentTimeMillis() + 5_000,
                    "monotonic-replay-key", false));
            var schedulerField = LodestoneRuntime.class.getDeclaredField("scheduler");
            schedulerField.setAccessible(true);
            var scheduler = (java.util.concurrent.ScheduledExecutorService) schedulerField.get(runtime);
            var schedulerBlocked = new java.util.concurrent.CountDownLatch(1);
            var releaseScheduler = new java.util.concurrent.CountDownLatch(1);
            scheduler.execute(() -> {
                schedulerBlocked.countDown();
                try {
                    releaseScheduler.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(schedulerBlocked.await(1, TimeUnit.SECONDS));
            var replay = runtime.invoke(new RequestEnvelope("1.0", "monotonic-replay", runtime.sessionId(),
                    descriptor.id(), "1.0", Map.of(), System.currentTimeMillis() + 50,
                    "monotonic-replay-key", false));
            Thread.sleep(100);
            completion.complete(Map.of("late", true));

            try {
                var replayResult = replay.get(1, TimeUnit.SECONDS);
                assertEquals(ResultEnvelope.Status.TIMED_OUT, replayResult.status());
                assertEquals("IDEMPOTENT_RESULT_PENDING", replayResult.error().code());
                assertEquals(ResultEnvelope.Status.OK, original.get(1, TimeUnit.SECONDS).status());
            } finally {
                releaseScheduler.countDown();
            }
        }
    }

    @Test
    void escapedDelegatedInvokerCannotStartAChildAfterParentCompletion() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var parent = capability("lodestone.test.sealed-scope", Availability.AVAILABLE,
                    Set.of(PermissionClass.OBSERVE), SideEffect.NONE, null);
            var child = capability("minecraft.test.sealed-scope", Availability.AVAILABLE,
                    Set.of(PermissionClass.OBSERVE), SideEffect.NONE, null);
            var escaped = new java.util.concurrent.atomic.AtomicReference<dev.lodestone.adapter.DelegatedInvoker>();
            var calls = new AtomicInteger();
            runtime.registerAdapter(adapter(List.of(parent, child), Map.of(
                    parent.id(), context -> {
                        escaped.set(InvocationAttributes.requireDelegatedInvoker(context));
                        return CompletableFuture.completedFuture(Map.of());
                    },
                    child.id(), context -> {
                        calls.incrementAndGet();
                        return CompletableFuture.completedFuture(Map.of());
                    })));

            assertEquals(ResultEnvelope.Status.OK,
                    runtime.invoke(request(runtime, parent.id(), Map.of())).get(1, TimeUnit.SECONDS).status());
            var failure = org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> escaped.get().invoke("late.0", child.id(), "1.0", Map.of())
                            .toCompletableFuture().get(1, TimeUnit.SECONDS));
            assertTrue(failure.getCause().getMessage().contains("scope is already sealed"));
            assertEquals(0, calls.get());
        }
    }

    @Test
    void underdeclaredWorkflowCannotEscalateThroughAChild() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.fromCsv("observe,control-player"))) {
            var parent = capability("lodestone.test.permission-escalation", Availability.AVAILABLE,
                    Set.of(PermissionClass.OBSERVE), SideEffect.NONE, null);
            var child = capability("minecraft.test.permission-escalation", Availability.AVAILABLE,
                    Set.of(PermissionClass.CONTROL_PLAYER), SideEffect.CONTROL_PLAYER, null);
            runtime.registerAdapter(adapter(List.of(parent, child), Map.of(
                    parent.id(), context -> InvocationAttributes.requireDelegatedInvoker(context)
                            .invoke("child.0", child.id(), "1.0", Map.of()).thenApply(ignored -> Map.of()),
                    child.id(), context -> CompletableFuture.completedFuture(Map.of()))));

            var result = runtime.invoke(request(runtime, parent.id(), Map.of())).get(1, TimeUnit.SECONDS);

            assertEquals("DELEGATION_PERMISSION_ESCALATION", result.error().code());
        }
    }

    @Test
    void eventHubDropsOldestEventsAtTheBound() {
        var hub = new EventHub();
        var subscription = hub.subscribe("session", "minecraft.", 2);
        hub.publish("session", "minecraft.one", Map.of("value", 1), 1);
        hub.publish("session", "minecraft.two", Map.of("value", 2), 2);
        hub.publish("session", "minecraft.three", Map.of("value", 3), 3);
        var events = hub.poll(subscription.id(), 10);
        assertEquals(3, events.size());
        assertEquals("lodestone.events.lost", events.get(0).event());
        assertEquals(1L, events.get(0).payload().get("dropped"));
        assertEquals("minecraft.two", events.get(1).event());
    }

    @Test
    void eventSubscriptionsEnforceTheirTransportOwner() {
        var hub = new EventHub();
        var subscription = hub.subscribe("caller-a", "runtime-session", "minecraft.", 2);

        org.junit.jupiter.api.Assertions.assertThrows(SecurityException.class,
                () -> hub.poll("caller-b", subscription.id(), 10));
        org.junit.jupiter.api.Assertions.assertThrows(SecurityException.class,
                () -> hub.unsubscribe("caller-b", subscription.id()));
        assertFalse(hub.unsubscribe("caller-b-missing", "missing"));
        assertTrue(hub.unsubscribe("caller-a", subscription.id()));
    }

    @Test
    void eventOwnershipFailureHasAStableNonRetryableError() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var subscription = runtime.subscribe("caller-a", "minecraft.", 2);
            var request = new RequestEnvelope("1.0", "foreign-event-poll", runtime.sessionId(),
                    "minecraft.event.poll", "1.0", Map.of("subscriptionId", subscription.id()),
                    null, null, false);

            var result = runtime.invoke(request, "caller-b").get(1, TimeUnit.SECONDS);

            assertEquals(ResultEnvelope.Status.ERROR, result.status());
            assertEquals("EVENT_SUBSCRIPTION_FORBIDDEN", result.error().code());
            assertFalse(result.error().retryable());
        }
    }

    @Test
    void cancellationRacingRuntimeEventPollNeverConsumesAnUnacknowledgedEvent() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var sink = new java.util.concurrent.atomic.AtomicReference<dev.lodestone.adapter.EventSink>();
            var descriptor = new AdapterDescriptor("test.events", "1.0.0", "minecraft", "test", "test",
                    Environment.REMOTE);
            runtime.registerAdapter(new LodestoneAdapter() {
                @Override
                public AdapterDescriptor descriptor() {
                    return descriptor;
                }

                @Override
                public CapabilityManifest manifest() {
                    return new CapabilityManifest(descriptor, List.of());
                }

                @Override
                public Map<String, CapabilityHandler> handlers() {
                    return Map.of();
                }

                @Override
                public void start(dev.lodestone.adapter.AdapterContext context) {
                    sink.set(context.eventSink());
                }
            });

            for (var index = 0; index < 50; index++) {
                var owner = "poll-race-" + index;
                var subscription = runtime.subscribe(owner, "minecraft.test.", 1);
                sink.get().publish("minecraft.test.race", Map.of("index", index), index);
                var poll = new RequestEnvelope("1.0", "poll-race-" + index, runtime.sessionId(),
                        "minecraft.event.poll", "1.0", Map.of("subscriptionId", subscription.id(), "maxEvents", 1),
                        System.currentTimeMillis() + 2_000, null, false);
                var invocation = runtime.invoke(poll, owner);
                var cancellationWon = invocation.cancel(false);
                ResultEnvelope terminal;
                if (cancellationWon) {
                    assertTrue(invocation.isCancelled());
                    terminal = runtime.invoke(new RequestEnvelope("1.0", "poll-recovery-" + index,
                                    runtime.sessionId(), "minecraft.event.poll", "1.0",
                                    Map.of("subscriptionId", subscription.id(), "maxEvents", 1),
                                    System.currentTimeMillis() + 2_000, null, false), owner)
                            .get(1, TimeUnit.SECONDS);
                } else {
                    terminal = invocation.get(1, TimeUnit.SECONDS);
                }
                assertEquals(ResultEnvelope.Status.OK, terminal.status());
                @SuppressWarnings("unchecked")
                var events = (List<Map<String, Object>>) terminal.output().get("events");
                assertEquals(1, events.size());
                @SuppressWarnings("unchecked")
                var payload = (Map<String, Object>) events.get(0).get("payload");
                assertEquals(index, ((Number) payload.get("index")).intValue());
                assertTrue(runtime.unsubscribe(owner, subscription.id()));
            }
        }
    }

    @Test
    void committedSideEffectFreeTimeoutDoesNotCreatePersistentQuarantine() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var descriptor = capability("minecraft.test.poll-drain", Availability.AVAILABLE,
                    Set.of(PermissionClass.OBSERVE), SideEffect.NONE, null,
                    Map.of("type", "object"), Map.of("type", "object"), new RateLimit(100, 1000, 100));
            var calls = new AtomicInteger();
            var committed = new java.util.concurrent.CountDownLatch(1);
            runtime.registerAdapter(adapter(descriptor, context -> {
                if (calls.incrementAndGet() == 1) {
                    context.cancellation().commitMutation();
                    committed.countDown();
                    return new CompletableFuture<>();
                }
                return CompletableFuture.completedFuture(Map.of("recovered", true));
            }));

            var firstInvocation = runtime.invoke(new RequestEnvelope("1.0", "committed-read-timeout", runtime.sessionId(),
                            descriptor.id(), "1.0", Map.of(), System.currentTimeMillis() + 2_000, null, false),
                    "caller-a");
            assertTrue(committed.await(5, TimeUnit.SECONDS));
            var timedOut = firstInvocation.get(5, TimeUnit.SECONDS);
            var otherCaller = runtime.invoke(new RequestEnvelope("1.0", "committed-read-other", runtime.sessionId(),
                            descriptor.id(), "1.0", Map.of(), System.currentTimeMillis() + 1_000, null, false),
                    "caller-b").get(1, TimeUnit.SECONDS);

            assertEquals(ResultEnvelope.Status.ERROR, timedOut.status());
            assertEquals("OUTCOME_INDETERMINATE", timedOut.error().code());
            assertEquals(ResultEnvelope.Status.OK, otherCaller.status());
            assertEquals(true, otherCaller.output().get("recovered"));
        }
    }

    @Test
    void rejectsSchemaInvalidInputBeforeHandlerExecution() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var calls = new AtomicInteger();
            var descriptor = capability("test.validated-input", Availability.AVAILABLE,
                    Set.of(PermissionClass.OBSERVE), SideEffect.NONE, null,
                    Map.of("type", "object", "properties", Map.of(
                            "value", Map.of("type", "integer", "minimum", 1)),
                            "required", java.util.List.of("value"), "additionalProperties", false),
                    Map.of("type", "object"), new dev.lodestone.protocol.RateLimit(10, 1000, 2));
            runtime.registerAdapter(adapter(descriptor, context -> {
                calls.incrementAndGet();
                return CompletableFuture.completedFuture(Map.of());
            }));
            var result = runtime.invoke(request(runtime, descriptor.id(), Map.of("value", 0))).get(1, TimeUnit.SECONDS);
            assertEquals("INVALID_INPUT", result.error().code());
            assertEquals(0, calls.get());
        }
    }

    @Test
    void rejectsIncompatibleCapabilityVersion() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var descriptor = capability("test.versioned", Availability.AVAILABLE,
                    Set.of(PermissionClass.OBSERVE), SideEffect.NONE, null);
            runtime.registerAdapter(adapter(descriptor, context -> CompletableFuture.completedFuture(Map.of())));
            var result = runtime.invoke(new RequestEnvelope("1.0", "version", runtime.sessionId(),
                    descriptor.id(), "9.9", Map.of(), null, null, false)).get(1, TimeUnit.SECONDS);
            assertEquals("CAPABILITY_VERSION_UNSUPPORTED", result.error().code());
        }
    }

    @Test
    void envelopeDryRunIsPropagatedAndUnsupportedDryRunIsRejected() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var seenDryRun = new AtomicBoolean();
            var descriptor = new CapabilityDescriptor("test.dry-run", CapabilityKind.ACTION, "1.0",
                    Stability.STABLE, Availability.AVAILABLE, null, "test.adapter", "1.0.0", "minecraft", "test",
                    "test", Environment.REMOTE,
                    Map.of("type", "object", "properties", Map.of("dryRun", Map.of("type", "boolean"))),
                    Map.of("type", "object"), Map.of(), Set.of(PermissionClass.OBSERVE), SideEffect.NONE,
                    Idempotency.IDEMPOTENT, new CapabilityPrerequisites(false, false, false, false), "runtime",
                    new RateLimit(10, 1000, 2), 1000, true,
                    new DeliveryGuarantees("request-order", "at-most-once", 1), "dry-run test", Set.of("dry-run"));
            runtime.registerAdapter(adapter(descriptor, context -> {
                seenDryRun.set(Boolean.TRUE.equals(context.request().input().get("dryRun")));
                return CompletableFuture.completedFuture(Map.of("dryRun", seenDryRun.get()));
            }));
            var supported = runtime.invoke(new RequestEnvelope("1.0", "dry-run", runtime.sessionId(),
                    descriptor.id(), "1.0", Map.of(), null, null, true)).get(1, TimeUnit.SECONDS);
            assertEquals(ResultEnvelope.Status.OK, supported.status());
            assertTrue(seenDryRun.get());

            var unsupported = runtime.invoke(new RequestEnvelope("1.0", "unsupported-dry-run", runtime.sessionId(),
                    "lodestone.system.health", "1.0", Map.of(), null, null, true)).get(1, TimeUnit.SECONDS);
            assertEquals("DRY_RUN_UNSUPPORTED", unsupported.error().code());
        }
    }

    @Test
    void inputOnlyWorkflowDryRunPropagatesToDelegatedChildEnvelopeAndInput() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.fromCsv("control-player"))) {
            var dryRunInput = Map.<String, Object>of(
                    "type", "object",
                    "properties", Map.of("dryRun", Map.of("type", "boolean")),
                    "additionalProperties", false);
            var parent = withFeatureFlags(capability("lodestone.test.input-dry-run", Availability.AVAILABLE,
                    Set.of(PermissionClass.CONTROL_PLAYER), SideEffect.CONTROL_PLAYER, null,
                    dryRunInput, Map.of("type", "object"), new RateLimit(10, 1000, 2)), Set.of("dry-run"));
            var child = withFeatureFlags(capability("minecraft.test.input-dry-run", Availability.AVAILABLE,
                    Set.of(PermissionClass.CONTROL_PLAYER), SideEffect.CONTROL_PLAYER, null,
                    dryRunInput, Map.of("type", "object"), new RateLimit(10, 1000, 2)), Set.of("dry-run"));
            var childSawEnvelope = new AtomicBoolean();
            var childSawInput = new AtomicBoolean();
            runtime.registerAdapter(adapter(List.of(parent, child), Map.of(
                    parent.id(), context -> InvocationAttributes.requireDelegatedInvoker(context)
                            .invoke("child.0", child.id(), "1.0", Map.of())
                            .thenApply(ignored -> Map.of()),
                    child.id(), context -> {
                        childSawEnvelope.set(context.request().dryRun());
                        childSawInput.set(Boolean.TRUE.equals(context.request().input().get("dryRun")));
                        return CompletableFuture.completedFuture(Map.of());
                    })));

            var result = runtime.invoke(new RequestEnvelope("1.0", "input-dry-run", runtime.sessionId(),
                    parent.id(), "1.0", Map.of("dryRun", true), null, null, false))
                    .get(1, TimeUnit.SECONDS);

            assertEquals(ResultEnvelope.Status.OK, result.status());
            assertTrue(childSawEnvelope.get());
            assertTrue(childSawInput.get());
        }
    }

    @Test
    void idempotencyKeyRunsNonIdempotentHandlerOnce() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var calls = new AtomicInteger();
            var descriptor = capability("test.idempotent-replay", Availability.AVAILABLE,
                    Set.of(PermissionClass.OBSERVE), SideEffect.NONE, null);
            runtime.registerAdapter(adapter(descriptor, context -> {
                calls.incrementAndGet();
                return CompletableFuture.completedFuture(Map.of("value", true));
            }));
            var first = runtime.invoke(new RequestEnvelope("1.0", "first", runtime.sessionId(), descriptor.id(),
                    "1.0", Map.of(), null, "same-key", false)).get(1, TimeUnit.SECONDS);
            var second = runtime.invoke(new RequestEnvelope("1.0", "second", runtime.sessionId(), descriptor.id(),
                    "1.0", Map.of(), null, "same-key", false)).get(1, TimeUnit.SECONDS);
            assertEquals(ResultEnvelope.Status.OK, first.status());
            assertEquals("second", second.requestId());
            assertEquals(1, calls.get());
        }
    }

    @Test
    void rejectedExpiredIdempotentRequestDoesNotPoisonItsRetryKey() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var calls = new AtomicInteger();
            var descriptor = capability("test.idempotent-admission", Availability.AVAILABLE,
                    Set.of(PermissionClass.OBSERVE), SideEffect.NONE, null);
            runtime.registerAdapter(adapter(descriptor, context -> {
                calls.incrementAndGet();
                return CompletableFuture.completedFuture(Map.of("accepted", true));
            }));

            var expired = runtime.invoke(new RequestEnvelope("1.0", "expired", runtime.sessionId(), descriptor.id(),
                    "1.0", Map.of(), System.currentTimeMillis() - 1, "retry-key", false))
                    .get(1, TimeUnit.SECONDS);
            assertEquals(ResultEnvelope.Status.TIMED_OUT, expired.status());

            var retry = runtime.invoke(new RequestEnvelope("1.0", "retry", runtime.sessionId(), descriptor.id(),
                    "1.0", Map.of(), null, "retry-key", false)).get(1, TimeUnit.SECONDS);
            assertEquals(ResultEnvelope.Status.OK, retry.status());
            assertEquals(1, calls.get());
        }
    }

    @Test
    void enforcesDeclaredCapabilityRateLimitPerSession() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var descriptor = capability("test.rate-limited", Availability.AVAILABLE,
                    Set.of(PermissionClass.OBSERVE), SideEffect.NONE, null,
                    Map.of("type", "object"), Map.of("type", "object"), new RateLimit(1, 60_000, 1));
            runtime.registerAdapter(adapter(descriptor, context -> CompletableFuture.completedFuture(Map.of())));

            var first = runtime.invoke(request(runtime, descriptor.id(), Map.of())).get(1, TimeUnit.SECONDS);
            var second = runtime.invoke(request(runtime, descriptor.id(), Map.of())).get(1, TimeUnit.SECONDS);

            assertEquals(ResultEnvelope.Status.OK, first.status());
            assertEquals("RATE_LIMIT_EXCEEDED", second.error().code());
        }
    }

    @Test
    void publicFutureCompletionAndTimeoutAffectOnlyTheObserverView() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var descriptor = capability("test.read-only-result", Availability.AVAILABLE,
                    Set.of(PermissionClass.OBSERVE), SideEffect.NONE, null,
                    Map.of("type", "object"), Map.of("type", "object"),
                    new RateLimit(100, 1000, 100));
            var completions = new java.util.concurrent.ConcurrentLinkedQueue<CompletableFuture<Map<String, Object>>>();
            var firstCompletion = new CompletableFuture<Map<String, Object>>();
            var secondCompletion = new CompletableFuture<Map<String, Object>>();
            completions.add(firstCompletion);
            completions.add(secondCompletion);
            var calls = new AtomicInteger();
            runtime.registerAdapter(adapter(descriptor, context -> {
                calls.incrementAndGet();
                return java.util.Objects.requireNonNull(completions.poll());
            }));

            var completedView = runtime.invoke(new RequestEnvelope("1.0", "completed-view", runtime.sessionId(),
                    descriptor.id(), "1.0", Map.of(), System.currentTimeMillis() + 2_000,
                    "completed-view-key", false));
            var forged = ResultEnvelope.ok("forged", Map.of("forged", true));
            assertTrue(completedView.complete(forged));
            assertSame(forged, completedView.get(1, TimeUnit.SECONDS));
            firstCompletion.complete(Map.of("canonical", true));
            var completedReplay = runtime.invoke(new RequestEnvelope("1.0", "completed-replay", runtime.sessionId(),
                    descriptor.id(), "1.0", Map.of(), System.currentTimeMillis() + 1_000,
                    "completed-view-key", false)).get(1, TimeUnit.SECONDS);
            assertEquals(ResultEnvelope.Status.OK, completedReplay.status());
            assertEquals(true, completedReplay.output().get("canonical"));

            var timedView = runtime.invoke(new RequestEnvelope("1.0", "timed-view", runtime.sessionId(),
                    descriptor.id(), "1.0", Map.of(), System.currentTimeMillis() + 2_000,
                    "timed-view-key", false));
            assertSame(timedView, timedView.orTimeout(20, TimeUnit.MILLISECONDS));
            var observerFailure = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> timedView.get(1, TimeUnit.SECONDS));
            assertInstanceOf(java.util.concurrent.TimeoutException.class, observerFailure.getCause());
            secondCompletion.complete(Map.of("canonical", true));
            var timedReplay = runtime.invoke(new RequestEnvelope("1.0", "timed-replay", runtime.sessionId(),
                    descriptor.id(), "1.0", Map.of(), System.currentTimeMillis() + 1_000,
                    "timed-view-key", false)).get(1, TimeUnit.SECONDS);
            assertEquals(ResultEnvelope.Status.OK, timedReplay.status());
            assertEquals(true, timedReplay.output().get("canonical"));
            assertEquals(2, calls.get());
        }
    }

    @Test
    void monotonicDeadlineBarrierPreventsDispatchWhenTheSchedulerIsBlocked() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.fromCsv("modify-world"))) {
            var descriptor = capability("minecraft.test.deadline-barrier", Availability.AVAILABLE,
                    Set.of(PermissionClass.MODIFY_WORLD), SideEffect.MODIFY_WORLD, null,
                    Map.of("type", "object"), Map.of("type", "object"),
                    new RateLimit(100, 1000, 100));
            var firstCompletion = new CompletableFuture<Map<String, Object>>();
            var firstStarted = new CompletableFuture<Void>();
            var calls = new AtomicInteger();
            runtime.registerAdapter(adapter(descriptor, context -> {
                if (calls.incrementAndGet() == 1) {
                    firstStarted.complete(null);
                    return firstCompletion;
                }
                return CompletableFuture.completedFuture(Map.of("unsafe", true));
            }));
            var schedulerField = LodestoneRuntime.class.getDeclaredField("scheduler");
            schedulerField.setAccessible(true);
            var scheduler = (java.util.concurrent.ScheduledExecutorService) schedulerField.get(runtime);
            var schedulerBlocked = new java.util.concurrent.CountDownLatch(1);
            var releaseScheduler = new java.util.concurrent.CountDownLatch(1);

            var first = runtime.invoke(new RequestEnvelope("1.0", "deadline-a", runtime.sessionId(),
                    descriptor.id(), "1.0", Map.of(), null, null, false));
            firstStarted.get(1, TimeUnit.SECONDS);
            scheduler.execute(() -> {
                schedulerBlocked.countDown();
                try {
                    releaseScheduler.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(schedulerBlocked.await(1, TimeUnit.SECONDS));
            var second = runtime.invoke(new RequestEnvelope("1.0", "deadline-b", runtime.sessionId(),
                    descriptor.id(), "1.0", Map.of(), System.currentTimeMillis() + 50, null, false));
            Thread.sleep(100);
            firstCompletion.complete(Map.of());

            try {
                assertEquals(ResultEnvelope.Status.OK, first.get(1, TimeUnit.SECONDS).status());
                var expired = second.get(1, TimeUnit.SECONDS);
                assertEquals(ResultEnvelope.Status.TIMED_OUT, expired.status());
                assertEquals("DEADLINE_EXCEEDED", expired.error().code());
                assertEquals(1, calls.get());
            } finally {
                releaseScheduler.countDown();
            }
        }
    }

    @Test
    void adapterCompletionAndPublicContinuationsNeverRunOnTheMinecraftThread() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var descriptor = capability("test.completion-boundary", Availability.AVAILABLE,
                    Set.of(PermissionClass.OBSERVE), SideEffect.NONE, null,
                    Map.of("type", "object"), Map.of("type", "object"),
                    new RateLimit(100, 1000, 100));
            var nativeCompletion = new CompletableFuture<Map<String, Object>>();
            var projectionThread = new java.util.concurrent.atomic.AtomicReference<String>();
            var continuationThread = new java.util.concurrent.atomic.AtomicReference<String>();
            runtime.registerAdapter(adapter(descriptor, context -> nativeCompletion));
            var result = runtime.invoke(request(runtime, descriptor.id(), Map.of()));
            var observed = result.thenApply(value -> {
                continuationThread.set(Thread.currentThread().getName());
                return value;
            });
            var hostileOutput = new java.util.AbstractMap<String, Object>() {
                @Override
                public Set<Entry<String, Object>> entrySet() {
                    projectionThread.compareAndSet(null, Thread.currentThread().getName());
                    return Set.of();
                }
            };
            var gameThread = new Thread(() -> nativeCompletion.complete(hostileOutput), "minecraft-client-main");
            gameThread.start();
            gameThread.join(1000);

            var terminal = observed.get(1, TimeUnit.SECONDS);
            assertEquals(ResultEnvelope.Status.OK, terminal.status(), terminal::toString);
            assertTrue(projectionThread.get().startsWith("lodestone-runtime-completion-"));
            assertTrue(continuationThread.get().startsWith("lodestone-runtime-completion-"));
        }
    }

    @RepeatedTest(20)
    void structuredScopeReportsTheChildThatTriggeredFailFast() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var parent = capability("lodestone.test.causal-child", Availability.AVAILABLE,
                    Set.of(PermissionClass.OBSERVE), SideEffect.NONE, null);
            var childA = capability("minecraft.test.causal-child-a", Availability.AVAILABLE,
                    Set.of(PermissionClass.OBSERVE), SideEffect.NONE, null);
            var childB = capability("minecraft.test.causal-child-b", Availability.AVAILABLE,
                    Set.of(PermissionClass.OBSERVE), SideEffect.NONE, null);
            var aStarted = new java.util.concurrent.CountDownLatch(1);
            var aCompletion = new CompletableFuture<Map<String, Object>>();
            var aToken = new java.util.concurrent.atomic.AtomicReference<dev.lodestone.adapter.CancellationToken>();
            runtime.registerAdapter(adapter(List.of(parent, childA, childB), Map.of(
                    parent.id(), context -> {
                        var invoker = InvocationAttributes.requireDelegatedInvoker(context);
                        invoker.invoke("a", childA.id(), "1.0", Map.of());
                        try {
                            if (!aStarted.await(1, TimeUnit.SECONDS)) {
                                return CompletableFuture.failedFuture(new IllegalStateException("child A did not start"));
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            return CompletableFuture.failedFuture(interrupted);
                        }
                        invoker.invoke("b", childB.id(), "1.0", Map.of());
                        return CompletableFuture.completedFuture(Map.of());
                    },
                    childA.id(), context -> {
                        aToken.set(context.cancellation());
                        aStarted.countDown();
                        return aCompletion;
                    },
                    childB.id(), context -> CompletableFuture.failedFuture(
                            new IllegalStateException("causal-child-b")))));

            var result = runtime.invoke(request(runtime, parent.id(), Map.of()));
            var cancellationDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while ((aToken.get() == null || !aToken.get().isCancelled())
                    && System.nanoTime() < cancellationDeadline) {
                Thread.onSpinWait();
            }
            assertTrue(aToken.get().isCancelled());
            aCompletion.completeExceptionally(new dev.lodestone.adapter.CancellationToken.CancellationException());

            var terminal = result.get(1, TimeUnit.SECONDS);
            assertEquals("ADAPTER_FAILURE", terminal.error().code(), terminal::toString);
            assertEquals("causal-child-b", terminal.error().message(), terminal::toString);
        }
    }

    @RepeatedTest(20)
    void structuredScopePrefersCausalChildWhenParentCooperativelyCancels() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var parent = capability("lodestone.test.cooperative-parent-cancellation", Availability.AVAILABLE,
                    Set.of(PermissionClass.OBSERVE), SideEffect.NONE, null);
            var child = capability("minecraft.test.cooperative-parent-cancellation", Availability.AVAILABLE,
                    Set.of(PermissionClass.OBSERVE), SideEffect.NONE, null);
            var parentCompletion = new CompletableFuture<Map<String, Object>>();
            var parentToken = new java.util.concurrent.atomic.AtomicReference<dev.lodestone.adapter.CancellationToken>();
            var parentStarted = new java.util.concurrent.CountDownLatch(1);
            runtime.registerAdapter(adapter(List.of(parent, child), Map.of(
                    parent.id(), context -> {
                        parentToken.set(context.cancellation());
                        parentStarted.countDown();
                        InvocationAttributes.requireDelegatedInvoker(context)
                                .invoke("causal", child.id(), "1.0", Map.of());
                        return parentCompletion;
                    },
                    child.id(), context -> CompletableFuture.failedFuture(
                            new IllegalStateException("causal-child-failure")))));

            var result = runtime.invoke(request(runtime, parent.id(), Map.of()));
            assertTrue(parentStarted.await(5, TimeUnit.SECONDS));
            var cancellationDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!parentToken.get().isCancelled()
                    && System.nanoTime() < cancellationDeadline) {
                Thread.onSpinWait();
            }
            assertTrue(parentToken.get().isCancelled());
            parentCompletion.completeExceptionally(
                    new dev.lodestone.adapter.CancellationToken.CancellationException());

            var terminal = result.get(1, TimeUnit.SECONDS);
            assertEquals("ADAPTER_FAILURE", terminal.error().code(), terminal::toString);
            assertEquals("causal-child-failure", terminal.error().message(), terminal::toString);
        }
    }

    @Test
    void sideEffectFreeWorkflowCannotDelegateAMutation() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.fromCsv("control-player"))) {
            var parent = capability("lodestone.test.side-effect-escalation", Availability.AVAILABLE,
                    Set.of(PermissionClass.CONTROL_PLAYER), SideEffect.NONE, null);
            var child = capability("minecraft.test.side-effect-escalation", Availability.AVAILABLE,
                    Set.of(PermissionClass.CONTROL_PLAYER), SideEffect.CONTROL_PLAYER, null);
            runtime.registerAdapter(adapter(List.of(parent, child), Map.of(
                    parent.id(), context -> InvocationAttributes.requireDelegatedInvoker(context)
                            .invoke("mutate", child.id(), "1.0", Map.of()).thenApply(ignored -> Map.of()),
                    child.id(), context -> CompletableFuture.completedFuture(Map.of()))));

            var terminal = runtime.invoke(request(runtime, parent.id(), Map.of())).get(1, TimeUnit.SECONDS);
            assertEquals("DELEGATION_SIDE_EFFECT_ESCALATION", terminal.error().code());
        }
    }

    @Test
    void delegationUsesTheParentDescriptorAdmittedBeforeRefresh() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.fromCsv("observe,control-player"))) {
            var original = capability("lodestone.test.refresh-escalation", Availability.AVAILABLE,
                    Set.of(PermissionClass.OBSERVE), SideEffect.NONE, null);
            var widened = withPermissionsAndSideEffect(original,
                    Set.of(PermissionClass.OBSERVE, PermissionClass.CONTROL_PLAYER), SideEffect.CONTROL_PLAYER);
            var child = capability("minecraft.test.refresh-escalation", Availability.AVAILABLE,
                    Set.of(PermissionClass.CONTROL_PLAYER), SideEffect.CONTROL_PLAYER, null);
            var current = new java.util.concurrent.atomic.AtomicReference<>(original);
            var parentStarted = new java.util.concurrent.CountDownLatch(1);
            var releaseParent = new java.util.concurrent.CountDownLatch(1);
            CapabilityHandler parentHandler = context -> {
                parentStarted.countDown();
                try {
                    releaseParent.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return CompletableFuture.failedFuture(interrupted);
                }
                return InvocationAttributes.requireDelegatedInvoker(context)
                        .invoke("mutate", child.id(), "1.0", Map.of()).thenApply(ignored -> Map.of());
            };
            var adapterDescriptor = new AdapterDescriptor("test.mutable-parent", "1.0.0",
                    "minecraft", "test", "test", Environment.REMOTE);
            var mutableParentAdapter = new LodestoneAdapter() {
                @Override
                public AdapterDescriptor descriptor() {
                    return adapterDescriptor;
                }

                @Override
                public CapabilityManifest manifest() {
                    return new CapabilityManifest(adapterDescriptor, List.of(current.get()));
                }

                @Override
                public Map<String, CapabilityHandler> handlers() {
                    return Map.of(original.id(), parentHandler);
                }
            };
            runtime.registerAdapter(mutableParentAdapter);
            runtime.registerAdapter(adapter(child, context -> CompletableFuture.completedFuture(Map.of())));

            var result = runtime.invoke(request(runtime, original.id(), Map.of()));
            assertTrue(parentStarted.await(1, TimeUnit.SECONDS));
            current.set(widened);
            runtime.refreshAdapter(mutableParentAdapter);
            releaseParent.countDown();

            assertEquals("DELEGATION_PERMISSION_ESCALATION",
                    result.get(1, TimeUnit.SECONDS).error().code());
        }
    }

    @Test
    void delegatedContainmentUsesTheExactChildDescriptorSelectedForDispatch() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.fromCsv("observe,control-player"))) {
            var parent = capability("lodestone.test.child-refresh-parent", Availability.AVAILABLE,
                    Set.of(PermissionClass.OBSERVE), SideEffect.NONE, null);
            var originalChild = capability("minecraft.test.child-refresh-target", Availability.AVAILABLE,
                    Set.of(PermissionClass.OBSERVE), SideEffect.NONE, null);
            var widenedChild = withPermissionsAndSideEffect(originalChild,
                    Set.of(PermissionClass.CONTROL_PLAYER), SideEffect.CONTROL_PLAYER);
            var childDescriptor = new java.util.concurrent.atomic.AtomicReference<>(originalChild);
            var childCalls = new AtomicInteger();
            var childAdapterRef = new java.util.concurrent.atomic.AtomicReference<LodestoneAdapter>();
            var adapterDescriptor = new AdapterDescriptor("test.mutable-child", "1.0.0",
                    "minecraft", "test", "test", Environment.REMOTE);
            var mutableChildAdapter = new LodestoneAdapter() {
                @Override
                public AdapterDescriptor descriptor() {
                    return adapterDescriptor;
                }

                @Override
                public CapabilityManifest manifest() {
                    return new CapabilityManifest(adapterDescriptor, List.of(childDescriptor.get()));
                }

                @Override
                public Map<String, CapabilityHandler> handlers() {
                    return Map.of(originalChild.id(), context -> {
                        childCalls.incrementAndGet();
                        return CompletableFuture.completedFuture(Map.of());
                    });
                }
            };
            childAdapterRef.set(mutableChildAdapter);
            CapabilityHandler parentHandler = context -> {
                var refreshed = new AtomicBoolean();
                Map<String, Object> refreshDuringAdmission = new java.util.AbstractMap<>() {
                    @Override
                    public Set<Entry<String, Object>> entrySet() {
                        if (refreshed.compareAndSet(false, true)) {
                            childDescriptor.set(widenedChild);
                            runtime.refreshAdapter(childAdapterRef.get());
                        }
                        return Set.of();
                    }
                };
                return InvocationAttributes.requireDelegatedInvoker(context)
                        .invoke("read", originalChild.id(), "1.0", refreshDuringAdmission)
                        .thenApply(ignored -> Map.of());
            };
            runtime.registerAdapter(adapter(parent, parentHandler));
            runtime.registerAdapter(mutableChildAdapter);

            var result = runtime.invoke(request(runtime, parent.id(), Map.of())).get(1, TimeUnit.SECONDS);

            assertEquals("DELEGATION_PERMISSION_ESCALATION", result.error().code());
            assertEquals(0, childCalls.get());
        }
    }

    @Test
    void committedProjectionFailureIsIndeterminateAndCannotStrandTheResult() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.fromCsv("modify-world"))) {
            var descriptor = capability("minecraft.test.hostile-output", Availability.AVAILABLE,
                    Set.of(PermissionClass.MODIFY_WORLD), SideEffect.MODIFY_WORLD, null,
                    Map.of("type", "object"), Map.of("type", "object"),
                    new RateLimit(100, 1000, 100));
            runtime.registerAdapter(adapter(descriptor, context -> {
                context.cancellation().commitMutation();
                return CompletableFuture.completedFuture(new java.util.AbstractMap<>() {
                    @Override
                    public Set<Entry<String, Object>> entrySet() {
                        throw new IllegalStateException("projection exploded");
                    }
                });
            }));

            var terminal = runtime.invoke(request(runtime, descriptor.id(), Map.of())).get(1, TimeUnit.SECONDS);
            assertEquals("OUTCOME_INDETERMINATE", terminal.error().code());
            var retry = runtime.invoke(new RequestEnvelope("1.0", "hostile-output-retry", runtime.sessionId(),
                    descriptor.id(), "1.0", Map.of(), null, null, false)).get(1, TimeUnit.SECONDS);
            assertEquals("CAPABILITY_QUARANTINED", retry.error().code());
        }
    }

    @Test
    void concurrentIdempotencyAdmissionNeverExceedsItsHardBound() throws Exception {
        var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly(), 8);
        var workers = java.util.concurrent.Executors.newFixedThreadPool(32);
        try {
            var descriptor = capability("test.idempotency-hard-bound", Availability.AVAILABLE,
                    Set.of(PermissionClass.OBSERVE), SideEffect.NONE, null,
                    Map.of("type", "object"), Map.of("type", "object"),
                    new RateLimit(100, 1000, 100));
            var pending = new CompletableFuture<Map<String, Object>>();
            runtime.registerAdapter(adapter(descriptor, context -> pending));
            var start = new java.util.concurrent.CountDownLatch(1);
            var admissions = new java.util.ArrayList<java.util.concurrent.Future<CompletableFuture<ResultEnvelope>>>();
            for (var index = 0; index < 32; index++) {
                var key = "key-" + index;
                admissions.add(workers.submit(() -> {
                    start.await();
                    return runtime.invoke(new RequestEnvelope("1.0", "request-" + key, runtime.sessionId(),
                            descriptor.id(), "1.0", Map.of(), System.currentTimeMillis() + 10_000, key, false));
                }));
            }
            start.countDown();
            for (var admission : admissions) {
                admission.get(5, TimeUnit.SECONDS);
            }
            var idempotencyField = LodestoneRuntime.class.getDeclaredField("idempotency");
            idempotencyField.setAccessible(true);
            var cache = (Map<?, ?>) idempotencyField.get(runtime);
            assertEquals(8, cache.size());
        } finally {
            runtime.close();
            workers.shutdownNow();
        }
    }

    @Test
    void unexpiredIdempotencyEntriesAreNeverEvictedAtCapacity() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.fromCsv("modify-world"), 2)) {
            var descriptor = capability("minecraft.test.idempotency-retention", Availability.AVAILABLE,
                    Set.of(PermissionClass.MODIFY_WORLD), SideEffect.MODIFY_WORLD, null,
                    Map.of("type", "object"), Map.of("type", "object"),
                    new RateLimit(100, 1000, 100));
            var calls = new AtomicInteger();
            runtime.registerAdapter(adapter(descriptor, context -> {
                calls.incrementAndGet();
                context.cancellation().commitMutation();
                return CompletableFuture.completedFuture(Map.of("value", context.request().input().get("value")));
            }));

            var first = runtime.invoke(new RequestEnvelope("1.0", "retention-a", runtime.sessionId(),
                    descriptor.id(), "1.0", Map.of("value", "a"), null, "retention-a", false))
                    .get(1, TimeUnit.SECONDS);
            var second = runtime.invoke(new RequestEnvelope("1.0", "retention-b", runtime.sessionId(),
                    descriptor.id(), "1.0", Map.of("value", "b"), null, "retention-b", false))
                    .get(1, TimeUnit.SECONDS);
            var rejected = runtime.invoke(new RequestEnvelope("1.0", "retention-c", runtime.sessionId(),
                    descriptor.id(), "1.0", Map.of("value", "c"), null, "retention-c", false))
                    .get(1, TimeUnit.SECONDS);
            var replay = runtime.invoke(new RequestEnvelope("1.0", "retention-a-replay", runtime.sessionId(),
                    descriptor.id(), "1.0", Map.of("value", "a"), null, "retention-a", false))
                    .get(1, TimeUnit.SECONDS);

            assertEquals(ResultEnvelope.Status.OK, first.status());
            assertEquals(ResultEnvelope.Status.OK, second.status());
            assertEquals("IDEMPOTENCY_CAPACITY_EXCEEDED", rejected.error().code());
            assertEquals(ResultEnvelope.Status.OK, replay.status());
            assertEquals("a", replay.output().get("value"));
            assertEquals(2, calls.get());
        }
    }

    @Test
    void explicitEmptyOrNullCallerPolicyDoesNotRestrictInvocation() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var descriptor = capability("test.fail-closed-policy", Availability.AVAILABLE,
                    Set.of(PermissionClass.OBSERVE), SideEffect.NONE, null);
            runtime.registerAdapter(adapter(descriptor, context -> CompletableFuture.completedFuture(Map.of())));

            assertTrue(new AuthorizationPolicy(Set.of()).allows(descriptor));
            var empty = runtime.invoke(request(runtime, descriptor.id(), Map.of()), "caller-empty",
                    new AuthorizationPolicy(Set.of())).get(1, TimeUnit.SECONDS);
            var absent = runtime.invoke(request(runtime, descriptor.id(), Map.of()), "caller-null", null)
                    .get(1, TimeUnit.SECONDS);
            assertEquals(ResultEnvelope.Status.OK, empty.status());
            assertEquals(ResultEnvelope.Status.OK, absent.status());
        }
    }

    @Test
    void invokeRacingCloseAlwaysReturnsATerminalResult() throws Exception {
        var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly());
        var workers = java.util.concurrent.Executors.newFixedThreadPool(16);
        try {
            var descriptor = capability("test.close-admission-race", Availability.AVAILABLE,
                    Set.of(PermissionClass.OBSERVE), SideEffect.NONE, null,
                    Map.of("type", "object"), Map.of("type", "object"),
                    new RateLimit(1000, 1000, 1000));
            runtime.registerAdapter(adapter(descriptor,
                    context -> CompletableFuture.completedFuture(Map.of())));
            var start = new java.util.concurrent.CountDownLatch(1);
            var invocations = new java.util.ArrayList<java.util.concurrent.Future<CompletableFuture<ResultEnvelope>>>();
            for (var index = 0; index < 256; index++) {
                var requestId = "close-race-" + index;
                invocations.add(workers.submit(() -> {
                    start.await();
                    return runtime.invoke(new RequestEnvelope("1.0", requestId, runtime.sessionId(),
                            descriptor.id(), "1.0", Map.of(), System.currentTimeMillis() + 2000,
                            null, false));
                }));
            }
            var closing = workers.submit(() -> {
                start.await();
                runtime.close();
                return null;
            });
            start.countDown();
            for (var invocation : invocations) {
                var terminal = invocation.get(2, TimeUnit.SECONDS).get(2, TimeUnit.SECONDS);
                assertTrue(terminal.status() == ResultEnvelope.Status.OK
                        || terminal.status() == ResultEnvelope.Status.CANCELLED
                        || terminal.status() == ResultEnvelope.Status.ERROR
                        || terminal.status() == ResultEnvelope.Status.TIMED_OUT);
            }
            closing.get(2, TimeUnit.SECONDS);
        } finally {
            runtime.close();
            workers.shutdownNow();
        }
    }

    @Test
    void unmarkedLodestoneCapabilityNeverReceivesADelegatedInvoker() throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly())) {
            var marked = capability("lodestone.test.unmarked", Availability.AVAILABLE,
                    Set.of(PermissionClass.OBSERVE), SideEffect.NONE, null);
            var unmarked = withExactFeatureFlags(marked, Set.of());
            runtime.registerAdapter(adapter(unmarked, context -> {
                InvocationAttributes.requireDelegatedInvoker(context);
                return CompletableFuture.completedFuture(Map.of());
            }));

            var terminal = runtime.invoke(request(runtime, unmarked.id(), Map.of())).get(1, TimeUnit.SECONDS);
            assertEquals("ADAPTER_FAILURE", terminal.error().code());
            assertTrue(terminal.error().message().contains("delegated invoker"));
        }
    }

    private static RequestEnvelope request(LodestoneRuntime runtime, String capability, Map<String, Object> input) {
        return new RequestEnvelope("1.0", "request", runtime.sessionId(), capability, "1.0", input, null, null, false);
    }

    private static LodestoneAdapter adapter(CapabilityDescriptor descriptor, CapabilityHandler handler) {
        return adapter(List.of(descriptor), Map.of(descriptor.id(), handler));
    }

    private static LodestoneAdapter adapter(List<CapabilityDescriptor> descriptors,
                                             Map<String, CapabilityHandler> handlers) {
        var adapterDescriptor = new AdapterDescriptor("test.adapter", "1.0.0", "minecraft", "test", "test", Environment.REMOTE);
        return new LodestoneAdapter() {
            @Override
            public AdapterDescriptor descriptor() {
                return adapterDescriptor;
            }

            @Override
            public CapabilityManifest manifest() {
                return new CapabilityManifest(adapterDescriptor, descriptors);
            }

            @Override
            public Map<String, CapabilityHandler> handlers() {
                return handlers;
            }
        };
    }

    /**
     * Like adapter(...), but also overrides LodestoneAdapter#reconcileSession() so tests can
     * simulate a native adapter's quiesce outcome for minecraft.session.reconcile - the plain
     * adapter(...) helper above leaves reconcileSession() at its default ("quiesced": false),
     * which is only useful for proving reconcile correctly refuses to clear anything.
     */
    private static LodestoneAdapter reconcilingAdapter(List<CapabilityDescriptor> descriptors,
                                                         Map<String, CapabilityHandler> handlers,
                                                         java.util.function.Supplier<java.util.concurrent.CompletionStage<Map<String, Object>>> reconcileSession) {
        var adapterDescriptor = new AdapterDescriptor("test.reconciling-adapter", "1.0.0", "minecraft", "test", "test",
                Environment.REMOTE);
        return new LodestoneAdapter() {
            @Override
            public AdapterDescriptor descriptor() {
                return adapterDescriptor;
            }

            @Override
            public CapabilityManifest manifest() {
                return new CapabilityManifest(adapterDescriptor, descriptors);
            }

            @Override
            public Map<String, CapabilityHandler> handlers() {
                return handlers;
            }

            @Override
            public java.util.concurrent.CompletionStage<Map<String, Object>> reconcileSession() {
                return reconcileSession.get();
            }
        };
    }

    private static CapabilityDescriptor capability(String id, Availability availability,
                                                    Set<PermissionClass> permissions, SideEffect sideEffect,
                                                    AvailabilityReason reason) {
        return capability(id, availability, permissions, sideEffect, reason,
                Map.of("type", "object"), Map.of("type", "object"), new RateLimit(10, 1000, 2));
    }

    private static CapabilityDescriptor capability(String id, Availability availability,
                                                    Set<PermissionClass> permissions, SideEffect sideEffect,
                                                    AvailabilityReason reason, Map<String, Object> inputSchema,
                                                    Map<String, Object> outputSchema, RateLimit rateLimit) {
        return new CapabilityDescriptor(id, CapabilityKind.ACTION, "1.0", Stability.STABLE, availability, reason,
                "test.adapter", "1.0.0", "minecraft", "test", "test", Environment.REMOTE,
                inputSchema, outputSchema, Map.of(), permissions, sideEffect,
                sideEffect == SideEffect.NONE ? Idempotency.IDEMPOTENT : Idempotency.NON_IDEMPOTENT,
                new CapabilityPrerequisites(false, false, false, false), "runtime", rateLimit,
                1000, true, new DeliveryGuarantees("request-order", "at-most-once", 1), "Test capability",
                id.startsWith("lodestone.") && !id.startsWith("lodestone.system.")
                        ? Set.of("delegates-native") : Set.of());
    }

    private static CapabilityDescriptor withFeatureFlags(CapabilityDescriptor capability, Set<String> featureFlags) {
        var combinedFlags = new java.util.HashSet<>(capability.featureFlags());
        combinedFlags.addAll(featureFlags);
        return new CapabilityDescriptor(capability.id(), capability.kind(), capability.version(), capability.stability(),
                capability.availability(), capability.reason(), capability.adapterId(), capability.adapterVersion(),
                capability.gameEdition(), capability.gameVersion(), capability.loader(), capability.environment(),
                capability.inputSchema(), capability.outputSchema(), capability.eventSchema(), capability.permissions(),
                capability.sideEffect(), capability.idempotency(), capability.prerequisites(), capability.nativeThread(),
                capability.rateLimit(), capability.timeoutMs(), capability.cancellable(), capability.delivery(),
                capability.documentation(), combinedFlags);
    }

    private static CapabilityDescriptor withPermissionsAndSideEffect(CapabilityDescriptor capability,
                                                                      Set<PermissionClass> permissions,
                                                                      SideEffect sideEffect) {
        return new CapabilityDescriptor(capability.id(), capability.kind(), capability.version(), capability.stability(),
                capability.availability(), capability.reason(), capability.adapterId(), capability.adapterVersion(),
                capability.gameEdition(), capability.gameVersion(), capability.loader(), capability.environment(),
                capability.inputSchema(), capability.outputSchema(), capability.eventSchema(), permissions,
                sideEffect, sideEffect == SideEffect.NONE ? Idempotency.IDEMPOTENT : Idempotency.NON_IDEMPOTENT,
                capability.prerequisites(), capability.nativeThread(), capability.rateLimit(), capability.timeoutMs(),
                capability.cancellable(), capability.delivery(), capability.documentation(), capability.featureFlags());
    }

    private static CapabilityDescriptor withExactFeatureFlags(CapabilityDescriptor capability,
                                                               Set<String> featureFlags) {
        return new CapabilityDescriptor(capability.id(), capability.kind(), capability.version(), capability.stability(),
                capability.availability(), capability.reason(), capability.adapterId(), capability.adapterVersion(),
                capability.gameEdition(), capability.gameVersion(), capability.loader(), capability.environment(),
                capability.inputSchema(), capability.outputSchema(), capability.eventSchema(), capability.permissions(),
                capability.sideEffect(), capability.idempotency(), capability.prerequisites(), capability.nativeThread(),
                capability.rateLimit(), capability.timeoutMs(), capability.cancellable(), capability.delivery(),
                capability.documentation(), featureFlags);
    }
}
