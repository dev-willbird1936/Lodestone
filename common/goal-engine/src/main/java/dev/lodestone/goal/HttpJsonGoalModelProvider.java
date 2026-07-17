// SPDX-License-Identifier: MIT
package dev.lodestone.goal;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import dev.lodestone.protocol.JsonSupport;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Optional OpenAI-compatible provider. It is enabled only when LODESTONE_GOAL_MODEL_URL is set. */
final class HttpJsonGoalModelProvider implements GoalModelProvider {
    /**
     * Situational deliberation budget for DELIBERATE_V1: when it is currently safe to stand still
     * (see {@link #isHazardous}), a decision may request the slower-but-smarter {@code xhigh}
     * reasoning effort instead of the fast configured default. 8 seconds is wide enough for a
     * meaningfully deeper single realtime decision without stalling the goal loop for long relative
     * to typical goal budgets (120-480s maxDurationMs); it never narrows an operator's own wider
     * configured timeout, only widens the fast default.
     */
    private static final Duration DELIBERATE_SAFE_TIMEOUT = Duration.ofSeconds(8);
    private static final String DELIBERATE_SAFE_EFFORT = "xhigh";

    private final String id;
    private final URI endpoint;
    private final String apiKey;
    private final long p95Ms;
    private final String reasoningEffort;
    private final HttpClient client;
    private final Duration timeout;

