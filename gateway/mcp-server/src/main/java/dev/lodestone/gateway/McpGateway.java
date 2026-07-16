// SPDX-License-Identifier: MIT
package dev.lodestone.gateway;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import dev.lodestone.goal.GoalMode;
import dev.lodestone.goal.GoalIntelligence;
import dev.lodestone.goal.GoalSafety;
import dev.lodestone.goal.GoalControls;
import dev.lodestone.protocol.JsonSupport;
import dev.lodestone.protocol.PermissionClass;
import dev.lodestone.protocol.ProtocolVersion;
import dev.lodestone.protocol.RequestEnvelope;
import dev.lodestone.protocol.ResultEnvelope;
import dev.lodestone.protocol.CapabilityDescriptor;
import dev.lodestone.runtime.AuthorizationPolicy;
import dev.lodestone.runtime.LodestoneRuntime;
import dev.lodestone.runtime.ResourceContent;

import java.util.EnumSet;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

/** JSON-RPC MCP gateway. It knows MCP and Lodestone protocol models, never native loader APIs. */
public final class McpGateway {
    private static final int MAX_SESSIONS = 128;
    private static final long SESSION_TTL_MS = 30 * 60 * 1000L;
    private static final String PLAYER_COMMAND_CAPABILITY = "minecraft.player.command.execute";
    private static final int WORLD_COORDINATE_LIMIT = 30_000_000;
    private static final int WORLD_EDIT_MIN_Y = -2048;
    private static final int WORLD_EDIT_MAX_Y = 2047;
    private static final Pattern UUID_TOKEN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern PLAYER_NAME_TOKEN = Pattern.compile("^[A-Za-z0-9_]{1,16}$");
    private static final String BLOCK_STATE_PATTERN =
            "^(?![^\\[]*\\[(?:[a-z0-9_]+=[a-z0-9_-]+,)*([a-z0-9_]+)=[a-z0-9_-]+"
                    + "(?:,[a-z0-9_]+=[a-z0-9_-]+)*,\\1=)"
                    + "[a-z0-9_.-]+:[a-z0-9_.-]+(?:/[a-z0-9_.-]+)*(?:\\[[a-z0-9_]+=[a-z0-9_-]+"
                    + "(?:,[a-z0-9_]+=[a-z0-9_-]+)*\\])?$";
    private static final Pattern BLOCK_STATE_TOKEN = Pattern.compile(BLOCK_STATE_PATTERN);
    private static final Set<String> WORLD_EDIT_DIRECTIONS =
            Set.of("up", "down", "north", "south", "east", "west");
    private static final AuthorizationPolicy FULL_PROCESS_CEILING =
            new AuthorizationPolicy(EnumSet.allOf(PermissionClass.class));
    private static final List<CapabilityAlias> COMPATIBILITY_ALIASES = List.of(
            new CapabilityAlias("ui_wait", "lodestone.ui.wait", "1.0"),
            new CapabilityAlias("ui_navigate", "lodestone.ui.navigate", "1.0"),
            new CapabilityAlias("ui_state", "minecraft.ui.state.read", "2.0"),
            new CapabilityAlias("get_player_position", "minecraft.player.state.read", "1.0"),
            new CapabilityAlias("get_server_info", "minecraft.server.info.read", "1.0"),
            new CapabilityAlias("search_minecraft_item", "minecraft.registry.item.search", "1.0"),
            new CapabilityAlias("get_player_context", "minecraft.player.context.read", "1.0"),
            new CapabilityAlias("get_nearby_entities", "minecraft.entity.nearby.read", "1.0"),
            new CapabilityAlias("calculate_shape", "lodestone.geometry.calculate", "1.0"),
            new CapabilityAlias("validate_mask", "lodestone.worldedit.mask.validate", "1.0"),
            new CapabilityAlias("place_furniture", "lodestone.furniture.place", "1.0"),
            new CapabilityAlias("place_building_pattern", "lodestone.building.pattern.place", "1.0"),
            new CapabilityAlias("capture_screenshot", "minecraft.client.screenshot.capture", "1.0"));
    private final LodestoneRuntime runtime;
    private final GoalService goalService;
    private final StaticCatalogTools staticCatalogTools;
    private final CallerGrantResolver callerGrantResolver;
    private final Map<String, GatewaySession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> subscriptionOwners = new ConcurrentHashMap<>();
    private final Object sessionAdmissionLock = new Object();
    private final ThreadLocal<GatewaySession> currentSession = new ThreadLocal<>();
    private final ThreadLocal<String> responseSessionId = new ThreadLocal<>();

    /**
     * Single-principal compatibility mode. Each session receives the full process permission
     * ceiling; {@link LodestoneRuntime} still intersects it with its configured process policy.
     */
    public McpGateway(LodestoneRuntime runtime) {
        this(runtime, ignored -> FULL_PROCESS_CEILING);
    }

    /** Create a gateway whose immutable session grants are resolved once at session admission. */
    public McpGateway(LodestoneRuntime runtime, CallerGrantResolver callerGrantResolver) {
        this.runtime = runtime;
        this.goalService = new GoalService(runtime);
        this.staticCatalogTools = new StaticCatalogTools(runtime);
        this.callerGrantResolver = callerGrantResolver;
    }

    @FunctionalInterface
    public interface CallerGrantResolver {
        AuthorizationPolicy resolve(String callerOrPrincipalId);
    }

    /** Return null for JSON-RPC notifications. */
    public String handle(String line) {
        return handle(line, "legacy-direct");
    }

    /** Handle one request in an isolated MCP session. */
    public String handle(String line, String clientSessionKey) {
        return handleInternal(line, clientSessionKey, false);
    }

    /** Handle an HTTP request using MCP's server-minted session rules. */
    public String handleHttp(String line, String clientSessionKey) {
        return handleInternal(line, clientSessionKey, true, null);
    }

    public String handleHttp(String line, String clientSessionKey, String protocolHeader) {
        return handleInternal(line, clientSessionKey, true, protocolHeader);
    }

    /** Session ID to place on the current HTTP response. */
    public String responseSessionId() {
        return responseSessionId.get();
    }

    public void clearResponseSessionId() {
        responseSessionId.remove();
    }

    private String handleInternal(String line, String clientSessionKey, boolean httpTransport) {
        return handleInternal(line, clientSessionKey, httpTransport, null);
    }

    private String handleInternal(String line, String clientSessionKey, boolean httpTransport, String protocolHeader) {
        responseSessionId.remove();
        pruneSessions();
        var requestedSession = clientSessionKey == null || clientSessionKey.isBlank()
                ? null : clientSessionKey.trim();
        final JsonObject request;
        try {
            var parsed = new JsonParser().parse(line);
            if (!parsed.isJsonObject()) throw new IllegalArgumentException("request must be a JSON object");
            request = parsed.getAsJsonObject();
        } catch (Exception malformed) {
            return errorResponse(JsonNull.INSTANCE, -32700, "invalid JSON-RPC request");
        }
        var method = text(request, "method", "");
        String sessionKey;
        GatewaySession session = null;
        boolean provisionalHttpSession = false;
        try {
            if (httpTransport) {
                if ("initialize".equals(method) && requestedSession == null) {
                    sessionKey = UUID.randomUUID().toString();
                    session = createSession(sessionKey);
                    provisionalHttpSession = true;
                } else {
                    if (requestedSession == null) {
                        return errorResponse(request.has("id") ? request.get("id") : JsonNull.INSTANCE,
                                -32001, "MCP session ID is required after initialize");
                    }
                    sessionKey = requestedSession;
                    session = sessions.get(sessionKey);
                    if (session == null) {
                        return errorResponse(request.has("id") ? request.get("id") : JsonNull.INSTANCE,
                                -32001, "MCP session ID is unknown or expired");
                    }
                    if (protocolHeader == null || !protocolHeader.equals(session.negotiatedMcpVersion)) {
                        return errorResponse(request.has("id") ? request.get("id") : JsonNull.INSTANCE,
                                -32002, "MCP protocol header does not match negotiated session version");
                    }
                }
            } else {
                sessionKey = requestedSession == null ? "legacy-direct" : requestedSession;
                session = sessions.get(sessionKey);
                if (session == null) {
                    session = createSession(sessionKey);
                }
            }
        } catch (GatewayException failure) {
            return errorResponse(request.has("id") ? request.get("id") : JsonNull.INSTANCE,
                    failure.code, failure.getMessage());
        }
        session.touch();
        responseSessionId.set(sessionKey);
        currentSession.set(session);
        try {
            var id = request.get("id");
            var result = dispatch(method, asObject(request.get("params")));
            if (id == null || result == null) {
                return null;
            }
            return response(id, result);
        } catch (GatewayException failure) {
            discardFailedInitialization(sessionKey, session, provisionalHttpSession);
            return errorResponse(safeId(line), failure.code, failure.getMessage());
        } catch (Exception failure) {
            discardFailedInitialization(sessionKey, session, provisionalHttpSession);
            return errorResponse(safeId(line), -32603,
                    "invalid or failed MCP request: " + failure.getClass().getSimpleName());
        } finally {
            currentSession.remove();
        }
    }

