// SPDX-License-Identifier: MIT
package dev.lodestone.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.lodestone.adapter.AdapterContext;
import dev.lodestone.adapter.AdapterHealth;
import dev.lodestone.adapter.CapabilityHandler;
import dev.lodestone.adapter.CancellationToken;
import dev.lodestone.adapter.DelegatedInvoker;
import dev.lodestone.adapter.DelegatedInvocationException;
import dev.lodestone.adapter.InvocationContext;
import dev.lodestone.adapter.InvocationAttributes;
import dev.lodestone.adapter.LodestoneAdapter;
import dev.lodestone.protocol.AdapterDescriptor;
import dev.lodestone.protocol.Availability;
import dev.lodestone.protocol.CapabilityKind;
import dev.lodestone.protocol.CapabilityManifest;
import dev.lodestone.protocol.CapabilityDescriptor;
import dev.lodestone.protocol.CapabilityPrerequisites;
import dev.lodestone.protocol.DeliveryGuarantees;
import dev.lodestone.protocol.Environment;
import dev.lodestone.protocol.Handshake;
import dev.lodestone.protocol.HealthSnapshot;
import dev.lodestone.protocol.Idempotency;
import dev.lodestone.protocol.JsonSupport;
import dev.lodestone.protocol.PermissionClass;
import dev.lodestone.protocol.ProtocolVersion;
import dev.lodestone.protocol.RequestEnvelope;
import dev.lodestone.protocol.RateLimit;
import dev.lodestone.protocol.ResourceDescriptor;
import dev.lodestone.protocol.ResultEnvelope;
import dev.lodestone.protocol.SchemaValidator;
import dev.lodestone.protocol.SideEffect;
import dev.lodestone.protocol.Stability;
import dev.lodestone.protocol.StructuredError;

import java.time.Instant;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

public final class LodestoneRuntime implements AutoCloseable {
    private static final int MAX_DELEGATION_DEPTH = 8;
    private static final int MAX_IDEMPOTENCY_ENTRIES = 4_096;
    private static final long IDEMPOTENCY_TTL_MS = 60 * 60 * 1000L;
    private static final int MAX_AUDIT_RECORDS = 4_096;
    private static final int MAX_INPUT_JSON_BYTES = 1_048_576;
    private static final int MAX_IDEMPOTENCY_FINGERPRINT_BYTES = 65_536;
    private static final int MAX_OUTPUT_JSON_BYTES = 4_194_304;
    private static final AtomicLong COMPLETION_THREAD_ID = new AtomicLong();
    private static final OrderingKey SHARED_MUTATION_ORDER = new OrderingKey("minecraft-mutation", "", "");
    private final String sessionId = UUID.randomUUID().toString();
    private final CapabilityRegistry registry = new CapabilityRegistry();
    private final WorldEditMaskValidationAdapter worldEditMaskValidationAdapter = new WorldEditMaskValidationAdapter();
    private final GeometryAdapter geometryAdapter = new GeometryAdapter();
    private final UiWorkflowAdapter uiWorkflowAdapter = new UiWorkflowAdapter(registry);
    private final FurnitureWorkflowAdapter furnitureWorkflowAdapter = new FurnitureWorkflowAdapter(registry);
    private final BuildingPatternWorkflowAdapter buildingPatternWorkflowAdapter = new BuildingPatternWorkflowAdapter();
    private final EventHub eventHub = new EventHub();
    private final AuthorizationPolicy authorization;
    private final int maxIdempotencyEntries;
    private final ExecutorService executor = createExecutor();
    private final ExecutorService completionExecutor = createCompletionExecutor();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final List<LodestoneAdapter> adapters = new ArrayList<>();
    private final List<AuditRecord> audit = new ArrayList<>();
    private final List<AuditTraceRecord> auditTrace = new ArrayList<>();
    private final Map<String, RuntimeResource> resources = new ConcurrentHashMap<>();
    private final RateLimiter rateLimiter = new RateLimiter();
    private final Map<IdempotencyCacheKey, IdempotencyEntry> idempotency = new ConcurrentHashMap<>();
    private final Map<OrderingKey, CompletableFuture<Void>> invocationTails = new ConcurrentHashMap<>();
    private final Map<OrderingKey, Quarantine> quarantined = new ConcurrentHashMap<>();
    private final Map<InvocationFuture, ActiveInvocation> activeInvocations = new ConcurrentHashMap<>();
    private volatile State state = State.RUNNING;

    public LodestoneRuntime(AuthorizationPolicy authorization) {
        this(authorization, MAX_IDEMPOTENCY_ENTRIES);
    }

    LodestoneRuntime(AuthorizationPolicy authorization, int maxIdempotencyEntries) {
        if (maxIdempotencyEntries < 1) {
            throw new IllegalArgumentException("maxIdempotencyEntries must be positive");
        }
        this.authorization = authorization == null ? AuthorizationPolicy.observeOnly() : authorization;
        this.maxIdempotencyEntries = maxIdempotencyEntries;
        registry.register(new SystemAdapter());
        registry.register(worldEditMaskValidationAdapter);
        registry.register(geometryAdapter);
        registry.register(uiWorkflowAdapter);
        registry.register(furnitureWorkflowAdapter);
        registry.register(buildingPatternWorkflowAdapter);
        putResource("lodestone://capabilities/manifest", "Capability manifest",
                "Negotiated Lodestone capability descriptors.", "application/json", () -> json(Map.of(
                "protocolVersion", ProtocolVersion.CURRENT,
                "sessionId", sessionId,
                "capabilities", registry.list())));
        putResource("lodestone://health", "Runtime health", "Current runtime and adapter health.",
                "application/json", () -> json(health()));
        putResource("lodestone://audit", "Caller audit", "Caller-scoped capability audit records.",
                "application/json", () -> json(Map.of("records", audit())));
        putResource("lodestone://audit/trace", "Caller delegation trace",
                "Caller-scoped capability delegation and transport trace records.",
                "application/json", () -> json(Map.of("records", auditTrace())));
        putBundledJsonResource("lodestone://vibecraft/furniture/catalog", "Vibecraft furniture catalog",
                "MIT-licensed furniture reference entries imported verbatim from Vibecraft.",
                "/vibecraft/minecraft_furniture_catalog.json");
        putBundledJsonResource("lodestone://vibecraft/furniture/layouts", "Vibecraft furniture layouts",
                "MIT-licensed coordinate layouts; placement is separately capability-negotiated.",
                "/vibecraft/minecraft_furniture_layouts.json");
        putBundledJsonResource("lodestone://vibecraft/building/patterns", "Vibecraft building patterns",
                "Metadata and construction guidance only; no structured placement claim.",
                "/vibecraft/building_patterns_complete.json");
        putBundledJsonResource("lodestone://vibecraft/terrain/patterns", "Vibecraft terrain patterns",
                "Metadata and manual terrain guidance only; no structured placement claim.",
                "/vibecraft/terrain_patterns_complete.json");
        putBundledJsonResource("lodestone://vibecraft/building/templates", "Vibecraft building templates",
                "Prompt and command-sequence guidance; not an executable compiler.",
                "/vibecraft/building_templates.json");
        putBundledJsonResource("lodestone://vibecraft/provenance", "Vibecraft resource provenance",
                "Source revisions, license, file inventory, and semantic limitations.",
                "/vibecraft/provenance.json");
    }

    public String sessionId() {
        return sessionId;
    }