    HttpJsonGoalModelProvider(String id, URI endpoint, String apiKey, long p95Ms,
                              String reasoningEffort, Duration timeout) {
        this.id = id;
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.p95Ms = p95Ms;
        this.reasoningEffort = reasoningEffort;
        this.timeout = timeout;
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    static Optional<GoalModelProvider> fromEnvironment() {
        var url = System.getenv("LODESTONE_GOAL_MODEL_URL");
        if (url == null || url.isBlank()) return Optional.empty();
        try {
            var p95 = longEnv("LODESTONE_GOAL_MODEL_P95_MS", 150);
            var timeoutMs = Math.max(100, longEnv("LODESTONE_GOAL_MODEL_TIMEOUT_MS", 1_500));
            var reasoningEffort = effortEnv("LODESTONE_GOAL_MODEL_REASONING_EFFORT", "low");
            return Optional.of(new HttpJsonGoalModelProvider(
                    env("LODESTONE_GOAL_MODEL_ID", GoalModelProviders.EXECUTOR_MODEL_ID), URI.create(url.trim()),
                    System.getenv("LODESTONE_GOAL_MODEL_API_KEY"), p95, reasoningEffort,
                    Duration.ofMillis(timeoutMs)));
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public long measuredP95LatencyMs() {
        return p95Ms;
    }

    @Override
    public String reasoningEffort() {
        return reasoningEffort;
    }

    @Override
    public boolean fallback() {
        return false;
    }

    @Override
    public Optional<GoalDecision> choose(GoalDecisionRequest request) {
        if (request.candidates().isEmpty()) return Optional.empty();
        var decisionState = request.decisionState();
        // A perfect player thinks longer when it is safe to stand still and reacts fast when it is
        // not. Only the top deliberate tier widens its budget, and only when nothing hazardous is
        // currently observed; every other tier, and any hazardous deliberate decision, keeps the
        // fast configured effort/timeout so a threatened player is never slowed down.
        var widenBudget = request.spec().intelligence() == GoalIntelligence.DELIBERATE_V1
                && !isHazardous(decisionState);
        var effort = widenBudget ? DELIBERATE_SAFE_EFFORT : reasoningEffort;
        var callTimeout = widenBudget
                ? Duration.ofMillis(Math.max(timeout.toMillis(), DELIBERATE_SAFE_TIMEOUT.toMillis()))
                : timeout;
        var prompt = JsonSupport.MAPPER.toJson(Map.of(
                "goal", request.spec().goal(),
                "mode", request.spec().mode().toString(),
                "policy", Map.of(
                        "intelligence", request.spec().intelligence().id(),
                        "safety", request.spec().safety().id(),
                        "chooseOnlyEligibleCandidates", true,
                        "preferPlayerSafetyWhenHigh", request.spec().safety().progressMayBePreempted(),
                        "useFreshWorldObservation", true),
                "state", decisionState,
                "candidates", request.candidates().stream().map(step -> Map.of(
                        "id", step.id(), "kind", step.kind().toString(),
                        "capability", step.capability() == null ? "" : step.capability(),
                        "input", step.input(), "observeAfter", step.observeAfter(),
                        "preconditions", step.preconditions().stream().map(GoalAssertion::toMap).toList(),
                        "assertionCount", step.assertions().size())).toList(),
                "response", "Return JSON only: {candidateIndex: integer, rationale: string}. Choose only an eligible candidate; do not invent capabilities or bypass a precondition. When safety is high, select recovery or observation before progress if the player is threatened, falling, on fire, in lava, in water, or facing an unsafe drop."));
        var decision = requestJson(prompt, effort, callTimeout).orElse(null);
        if (decision == null || !decision.isJsonObject()) return Optional.empty();
        try {
            var object = decision.getAsJsonObject();
            var index = object.has("candidateIndex") ? object.get("candidateIndex").getAsInt()
                    : object.get("candidate_index").getAsInt();
            if (index < 0 || index >= request.candidates().size()) return Optional.empty();
            return Optional.of(new GoalDecision(index,
                    object.has("rationale") ? object.get("rationale").getAsString() : "model selection", effort));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    /**
     * Conservative hazard read for the situational deliberation budget above. This walks the
     * already depth/size-bounded projected decision state (see {@link GoalDecisionState}) for the
     * hazard signals the shared {@code minecraft.player.state.read} observation documents across
     * loaders (see {@code NeoForgeGoalObservation}: {@code player.onFire}/{@code inLava}/
     * {@code inWater}/{@code fallDistance}, {@code nearbyThreats[].targetingPlayer}, and
     * {@code localNavigation.forwardDropRisk}). Matching is by key/shape rather than a hardcoded
     * adapter-specific path so goal-engine stays loader-agnostic and any provider's observation
     * payload is covered the same way.
     */
    private static boolean isHazardous(Object value) {
        if (value instanceof Map<?, ?> map) {
            if (lowHealth(map)) return true;
            for (var entry : map.entrySet()) {
                if (hazardFlag(String.valueOf(entry.getKey()), entry.getValue())) return true;
                if (isHazardous(entry.getValue())) return true;
            }
            return false;
        }
        if (value instanceof List<?> list) {
            for (var item : list) if (isHazardous(item)) return true;
            return false;
        }
        return false;
    }

    private static boolean hazardFlag(String key, Object value) {
        if (Boolean.TRUE.equals(value)) {
            return switch (key) {
                case "onFire", "inLava", "inWater", "forwardDropRisk", "targetingPlayer" -> true;
                default -> false;
            };
        }
        // A player who has fallen more than two blocks and not yet landed is actively falling;
        // ordinary jumps/steps accumulate far less before onGround resets fallDistance to zero.
        return "fallDistance".equals(key) && value instanceof Number distance && distance.doubleValue() >= 2.0;
    }

    private static boolean lowHealth(Map<?, ?> map) {
        var health = map.get("health");
        var maxHealth = map.get("maxHealth");
        if (health instanceof Number healthValue && maxHealth instanceof Number maxValue && maxValue.doubleValue() > 0) {
            return healthValue.doubleValue() / maxValue.doubleValue() <= 0.3;
        }
        return false;
    }

    @Override
    public Optional<GoalPlan> plan(GoalPlanRequest request) {
        var spec = request.spec();
        var prompt = JsonSupport.MAPPER.toJson(Map.of(
                "goal", spec.goal(),
                "mode", spec.mode().toString(),
                "policy", Map.of(
                        "intelligence", spec.intelligence().id(),
                        "safety", spec.safety().id(),
                        "observation", spec.controls().observation(),
                        "combatPolicy", spec.controls().combatPolicy(),
                        "allowBlockBreaking", spec.controls().allowBlockBreaking(),
                        "allowBlockPlacing", spec.controls().allowBlockPlacing(),
                        "allowCommands", spec.controls().allowCommands()),
                "builtInTasks", request.builtInTasks(),
                "planContract", Map.of(
                        "segments", "non-empty array, at most 16 segments",
                        "stepsPerSegment", "at least one and at most 32 steps",
                        "stepKinds", List.of("observe", "invoke", "assert"),
                        "stateHandoff", "outputs are available under steps.<id>; use ${steps.<id>.<field>} for later inputs",
                        "preconditions", "assertion-shaped and checked before invoke steps",
                        "terminal", "metadata.completionPredicateReady must be true and at least one assertion must prove the goal",
                        "allowedCapabilityFamilies", List.of("minecraft.*", "lodestone.*")),
                "prerequisiteRules", Map.of(
                        "highestIntelligence", "adaptive-v1 must plan the shortest legitimate prerequisite chain before the stated action, then verify each handoff from fresh observation",
                        "earlySurvival", "logs, leaves, and other genuinely hand-harvestable starter resources may be gathered by hand; do not mine down blindly to search for stone",
                        "toolTiers", Map.of(
                                "stoneOrCobblestone", "obtain and equip a wooden or better pickaxe before mining",
                                "ironOrCopper", "obtain and equip a stone or better pickaxe before mining",
                                "diamondOrEmerald", "obtain and equip an iron or better pickaxe before mining",
                                "logs", "obtain and equip an axe when one is required for efficient tree work",
                                "dirtSandGravel", "use a shovel when it materially improves the route",
                                "hostileMob", "obtain or select a weapon before engaging unless escaping is safer"),
                        "recovery", "if a target requires a missing tool, stop the attack, observe inventory and nearby loaded chunks, acquire the tool naturally, equip it, and only then retry",
                        "navigation", "prefer safe walkable routes through loaded chunks; never use a fall, water, lava, teleport, command, or direct world mutation as a shortcut"),
                "instructions", "Return JSON only. Create a bounded declarative plan, starting with observations when the world state is unknown. Never emit shell, JavaScript, Python, seed manipulation, teleportation, direct inventory mutation, direct world mutation, chat commands, text injection, or raw minecraft.input key/mouse actions for a survival goal. Use typed minecraft.player.move and minecraft.player.interact calls or an existing native minecraft.goal.* actor for normal player actions. For adaptive-v1, a plan that attacks a tool-required block before proving the correct tool is equipped is invalid; include the natural gathering, crafting, equipping, and verification steps first. Do not turn a failed attack into repeated retries. Declare metadata.gameMode as survival for a survival goal and include explicit terminal assertions.",
                "response", "Return the plan object directly: {id, goal, metadata, segments}."));
        var root = requestJson(prompt).orElse(null);
        if (root == null || !root.isJsonObject()) return Optional.empty();
        try {
            var object = root.getAsJsonObject();
            var plan = object.has("plan") ? object.get("plan") : root;
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = (Map<String, Object>) JsonSupport.MAPPER.fromJson(plan,
                    new TypeToken<Map<String, Object>>() { }.getType());
            return Optional.of(GoalPlan.fromMap(raw));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private Optional<JsonElement> requestJson(String prompt) {
        return requestJson(prompt, reasoningEffort, timeout);
    }

    private Optional<JsonElement> requestJson(String prompt, String effort, Duration requestTimeout) {
        var body = JsonSupport.MAPPER.toJson(Map.of(
                "model", id,
                "reasoning_effort", effort,
                "temperature", 0,
                "messages", List.of(Map.of("role", "user", "content", prompt)),
                "response_format", Map.of("type", "json_object")));
        var builder = HttpRequest.newBuilder(endpoint).timeout(requestTimeout)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (apiKey != null && !apiKey.isBlank()) builder.header("Authorization", "Bearer " + apiKey);
        try {
            var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) return Optional.empty();
            var root = JsonParser.parseString(response.body());
            if (root.isJsonObject() && root.getAsJsonObject().has("choices")) {
                var choices = root.getAsJsonObject().getAsJsonArray("choices");
                if (choices.size() == 0) return Optional.empty();
                var content = choices.get(0).getAsJsonObject().getAsJsonObject("message").get("content");
                if (content == null || !content.isJsonPrimitive()) return Optional.empty();
                return Optional.of(JsonParser.parseString(content.getAsString()));
            }
            return Optional.of(root);
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private static String env(String name, String fallback) {
        var value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static long longEnv(String name, long fallback) {
        try {
            return Long.parseLong(env(name, String.valueOf(fallback)));
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }

    private static String effortEnv(String name, String fallback) {
        var value = env(name, fallback).toLowerCase(java.util.Locale.ROOT);
        return isValidReasoningEffort(value) ? value : fallback;
    }

    /**
     * Bug fix: LODESTONE_GOAL_MODEL_REASONING_EFFORT="xhigh" previously fell through to the
     * "low" fallback here (an unrecognized-value default, not a deliberate downgrade), silently
     * giving a caller who asked for the highest effort the lowest one instead. "xhigh" is now an
     * accepted value; any other unrecognized string still falls back exactly as before.
     */
    private static boolean isValidReasoningEffort(String value) {
        return switch (value) {
            case "low", "medium", "high", "xhigh" -> true;
            default -> false;
        };
    }
}