    private void discardFailedInitialization(String sessionKey, GatewaySession session, boolean provisional) {
        if (session != null && !session.initialized && sessions.remove(sessionKey, session)) {
            responseSessionId.remove();
        }
    }

    private GatewaySession createSession(String sessionKey) {
        synchronized (sessionAdmissionLock) {
            var existing = sessions.get(sessionKey);
            if (existing != null) {
                return existing;
            }
            if (sessions.size() >= MAX_SESSIONS) {
                pruneSessions();
                if (sessions.size() >= MAX_SESSIONS) {
                    throw new GatewayException(-32003, "MCP session capacity reached");
                }
            }
            var authorization = resolveCallerAuthorization(sessionKey);
            var created = new GatewaySession(sessionKey, authorization);
            sessions.put(sessionKey, created);
            return created;
        }
    }

    private AuthorizationPolicy resolveCallerAuthorization(String sessionKey) {
        try {
            var resolved = callerGrantResolver == null ? null : callerGrantResolver.resolve(sessionKey);
            if (resolved == null) {
                throw new GatewayException(-32004, "caller authorization could not be resolved");
            }
            return new AuthorizationPolicy(resolved.allowed());
        } catch (GatewayException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new GatewayException(-32004, "caller authorization could not be resolved");
        }
    }

    private void pruneSessions() {
        var cutoff = System.currentTimeMillis() - SESSION_TTL_MS;
        sessions.forEach((id, session) -> {
            if (session.lastSeen < cutoff && sessions.remove(id, session)) {
                session.subscriptions.forEach(subscriptionId -> {
                    try {
                        runtime.unsubscribe(id, subscriptionId);
                    } finally {
                        subscriptionOwners.remove(subscriptionId, id);
                    }
                });
                runtime.releaseCallerArtifacts(id);
            }
        });
    }

    private JsonElement dispatch(String method, JsonObject params) {
        if ("initialize".equals(method)) {
            var version = McpProtocol.negotiate(text(params, "protocolVersion", null));
            session().initialized = true;
            session().negotiatedMcpVersion = version;
            var result = new JsonObject();
            result.addProperty("protocolVersion", version);
            result.add("capabilities", object(Map.of(
                    "tools", object(Map.of("listChanged", false)),
                    "resources", object(Map.of("subscribe", false, "listChanged", false)))));
            result.add("serverInfo", object(Map.of("name", "lodestone", "version", "1.0.0")));
            result.addProperty("instructions", supportsNeoForgeGoals()
                    ? "Use minecraft_goal for bounded multi-action goals; use minecraft_goal_tasks to inspect contracts. Use lodestone_capability_invoke for individual typed access and inspect availability before mutation."
                    : "Use lodestone_capability_invoke for individual typed access and inspect availability before mutation.");
            return result;
        }
        if ("notifications/initialized".equals(method)) {
            return null;
        }
        if ("ping".equals(method)) {
            requireInitialized();
            return object(Map.of());
        }
        requireInitialized();
        return switch (method) {
            case "tools/list" -> toolsList();
            case "tools/call" -> toolCall(params);
            case "resources/list" -> resourcesList();
            case "resources/read" -> resourceRead(params);
            default -> throw new GatewayException(-32601, "method not found: " + method);
        };
    }