    public synchronized void registerAdapter(LodestoneAdapter adapter) {
        requireRunning();
        if (adapter == null) {
            throw new IllegalArgumentException("adapter must not be null");
        }
        try {
            adapter.start(new AdapterContext(sessionId, executor,
                    (event, payload, gameTick) -> eventHub.publish(sessionId, event, payload, gameTick)));
            registry.register(adapter);
            registry.refresh(uiWorkflowAdapter);
            registry.refresh(furnitureWorkflowAdapter);
            adapters.add(adapter);
        } catch (RuntimeException | Error failure) {
            try {
                adapter.close();
            } catch (Throwable closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    public synchronized void refreshAdapter(LodestoneAdapter adapter) {
        requireRunning();
        if (!adapters.contains(adapter)) {
            throw new IllegalArgumentException("adapter is not registered");
        }
        registry.refresh(adapter);
        registry.refresh(uiWorkflowAdapter);
        registry.refresh(furnitureWorkflowAdapter);
    }

    public Handshake handshake() {
        var descriptor = adapters.isEmpty() ? runtimeDescriptor() : adapters.get(0).descriptor();
        return new Handshake(ProtocolVersion.CURRENT, sessionId, descriptor, registry.list(), "lodestone-runtime");
    }

    public List<CapabilityDescriptor> capabilities(String query) {
        return query == null || query.isBlank() ? registry.list() : registry.search(query);
    }

    public CompletableFuture<ResultEnvelope> invoke(RequestEnvelope request) {
        return invokeInternal(request, request.sessionId(), authorization, null, null, List.of());
    }

    /**
     * Invoke a capability on behalf of a transport session. The request session remains the
     * runtime identity used by native adapters; the caller key isolates transport ordering and
     * rate limits so one MCP client cannot consume another client's budget or queue.
     */
    public CompletableFuture<ResultEnvelope> invoke(RequestEnvelope request, String callerSessionId) {
        return invokeInternal(request, callerSessionId, authorization, null, null, List.of());
    }

    /**
     * Invoke with a caller-specific permission ceiling. The runtime policy remains the upper bound;
     * transports may only narrow it, never grant permissions the process did not enable.
     */
    public CompletableFuture<ResultEnvelope> invoke(RequestEnvelope request, String callerSessionId,
                                                     AuthorizationPolicy callerAuthorization) {
        var effectiveAuthorization = authorization.intersect(callerAuthorization);
        return invokeInternal(request, callerSessionId, effectiveAuthorization, null, null, List.of());
    }

    private InvocationFuture invokeInternal(RequestEnvelope request, String callerSessionId,
                                            AuthorizationPolicy callerAuthorization,
                                            CapabilityDescriptor delegatingParentDescriptor,
                                            RequestCancellationToken parentCancellation,
                                            List<String> parentDelegationPath) {
        var result = new InvocationFuture(completionExecutor);
        var callerKey = callerSessionId == null || callerSessionId.isBlank()
                ? request.sessionId() : callerSessionId.trim();
        var tracePath = new ArrayList<>(parentDelegationPath);
        tracePath.add(request.capability());
        var trace = new InvocationTrace(callerKey, List.copyOf(tracePath));
        if (state != State.RUNNING) {
            return completedError(request, result, trace, "RUNTIME_STOPPING",
                    "runtime is stopping or closed", true);
        }
        if (!sessionId.equals(request.sessionId())) {
            return completedError(request, result, trace, "SESSION_NOT_FOUND", "session does not belong to this runtime", false);
        }
        var entry = registry.get(request.capability());
        if (entry == null) {
            return completedError(request, result, trace, "CAPABILITY_NOT_FOUND", "capability is not registered", false);
        }
        var descriptor = entry.descriptor();
        if (delegatingParentDescriptor != null
                && !delegatingParentDescriptor.permissions().containsAll(descriptor.permissions())) {
            return completedError(request, result, trace, "DELEGATION_PERMISSION_ESCALATION",
                    "workflow permissions must include every delegated child permission", false);
        }
        if (delegatingParentDescriptor != null && delegatingParentDescriptor.sideEffect() == SideEffect.NONE
                && descriptor.sideEffect() != SideEffect.NONE) {
            return completedError(request, result, trace, "DELEGATION_SIDE_EFFECT_ESCALATION",
                    "side-effect-free workflows cannot delegate mutating native capabilities", false);
        }
        if (request.capabilityVersion() != null && !request.capabilityVersion().equals(descriptor.version())) {
            return completedError(request, result, trace, "CAPABILITY_VERSION_UNSUPPORTED",
                    "requested capability version is not negotiated", false);
        }
        if (descriptor.availability() == Availability.UNAVAILABLE
                || descriptor.availability() == Availability.DEGRADED) {
            return completedError(request, result, trace, "CAPABILITY_UNAVAILABLE",
                    descriptor.reason().message(), false);
        }
        if (entry.handler() == null) {
            return completedError(request, result, trace, "CAPABILITY_UNAVAILABLE",
                    descriptor.reason() == null ? "capability has no active adapter handler" : descriptor.reason().message(), false);
        }
        if (!callerAuthorization.allows(descriptor)) {
            return completedError(request, result, trace, "AUTHORIZATION_DENIED",
                    "capability permission is not enabled for this session", false);
        }
        final JsonMapSnapshot admittedInput;
        try {
            admittedInput = snapshotJsonMap(request.input(), MAX_INPUT_JSON_BYTES);
        } catch (Throwable invalidInput) {
            return completedError(request, result, trace, "INVALID_INPUT",
                    "capability input must be a bounded JSON object", false);
        }
        var input = admittedInput.value();
        var inputDryRun = Boolean.TRUE.equals(input.get("dryRun"));
        var effectiveDryRun = request.dryRun() || inputDryRun;
        if (effectiveDryRun && !descriptor.featureFlags().contains("dry-run")) {
            return completedError(request, result, trace, "DRY_RUN_UNSUPPORTED",
                    "capability does not declare dry-run support", false);
        }
        if (request.dryRun() && !inputDryRun) {
            var effectiveInput = new LinkedHashMap<>(input);
            effectiveInput.put("dryRun", true);
            input = Map.copyOf(effectiveInput);
        }
        final JsonMapSnapshot effectiveInput;
        try {
            effectiveInput = input == admittedInput.value()
                    ? admittedInput : snapshotJsonMap(input, MAX_INPUT_JSON_BYTES);
        } catch (Throwable invalidInput) {
            return completedError(request, result, trace, "INVALID_INPUT",
                    "capability input must be a bounded JSON object", false);
        }
        var effectiveRequest = new RequestEnvelope(
                request.protocolVersion(), request.requestId(), request.sessionId(), request.capability(),
                request.capabilityVersion(), effectiveInput.value(), request.deadlineEpochMs(),
                request.idempotencyKey(), effectiveDryRun);
        var inputErrors = SchemaValidator.validate(descriptor.inputSchema(), effectiveRequest.input());
        if (!inputErrors.isEmpty()) {
            return completedError(request, result, trace, "INVALID_INPUT", "capability input failed schema validation", false,
                    Map.of("violations", inputErrors));
        }
        var deadline = request.deadlineEpochMs() == null
                ? System.currentTimeMillis() + descriptor.timeoutMs()
                : request.deadlineEpochMs();
        var remainingMs = deadline - System.currentTimeMillis();
        if (remainingMs <= 0) {
            return completedError(request, result, trace, "DEADLINE_EXCEEDED", "request deadline has passed", true,
                    ResultEnvelope.Status.TIMED_OUT);
        }
        var deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(remainingMs);
        var invocationRequest = effectiveRequest.deadlineEpochMs() == null
                ? new RequestEnvelope(effectiveRequest.protocolVersion(), effectiveRequest.requestId(),
                effectiveRequest.sessionId(), effectiveRequest.capability(), effectiveRequest.capabilityVersion(),
                effectiveRequest.input(), deadline, effectiveRequest.idempotencyKey(), effectiveRequest.dryRun())
                : effectiveRequest;

        var orderingKey = orderingKey(callerKey, request.capability(), descriptor);
        var idempotencyKey = parentCancellation != null
                && descriptor.sideEffect() == SideEffect.NONE
                && descriptor.idempotency() == Idempotency.IDEMPOTENT
                ? null : request.idempotencyKey();
        IdempotencyCacheKey cacheKey = null;
        String fingerprint = null;
        if (idempotencyKey != null) {
            pruneIdempotency();
            cacheKey = new IdempotencyCacheKey(parentCancellation == null ? "external" : "delegated",
                    callerKey, request.capability(), descriptor.version(), idempotencyKey);
            if (idempotencyKey.length() > 256) {
                return completedError(request, result, trace, "IDEMPOTENCY_KEY_TOO_LARGE",
                        "idempotency key must be at most 256 characters", false);
            }
            fingerprint = effectiveInput.canonicalJson();
            if (effectiveInput.utf8Bytes() > MAX_IDEMPOTENCY_FINGERPRINT_BYTES) {
                return completedError(request, result, trace, "IDEMPOTENCY_INPUT_TOO_LARGE",
                        "idempotent input fingerprint is too large", false);
            }
            var existing = idempotency.get(cacheKey);
            if (existing != null) {
                if (!existing.fingerprint().equals(fingerprint)) {
                    return completedError(request, result, trace, "IDEMPOTENCY_KEY_REUSED",
                            "idempotency key was already used with different input", false);
                }
                return replayIdempotent(request, trace, existing, deadlineNanos);
            }
        }

        if (deadlineNanos - System.nanoTime() <= 0) {
            if (cacheKey != null) {
                var expiredResult = result;
                idempotency.computeIfPresent(cacheKey,
                        (key, entryValue) -> entryValue.result() == expiredResult ? null : entryValue);
            }
            return completedError(request, result, trace, "DEADLINE_EXCEEDED",
                    "request deadline passed during admission", true, ResultEnvelope.Status.TIMED_OUT);
        }
        var quarantine = activeQuarantine(orderingKey, descriptor);
        if (quarantine != null) {
            return completedError(request, result, trace, "CAPABILITY_QUARANTINED",
                    quarantine.outcomeIndeterminate()
                            ? "a previous irreversible invocation has an indeterminate outcome; reconcile state and restart the runtime before retrying"
                            : "a previous invocation exceeded its deadline and is still running; retry after it finishes",
                    !quarantine.outcomeIndeterminate());
        }
        if (!rateLimiter.allow(lengthPrefixedKey(callerKey, request.capability()), descriptor.rateLimit())) {
            return completedError(request, result, trace, "RATE_LIMIT_EXCEEDED",
                    "capability rate limit exceeded", true);
        }

        if (idempotencyKey != null) {
            synchronized (idempotency) {
                var existing = idempotency.get(cacheKey);
                if (existing != null) {
                    if (!existing.fingerprint().equals(fingerprint)) {
                        return completedError(request, result, trace, "IDEMPOTENCY_KEY_REUSED",
                                "idempotency key was already used with different input", false);
                    }
                    return replayIdempotent(request, trace, existing, deadlineNanos);
                }
                if (idempotency.size() >= maxIdempotencyEntries) {
                    return completedError(request, result, trace, "IDEMPOTENCY_CAPACITY_EXCEEDED",
                            "idempotency retention capacity is full; retry after an entry expires", true);
                }
                idempotency.put(cacheKey,
                        new IdempotencyEntry(fingerprint, result, System.currentTimeMillis()));
            }
        }

        var treeSeed = parentCancellation == null
                ? rootDelegationSeed(callerKey, request)
                : parentCancellation.delegationSeed();
        var token = new RequestCancellationToken(parentCancellation, treeSeed);
        var finalCacheKey = cacheKey;
        result.bind(token, () -> {
            if (finalCacheKey != null) {
                idempotency.computeIfPresent(finalCacheKey,
                        (key, entryValue) -> entryValue.result() == result ? null : entryValue);
            }
            recordAudit(request, trace, "cancelled");
        });
        var gate = new CompletableFuture<Void>();
        CompletableFuture<Void> predecessor;
        synchronized (invocationTails) {
            predecessor = invocationTails.getOrDefault(orderingKey, CompletableFuture.completedFuture(null));
            invocationTails.put(orderingKey, gate);
        }
        var active = new ActiveInvocation(request, trace, token);
        final ScheduledFuture<?> timeout;
        synchronized (this) {
            if (state != State.RUNNING) {
                token.requestCancellation();
                gate.complete(null);
                invocationTails.remove(orderingKey, gate);
                if (finalCacheKey != null) {
                    idempotency.computeIfPresent(finalCacheKey,
                            (key, entryValue) -> entryValue.result() == result ? null : entryValue);
                }
                return completedError(request, result, trace, "RUNTIME_STOPPING",
                        "runtime is stopping or closed", true);
            }
            result.attach(active);
            activeInvocations.put(result, active);
            try {
                timeout = scheduler.schedule(() -> {
                boolean publish = false;
                synchronized (active) {
                    if (active.underlyingCompleted() || result.outcomeIsDone()) {
                        return;
                    }
                    token.requestCancellation();
                    token.cancelChildren();
                    var committed = token.mutationCommitted();
                    if (active.started()) {
                        if (committed) {
                            active.markOutcomeIndeterminate();
                            putIndeterminateQuarantine(orderingKey, descriptor, active.ownerId());
                        } else {
                            var runningQuarantine = Quarantine.running(active.ownerId());
                            if (quarantined.putIfAbsent(orderingKey, runningQuarantine) == null) {
                                active.markTransientQuarantinePlaced(runningQuarantine);
                            }
                        }
                    }
                    boolean completed;
                    if (committed) {
                        completed = completeError(result, request.requestId(), ResultEnvelope.Status.ERROR,
                                new StructuredError("OUTCOME_INDETERMINATE",
                                        "capability deadline exceeded after irreversible mutation dispatch; do not retry automatically",
                                        false, Map.of("mutationCommitted", true)));
                        if (completed) {
                            recordAudit(request, trace, "error");
                            publish = true;
                        }
                    } else {
                        completed = completeError(result, request.requestId(), ResultEnvelope.Status.TIMED_OUT,
                                StructuredError.of("DEADLINE_EXCEEDED", "capability deadline exceeded", true));
                        if (completed) {
                            recordAudit(request, trace, "timed-out");
                            publish = true;
                        }
                    }
                }
                if (publish) {
                    result.publish();
                }
                }, Math.max(1L, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (RejectedExecutionException stopping) {
                activeInvocations.remove(result);
                gate.complete(null);
                invocationTails.remove(orderingKey, gate);
                if (finalCacheKey != null) {
                    idempotency.computeIfPresent(finalCacheKey,
                            (key, entryValue) -> entryValue.result() == result ? null : entryValue);
                }
                return completedError(request, result, trace, "RUNTIME_STOPPING",
                        "runtime is stopping or closed", true);
            }
        }
        try {
            CompletionStage<Map<String, Object>> invocation = predecessor
                    .handle((ignored, priorFailure) -> (Void) null)
                    .thenCompose(ignored -> {
                        token.throwIfDeadlinePassed(deadlineNanos);
                        var activeQuarantine = activeQuarantine(orderingKey, descriptor);
                        if (activeQuarantine != null) {
                            throw new CapabilityQuarantinedException(activeQuarantine);
                        }
                        active.markStarted();
                        var attributes = new LinkedHashMap<String, Object>();
                        attributes.put(InvocationAttributes.CALLER_SESSION_ID, callerKey);
                        attributes.put(InvocationAttributes.DELEGATION_PATH, trace.path());
                        if (isWorkflowCapability(request.capability())
                                && descriptor.featureFlags().contains("delegates-native")) {
                            DelegatedInvoker delegatedInvoker = (stepId, capability, capabilityVersion, childInput) ->
                                    invokeDelegated(invocationRequest, descriptor, callerKey, callerAuthorization,
                                            token, trace.path(), stepId, capability, capabilityVersion, childInput);
                            attributes.put(InvocationAttributes.DELEGATED_INVOKER, delegatedInvoker);
                        }
                        var handlerStage = CompletionStageAdapter.invoke(
                                entry.handler(), invocationRequest, token, deadlineNanos,
                                executor, Map.copyOf(attributes));
                        return awaitStructuredCompletion(shiftCompletion(handlerStage), token);
                    });
            invocation.whenComplete((output, failure) -> executeSafely(completionExecutor,
                    () -> finishInvocation(result, active, timeout, gate, orderingKey, descriptor,
                            request, trace, token, deadlineNanos, output, failure)));
        } catch (Throwable failure) {
            synchronized (active) {
                token.requestCancellation();
                token.cancelChildren();
                active.markUnderlyingCompleted();
                activeInvocations.remove(result);
                gate.complete(null);
                invocationTails.remove(orderingKey, gate);
                timeout.cancel(false);
                var committed = token.mutationCommitted();
                if (committed) {
                    active.markOutcomeIndeterminate();
                    putIndeterminateQuarantine(orderingKey, descriptor, active.ownerId());
                } else if (active.transientQuarantinePlaced()) {
                    quarantined.remove(orderingKey, active.transientQuarantine());
                }
                var terminal = delegatedOrRuntimeFailure(request.requestId(), unwrap(failure), committed);
                if (result.completeOutcome(terminal)) {
                    recordAudit(request, trace, auditOutcome(terminal.status()));
                }
            }
            result.markTerminated();
            result.publish();
        }
        return result;
    }

    private <T> CompletionStage<T> shiftCompletion(CompletionStage<T> source) {
        var shifted = new CompletableFuture<T>();
        source.whenComplete((value, failure) -> executeSafely(completionExecutor, () -> {
            if (failure == null) {
                shifted.complete(value);
            } else {
                shifted.completeExceptionally(unwrap(failure));
            }
        }));
        return shifted;
    }

    private void finishInvocation(InvocationFuture result, ActiveInvocation active,
                                  ScheduledFuture<?> timeout, CompletableFuture<Void> gate,
                                  OrderingKey orderingKey, CapabilityDescriptor descriptor,
                                  RequestEnvelope request, InvocationTrace trace,
                                  RequestCancellationToken token, long deadlineNanos,
                                  Map<String, Object> output,
                                  Throwable failure) {
        try {
            synchronized (active) {
                active.markUnderlyingCompleted();
                activeInvocations.remove(result);
                timeout.cancel(false);
                var cause = failure == null ? null : unwrap(failure);
                var safeOutput = output;
                List<String> outputErrors = List.of();
                if (cause == null && !result.outcomeIsDone()) {
                    safeOutput = snapshotJsonMap(output, MAX_OUTPUT_JSON_BYTES).value();
                    outputErrors = SchemaValidator.validate(descriptor.outputSchema(), safeOutput);
                }
                if (!result.outcomeIsDone() && deadlineNanos - System.nanoTime() <= 0) {
                    token.requestCancellation();
                    token.cancelChildren();
                    cause = new DeadlineExceededException();
                }
                var committed = token.mutationCommitted();
                var invalidOutput = !outputErrors.isEmpty();
                var quarantinedFailure = cause instanceof CapabilityQuarantinedException;
                if (active.outcomeIndeterminateReported()
                        || committed && (cause != null || invalidOutput)) {
                    active.markOutcomeIndeterminate();
                    putIndeterminateQuarantine(orderingKey, descriptor, active.ownerId());
                } else if (!quarantinedFailure && active.transientQuarantinePlaced()) {
                    quarantined.remove(orderingKey, active.transientQuarantine());
                }
                gate.complete(null);
                invocationTails.remove(orderingKey, gate);
                if (result.outcomeIsDone()) {
                    return;
                }
                if (cause == null) {
                    if (invalidOutput) {
                        var error = committed
                                ? new StructuredError("OUTCOME_INDETERMINATE",
                                "native mutation completed but its acknowledgement failed output validation",
                                false, Map.of("mutationCommitted", true, "violations", outputErrors))
                                : new StructuredError("INVALID_OUTPUT",
                                "capability output failed schema validation",
                                false, Map.of("violations", outputErrors));
                        if (completeError(result, request.requestId(), ResultEnvelope.Status.ERROR, error)) {
                            recordAudit(request, trace, "error");
                        }
                    } else if (result.completeOutcome(ResultEnvelope.ok(request.requestId(), safeOutput))) {
                        recordAudit(request, trace, "ok");
                    }
                } else {
                    var terminal = delegatedOrRuntimeFailure(request.requestId(), cause, committed);
                    if (result.completeOutcome(terminal)) {
                        recordAudit(request, trace, auditOutcome(terminal.status()));
                    }
                }
            }
        } catch (Throwable terminalFailure) {
            synchronized (active) {
                active.markUnderlyingCompleted();
                activeInvocations.remove(result);
                timeout.cancel(false);
                var committed = token.mutationCommitted();
                if (committed) {
                    active.markOutcomeIndeterminate();
                    putIndeterminateQuarantine(orderingKey, descriptor, active.ownerId());
                } else if (active.transientQuarantinePlaced()) {
                    quarantined.remove(orderingKey, active.transientQuarantine());
                }
                gate.complete(null);
                invocationTails.remove(orderingKey, gate);
                if (!result.outcomeIsDone()) {
                    var terminal = delegatedOrRuntimeFailure(request.requestId(), terminalFailure, committed);
                    if (result.completeOutcome(terminal)) {
                        recordAudit(request, trace, auditOutcome(terminal.status()));
                    }
                }
            }
        } finally {
            result.markTerminated();
            result.publish();
        }
    }

    private CompletableFuture<ResultEnvelope> invokeDelegated(RequestEnvelope parent,
                                                               CapabilityDescriptor admittedParentDescriptor,
                                                               String callerKey,
                                                               AuthorizationPolicy callerAuthorization,
                                                               RequestCancellationToken parentCancellation,
                                                               List<String> delegationPath, String stepId,
                                                               String capability, String capabilityVersion,
                                                               Map<String, Object> input) {
        final ChildRegistration child;
        try {
            child = parentCancellation.beginChild();
        } catch (Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }
        try {
            if (stepId == null || !stepId.matches("[A-Za-z0-9._-]{1,64}")) {
                throw new IllegalArgumentException("delegated stepId must match [A-Za-z0-9._-]{1,64}");
            }
            if (capability == null || capability.isBlank()) {
                throw new IllegalArgumentException("delegated capability must not be blank");
            }
            if (!isWorkflowCapability(parent.capability())
                    || !admittedParentDescriptor.id().equals(parent.capability())
                    || !admittedParentDescriptor.featureFlags().contains("delegates-native")
                    || !capability.startsWith("minecraft.")
                    || capability.startsWith("minecraft.event.")) {
                throw new DelegationRejectedException("DELEGATION_BOUNDARY_VIOLATION",
                        "delegation boundary permits only lodestone workflow capabilities to invoke minecraft native capabilities");
            }
            if (delegationPath.size() >= MAX_DELEGATION_DEPTH) {
                throw new IllegalStateException("delegated invocation depth exceeds " + MAX_DELEGATION_DEPTH);
            }
            if (delegationPath.contains(capability)) {
                throw new IllegalStateException("delegated invocation cycle detected for " + capability);
            }
            var request = new RequestEnvelope(parent.protocolVersion(), parent.requestId() + ":" + stepId,
                    parent.sessionId(), capability, capabilityVersion, input, parent.deadlineEpochMs(),
                    delegatedIdempotencyKey(parentCancellation.delegationSeed(), delegationPath, stepId,
                            capability, capabilityVersion),
                    parent.dryRun());
            var invocation = invokeInternal(request, callerKey, callerAuthorization, admittedParentDescriptor,
                    parentCancellation, delegationPath);
            child.attach(invocation);
        } catch (Throwable failure) {
            child.fail(failure);
        }
        return child.result();
    }

    private static String delegatedIdempotencyKey(String treeSeed, List<String> delegationPath,
                                                   String stepId, String capability, String capabilityVersion) {
        var parts = new ArrayList<String>();
        parts.add("lodestone-delegated-idempotency-v1");
        parts.add(treeSeed);
        parts.addAll(delegationPath);
        parts.add(stepId);
        parts.add(capability);
        parts.add(capabilityVersion == null ? "" : capabilityVersion);
        return "delegated:v1:" + sha256Parts(parts);
    }

    public EventHub.SubscriptionInfo subscribe(String eventPrefix, int bufferLimit) {
        return eventHub.subscribe(sessionId, eventPrefix, bufferLimit);
    }

    public EventHub.SubscriptionInfo subscribe(String ownerSessionId, String eventPrefix, int bufferLimit) {
        return eventHub.subscribe(ownerSessionId, sessionId, eventPrefix, bufferLimit);
    }

    private EventHub.SubscriptionInfo subscribe(String ownerSessionId, String eventPrefix, int bufferLimit,
                                                Runnable mutationCommit) {
        return eventHub.subscribe(ownerSessionId, sessionId, eventPrefix, bufferLimit, mutationCommit);
    }

    public List<dev.lodestone.protocol.EventEnvelope> poll(String subscriptionId, int maxEvents) {
        return eventHub.poll(subscriptionId, maxEvents);
    }

    public List<dev.lodestone.protocol.EventEnvelope> poll(String ownerSessionId,
                                                            String subscriptionId, int maxEvents) {
        return eventHub.poll(ownerSessionId, subscriptionId, maxEvents);
    }

    private List<dev.lodestone.protocol.EventEnvelope> poll(String ownerSessionId,
                                                             String subscriptionId, int maxEvents,
                                                             Runnable mutationCommit) {
        return eventHub.poll(ownerSessionId, subscriptionId, maxEvents, mutationCommit);
    }

    public boolean unsubscribe(String subscriptionId) {
        return eventHub.unsubscribe(subscriptionId);
    }

    public boolean unsubscribe(String ownerSessionId, String subscriptionId) {
        return eventHub.unsubscribe(ownerSessionId, subscriptionId);
    }

    private boolean unsubscribe(String ownerSessionId, String subscriptionId, Runnable mutationCommit) {
        return eventHub.unsubscribe(ownerSessionId, subscriptionId, mutationCommit);
    }

    public HealthSnapshot health() {
        var health = adapters.stream().map(LodestoneAdapter::health).toList();
        var failed = health.stream().filter(value -> value.state() == AdapterHealth.State.FAILED).findAny();
        var starting = health.stream().filter(value -> value.state() == AdapterHealth.State.STARTING).findAny();
        var stopping = health.stream().filter(value -> value.state() == AdapterHealth.State.STOPPING).findAny();
        var noWorld = health.stream().filter(value -> value.state() == AdapterHealth.State.NO_WORLD).findAny();
        var state = failed.isPresent() ? "failed"
                : starting.isPresent() ? "starting"
                : stopping.isPresent() ? "stopping"
                : noWorld.isPresent() ? "no-world"
                : health.isEmpty() ? "no-adapter" : "ready";
        var message = failed.map(AdapterHealth::message)
                .or(() -> starting.map(AdapterHealth::message))
                .or(() -> stopping.map(AdapterHealth::message))
                .or(() -> noWorld.map(AdapterHealth::message))
                .orElseGet(() -> health.isEmpty() ? "no native adapter registered" : "runtime is healthy");
        return new HealthSnapshot(state, message, Instant.now(), 1, adapters.size(), eventHub.queuedEvents());
    }

    public List<ResourceDescriptor> resources() {
        return resources.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> new ResourceDescriptor(entry.getKey(), entry.getValue().name(),
                        entry.getValue().description(), entry.getValue().mimeType()))
                .toList();
    }

    public String readResource(String uri) {
        var resource = resources.get(uri);
        if (resource == null) {
            throw new IllegalArgumentException("resource not found: " + uri);
        }
        return resource.content().get();
    }

    private void putResource(String uri, String name, String description, String mimeType,
                             Supplier<String> content) {
        resources.put(uri, new RuntimeResource(name, description, mimeType, content));
    }

    private void putBundledJsonResource(String uri, String name, String description, String path) {
        var content = readBundledUtf8(path);
        putResource(uri, name, description, "application/json", () -> content);
    }

    private static String readBundledUtf8(String path) {
        try (InputStream input = LodestoneRuntime.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("bundled runtime resource is missing: " + path);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("unable to read bundled runtime resource: " + path, failure);
        }
    }

    /**
     * Read a resource for an untrusted remote caller. Audit resources are reconstructed from the
     * trace records owned by that exact caller; filtering the global audit by request ID would leak
     * records when two callers reuse the same request ID. Trusted in-process callers may continue
     * to use {@link #readResource(String)} for the complete runtime view.
     */
    public String readResource(String uri, String callerSessionId) {
        if (callerSessionId == null || callerSessionId.isBlank()) {
            throw new IllegalArgumentException("callerSessionId must not be blank");
        }
        var caller = callerSessionId.trim();
        if ("lodestone://audit".equals(uri)) {
            return json(Map.of("records", auditForCaller(caller)));
        }
        if ("lodestone://audit/trace".equals(uri)) {
            return json(Map.of("records", auditTraceForCaller(caller)));
        }
        return readResource(uri);
    }

    public List<AuditRecord> audit() {
        synchronized (audit) {
            return List.copyOf(audit);
        }
    }

    public List<AuditTraceRecord> auditTrace() {
        synchronized (audit) {
            return List.copyOf(auditTrace);
        }
    }

    private List<AuditRecord> auditForCaller(String callerSessionId) {
        synchronized (audit) {
            return auditTrace.stream()
                    .filter(record -> callerSessionId.equals(record.callerSessionId()))
                    .map(record -> new AuditRecord(record.requestId(), record.sessionId(), record.capability(),
                            record.outcome(), record.occurredAt()))
                    .toList();
        }
    }

    private List<AuditTraceRecord> auditTraceForCaller(String callerSessionId) {
        synchronized (audit) {
            return auditTrace.stream()
                    .filter(record -> callerSessionId.equals(record.callerSessionId()))
                    .toList();
        }
    }

    private AdapterDescriptor runtimeDescriptor() {
        return new AdapterDescriptor("lodestone.system", "0.1.0", "minecraft", "unknown",
                "none", Environment.REMOTE);
    }

    private CapabilityDescriptor systemCapability(String id, String documentation) {
        return new CapabilityDescriptor(id, CapabilityKind.QUERY, "1.0", Stability.STABLE,
                Availability.AVAILABLE, null, "lodestone.system", "0.1.0", "minecraft", "unknown",
                "runtime", Environment.REMOTE, Map.of("type", "object"), Map.of("type", "object"), Map.of(),
                java.util.Set.of(PermissionClass.OBSERVE), SideEffect.NONE, Idempotency.IDEMPOTENT,
                new CapabilityPrerequisites(false, false, false, false), "runtime", new RateLimit(60, 60_000, 10),
                5_000, true, new DeliveryGuarantees("request-order", "at-most-once", 1), documentation, java.util.Set.of());
    }

    private InvocationFuture completedError(RequestEnvelope request, InvocationFuture result,
                                             InvocationTrace trace, String code, String message,
                                             boolean retryable) {
        return completedError(request, result, trace, code, message, retryable, ResultEnvelope.Status.ERROR);
    }

    private InvocationFuture completedError(RequestEnvelope request, InvocationFuture result,
                                             InvocationTrace trace, String code, String message,
                                             boolean retryable, Map<String, Object> details) {
        completeError(result, request.requestId(), ResultEnvelope.Status.ERROR,
                new StructuredError(code, message, retryable, details));
        recordAudit(request, trace, "error");
        result.markTerminated();
        result.publish();
        return result;
    }

    private InvocationFuture completedError(RequestEnvelope request, InvocationFuture result,
                                             InvocationTrace trace, String code, String message,
                                             boolean retryable, ResultEnvelope.Status status) {
        completeError(result, request.requestId(), status, StructuredError.of(code, message, retryable));
        recordAudit(request, trace, auditOutcome(status));
        result.markTerminated();
        result.publish();
        return result;
    }

    private static String auditOutcome(ResultEnvelope.Status status) {
        return switch (status) {
            case TIMED_OUT -> "timed-out";
            case CANCELLED -> "cancelled";
            case OK -> "ok";
            case ERROR -> "error";
        };
    }

    private void recordAudit(RequestEnvelope request, InvocationTrace trace, String outcome) {
        synchronized (audit) {
            if (audit.size() >= MAX_AUDIT_RECORDS) {
                audit.remove(0);
            }
            if (auditTrace.size() >= MAX_AUDIT_RECORDS) {
                auditTrace.remove(0);
            }
            var occurredAt = Instant.now();
            audit.add(new AuditRecord(request.requestId(), sessionId, request.capability(), outcome, occurredAt));
            auditTrace.add(new AuditTraceRecord(request.requestId(), sessionId, trace.callerSessionId(),
                    request.capability(), trace.path(), outcome, occurredAt));
        }
    }

    private static boolean completeError(InvocationFuture result, String requestId,
                                         ResultEnvelope.Status status, StructuredError error) {
        return result.completeOutcome(ResultEnvelope.error(requestId, status, error));
    }

    private InvocationFuture replayIdempotent(RequestEnvelope request, InvocationTrace trace,
                                               IdempotencyEntry existing, long deadlineNanos) {
        var replay = new InvocationFuture(completionExecutor, existing.result().terminated());
        final ScheduledFuture<?> replayTimeout;
        try {
            replayTimeout = scheduler.schedule(() -> completeReplayTimeout(replay, request, trace),
                    Math.max(1L, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (RejectedExecutionException stopping) {
            completeError(replay, request.requestId(), ResultEnvelope.Status.ERROR,
                    StructuredError.of("RUNTIME_STOPPING", "runtime is stopping or closed", true));
            recordAudit(request, trace, "error");
            replay.publish();
            return replay;
        }
        existing.result().whenOutcomeComplete((value, failure) -> {
            if (deadlineNanos - System.nanoTime() <= 0) {
                completeReplayTimeout(replay, request, trace);
                return;
            }
            if (failure == null) {
                if (replay.completeOutcome(value.withRequestId(request.requestId()))) {
                    recordAudit(request, trace, auditOutcome(value.status()));
                    replay.publish();
                }
            } else {
                var cause = unwrap(failure);
                var status = isCancellation(cause) ? ResultEnvelope.Status.CANCELLED : ResultEnvelope.Status.ERROR;
                var error = StructuredError.of(status == ResultEnvelope.Status.CANCELLED
                                ? "CANCELLED" : "ADAPTER_FAILURE",
                        safeMessage(cause), status != ResultEnvelope.Status.CANCELLED);
                if (completeError(replay, request.requestId(), status, error)) {
                    recordAudit(request, trace, auditOutcome(status));
                    replay.publish();
                }
            }
        });
        replay.whenComplete((ignored, failure) -> replayTimeout.cancel(false));
        return replay;
    }

    private void completeReplayTimeout(InvocationFuture replay, RequestEnvelope request, InvocationTrace trace) {
        if (completeError(replay, request.requestId(), ResultEnvelope.Status.TIMED_OUT,
                new StructuredError("IDEMPOTENT_RESULT_PENDING",
                        "the original idempotent invocation is still running; retry only with the same idempotency key",
                        true, Map.of("sameIdempotencyKeyRequired", true)))) {
            recordAudit(request, trace, "timed-out");
            replay.publish();
        }
    }

    private static OrderingKey orderingKey(String callerKey, String capability,
                                           CapabilityDescriptor descriptor) {
        if (capability.startsWith("minecraft.") && descriptor.sideEffect() != SideEffect.NONE) {
            return SHARED_MUTATION_ORDER;
        }
        if (isWorkflowCapability(capability)) {
            return new OrderingKey("workflow", callerKey, capability);
        }
        return new OrderingKey("capability", callerKey, capability);
    }

    private Quarantine activeQuarantine(OrderingKey orderingKey, CapabilityDescriptor descriptor) {
        var quarantine = quarantined.get(orderingKey);
        if (quarantine == null && descriptor.sideEffect() != SideEffect.NONE
                && !SHARED_MUTATION_ORDER.equals(orderingKey)) {
            quarantine = quarantined.get(SHARED_MUTATION_ORDER);
        }
        return quarantine;
    }

    private void putIndeterminateQuarantine(OrderingKey orderingKey, CapabilityDescriptor descriptor,
                                             String ownerId) {
        if (descriptor.sideEffect() == SideEffect.NONE) {
            return;
        }
        putQuarantine(orderingKey, Quarantine.indeterminate(ownerId));
        putQuarantine(SHARED_MUTATION_ORDER, Quarantine.indeterminate(ownerId));
    }

    private void putQuarantine(OrderingKey key, Quarantine quarantine) {
        quarantined.merge(key, quarantine,
                (existing, replacement) -> existing.outcomeIndeterminate() ? existing : replacement);
    }

    private static boolean isWorkflowCapability(String capability) {
        return capability != null && capability.startsWith("lodestone.")
                && !capability.startsWith("lodestone.system.");
    }

    private static String lengthPrefixedKey(String first, String second) {
        return first.length() + ":" + first + second.length() + ":" + second;
    }

    private static String rootDelegationSeed(String callerKey, RequestEnvelope request) {
        if (request.idempotencyKey() == null) {
            return "ephemeral:" + UUID.randomUUID();
        }
        return "stable:v1:" + sha256Parts(List.of(
                "lodestone-root-idempotency-v1", callerKey, request.capability(),
                request.capabilityVersion() == null ? "" : request.capabilityVersion(),
                request.idempotencyKey()));
    }

    private static String sha256Parts(List<String> parts) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (var part : parts) {
                var bytes = part.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static CompletionStage<Map<String, Object>> awaitStructuredCompletion(
            CompletionStage<Map<String, Object>> handlerStage, RequestCancellationToken token) {
        return handlerStage.handle(HandlerCompletion::new).thenCompose(completion -> {
            var handlerFailure = completion.failure() == null ? null : unwrap(completion.failure());
            var cancellationWasAlreadyRequested = handlerFailure != null
                    && token.requestCancellationAndReportExisting();
            var cancelChildren = handlerFailure != null
                    && (!isCancellation(handlerFailure) || !cancellationWasAlreadyRequested);
            return token.sealAndAwaitChildren(cancelChildren).handle((ignored, childFailure) -> {
                var causalChildFailure = token.firstChildFailure();
                var failure = handlerFailure != null
                        ? isCancellation(handlerFailure) && causalChildFailure != null
                                ? causalChildFailure
                                : handlerFailure
                        : childFailure == null ? null : unwrap(childFailure);
                if (failure != null) {
                    throw new CompletionException(failure);
                }
                return completion.output();
            });
        });
    }

    private static ResultEnvelope delegatedOrRuntimeFailure(String requestId, Throwable cause, boolean committed) {
        if (committed) {
            var details = new LinkedHashMap<String, Object>();
            details.put("mutationCommitted", true);
            if (cause instanceof DelegatedInvocationException delegated && delegated.result().error() != null) {
                details.put("childCode", delegated.result().error().code());
                details.put("childStatus", delegated.result().status().name());
                details.put("childDetails", delegated.result().error().details());
            }
            return ResultEnvelope.error(requestId, ResultEnvelope.Status.ERROR,
                    new StructuredError("OUTCOME_INDETERMINATE",
                            "irreversible mutation was dispatched before failure: " + safeMessage(cause),
                            false, Map.copyOf(details)));
        }
        if (cause instanceof DelegatedInvocationException delegated) {
            return delegated.result().withRequestId(requestId);
        }
        if (cause instanceof DelegationRejectedException rejected) {
            return ResultEnvelope.error(requestId, ResultEnvelope.Status.ERROR,
                    StructuredError.of(rejected.code(), safeMessage(rejected), false));
        }
        if (cause instanceof EventOwnershipException) {
            return ResultEnvelope.error(requestId, ResultEnvelope.Status.ERROR,
                    StructuredError.of("EVENT_SUBSCRIPTION_FORBIDDEN",
                            "event subscription does not belong to this caller", false));
        }
        if (cause instanceof CapabilityQuarantinedException quarantinedFailure) {
            return ResultEnvelope.error(requestId, ResultEnvelope.Status.ERROR,
                    new StructuredError("CAPABILITY_QUARANTINED", safeMessage(cause),
                            !quarantinedFailure.quarantine().outcomeIndeterminate(), Map.of()));
        }
        if (cause instanceof DeadlineExceededException) {
            return ResultEnvelope.error(requestId, ResultEnvelope.Status.TIMED_OUT,
                    StructuredError.of("DEADLINE_EXCEEDED", "capability deadline exceeded", true));
        }
        var status = isCancellation(cause) ? ResultEnvelope.Status.CANCELLED : ResultEnvelope.Status.ERROR;
        return ResultEnvelope.error(requestId, status,
                new StructuredError(status == ResultEnvelope.Status.CANCELLED ? "CANCELLED" : "ADAPTER_FAILURE",
                        safeMessage(cause), status != ResultEnvelope.Status.CANCELLED, Map.of()));
    }

    private void pruneIdempotency() {
        var cutoff = System.currentTimeMillis() - IDEMPOTENCY_TTL_MS;
        idempotency.forEach((key, entry) -> {
            if (entry.createdAt() < cutoff && entry.result().terminated().isDone()) {
                idempotency.remove(key, entry);
            }
        });
    }

    private record IdempotencyEntry(String fingerprint, InvocationFuture result, long createdAt) {
    }

    private static Throwable unwrap(Throwable failure) {
        var current = failure;
        while ((current instanceof CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static boolean isCancellation(Throwable failure) {
        return failure instanceof CancellationToken.CancellationException
                || failure instanceof java.util.concurrent.CancellationException
                || failure instanceof DelegatedInvocationException delegated
                && delegated.result().status() == ResultEnvelope.Status.CANCELLED;
    }

    private static String safeMessage(Throwable failure) {
        final String raw;
        try {
            raw = failure == null ? null : failure.getMessage();
        } catch (Throwable hostileException) {
            return "runtime failure";
        }
        var message = raw == null ? "" : raw.replace('\r', ' ').replace('\n', ' ').replace('\0', '?').trim();
        if (message.isBlank()) {
            return failure == null ? "runtime failure" : failure.getClass().getSimpleName();
        }
        return message.length() <= 512 ? message : message.substring(0, 512);
    }

    private static String json(Object value) {
        try {
            return JsonSupport.MAPPER.toJson(value);
        } catch (Exception failure) {
            return "{\"error\":\"resource serialization failed\"}";
        }
    }

    @SuppressWarnings("unchecked")
    private static JsonMapSnapshot snapshotJsonMap(Map<String, Object> value, int maxUtf8Bytes) {
        if (value == null) {
            throw new IllegalArgumentException("JSON object must not be null");
        }
        // Serialize through the declared Map contract so anonymous/custom Map implementations
        // cannot be treated as excluded reflective objects by Gson.
        var canonicalTree = canonicalizeJson(JsonSupport.MAPPER.toJsonTree(value, Map.class));
        if (!canonicalTree.isJsonObject()) {
            throw new IllegalArgumentException("value must serialize as a JSON object");
        }
        var canonicalJson = JsonSupport.MAPPER.toJson(canonicalTree);
        var utf8Bytes = canonicalJson.getBytes(StandardCharsets.UTF_8).length;
        if (utf8Bytes > maxUtf8Bytes) {
            throw new IllegalArgumentException("JSON object exceeds " + maxUtf8Bytes + " UTF-8 bytes");
        }
        var decoded = JsonSupport.MAPPER.fromJson(canonicalTree, Object.class);
        if (!(decoded instanceof Map<?, ?> decodedMap)) {
            throw new IllegalArgumentException("value must decode as a JSON object");
        }
        return new JsonMapSnapshot((Map<String, Object>) freezeJson(decodedMap), canonicalJson, utf8Bytes);
    }

    private static JsonElement canonicalizeJson(JsonElement value) {
        if (value == null || value.isJsonNull() || value.isJsonPrimitive()) {
            return value == null ? com.google.gson.JsonNull.INSTANCE : value.deepCopy();
        }
        if (value.isJsonArray()) {
            var canonical = new JsonArray();
            value.getAsJsonArray().forEach(item -> canonical.add(canonicalizeJson(item)));
            return canonical;
        }
        var canonical = new JsonObject();
        value.getAsJsonObject().keySet().stream().sorted()
                .forEach(key -> canonical.add(key, canonicalizeJson(value.getAsJsonObject().get(key))));
        return canonical;
    }

    private static Object freezeJson(Object value) {
        if (value instanceof Map<?, ?> map) {
            var frozen = new LinkedHashMap<String, Object>();
            map.forEach((key, nested) -> {
                if (!(key instanceof String textKey)) {
                    throw new IllegalArgumentException("JSON object keys must be strings");
                }
                frozen.put(textKey, freezeJson(nested));
            });
            return java.util.Collections.unmodifiableMap(frozen);
        }
        if (value instanceof List<?> list) {
            var frozen = new ArrayList<Object>(list.size());
            list.forEach(nested -> frozen.add(freezeJson(nested)));
            return java.util.Collections.unmodifiableList(frozen);
        }
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        throw new IllegalArgumentException("unsupported JSON value type: " + value.getClass().getName());
    }

    @Override
    public synchronized void close() {
        if (state != State.RUNNING) {
            return;
        }
        state = State.STOPPING;
        activeInvocations.forEach((result, active) -> {
            boolean publish = false;
            synchronized (active) {
                active.token().requestCancellation();
                active.token().cancelChildren();
                if (active.token().mutationCommitted()) {
                    active.markOutcomeIndeterminate();
                    if (completeError(result, active.request().requestId(), ResultEnvelope.Status.ERROR,
                            new StructuredError("OUTCOME_INDETERMINATE",
                                    "runtime stopped after irreversible mutation dispatch; reconcile state before retrying",
                                    false, Map.of("mutationCommitted", true)))) {
                        recordAudit(active.request(), active.trace(), "error");
                        publish = true;
                    }
                } else {
                    if (completeError(result, active.request().requestId(), ResultEnvelope.Status.CANCELLED,
                            StructuredError.of("RUNTIME_STOPPING", "runtime is stopping or closed", true))) {
                        recordAudit(active.request(), active.trace(), "cancelled");
                        publish = true;
                    }
                }
            }
            if (publish) {
                result.publish();
            }
        });
        var reverseAdapters = new ArrayList<>(adapters);
        java.util.Collections.reverse(reverseAdapters);
        for (var adapter : reverseAdapters) {
            try {
                adapter.close();
            } catch (RuntimeException ignored) {
                // Shutdown must continue for every adapter.
            }
        }
        scheduler.shutdownNow();
        executor.shutdownNow();
        activeInvocations.clear();
        invocationTails.forEach((key, gate) -> gate.complete(null));
        invocationTails.clear();
        quarantined.clear();
        state = State.CLOSED;
        completionExecutor.shutdown();
    }

    private synchronized void requireRunning() {
        if (state != State.RUNNING) {
            throw new IllegalStateException("runtime is stopping or closed");
        }
    }

    private enum State {
        RUNNING,
        STOPPING,
        CLOSED
    }

    private static ExecutorService createExecutor() {
        try {
            var virtualThreadFactory = Executors.class.getMethod("newVirtualThreadPerTaskExecutor");
            return (ExecutorService) virtualThreadFactory.invoke(null);
        } catch (ReflectiveOperationException ignored) {
            return Executors.newCachedThreadPool();
        }
    }

    private static ExecutorService createCompletionExecutor() {
        return Executors.newCachedThreadPool(task -> {
            var thread = new Thread(task,
                    "lodestone-runtime-completion-" + COMPLETION_THREAD_ID.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    private static void executeSafely(Executor executor, Runnable task) {
        try {
            executor.execute(task);
        } catch (RejectedExecutionException rejected) {
            try {
                ForkJoinPool.commonPool().execute(task);
            } catch (RuntimeException unavailable) {
                var thread = new Thread(task,
                        "lodestone-runtime-completion-fallback-" + COMPLETION_THREAD_ID.incrementAndGet());
                thread.setDaemon(true);
                thread.start();
            }
        }
    }

    private static final class RequestCancellationToken implements CancellationToken {
        private final CancellationTree tree;
        private final RequestCancellationToken parent;
        private final Object childrenMonitor = new Object();
        private final List<ChildRegistration> children = new ArrayList<>();
        private boolean childrenSealed;
        /** Guarded by {@link CancellationTree#monitor}. */
        private boolean subtreeMutationCommitted;

        private RequestCancellationToken(RequestCancellationToken parent, String delegationSeed) {
            this.parent = parent;
            this.tree = parent == null ? new CancellationTree(delegationSeed) : parent.tree;
        }

        @Override
        public boolean isCancelled() {
            synchronized (tree.monitor) {
                return tree.cancelRequested;
            }
        }

        @Override
        public void commitMutation() {
            synchronized (tree.monitor) {
                if (tree.cancelRequested) {
                    throw new CancellationException();
                }
                for (var scope = this; scope != null; scope = scope.parent) {
                    scope.subtreeMutationCommitted = true;
                }
            }
        }

        private boolean requestCancellation() {
            synchronized (tree.monitor) {
                tree.cancelRequested = true;
                return !subtreeMutationCommitted;
            }
        }

        private boolean requestCancellationAndReportExisting() {
            synchronized (tree.monitor) {
                var alreadyRequested = tree.cancelRequested;
                tree.cancelRequested = true;
                return alreadyRequested;
            }
        }

        private boolean mutationCommitted() {
            synchronized (tree.monitor) {
                return subtreeMutationCommitted;
            }
        }

        private String delegationSeed() {
            return tree.delegationSeed;
        }

        private void throwIfDeadlinePassed(long deadlineNanos) {
            if (deadlineNanos - System.nanoTime() <= 0) {
                requestCancellation();
                cancelChildren();
                throw new DeadlineExceededException();
            }
            throwIfCancelled();
        }

        private ChildRegistration beginChild() {
            synchronized (childrenMonitor) {
                throwIfCancelled();
                if (childrenSealed) {
                    throw new IllegalStateException("delegated invocation scope is already sealed");
                }
                var child = new ChildRegistration(this);
                children.add(child);
                return child;
            }
        }

        private CompletionStage<Void> sealAndAwaitChildren(boolean cancelChildren) {
            final List<ChildRegistration> snapshot;
            synchronized (childrenMonitor) {
                childrenSealed = true;
                snapshot = List.copyOf(children);
            }
            if (cancelChildren) {
                cancelChildren(snapshot);
            }
            var completions = snapshot.stream().map(ChildRegistration::completion)
                    .toArray(CompletableFuture[]::new);
            return CompletableFuture.allOf(completions).handle((ignored, failure) -> {
                var causalFailure = firstChildFailure();
                if (causalFailure != null) {
                    throw new CompletionException(causalFailure);
                }
                for (var child : snapshot) {
                    try {
                        child.completion().join();
                    } catch (CompletionException | java.util.concurrent.CancellationException childFailure) {
                        throw new CompletionException(unwrap(childFailure));
                    }
                }
                return null;
            });
        }

        private void childFailed(Throwable failure) {
            final boolean cancelSiblings;
            synchronized (tree.monitor) {
                // A substantive child failure requests tree cancellation before its translated
                // ResultEnvelope reaches this registration. Do not let a sibling's resulting
                // cancellation race overwrite that still-in-flight causal failure.
                var secondaryCancellation = tree.cancelRequested && isCancellation(failure);
                if (tree.firstChildFailure == null && !secondaryCancellation) {
                    tree.firstChildFailure = failure;
                }
                cancelSiblings = !tree.cancelRequested;
                tree.cancelRequested = true;
            }
            if (cancelSiblings) {
                cancelChildren();
            }
        }

        private Throwable firstChildFailure() {
            synchronized (tree.monitor) {
                return tree.firstChildFailure;
            }
        }

        private void cancelChildren() {
            final List<ChildRegistration> snapshot;
            synchronized (childrenMonitor) {
                snapshot = List.copyOf(children);
            }
            cancelChildren(snapshot);
        }

        private static void cancelChildren(List<ChildRegistration> children) {
            children.forEach(ChildRegistration::cancel);
        }
    }

    private static final class CancellationTree {
        private final Object monitor = new Object();
        private final String delegationSeed;
        private boolean cancelRequested;
        private Throwable firstChildFailure;

        private CancellationTree(String delegationSeed) {
            this.delegationSeed = delegationSeed;
        }
    }

    private static final class ChildRegistration {
        private final RequestCancellationToken owner;
        private final CompletableFuture<ResultEnvelope> result = new CompletableFuture<>();
        private final CompletableFuture<Throwable> semanticCompletion = new CompletableFuture<>();
        private final CompletableFuture<Void> terminated = new CompletableFuture<>();
        private volatile InvocationFuture invocation;

        private ChildRegistration(RequestCancellationToken owner) {
            this.owner = owner;
            result.whenComplete((ignored, failure) -> {
                var causalFailure = failure == null ? null : unwrap(failure);
                if (causalFailure == null) {
                    semanticCompletion.complete(null);
                    return;
                }
                try {
                    owner.childFailed(causalFailure);
                } finally {
                    // Aggregate completion must never overtake causal-failure publication.
                    semanticCompletion.complete(causalFailure);
                }
            });
        }

        private void attach(InvocationFuture invocation) {
            this.invocation = invocation;
            if (result.isCancelled()) {
                invocation.cancel(true);
            }
            invocation.whenOutcomeComplete((value, failure) -> {
                if (failure != null) {
                    result.completeExceptionally(unwrap(failure));
                } else if (value.status() == ResultEnvelope.Status.OK) {
                    result.complete(value);
                } else {
                    result.completeExceptionally(new DelegatedInvocationException(value));
                }
            });
            invocation.terminated().whenComplete((ignored, failure) -> terminated.complete(null));
        }

        private void fail(Throwable failure) {
            result.completeExceptionally(failure);
            terminated.complete(null);
        }

        private CompletableFuture<ResultEnvelope> result() {
            return result;
        }

        private CompletableFuture<Void> completion() {
            return semanticCompletion.thenCombine(terminated, (failure, ignored) -> {
                if (failure != null) {
                    throw new CompletionException(failure);
                }
                return null;
            });
        }

        private void cancel() {
            var active = invocation;
            if (active == null) {
                result.cancel(false);
            } else if (active.cancel(true)) {
                result.cancel(false);
            }
        }
    }

    /**
     * Cancellation-aware public facade over a runtime-owned outcome promise. Standard public
     * completion mutators affect only this observer view; the separate outcome remains authoritative
     * for runtime completion, audit, deadlines, and idempotent replay.
     */
    private static final class InvocationFuture extends CompletableFuture<ResultEnvelope> {
        private final CompletableFuture<ResultEnvelope> outcome = new CompletableFuture<>();
        private final CompletableFuture<Void> terminated;
        private final Executor publicationExecutor;
        private final AtomicBoolean publicationClaimed = new AtomicBoolean();
        private volatile RequestCancellationToken token;
        private volatile ActiveInvocation active;
        private volatile Runnable cancellationAction = () -> { };

        private InvocationFuture(Executor publicationExecutor) {
            this(publicationExecutor, new CompletableFuture<>());
        }

        private InvocationFuture(Executor publicationExecutor, CompletableFuture<Void> terminated) {
            this.publicationExecutor = publicationExecutor;
            this.terminated = terminated;
        }

        private void bind(RequestCancellationToken token, Runnable cancellationAction) {
            this.token = token;
            this.cancellationAction = cancellationAction;
        }

        private void attach(ActiveInvocation active) {
            this.active = active;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            var boundToken = token;
            var boundActive = active;
            if (boundToken == null || boundActive == null) {
                if (!outcome.cancel(mayInterruptIfRunning)) {
                    return false;
                }
                publicationClaimed.set(true);
                return super.cancel(mayInterruptIfRunning);
            }
            final boolean cancelled;
            synchronized (boundActive) {
                if (outcome.isDone()) {
                    return false;
                }
                var beforeMutation = boundToken.requestCancellation();
                boundToken.cancelChildren();
                if (!beforeMutation) {
                    return false;
                }
                cancelled = outcome.cancel(mayInterruptIfRunning);
                if (cancelled) {
                    publicationClaimed.set(true);
                    cancellationAction.run();
                }
            }
            return cancelled && super.cancel(mayInterruptIfRunning);
        }

        private boolean completeOutcome(ResultEnvelope value) {
            return outcome.complete(value);
        }

        private boolean outcomeIsDone() {
            return outcome.isDone();
        }

        private void whenOutcomeComplete(BiConsumer<? super ResultEnvelope, ? super Throwable> observer) {
            outcome.whenComplete((value, failure) -> executeSafely(publicationExecutor,
                    () -> observer.accept(value, failure)));
        }

        private void publish() {
            if (!outcome.isDone() || !publicationClaimed.compareAndSet(false, true)) {
                return;
            }
            var publication = (Runnable) () -> outcome.whenComplete((value, failure) -> {
                if (failure == null) {
                    super.complete(value);
                } else if (isCancellation(unwrap(failure))) {
                    super.cancel(false);
                } else {
                    super.completeExceptionally(unwrap(failure));
                }
            });
            executeSafely(publicationExecutor, publication);
        }

        private CompletableFuture<Void> terminated() {
            return terminated;
        }

        private void markTerminated() {
            terminated.complete(null);
        }
    }

    private static final class ActiveInvocation {
        private final String ownerId = UUID.randomUUID().toString();
        private final RequestEnvelope request;
        private final InvocationTrace trace;
        private final RequestCancellationToken token;
        private volatile boolean started;
        private volatile boolean underlyingCompleted;
        private volatile boolean outcomeIndeterminateReported;
        private volatile Quarantine transientQuarantine;

        private ActiveInvocation(RequestEnvelope request, InvocationTrace trace,
                                 RequestCancellationToken token) {
            this.request = request;
            this.trace = trace;
            this.token = token;
        }

        private RequestEnvelope request() {
            return request;
        }

        private String ownerId() {
            return ownerId;
        }

        private RequestCancellationToken token() {
            return token;
        }

        private InvocationTrace trace() {
            return trace;
        }

        private boolean started() {
            return started;
        }

        private void markStarted() {
            started = true;
        }

        private boolean underlyingCompleted() {
            return underlyingCompleted;
        }

        private void markUnderlyingCompleted() {
            underlyingCompleted = true;
        }

        private boolean outcomeIndeterminateReported() {
            return outcomeIndeterminateReported;
        }

        private void markOutcomeIndeterminate() {
            outcomeIndeterminateReported = true;
        }

        private boolean transientQuarantinePlaced() {
            return transientQuarantine != null;
        }

        private Quarantine transientQuarantine() {
            return transientQuarantine;
        }

        private void markTransientQuarantinePlaced(Quarantine quarantine) {
            transientQuarantine = quarantine;
        }
    }

    private record InvocationTrace(String callerSessionId, List<String> path) {
    }

    private record OrderingKey(String scope, String callerSessionId, String capability) {
    }

    private record IdempotencyCacheKey(String namespace, String callerSessionId, String capability,
                                       String capabilityVersion, String key) {
    }

    private record HandlerCompletion(Map<String, Object> output, Throwable failure) {
    }

    private record RuntimeResource(String name, String description, String mimeType,
                                   Supplier<String> content) {
    }

    private record JsonMapSnapshot(Map<String, Object> value, String canonicalJson, int utf8Bytes) {
    }

    private record Quarantine(boolean outcomeIndeterminate, String ownerId) {
        private static Quarantine running(String ownerId) {
            return new Quarantine(false, ownerId);
        }

        private static Quarantine indeterminate(String ownerId) {
            return new Quarantine(true, ownerId);
        }
    }

    private static final class DelegationRejectedException extends RuntimeException {
        private final String code;

        private DelegationRejectedException(String code, String message) {
            super(message);
            this.code = code;
        }

        private String code() {
            return code;
        }
    }

    private static final class DeadlineExceededException extends RuntimeException {
        private DeadlineExceededException() {
            super("capability deadline exceeded before native dispatch");
        }
    }

    private static final class CapabilityQuarantinedException extends RuntimeException {
        private final Quarantine quarantine;

        private CapabilityQuarantinedException(Quarantine quarantine) {
            super(quarantine.outcomeIndeterminate()
                    ? "a previous irreversible invocation has an indeterminate outcome; reconcile state and restart the runtime before retrying"
                    : "a previous invocation exceeded its deadline and is still running; retry after it finishes");
            this.quarantine = quarantine;
        }

        private Quarantine quarantine() {
            return quarantine;
        }
    }

    private final class SystemAdapter implements LodestoneAdapter {
        private final AdapterDescriptor descriptor = runtimeDescriptor();
        private final List<CapabilityDescriptor> capabilities = CoreCatalog.load().stream()
                .map(this::activateSystemCapability)
                .toList();

        @Override
        public AdapterDescriptor descriptor() {
            return descriptor;
        }

        private CapabilityDescriptor activateSystemCapability(CapabilityDescriptor capability) {
            if (capability.id().startsWith("lodestone.system.")
                    || capability.id().equals("minecraft.event.subscribe")
                    || capability.id().equals("minecraft.event.poll")
                    || capability.id().equals("minecraft.event.unsubscribe")) {
                return capability.forAdapter(descriptor, Availability.AVAILABLE, null);
            }
            return capability.forAdapter(descriptor, Availability.UNAVAILABLE,
                    new dev.lodestone.protocol.AvailabilityReason("no-native-adapter",
                            "No native Minecraft adapter is registered for this capability.",
                            Map.of("capability", capability.id())));
        }

        @Override
        public CapabilityManifest manifest() {
            return new CapabilityManifest(descriptor, capabilities);
        }

        @Override
        public Map<String, CapabilityHandler> handlers() {
            return Map.of(
                    "lodestone.system.handshake", context -> CompletableFuture.completedFuture(
                            JsonSupport.MAPPER.fromJson(JsonSupport.MAPPER.toJson(handshake()), Map.class)),
                    "lodestone.system.health", context -> CompletableFuture.completedFuture(
                            JsonSupport.MAPPER.fromJson(JsonSupport.MAPPER.toJson(health()), Map.class)),
                    "lodestone.system.capabilities.list", context -> CompletableFuture.completedFuture(Map.of(
                            "capabilities", capabilities(context.request().input().get("query") instanceof String query
                                    ? query : ""))),
                    "lodestone.system.capabilities.get", context -> CompletableFuture.completedFuture(
                            capabilityGet(context.request().input().get("id") instanceof String id ? id : "")),
                    "lodestone.system.capabilities.search", context -> CompletableFuture.completedFuture(Map.of(
                            "capabilities", capabilities(context.request().input().get("query") instanceof String query
                                    ? query : ""))),
                    "minecraft.event.subscribe", this::subscribeEvents,
                    "minecraft.event.poll", this::pollEvents,
                    "minecraft.event.unsubscribe", this::unsubscribeEvents);
        }

        private java.util.concurrent.CompletionStage<Map<String, Object>> subscribeEvents(InvocationContext context) {
            return CompletableFuture.completedFuture(Map.of(
                    "subscription", subscribe(callerSession(context),
                            context.request().input().get("eventPrefix") instanceof String prefix
                            ? prefix : "", context.request().input().get("bufferLimit") instanceof Number limit
                            ? limit.intValue() : 256, context.cancellation()::commitMutation)));
        }

        private java.util.concurrent.CompletionStage<Map<String, Object>> pollEvents(InvocationContext context) {
            return CompletableFuture.completedFuture(Map.of(
                    "events", poll(callerSession(context),
                            String.valueOf(context.request().input().getOrDefault("subscriptionId", "")),
                            context.request().input().get("maxEvents") instanceof Number limit ? limit.intValue() : 100,
                            context.cancellation()::commitMutation)));
        }

        private java.util.concurrent.CompletionStage<Map<String, Object>> unsubscribeEvents(InvocationContext context) {
            return CompletableFuture.completedFuture(Map.of(
                    "removed", unsubscribe(callerSession(context),
                            String.valueOf(context.request().input().getOrDefault("subscriptionId", "")),
                            context.cancellation()::commitMutation)));
        }

        private String callerSession(InvocationContext context) {
            var caller = context.attributes().get(InvocationAttributes.CALLER_SESSION_ID);
            return caller instanceof String value && !value.isBlank() ? value : context.request().sessionId();
        }

        private Map<String, Object> capabilityGet(String id) {
            if (id == null || id.isBlank()) {
                return Map.of("found", false, "capabilityId", "");
            }
            var found = capabilities(id).stream().filter(capability -> capability.id().equals(id)).findFirst();
            return found.<Map<String, Object>>map(capability -> Map.of("found", true, "capability", capability))
                    .orElseGet(() -> Map.of("found", false, "capabilityId", id));
        }
    }

    private static final class CompletionStageAdapter {
        private static java.util.concurrent.CompletionStage<Map<String, Object>> invoke(
                CapabilityHandler handler, RequestEnvelope request, RequestCancellationToken token,
                long deadlineNanos,
                ExecutorService executor, Map<String, Object> attributes) {
            return CompletableFuture.supplyAsync(() -> {
                token.throwIfDeadlinePassed(deadlineNanos);
                return handler.invoke(new InvocationContext(request, token, executor, attributes));
            }, executor).thenCompose(stage -> stage);
        }
    }
}