    private JsonElement toolsList() {
        var tools = new JsonArray();
        tools.add(tool("lodestone_status", "Read runtime and adapter health.", schema(Map.of())));
        tools.add(tool("lodestone_capabilities_list", "List or search negotiated capabilities.", schema(Map.of(
                "query", Map.of("type", "string")))));
        tools.add(tool("lodestone_capability_get", "Get one negotiated capability by stable ID.", schema(Map.of(
                "capability", Map.of("type", "string"))), List.of("capability")));
        tools.add(tool("lodestone_capability_search", "Search negotiated capabilities by ID or documentation.", schema(Map.of(
                "query", Map.of("type", "string"))), List.of("query")));
        tools.add(tool("lodestone_capability_invoke", "Invoke one typed capability after inspecting its state.", schema(Map.of(
                "capability", Map.of("type", "string"),
                "capabilityVersion", Map.of("type", "string"),
                "input", Map.of("type", "object"),
                "deadlineEpochMs", Map.of("type", "integer"),
                "idempotencyKey", Map.of("type", "string"),
                "dryRun", Map.of("type", "boolean"))), List.of("capability", "input")));
        if (supportsNeoForgeGoals()) {
            tools.add(tool("minecraft_goal", "Run a bounded Minecraft goal in script or realtime mode with independent intelligence and safety policies. Defaults to guarded-v1 plus balanced safety; long native tree and Nether workflows receive a 480-second default budget. Raw preserves legacy behavior; adaptive is the highest profile and uses a low-latency model for realtime high-level replanning. Custom plan steps may declare state preconditions; realtime filters ineligible candidates and script fails closed before invoking them.", schema(Map.ofEntries(
                    Map.entry("goal", Map.of("type", "string", "minLength", 1, "maxLength", 4096)),
                    Map.entry("mode", Map.of("type", "string", "enum", List.of("script", "realtime"))),
                    Map.entry("taskId", Map.of("type", "string", "minLength", 1, "maxLength", 128)),
                    Map.entry("maxSteps", Map.of("type", "integer", "minimum", 1, "maximum", 1000)),
                    Map.entry("maxDurationMs", Map.of("type", "integer", "minimum", 100, "maximum", 600000)),
                    Map.entry("dryRun", Map.of("type", "boolean")),
                    Map.entry("suppressInGameMessages", Map.of("type", "boolean")),
                    Map.entry("intelligence", Map.of("type", "string", "enum", List.of("raw-v1", "guarded-v1", "adaptive-v1"))),
                    Map.entry("safety", Map.of("type", "string", "enum", List.of("low", "balanced", "high"))),
                    Map.entry("observation", Map.of("type", "string", "enum", List.of("loaded-chunks"))),
                    Map.entry("combatPolicy", Map.of("type", "string", "enum", List.of("defensive", "avoid", "none"))),
                    Map.entry("allowBlockBreaking", Map.of("type", "boolean")),
                    Map.entry("allowBlockPlacing", Map.of("type", "boolean")),
                    Map.entry("allowCommands", Map.of("type", "boolean")),
                    Map.entry("plan", Map.of("type", "object", "description",
                            "Optional declarative plan; invoke steps may declare assertion-shaped preconditions")))), List.of("goal")));
            tools.add(tool("minecraft_goal_tasks", "List built-in Minecraft goal tasks, required capabilities, fixtures, and honest success contracts.", schema(Map.of(
                    "category", Map.of("type", "string", "minLength", 1, "maxLength", 64)))));
            tools.add(tool("minecraft_goal_benchmark", "Run matched script and realtime task cases and compare correctness before elapsed time. Use dryRun only where the capability documents dry-run support; otherwise use an isolated fixture.", schema(Map.of(
                    "taskIds", Map.of("type", "array", "maxItems", 32, "items", Map.of("type", "string", "minLength", 1, "maxLength", 128)),
                    "dryRun", Map.of("type", "boolean"),
                    "intelligence", Map.of("type", "string", "enum", List.of("raw-v1", "guarded-v1", "adaptive-v1")),
                    "safety", Map.of("type", "string", "enum", List.of("low", "balanced", "high")),
                    "observation", Map.of("type", "string", "enum", List.of("loaded-chunks")),
                    "combatPolicy", Map.of("type", "string", "enum", List.of("defensive", "avoid", "none")),
                    "allowBlockBreaking", Map.of("type", "boolean"),
                    "allowBlockPlacing", Map.of("type", "boolean"),
                    "allowCommands", Map.of("type", "boolean")))));
        }
        tools.add(tool("lodestone_events_subscribe", "Subscribe to a bounded event stream; sensitive raw input events are redacted.", schema(Map.of(
                "eventPrefix", Map.of("type", "string", "maxLength", 256),
                "bufferLimit", Map.of("type", "integer", "minimum", 1, "maximum", 10_000)))));
        tools.add(tool("lodestone_events_poll", "Poll and acknowledge buffered events.", schema(Map.of(
                "subscriptionId", Map.of("type", "string", "minLength", 1, "maxLength", 128),
                "maxEvents", Map.of("type", "integer", "minimum", 1, "maximum", 1_000))), List.of("subscriptionId")));
        tools.add(tool("lodestone_events_unsubscribe", "Remove an event subscription.", schema(Map.of(
                "subscriptionId", Map.of("type", "string", "minLength", 1, "maxLength", 128))),
                List.of("subscriptionId")));
        tools.add(tool("furniture_lookup", "Search or retrieve the bundled MIT-licensed executable furniture layouts.",
                schema(Map.of(
                        "action", Map.of("type", "string", "enum", List.of("search", "get")),
                        "query", Map.of("type", "string", "minLength", 1, "maxLength", 256),
                        "category", Map.of("type", "string", "minLength", 1, "maxLength", 256),
                        "tags", Map.of("type", "array", "maxItems", 16,
                                "items", Map.of("type", "string", "minLength", 1, "maxLength", 256)),
                        "furniture_id", Map.of("type", "string", "minLength", 1, "maxLength", 256))),
                List.of("action")));
        tools.add(tool("get_heightmap",
                "Read an inclusive rectangular loaded-column heightmap without loading chunks.",
                schema(Map.of(
                        "x1", Map.of("type", "integer"), "z1", Map.of("type", "integer"),
                        "x2", Map.of("type", "integer"), "z2", Map.of("type", "integer"),
                        "include_surface_blocks", Map.of("type", "boolean"))),
                List.of("x1", "z1", "x2", "z2")));
        tools.add(tool("analyze_lighting",
                "Analyze an inclusive loaded-world region using bounded deterministic light samples.",
                schema(Map.of(
                        "x1", Map.of("type", "integer"), "y1", Map.of("type", "integer"),
                        "z1", Map.of("type", "integer"), "x2", Map.of("type", "integer"),
                        "y2", Map.of("type", "integer"), "z2", Map.of("type", "integer"),
                        "resolution", Map.of("type", "integer", "minimum", 1, "maximum", 4),
                        "dark_spot_limit", Map.of("type", "integer", "minimum", 0, "maximum", 256),
                        "light_source_limit", Map.of("type", "integer", "minimum", 0, "maximum", 256))),
                List.of("x1", "y1", "z1", "x2", "y2", "z2")));
        var patternSchema = schema(Map.of(
                "action", Map.of("type", "string", "enum", List.of(
                        "browse", "categories", "subcategories", "tags", "search", "get")),
                "query", Map.of("type", "string", "minLength", 1, "maxLength", 256),
                "category", Map.of("type", "string", "minLength", 1, "maxLength", 256),
                "subcategory", Map.of("type", "string", "minLength", 1, "maxLength", 256),
                "tags", Map.of("type", "array", "maxItems", 16,
                        "items", Map.of("type", "string", "minLength", 1, "maxLength", 256)),
                "pattern_id", Map.of("type", "string", "minLength", 1, "maxLength", 256)));
        tools.add(tool("building_pattern_lookup",
                "Browse building metadata honestly; the donor's executable structured placement file is absent.",
                copyJson(patternSchema).getAsJsonObject(), List.of("action")));
        tools.add(tool("terrain_pattern_lookup",
                "Browse terrain metadata honestly; entries are guidance rather than executable blueprints.",
                copyJson(patternSchema).getAsJsonObject(), List.of("action")));
        tools.add(tool("building_template", "List and inspect five parametric guidance templates without claiming execution.",
                schema(Map.of(
                        "action", Map.of("type", "string", "enum", List.of("list", "search", "get", "customize")),
                        "template_id", Map.of("type", "string", "minLength", 1, "maxLength", 256),
                        "category", Map.of("type", "string", "minLength", 1, "maxLength", 256),
                        "difficulty", Map.of("type", "string", "enum", List.of("beginner", "intermediate", "advanced")),
                        "style_tags", Map.of("type", "array", "maxItems", 16,
                                "items", Map.of("type", "string", "minLength", 1, "maxLength", 256)))),
                List.of("action")));
        tools.add(worldEditSelectionTool());
        tools.add(worldEditRegionTool());
        tools.add(worldEditGenerationTool());
        tools.add(worldEditClipboardTool());
        tools.add(worldEditHistoryTool());
        for (var alias : COMPATIBILITY_ALIASES) {
            if (alias.name().equals("capture_screenshot") && !supportsStagedArtifacts()) {
                continue;
            }
            capability(alias.capability(), alias.version()).ifPresent(capability -> {
                if (alias.name().equals("capture_screenshot")) {
                    tools.add(captureScreenshotTool(capability));
                } else {
                    tools.add(tool(alias.name(), capability.documentation() + " Negotiated state: "
                                    + capability.availability() + ".",
                            JsonSupport.MAPPER.toJsonTree(capability.inputSchema()).getAsJsonObject()));
                }
            });
        }
        return object(Map.of("tools", tools));
    }

    private JsonElement captureScreenshotTool(CapabilityDescriptor capability) {
        return tool("capture_screenshot", capability.documentation() + " Negotiated state: "
                        + capability.availability() + ".",
                schema(Map.of(
                        "max_width", Map.of("type", "integer", "minimum", 1, "maximum", 8192,
                                "default", 1920),
                        "max_height", Map.of("type", "integer", "minimum", 1, "maximum", 8192,
                                "default", 1080))));
    }

    private JsonElement worldEditSelectionTool() {
        var properties = worldEditProperties();
        properties.put("action", Map.of("type", "string", "enum", List.of("pos1", "pos2")));
        properties.put("x", Map.of("type", "integer", "minimum", -WORLD_COORDINATE_LIMIT,
                "maximum", WORLD_COORDINATE_LIMIT));
        properties.put("y", Map.of("type", "integer", "minimum", WORLD_EDIT_MIN_Y,
                "maximum", WORLD_EDIT_MAX_Y));
        properties.put("z", Map.of("type", "integer", "minimum", -WORLD_COORDINATE_LIMIT,
                "maximum", WORLD_COORDINATE_LIMIT));
        var input = schema(properties);
        addActionBranches(input, actionBranches(
                "pos1", List.of("x", "y", "z"),
                "pos2", List.of("x", "y", "z")));
        return tool("worldedit_selection",
                "Set actor-local WorldEdit pos1 or pos2 using bounded coordinates. Requires WorldEdit installed and actor permission; other player activity may change this state.",
                input, List.of("player", "action", "x", "y", "z"));
    }

    private JsonElement worldEditRegionTool() {
        var properties = worldEditProperties();
        properties.put("action", Map.of("type", "string", "enum", List.of("set", "replace", "stack")));
        properties.put("block", worldEditBlockSchema());
        properties.put("from", worldEditBlockSchema());
        properties.put("to", worldEditBlockSchema());
        properties.put("count", Map.of("type", "integer", "minimum", 1, "maximum", 64));
        properties.put("direction", Map.of("type", "string",
                "enum", List.of("up", "down", "north", "south", "east", "west")));
        var input = schema(properties);
        addActionBranches(input, actionBranches(
                "set", List.of("block"),
                "replace", List.of("from", "to"),
                "stack", List.of("count", "direction")));
        return tool("worldedit_region",
                "Run bounded set, replace, or stack on actor's current WorldEdit selection. Requires WorldEdit installed, actor permission, and server edit limits; selection state may be changed elsewhere.",
                input, List.of("player", "action"));
    }

    private JsonElement worldEditGenerationTool() {
        var properties = worldEditProperties();
        properties.put("action", Map.of("type", "string",
                "enum", List.of("sphere", "cylinder", "pyramid")));
        properties.put("block", worldEditBlockSchema());
        properties.put("radius", Map.of("type", "integer", "minimum", 1, "maximum", 64));
        properties.put("height", Map.of("type", "integer", "minimum", 1, "maximum", 256));
        properties.put("size", Map.of("type", "integer", "minimum", 1, "maximum", 64));
        properties.put("hollow", Map.of("type", "boolean", "default", false));
        var input = schema(properties);
        addActionBranches(input, actionBranches(
                "sphere", List.of("block", "radius"),
                "cylinder", List.of("block", "radius", "height"),
                "pyramid", List.of("block", "size")), Map.of(
                "sphere", List.of("hollow"),
                "cylinder", List.of("hollow"),
                "pyramid", List.of("hollow")));
        return tool("worldedit_generation",
                "Generate a bounded basic shape at actor placement position, normally the player's location but mutable by WorldEdit. Requires WorldEdit installed, actor permission, and server edit limits.",
                input, List.of("player", "action", "block"));
    }

    private JsonElement worldEditClipboardTool() {
        var properties = worldEditProperties();
        properties.put("action", Map.of("type", "string", "enum", List.of("copy", "paste")));
        properties.put("skip_air", Map.of("type", "boolean", "default", false));
        properties.put("include_biomes", Map.of("type", "boolean", "default", false));
        properties.put("include_entities", Map.of("type", "boolean", "default", false));
        properties.put("original_position", Map.of("type", "boolean", "default", false));
        properties.put("select", Map.of("type", "boolean", "default", false));
        var input = schema(properties);
        addActionBranches(input, actionBranches("copy", List.of(), "paste", List.of()), Map.of(
                "copy", List.of("include_biomes", "include_entities"),
                "paste", List.of("skip_air", "include_biomes", "include_entities",
                        "original_position", "select")));
        return tool("worldedit_clipboard",
                "Copy or paste actor-local WorldEdit clipboard state with a bounded flag subset. Requires WorldEdit installed and actor permission; other player activity may change clipboard state.",
                input, List.of("player", "action"));
    }

    private JsonElement worldEditHistoryTool() {
        var properties = worldEditProperties();
        properties.put("action", Map.of("type", "string", "enum", List.of("undo", "redo")));
        properties.put("count", Map.of("type", "integer", "minimum", 1, "maximum", 64));
        var input = schema(properties);
        addActionBranches(input, actionBranches("undo", List.of("count"), "redo", List.of("count")));
        return tool("worldedit_history",
                "Undo or redo bounded actor-local WorldEdit history. Requires WorldEdit installed and actor permission; history may include edits made outside this gateway.",
                input, List.of("player", "action", "count"));
    }

    private LinkedHashMap<String, Object> worldEditProperties() {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("player", worldEditPlayerSchema());
        properties.put("capture", worldEditCaptureSchema());
        return properties;
    }

    private Map<String, Object> worldEditPlayerSchema() {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("uuid", Map.of("type", "string", "pattern", UUID_TOKEN.pattern(),
                "minLength", 36, "maxLength", 36));
        properties.put("name", Map.of("type", "string", "pattern", PLAYER_NAME_TOKEN.pattern(),
                "minLength", 1, "maxLength", 16));
        var schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("anyOf", List.of(Map.of("required", List.of("uuid")),
                Map.of("required", List.of("name"))));
        schema.put("additionalProperties", false);
        return schema;
    }

    private Map<String, Object> worldEditCaptureSchema() {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("enabled", Map.of("type", "boolean"));
        properties.put("windowMs", Map.of("type", "integer", "minimum", 0, "maximum", 2000));
        properties.put("maxMessages", Map.of("type", "integer", "minimum", 0, "maximum", 64));
        properties.put("maxBytes", Map.of("type", "integer", "minimum", 0, "maximum", 65536));
        return Map.of("type", "object", "properties", properties, "additionalProperties", false);
    }

    private Map<String, Object> worldEditBlockSchema() {
        return Map.of("type", "string", "minLength", 1, "maxLength", 256,
                "pattern", BLOCK_STATE_PATTERN,
                "description", "Namespaced single block-state token with unique property keys only; patterns, masks, tags, NBT, and existence claims are unsupported.");
    }

    private LinkedHashMap<String, List<String>> actionBranches(Object... values) {
        var result = new LinkedHashMap<String, List<String>>();
        for (var index = 0; index < values.length; index += 2) {
            @SuppressWarnings("unchecked") var required = (List<String>) values[index + 1];
            result.put(String.valueOf(values[index]), required);
        }
        return result;
    }

    private void addActionBranches(JsonObject schema, LinkedHashMap<String, List<String>> branches) {
        addActionBranches(schema, branches, Map.of());
    }

    private void addActionBranches(JsonObject schema, LinkedHashMap<String, List<String>> branches,
                                   Map<String, List<String>> optionalFields) {
        var rootProperties = schema.getAsJsonObject("properties");
        var oneOf = new JsonArray();
        branches.forEach((action, fields) -> {
            var branch = new JsonObject();
            branch.addProperty("type", "object");
            var branchProperties = new JsonObject();
            branchProperties.add("player", copyJson(rootProperties.get("player")));
            branchProperties.add("capture", copyJson(rootProperties.get("capture")));
            branchProperties.add("action", JsonSupport.MAPPER.toJsonTree(Map.of("const", action)));
            fields.forEach(field -> branchProperties.add(field, copyJson(rootProperties.get(field))));
            optionalFields.getOrDefault(action, List.of()).forEach(
                    field -> branchProperties.add(field, copyJson(rootProperties.get(field))));
            branch.add("properties", branchProperties);
            var required = new JsonArray();
            required.add("action");
            fields.forEach(required::add);
            branch.add("required", required);
            branch.addProperty("additionalProperties", false);
            oneOf.add(branch);
        });
        schema.add("oneOf", oneOf);
    }

    /** Copies JSON without Gson APIs introduced after the 2.8.0 bundled by Forge 1.16.5. */
    private static JsonElement copyJson(JsonElement value) {
        if (value == null || value.isJsonNull() || value.isJsonPrimitive()) {
            return value == null ? JsonNull.INSTANCE : value;
        }
        if (value.isJsonArray()) {
            var copy = new JsonArray();
            value.getAsJsonArray().forEach(item -> copy.add(copyJson(item)));
            return copy;
        }
        var copy = new JsonObject();
        value.getAsJsonObject().entrySet().forEach(entry -> copy.add(entry.getKey(), copyJson(entry.getValue())));
        return copy;
    }

    private JsonElement toolCall(JsonObject params) {
        var name = text(params, "name", "");
        var args = asObject(params.get("arguments"));
        return switch (name) {
            case "lodestone_status" -> toolResult(runtime.health());
            case "lodestone_capabilities_list" -> toolResult(Map.of(
                    "capabilities", runtime.capabilities(text(args, "query", ""))));
            case "lodestone_capability_get" -> systemCapability("lodestone.system.capabilities.get",
                    Map.of("id", requiredText(args, "capability")));
            case "lodestone_capability_search" -> systemCapability("lodestone.system.capabilities.search",
                    Map.of("query", requiredText(args, "query")));
            case "lodestone_capability_invoke" -> invoke(args);
            case "minecraft_goal" -> { requireNeoForgeGoals(); yield goalRun(args); }
            case "minecraft_goal_tasks" -> { requireNeoForgeGoals(); yield goalTasks(args); }
            case "minecraft_goal_benchmark" -> { requireNeoForgeGoals(); yield goalBenchmark(args); }
            case "lodestone_events_subscribe" -> subscribe(args);
            case "lodestone_events_poll" -> poll(args);
            case "lodestone_events_unsubscribe" -> unsubscribe(args);
            case "furniture_lookup" -> catalogTool(() -> staticCatalogTools.furniture(args));
            case "get_heightmap" -> invokeHeightmap(args);
            case "analyze_lighting" -> invokeLightAnalysis(args);
            case "building_pattern_lookup" -> catalogTool(() -> staticCatalogTools.buildingPatterns(args));
            case "terrain_pattern_lookup" -> catalogTool(() -> staticCatalogTools.terrainPatterns(args));
            case "building_template" -> catalogTool(() -> staticCatalogTools.buildingTemplates(args));
            case "worldedit_selection" -> invokeWorldEditSelection(args);
            case "worldedit_region" -> invokeWorldEditRegion(args);
            case "worldedit_generation" -> invokeWorldEditGeneration(args);
            case "worldedit_clipboard" -> invokeWorldEditClipboard(args);
            case "worldedit_history" -> invokeWorldEditHistory(args);
            default -> {
                var alias = COMPATIBILITY_ALIASES.stream()
                        .filter(candidate -> candidate.name().equals(name))
                        .findFirst().orElseThrow(() -> new GatewayException(-32602, "unknown tool: " + name));
                yield invokeAlias(alias, args);
            }
        };
    }

    private JsonElement invokeAlias(CapabilityAlias alias, JsonObject args) {
        if (capability(alias.capability(), alias.version()).isEmpty()) {
            throw new GatewayException(-32602, "capability alias is not negotiated: " + alias.name());
        }
        requireStagedArtifacts(alias.capability());
        Map<String, Object> input = JsonSupport.MAPPER.fromJson(args,
                TypeToken.getParameterized(Map.class, String.class, Object.class).getType());
        if (alias.name().equals("capture_screenshot")) {
            input = new LinkedHashMap<>();
            input.put("maxWidth", args.has("max_width") ? args.get("max_width").getAsInt() : 1920);
            input.put("maxHeight", args.has("max_height") ? args.get("max_height").getAsInt() : 1080);
        }
        return invokeCanonical(alias.capability(), alias.version(), input);
    }

    private JsonElement invokeWorldEditSelection(JsonObject args) {
        var action = requiredEnum(args, "action", Set.of("pos1", "pos2"));
        requireWorldEditFields(args, "action", "x", "y", "z");
        var x = requiredBoundedInteger(args, "x", -WORLD_COORDINATE_LIMIT, WORLD_COORDINATE_LIMIT);
        var y = requiredBoundedInteger(args, "y", WORLD_EDIT_MIN_Y, WORLD_EDIT_MAX_Y);
        var z = requiredBoundedInteger(args, "z", -WORLD_COORDINATE_LIMIT, WORLD_COORDINATE_LIMIT);
        return invokeWorldEditCommand(args, "//" + action + " " + x + "," + y + "," + z);
    }

    private JsonElement invokeWorldEditRegion(JsonObject args) {
        var action = requiredEnum(args, "action", Set.of("set", "replace", "stack"));
        var command = switch (action) {
            case "set" -> {
                requireWorldEditFields(args, "action", "block");
                yield "//set " + requiredBlockState(args, "block");
            }
            case "replace" -> {
                requireWorldEditFields(args, "action", "from", "to");
                yield "//replace " + requiredBlockState(args, "from") + " "
                        + requiredBlockState(args, "to");
            }
            case "stack" -> {
                requireWorldEditFields(args, "action", "count", "direction");
                var count = requiredBoundedInteger(args, "count", 1, 64);
                var direction = requiredEnum(args, "direction", WORLD_EDIT_DIRECTIONS);
                yield "//stack " + count + " " + direction;
            }
            default -> throw new IllegalStateException("validated WorldEdit region action was lost");
        };
        return invokeWorldEditCommand(args, command);
    }

    private JsonElement invokeWorldEditGeneration(JsonObject args) {
        var action = requiredEnum(args, "action", Set.of("sphere", "cylinder", "pyramid"));
        var command = switch (action) {
            case "sphere" -> {
                requireWorldEditFields(args, "action", "block", "radius", "hollow");
                var prefix = optionalBoolean(args, "hollow", false) ? "//hsphere " : "//sphere ";
                yield prefix + requiredBlockState(args, "block") + " "
                        + requiredBoundedInteger(args, "radius", 1, 64);
            }
            case "cylinder" -> {
                requireWorldEditFields(args, "action", "block", "radius", "height", "hollow");
                var prefix = optionalBoolean(args, "hollow", false) ? "//hcyl " : "//cyl ";
                yield prefix + requiredBlockState(args, "block") + " "
                        + requiredBoundedInteger(args, "radius", 1, 64) + " "
                        + requiredBoundedInteger(args, "height", 1, 256);
            }
            case "pyramid" -> {
                requireWorldEditFields(args, "action", "block", "size", "hollow");
                var prefix = optionalBoolean(args, "hollow", false) ? "//hpyramid " : "//pyramid ";
                yield prefix + requiredBlockState(args, "block") + " "
                        + requiredBoundedInteger(args, "size", 1, 64);
            }
            default -> throw new IllegalStateException("validated WorldEdit generation action was lost");
        };
        return invokeWorldEditCommand(args, command);
    }

    private JsonElement invokeWorldEditClipboard(JsonObject args) {
        var action = requiredEnum(args, "action", Set.of("copy", "paste"));
        var flags = new StringBuilder();
        if ("copy".equals(action)) {
            requireWorldEditFields(args, "action", "include_biomes", "include_entities");
            if (optionalBoolean(args, "include_biomes", false)) flags.append('b');
            if (optionalBoolean(args, "include_entities", false)) flags.append('e');
        } else {
            requireWorldEditFields(args, "action", "skip_air", "include_biomes", "include_entities",
                    "original_position", "select");
            if (optionalBoolean(args, "skip_air", false)) flags.append('a');
            if (optionalBoolean(args, "include_biomes", false)) flags.append('b');
            if (optionalBoolean(args, "include_entities", false)) flags.append('e');
            if (optionalBoolean(args, "original_position", false)) flags.append('o');
            if (optionalBoolean(args, "select", false)) flags.append('s');
        }
        var command = "//" + action + (flags.isEmpty() ? "" : " -" + flags);
        return invokeWorldEditCommand(args, command);
    }

    private JsonElement invokeWorldEditHistory(JsonObject args) {
        var action = requiredEnum(args, "action", Set.of("undo", "redo"));
        requireWorldEditFields(args, "action", "count");
        var count = requiredBoundedInteger(args, "count", 1, 64);
        return invokeWorldEditCommand(args, "//" + action + " " + count);
    }

    private JsonElement invokeWorldEditCommand(JsonObject args, String command) {
        var input = new LinkedHashMap<String, Object>();
        input.put("player", requiredWorldEditPlayer(args));
        input.put("command", command);
        var capture = worldEditCapture(args);
        if (capture != null) input.put("capture", capture);
        return invokeCanonical(PLAYER_COMMAND_CAPABILITY, "1.0", Map.copyOf(input));
    }

    private static void requireWorldEditFields(JsonObject args, String... actionFields) {
        var allowed = new HashSet<>(Set.of("player", "capture"));
        for (var field : actionFields) allowed.add(field);
        for (var entry : args.entrySet()) {
            var field = entry.getKey();
            if (!allowed.contains(field)) {
                throw new GatewayException(-32602, "field is not allowed for this WorldEdit action: " + field);
            }
        }
    }

    private static Map<String, Object> requiredWorldEditPlayer(JsonObject args) {
        var value = args.get("player");
        if (value == null || !value.isJsonObject()) {
            throw new GatewayException(-32602, "missing or invalid object field: player");
        }
        var player = value.getAsJsonObject();
        requireAllowedFields(player, Set.of("uuid", "name"), "player");
        var result = new LinkedHashMap<String, Object>();
        if (player.has("uuid")) {
            var uuid = requiredStrictText(player, "uuid", 36);
            if (!UUID_TOKEN.matcher(uuid).matches()) {
                throw new GatewayException(-32602, "player.uuid must be an exact UUID");
            }
            try {
                UUID.fromString(uuid);
            } catch (IllegalArgumentException invalid) {
                throw new GatewayException(-32602, "player.uuid must be an exact UUID");
            }
            result.put("uuid", uuid);
        }
        if (player.has("name")) {
            var name = requiredStrictText(player, "name", 16);
            if (!PLAYER_NAME_TOKEN.matcher(name).matches()) {
                throw new GatewayException(-32602, "player.name must be a vanilla account-name token");
            }
            result.put("name", name);
        }
        if (result.isEmpty()) {
            throw new GatewayException(-32602, "player requires uuid and/or name");
        }
        return Map.copyOf(result);
    }

    private static Map<String, Object> worldEditCapture(JsonObject args) {
        if (!args.has("capture")) return null;
        var value = args.get("capture");
        if (value == null || !value.isJsonObject()) {
            throw new GatewayException(-32602, "capture must be an object when present");
        }
        var capture = value.getAsJsonObject();
        requireAllowedFields(capture, Set.of("enabled", "windowMs", "maxMessages", "maxBytes"), "capture");
        var result = new LinkedHashMap<String, Object>();
        if (capture.has("enabled")) result.put("enabled", requiredBoolean(capture, "enabled"));
        if (capture.has("windowMs")) {
            result.put("windowMs", requiredBoundedInteger(capture, "windowMs", 0, 2000));
        }
        if (capture.has("maxMessages")) {
            result.put("maxMessages", requiredBoundedInteger(capture, "maxMessages", 0, 64));
        }
        if (capture.has("maxBytes")) {
            result.put("maxBytes", requiredBoundedInteger(capture, "maxBytes", 0, 65536));
        }
        return Map.copyOf(result);
    }

    private static String requiredBlockState(JsonObject args, String field) {
        var block = requiredStrictText(args, field, 256);
        if (!BLOCK_STATE_TOKEN.matcher(block).matches()) {
            throw new GatewayException(-32602, field + " must be a namespaced single block-state token");
        }
        var bracket = block.indexOf('[');
        if (bracket >= 0) {
            var keys = new HashSet<String>();
            var states = block.substring(bracket + 1, block.length() - 1).split(",");
            for (var state : states) {
                var key = state.substring(0, state.indexOf('='));
                if (!keys.add(key)) {
                    throw new GatewayException(-32602, field + " contains a duplicate block-state key");
                }
            }
        }
        return block;
    }

    private static String requiredEnum(JsonObject args, String field, Set<String> allowed) {
        var value = requiredStrictText(args, field, 64);
        if (!allowed.contains(value)) {
            throw new GatewayException(-32602, "invalid enum field: " + field);
        }
        return value;
    }

    private static String requiredStrictText(JsonObject args, String field, int maximumLength) {
        var value = args.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new GatewayException(-32602, "missing or invalid string field: " + field);
        }
        var text = value.getAsString();
        if (text.isEmpty() || text.length() > maximumLength || text.chars().anyMatch(Character::isISOControl)) {
            throw new GatewayException(-32602, "invalid string field: " + field);
        }
        return text;
    }

    private static boolean requiredBoolean(JsonObject args, String field) {
        var value = args.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new GatewayException(-32602, "missing or invalid boolean field: " + field);
        }
        return value.getAsBoolean();
    }

    private static boolean optionalBoolean(JsonObject args, String field, boolean fallback) {
        return args.has(field) ? requiredBoolean(args, field) : fallback;
    }

    private static int requiredBoundedInteger(JsonObject args, String field, int minimum, int maximum) {
        var value = requiredInteger(args, field);
        if (value < minimum || value > maximum) {
            throw new GatewayException(-32602, field + " must be between " + minimum + " and " + maximum);
        }
        return value;
    }

    private static void requireAllowedFields(JsonObject object, Set<String> allowed, String objectName) {
        for (var entry : object.entrySet()) {
            var field = entry.getKey();
            if (!allowed.contains(field)) {
                throw new GatewayException(-32602, "unknown " + objectName + " field: " + field);
            }
        }
    }

    private JsonElement invokeHeightmap(JsonObject args) {
        var x1 = requiredInteger(args, "x1");
        var z1 = requiredInteger(args, "z1");
        var x2 = requiredInteger(args, "x2");
        var z2 = requiredInteger(args, "z2");
        var input = Map.<String, Object>of(
                "x", Math.min(x1, x2), "z", Math.min(z1, z2),
                "sizeX", inclusiveSpan(x1, x2, 256, "x"),
                "sizeZ", inclusiveSpan(z1, z2, 256, "z"),
                "includeSurfaceBlocks", bool(args, "include_surface_blocks", true));
        return invokeCanonical("minecraft.world.heightmap.read", "1.0", input);
    }

    private JsonElement invokeLightAnalysis(JsonObject args) {
        var x1 = requiredInteger(args, "x1");
        var y1 = requiredInteger(args, "y1");
        var z1 = requiredInteger(args, "z1");
        var x2 = requiredInteger(args, "x2");
        var y2 = requiredInteger(args, "y2");
        var z2 = requiredInteger(args, "z2");
        var input = new java.util.LinkedHashMap<String, Object>();
        input.put("x", Math.min(x1, x2));
        input.put("y", Math.min(y1, y2));
        input.put("z", Math.min(z1, z2));
        input.put("sizeX", inclusiveSpan(x1, x2, 512, "x"));
        input.put("sizeY", inclusiveSpan(y1, y2, 384, "y"));
        input.put("sizeZ", inclusiveSpan(z1, z2, 512, "z"));
        input.put("resolution", boundedInteger(args, "resolution", 1, 1, 4));
        input.put("darkSpotLimit", boundedInteger(args, "dark_spot_limit", 100, 0, 256));
        input.put("lightSourceLimit", boundedInteger(args, "light_source_limit", 100, 0, 256));
        return invokeCanonical("minecraft.world.light.analyze", "1.0", Map.copyOf(input));
    }

    private JsonElement invokeCanonical(String capability, String version, Map<String, Object> input) {
        if (capability(capability, version).isEmpty()) {
            throw new GatewayException(-32602, "capability is not negotiated: " + capability + "@" + version);
        }
        requireStagedArtifacts(capability);
        var request = new RequestEnvelope(ProtocolVersion.CURRENT, UUID.randomUUID().toString(),
                runtime.sessionId(), capability, version, input, null, null, false);
        var session = session();
        return toolResult(runtime.invoke(request, session.id, session.authorization).join());
    }

    private JsonElement catalogTool(java.util.function.Supplier<Map<String, Object>> operation) {
        try {
            return toolResult(operation.get());
        } catch (IllegalArgumentException invalid) {
            throw new GatewayException(-32602, invalid.getMessage());
        }
    }

    private java.util.Optional<CapabilityDescriptor> capability(String id, String version) {
        return runtime.capabilities(id).stream()
                .filter(capability -> capability.id().equals(id) && capability.version().equals(version))
                .findFirst();
    }

    private JsonElement invoke(JsonObject args) {
        var capability = requiredText(args, "capability");
        if (capability.startsWith("minecraft.event.")) {
            throw new GatewayException(-32602,
                    "event capabilities must use the session-owned MCP event tools");
        }
        var inputElement = args.get("input");
        Map<String, Object> input = inputElement == null || inputElement.isJsonNull()
                ? Map.of() : JsonSupport.MAPPER.fromJson(inputElement,
                TypeToken.getParameterized(Map.class, String.class, Object.class).getType());
        var deadline = args.has("deadlineEpochMs") && !args.get("deadlineEpochMs").isJsonNull()
                ? args.get("deadlineEpochMs").getAsLong() : null;
        var request = new RequestEnvelope(ProtocolVersion.CURRENT, UUID.randomUUID().toString(),
                runtime.sessionId(), capability, text(args, "capabilityVersion", null), input, deadline,
                namespacedIdempotencyKey(text(args, "idempotencyKey", null)), bool(args, "dryRun", false));
        requireStagedArtifacts(capability);
        var session = session();
        return toolResult(runtime.invoke(request, session.id, session.authorization).join());
    }

    private JsonElement goalRun(JsonObject args) {
        var goal = requiredText(args, "goal");
        var modeName = text(args, "mode", "script").replace('-', '_').toUpperCase(java.util.Locale.ROOT);
        final GoalMode mode;
        try {
            mode = GoalMode.valueOf(modeName);
        } catch (IllegalArgumentException invalid) {
            throw new GatewayException(-32602, "mode must be script or realtime");
        }
        var taskId = text(args, "taskId", null);
        var maxSteps = boundedArgument(args, "maxSteps", 256, 1, 1000);
        var maxDurationMs = boundedLongArgument(args, "maxDurationMs",
                GoalSpec.defaultMaxDurationMs(goal, taskId), 100L, 600_000L);
        try {
            var customPlan = GoalService.parsePlan(args.get("plan"));
            var intelligence = GoalIntelligence.parse(text(args, "intelligence", "guarded-v1"));
            var safety = GoalSafety.parse(text(args, "safety", "balanced"));
            var controls = new GoalControls(text(args, "observation", "loaded-chunks"),
                    text(args, "combatPolicy", "defensive"),
                    bool(args, "allowBlockBreaking", true), bool(args, "allowBlockPlacing", true),
                    bool(args, "allowCommands", false));
            var report = goalService.run(goal, mode, taskId, maxSteps, maxDurationMs,
                    bool(args, "dryRun", false), customPlan,
                    bool(args, "suppressInGameMessages", false), intelligence, safety,
                    controls,
                    session().id, session().authorization);
            return toolResult(report);
        } catch (IllegalArgumentException invalid) {
            throw new GatewayException(-32602, invalid.getMessage());
        }
    }

    private JsonElement goalTasks(JsonObject args) {
        var descriptor = runtime.handshake().adapter();
        return toolResult(Map.of("tasks", goalService.tasks(text(args, "category", "")),
                "modelProviders", goalService.modelProviders(),
                "executorModel", dev.lodestone.goal.GoalModelProviders.EXECUTOR_MODEL_ID,
                "intelligenceLevels", List.of(
                        Map.of("id", "raw-v1", "behavior", "legacy action order; no prerequisite or safety intervention"),
                        Map.of("id", "guarded-v1", "behavior", "deterministic prerequisite/tool checks, safe navigation, obstruction and threat recovery"),
                        Map.of("id", "adaptive-v1", "behavior", "layered prerequisites, loaded-chunk replanning, segment checkpoints, and realtime model selection")),
                "safetyPolicies", List.of(
                        Map.of("id", "low", "behavior", "goal progress with minimal intervention"),
                        Map.of("id", "balanced", "avoid fluids and damaging routes; recover visible hazards"),
                        Map.of("id", "high", "player safety preempts progress; avoid unsafe drops and hostile threats")),
                "minecraftVersion", descriptor.gameVersion(), "loader", descriptor.loader()));
    }

    private JsonElement goalBenchmark(JsonObject args) {
        var taskIds = new ArrayList<String>();
        var taskIdNode = args.get("taskIds");
        if (taskIdNode != null && !taskIdNode.isJsonNull()) {
            if (!taskIdNode.isJsonArray() || taskIdNode.getAsJsonArray().size() > 32) {
                throw new GatewayException(-32602, "taskIds must be an array with at most 32 entries");
            }
            taskIdNode.getAsJsonArray().forEach(value -> taskIds.add(value.getAsString()));
        }
        final GoalIntelligence intelligence;
        final GoalSafety safety;
        try {
            intelligence = GoalIntelligence.parse(text(args, "intelligence", "guarded-v1"));
            safety = GoalSafety.parse(text(args, "safety", "balanced"));
        } catch (IllegalArgumentException invalid) {
            throw new GatewayException(-32602, invalid.getMessage());
        }
        var controls = new GoalControls(text(args, "observation", "loaded-chunks"),
                text(args, "combatPolicy", "defensive"), bool(args, "allowBlockBreaking", true),
                bool(args, "allowBlockPlacing", true), bool(args, "allowCommands", false));
        var descriptor = runtime.handshake().adapter();
        return toolResult(Map.of("minecraftVersion", descriptor.gameVersion(), "loader", descriptor.loader(), "results",
                goalService.benchmark(taskIds, bool(args, "dryRun", false), intelligence, safety, controls,
                        session().id, session().authorization),
                "intelligence", intelligence.id(), "safety", safety.id()));
    }

    private boolean supportsNeoForgeGoals() {
        var descriptor = runtime.handshake().adapter();
        return "neoforge".equalsIgnoreCase(descriptor.loader()) && "1.21.1".equals(descriptor.gameVersion());
    }

    private void requireNeoForgeGoals() {
        if (!supportsNeoForgeGoals()) {
            throw new GatewayException(-32601, "minecraft goals are enabled only for NeoForge 1.21.1");
        }
    }

    private static int boundedArgument(JsonObject args, String name, int defaultValue, int minimum, int maximum) {
        if (!args.has(name) || args.get(name).isJsonNull()) return defaultValue;
        if (!args.get(name).isJsonPrimitive() || !args.get(name).getAsJsonPrimitive().isNumber()) {
            throw new GatewayException(-32602, name + " must be an integer");
        }
        var value = args.get(name).getAsInt();
        if (value < minimum || value > maximum) throw new GatewayException(-32602,
                name + " must be between " + minimum + " and " + maximum);
        return value;
    }

    private static long boundedLongArgument(JsonObject args, String name, long defaultValue, long minimum, long maximum) {
        if (!args.has(name) || args.get(name).isJsonNull()) return defaultValue;
        if (!args.get(name).isJsonPrimitive() || !args.get(name).getAsJsonPrimitive().isNumber()) {
            throw new GatewayException(-32602, name + " must be an integer");
        }
        var value = args.get(name).getAsLong();
        if (value < minimum || value > maximum) throw new GatewayException(-32602,
                name + " must be between " + minimum + " and " + maximum);
        return value;
    }

    private boolean supportsStagedArtifacts() {
        return McpProtocol.CURRENT.equals(session().negotiatedMcpVersion);
    }

    private void requireStagedArtifacts(String capability) {
        if ("minecraft.client.screenshot.capture".equals(capability) && !supportsStagedArtifacts()) {
            throw new GatewayException(-32602,
                    "minecraft.client.screenshot.capture requires MCP protocol 2025-11-25");
        }
    }

    private JsonElement subscribe(JsonObject args) {
        var session = session();
        var result = invokeEventCapability("minecraft.event.subscribe", args);
        if (result.status() != ResultEnvelope.Status.OK) {
            return toolResult(result);
        }
        var subscription = result.output().get("subscription");
        var subscriptionId = JsonSupport.MAPPER.toJsonTree(subscription).getAsJsonObject()
                .get("id").getAsString();
        session.subscriptions.add(subscriptionId);
        subscriptionOwners.put(subscriptionId, session.id);
        return toolResult(subscription);
    }

    private JsonElement poll(JsonObject args) {
        var result = invokeEventCapability("minecraft.event.poll", args);
        return result.status() == ResultEnvelope.Status.OK ? toolResult(result.output()) : toolResult(result);
    }

    private JsonElement unsubscribe(JsonObject args) {
        var result = invokeEventCapability("minecraft.event.unsubscribe", args);
        if (result.status() != ResultEnvelope.Status.OK) {
            return toolResult(result);
        }
        var subscriptionId = requiredText(args, "subscriptionId");
        subscriptionOwners.remove(subscriptionId, session().id);
        session().subscriptions.remove(subscriptionId);
        return toolResult(result.output());
    }

    private ResultEnvelope invokeEventCapability(String capability, JsonObject args) {
        Map<String, Object> input = JsonSupport.MAPPER.fromJson(args,
                TypeToken.getParameterized(Map.class, String.class, Object.class).getType());
        var request = new RequestEnvelope(ProtocolVersion.CURRENT, UUID.randomUUID().toString(),
                runtime.sessionId(), capability, "1.0", input, null, null, false);
        var session = session();
        return runtime.invoke(request, session.id, session.authorization).join();
    }

    private String namespacedIdempotencyKey(String key) {
        return key == null ? null : session().id + ":" + key;
    }

    private JsonElement systemCapability(String capability, Map<String, Object> input) {
        var request = new RequestEnvelope(ProtocolVersion.CURRENT, UUID.randomUUID().toString(),
                runtime.sessionId(), capability, "1.0", input, null, null, false);
        var session = session();
        return toolResult(runtime.invoke(request, session.id, session.authorization).join());
    }

    private JsonElement resourcesList() {
        return object(Map.of("resources", runtime.resources(session().id)));
    }

    private JsonElement resourceRead(JsonObject params) {
        var uri = requiredText(params, "uri");
        final ResourceContent resource;
        try {
            resource = runtime.readResourceContent(uri, session().id);
        } catch (IllegalArgumentException missing) {
            throw new GatewayException(-32002, "resource not found");
        }
        var content = new LinkedHashMap<String, Object>();
        content.put("uri", uri);
        content.put("mimeType", resource.mimeType());
        if (resource.binary()) {
            content.put("blob", Base64.getEncoder().encodeToString(resource.bytes()));
        } else {
            content.put("text", resource.text());
        }
        return object(Map.of("contents", List.of(content)));
    }

    private JsonElement tool(String name, String description, JsonObject inputSchema) {
        return tool(name, description, inputSchema, List.of());
    }

    private JsonElement tool(String name, String description, JsonObject inputSchema, List<String> required) {
        var tool = new JsonObject();
        tool.addProperty("name", name);
        tool.addProperty("description", description);
        inputSchema.addProperty("additionalProperties", false);
        if (!required.isEmpty()) {
            var requiredNode = new JsonArray();
            required.forEach(requiredNode::add);
            inputSchema.add("required", requiredNode);
        }
        tool.add("inputSchema", inputSchema);
        return tool;
    }

    private JsonObject schema(Map<String, Object> properties) {
        var schema = new JsonObject();
        schema.addProperty("type", "object");
        var propertyNode = new JsonObject();
        properties.forEach((key, value) -> propertyNode.add(key, JsonSupport.MAPPER.toJsonTree(value)));
        schema.add("properties", propertyNode);
        return schema;
    }

    private JsonObject object(Map<String, ?> values) {
        return JsonSupport.MAPPER.toJsonTree(values).getAsJsonObject();
    }

    private JsonElement toolResult(Object value) {
        var result = new JsonObject();
        result.addProperty("isError", value instanceof ResultEnvelope envelope && envelope.status() != ResultEnvelope.Status.OK);
        var output = value instanceof ResultEnvelope envelope && envelope.status() == ResultEnvelope.Status.OK
                ? envelope.output() : null;
        var artifactUri = findArtifactUri(output);
        result.add("structuredContent", artifactUri == null
                ? JsonSupport.MAPPER.toJsonTree(value)
                : JsonSupport.MAPPER.toJsonTree(output));
        var content = new JsonArray();
        if (artifactUri != null) {
            try {
                var resource = runtime.readResourceContent(artifactUri, session().id);
                if (resource.binary()) {
                    var image = new JsonObject();
                    image.addProperty("type", "image");
                    image.addProperty("mimeType", resource.mimeType());
                    image.addProperty("data", Base64.getEncoder().encodeToString(resource.bytes()));
                    content.add(image);
                }
            } catch (RuntimeException ignored) {
                // The structured result remains authoritative if the ephemeral blob expires.
            }
        }
        if (content.size() == 0) {
            var text = new JsonObject();
            text.addProperty("type", "text");
            try {
                text.addProperty("text", JsonSupport.MAPPER.toJson(value));
            } catch (Exception failure) {
                text.addProperty("text", "{\"error\":\"serialization failed\"}");
            }
            content.add(text);
        }
        result.add("content", content);
        return result;
    }

    private static String findArtifactUri(Object value) {
        if (value instanceof Map<?, ?> map) {
            var artifact = map.get("artifact");
            if (artifact instanceof Map<?, ?> metadata) {
                var uri = metadata.get("uri");
                if (uri instanceof String text && text.startsWith("lodestone://artifacts/sha256/")) {
                    return text;
                }
            }
            var uri = map.get("uri");
            if (uri instanceof String text && text.startsWith("lodestone://artifacts/sha256/")) {
                return text;
            }
            for (var nested : map.values()) {
                var found = findArtifactUri(nested);
                if (found != null) return found;
            }
        } else if (value instanceof Iterable<?> iterable) {
            for (var nested : iterable) {
                var found = findArtifactUri(nested);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void requireInitialized() {
        if (!session().initialized) {
            throw new GatewayException(-32002, "MCP session is not initialized");
        }
    }

    private GatewaySession session() {
        var session = currentSession.get();
        if (session == null) {
            throw new GatewayException(-32002, "MCP session is not initialized");
        }
        return session;
    }

    private static String requiredText(JsonObject node, String field) {
        var value = text(node, field, null);
        if (value == null || value.isBlank()) {
            throw new GatewayException(-32602, "missing required field: " + field);
        }
        return value;
    }

    private String response(JsonElement id, JsonElement result) {
        var response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id);
        response.add("result", result);
        return response.toString();
    }

    private String errorResponse(JsonElement id, int code, String message) {
        var response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id);
        var error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message == null ? "request failed" : message);
        response.add("error", error);
        return response.toString();
    }

    private JsonElement safeId(String line) {
        try {
            var request = new JsonParser().parse(line).getAsJsonObject();
            return request.has("id") ? request.get("id") : JsonNull.INSTANCE;
        } catch (Exception ignored) {
            return JsonNull.INSTANCE;
        }
    }

    private static JsonObject asObject(JsonElement value) {
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }

    private static String text(JsonObject node, String field, String fallback) {
        var value = node.get(field);
        return value == null || value.isJsonNull() ? fallback : value.getAsString();
    }

    private static int integer(JsonObject node, String field, int fallback) {
        var value = node.get(field);
        return value == null || value.isJsonNull() ? fallback : value.getAsInt();
    }

    private static int requiredInteger(JsonObject node, String field) {
        var value = node.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new GatewayException(-32602, "missing or invalid integer field: " + field);
        }
        try {
            return value.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException failure) {
            throw new GatewayException(-32602, "integer field is out of range: " + field);
        }
    }

    private static int boundedInteger(JsonObject node, String field, int fallback, int minimum, int maximum) {
        var value = node.get(field);
        var result = value == null || value.isJsonNull() ? fallback : requiredInteger(node, field);
        if (result < minimum || result > maximum) {
            throw new GatewayException(-32602,
                    field + " must be between " + minimum + " and " + maximum);
        }
        return result;
    }

    private static int inclusiveSpan(int first, int second, int maximum, String axis) {
        var span = Math.abs((long) second - first) + 1L;
        if (span > maximum) {
            throw new GatewayException(-32602,
                    axis + " span must contain at most " + maximum + " coordinates");
        }
        return (int) span;
    }

    private static boolean bool(JsonObject node, String field, boolean fallback) {
        var value = node.get(field);
        return value == null || value.isJsonNull() ? fallback : value.getAsBoolean();
    }

    private static final class GatewaySession {
        private final String id;
        private final AuthorizationPolicy authorization;
        private final java.util.Set<String> subscriptions = ConcurrentHashMap.newKeySet();
        private volatile long lastSeen = System.currentTimeMillis();
        private volatile boolean initialized;
        private String negotiatedMcpVersion;

        private GatewaySession(String id, AuthorizationPolicy authorization) {
            this.id = id;
            this.authorization = authorization;
        }

        private void touch() {
            lastSeen = System.currentTimeMillis();
        }
    }

    private static final class GatewayException extends RuntimeException {
        private final int code;

        private GatewayException(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    private record CapabilityAlias(String name, String capability, String version) {
    }
}
