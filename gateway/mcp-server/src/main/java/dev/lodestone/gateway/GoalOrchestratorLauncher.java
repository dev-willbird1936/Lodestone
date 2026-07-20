// SPDX-License-Identifier: MIT
package dev.lodestone.gateway;

import com.google.gson.reflect.TypeToken;
import dev.lodestone.goal.GoalRunReport;
import dev.lodestone.goal.GoalSpec;
import dev.lodestone.goal.GoalStatus;
import dev.lodestone.protocol.JsonSupport;
import dev.lodestone.runtime.AuthorizationPolicy;
import dev.lodestone.runtime.LodestoneRuntime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Runs a Minecraft goal by spawning {@code verification/goal-orchestrator-milestone1.py} as a real
 * MCP client against a private, throwaway {@link LoopbackHttpServer}/{@link McpGateway} pair that
 * shares the caller's own already-running {@link LodestoneRuntime}. Because the ephemeral gateway
 * shares that same runtime instance, the orchestrator subprocess can only ever reach the exact same
 * native Minecraft actor the caller could reach, with exactly the caller's own already-resolved
 * {@link AuthorizationPolicy} (never more), and every request it makes still crosses the same
 * runtime-level authorization/rate-limit/quarantine machinery any other MCP client would.
 *
 * <p>{@code GoalService} is responsible for serializing calls to {@link #launch} through
 * {@link GoalExecutionQueue}; this class assumes it is never called concurrently with another goal
 * run against the same runtime and performs no serialization of its own.
 *
 * <p>{@link #reportFromEvidence} is deliberately evidence-only (no process/subprocess involvement) so
 * the orchestrator's {@code summary-*.json} -&gt; {@link GoalRunReport} mapping can be unit-tested
 * against fake/stub evidence files without ever spawning Python.
 */
final class GoalOrchestratorLauncher {
    private static final boolean WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).contains("win");
    private static final SecureRandom TOKEN_SOURCE = new SecureRandom();
    private static final long PROCESS_TREE_KILL_GRACE_MS = 5_000L;
    private static final int STDERR_CAPTURE_LIMIT = 8_192;
    /**
     * The orchestrator's own {@code --max-turns} default is 20 and its docstring's proven live run
     * used 30; {@code GoalSpec.maxSteps} legally goes up to 1000 (a bound sized for the old
     * step-per-native-action engine), which would be a wildly expensive number of real model turns
     * here. Cap what this launcher actually passes through so an unusually large {@code maxSteps}
     * cannot silently balloon per-turn model cost/latency; {@code maxDurationMs} remains the real
     * wall-clock backstop regardless of this cap.
     */
    private static final int MAX_ORCHESTRATOR_TURNS = 60;

    private final LodestoneRuntime runtime;
    private final Path scriptPath;
    private final String pythonExecutable;

    GoalOrchestratorLauncher(LodestoneRuntime runtime) {
        this(runtime, defaultScriptPath(), defaultPythonExecutable());
    }

    GoalOrchestratorLauncher(LodestoneRuntime runtime, Path scriptPath, String pythonExecutable) {
        this.runtime = runtime;
        this.scriptPath = scriptPath;
        this.pythonExecutable = pythonExecutable;
    }

    GoalRunReport launch(GoalSpec spec, String callerSessionId, AuthorizationPolicy authorization) {
        var startedNanos = System.nanoTime();
        var token = randomToken();
        var ephemeralGateway = new McpGateway(runtime, ignoredCaller -> authorization);
        try (var loopback = new LoopbackHttpServer(ephemeralGateway, 0, token)) {
            loopback.start();
            var port = loopback.port();
            final Path evidenceDir;
            try {
                evidenceDir = Files.createTempDirectory("lodestone-goal-orchestrator-");
            } catch (IOException failure) {
                return infrastructureFailureReport(spec, startedNanos, "ORCHESTRATOR_EVIDENCE_DIR_FAILED",
                        "orchestrator:evidence-dir-failed",
                        "unable to create a throwaway evidence directory: " + failure.getMessage());
            }
            try {
                return runProcess(spec, startedNanos, port, token, evidenceDir);
            } finally {
                deleteRecursivelyQuietly(evidenceDir);
            }
        } catch (IOException failure) {
            return infrastructureFailureReport(spec, startedNanos, "ORCHESTRATOR_LOOPBACK_START_FAILED",
                    "orchestrator:loopback-start-failed",
                    "unable to start the ephemeral loopback MCP server: " + failure.getMessage());
        }
    }

    private GoalRunReport runProcess(GoalSpec spec, long startedNanos, int port, String token, Path evidenceDir) {
        var command = buildCommand(spec, port, token, evidenceDir);
        final Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(false).start();
        } catch (IOException failure) {
            return infrastructureFailureReport(spec, startedNanos, "ORCHESTRATOR_LAUNCH_FAILED",
                    "orchestrator:launch-failed",
                    "unable to start the goal orchestrator subprocess: " + failure.getMessage());
        }
        var stderrTail = new StringBuilder();
        var stdoutDrain = drainQuietly(process.getInputStream(), null);
        var stderrDrain = drainQuietly(process.getErrorStream(), stderrTail);
        final boolean finished;
        try {
            finished = process.waitFor(Math.max(1L, spec.maxDurationMs()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            killProcessTree(process);
            joinQuietly(stdoutDrain);
            joinQuietly(stderrDrain);
            return report(spec, GoalStatus.CANCELLED,
                    "ORCHESTRATOR_INTERRUPTED: cause=orchestrator:interrupted; the calling thread was "
                            + "interrupted while waiting for the orchestrator subprocess and its process tree "
                            + "was killed", elapsedMs(startedNanos));
        }
        if (!finished) {
            killProcessTree(process);
            joinQuietly(stdoutDrain);
            joinQuietly(stderrDrain);
            return report(spec, GoalStatus.TIMED_OUT,
                    "ORCHESTRATOR_TIMEOUT: cause=orchestrator:timeout; goal exceeded its maxDurationMs budget of "
                            + spec.maxDurationMs() + "ms and the orchestrator process tree was killed",
                    elapsedMs(startedNanos));
        }
        var exitCode = process.exitValue();
        joinQuietly(stdoutDrain);
        joinQuietly(stderrDrain);
        return reportFromEvidence(spec, elapsedMs(startedNanos), evidenceDir, exitCode, stderrTail.toString());
    }

    private List<String> buildCommand(GoalSpec spec, int port, String token, Path evidenceDir) {
        var command = new ArrayList<String>();
        command.add(pythonExecutable);
        command.add(scriptPath.toString());
        command.add("--port");
        command.add(String.valueOf(port));
        command.add("--token");
        command.add(token);
        command.add("--goal");
        command.add(spec.goal());
        command.add("--max-turns");
        command.add(String.valueOf(Math.min(spec.maxSteps(), MAX_ORCHESTRATOR_TURNS)));
        command.add("--evidence-dir");
        command.add(evidenceDir.toString());
        // The caller's LodestoneRuntime already has a live adapter attached to an in-progress game
        // session; the script's own ensure_fresh_world() create-world menu navigation is only correct
        // for its standalone verification runs starting cold at the title screen, and would be
        // actively harmful (unwanted menu clicks) against a session that is already in-world.
        command.add("--skip-bootstrap");
        return command;
    }

    /**
     * Maps the orchestrator's own {@code summary-*.json} evidence file to a {@link GoalRunReport}.
     * {@code elapsedMs} is supplied by the caller (rather than a start-time nanosecond stamp) so this
     * method itself needs no clock and can be exercised in isolation with a fake evidence directory.
     */
    static GoalRunReport reportFromEvidence(GoalSpec spec, long elapsedMs, Path evidenceDir,
                                            int exitCode, String stderrTail) {
        var summary = findSummaryFile(evidenceDir);
        if (summary.isEmpty()) {
            var detail = stderrTail == null || stderrTail.isBlank() ? "" : " stderr: " + truncate(stderrTail, 500);
            return report(spec, GoalStatus.INDETERMINATE,
                    "ORCHESTRATOR_NO_EVIDENCE: cause=orchestrator:no-evidence; subprocess exited (code="
                            + exitCode + ") without writing a summary file." + detail, elapsedMs);
        }
        final Map<String, Object> parsed;
        try {
            parsed = readSummary(summary.get());
        } catch (IOException | RuntimeException failure) {
            return report(spec, GoalStatus.INDETERMINATE,
                    "ORCHESTRATOR_EVIDENCE_UNREADABLE: cause=orchestrator:evidence-unreadable; unable to parse "
                            + summary.get() + ": " + failure.getMessage(), elapsedMs);
        }
        var orchestratorStatus = String.valueOf(parsed.getOrDefault("status", "")).trim().toUpperCase(Locale.ROOT);
        return switch (orchestratorStatus) {
            case "SUCCEEDED" -> report(spec, GoalStatus.SUCCEEDED,
                    "goal orchestrator reported SUCCEEDED", elapsedMs, parsed);
            case "GOAL_NOT_CONFIRMED" -> report(spec, GoalStatus.FAILED,
                    "ORCHESTRATOR_GOAL_NOT_CONFIRMED: cause=orchestrator:unconfirmed; the goal loop finished but "
                            + "the orchestrator's own completion verification did not confirm the goal state",
                    elapsedMs, parsed);
            case "BLOCKED" -> report(spec, GoalStatus.FAILED,
                    "ORCHESTRATOR_BLOCKED: cause=orchestrator:blocked; " + blockerReason(parsed), elapsedMs, parsed);
            case "ERROR" -> report(spec, GoalStatus.FAILED,
                    "ORCHESTRATOR_ERROR: cause=orchestrator:error; " + orchestratorExceptionSummary(parsed),
                    elapsedMs, parsed);
            default -> report(spec, GoalStatus.INDETERMINATE,
                    "ORCHESTRATOR_UNKNOWN_STATUS: cause=orchestrator:unknown-status; orchestrator summary reported "
                            + "an unrecognized status: " + parsed.getOrDefault("status", "<missing>"), elapsedMs, parsed);
        };
    }

    private static GoalRunReport report(GoalSpec spec, GoalStatus status, String message, long elapsedMs) {
        return report(spec, status, message, elapsedMs, Map.of());
    }

    private static GoalRunReport report(GoalSpec spec, GoalStatus status, String message, long elapsedMs,
                                        Map<String, Object> orchestratorSummary) {
        var runId = orchestratorSummary.get("runId") instanceof String value && !value.isBlank()
                ? value : UUID.randomUUID().toString();
        var selectedModel = orchestratorSummary.get("model") instanceof String value && !value.isBlank()
                ? value : "none";
        var state = new LinkedHashMap<String, Object>();
        state.put("orchestratorStatus", orchestratorSummary.getOrDefault("status", "none"));
        if (!orchestratorSummary.isEmpty()) {
            state.put("orchestratorSummary", orchestratorSummary);
        }
        return new GoalRunReport(runId, "none", spec.goal(), spec.mode(), status, message, elapsedMs, 0, 0,
                selectedModel, List.of(), state);
    }

    private static GoalRunReport infrastructureFailureReport(GoalSpec spec, long startedNanos, String label,
                                                              String cause, String detail) {
        return report(spec, GoalStatus.FAILED, label + ": cause=" + cause + "; " + detail, elapsedMs(startedNanos));
    }

    private static Optional<Path> findSummaryFile(Path evidenceDir) {
        if (evidenceDir == null || !Files.isDirectory(evidenceDir)) {
            return Optional.empty();
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(evidenceDir, "summary-*.json")) {
            Path newest = null;
            var newestModifiedMillis = Long.MIN_VALUE;
            for (var entry : entries) {
                // A fresh throwaway evidence directory should hold exactly one summary file; break
                // ties toward the most recently written one defensively rather than failing outright.
                var modifiedMillis = Files.getLastModifiedTime(entry).toMillis();
                if (newest == null || modifiedMillis > newestModifiedMillis) {
                    newest = entry;
                    newestModifiedMillis = modifiedMillis;
                }
            }
            return Optional.ofNullable(newest);
        } catch (IOException failure) {
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readSummary(Path summaryFile) throws IOException {
        var json = Files.readString(summaryFile, StandardCharsets.UTF_8);
        var parsed = (Map<String, Object>) JsonSupport.MAPPER.fromJson(json,
                TypeToken.getParameterized(Map.class, String.class, Object.class).getType());
        return parsed == null ? Map.of() : parsed;
    }

    private static String blockerReason(Map<String, Object> parsed) {
        var reason = parsed.get("blockerReason");
        return reason == null ? "orchestrator reported BLOCKED with no blockerReason" : String.valueOf(reason);
    }

    private static String orchestratorExceptionSummary(Map<String, Object> parsed) {
        var type = parsed.getOrDefault("exceptionType", "UnknownException");
        var message = parsed.getOrDefault("exceptionMessage", "no exception message reported");
        return type + ": " + message;
    }

    private static String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...(truncated)";
    }

    private static long elapsedMs(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
    }

    private static String randomToken() {
        var bytes = new byte[32];
        TOKEN_SOURCE.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** Best-effort: {@code ProcessHandle} descendants first, then a Windows {@code taskkill /T /F} fallback. */
    private static void killProcessTree(Process process) {
        try {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
        } catch (RuntimeException ignored) {
            // best-effort; fall through to the process's own handle and the OS-level fallback below
        }
        process.destroyForcibly();
        if (WINDOWS) {
            try {
                var killer = new ProcessBuilder("taskkill", "/PID", String.valueOf(process.pid()), "/T", "/F")
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
                killer.waitFor(PROCESS_TREE_KILL_GRACE_MS, TimeUnit.MILLISECONDS);
            } catch (IOException failure) {
                // best-effort fallback only; destroyForcibly() above already requested termination
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Drains a subprocess stream on a daemon thread so a full OS pipe buffer can never deadlock waitFor. */
    private static Thread drainQuietly(InputStream stream, StringBuilder sink) {
        var thread = new Thread(() -> {
            try (stream) {
                var buffer = new byte[4096];
                int read;
                while ((read = stream.read(buffer)) != -1) {
                    if (sink != null) {
                        synchronized (sink) {
                            if (sink.length() < STDERR_CAPTURE_LIMIT) {
                                sink.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                            }
                        }
                    }
                }
            } catch (IOException ignored) {
                // best-effort capture; the exit code and evidence file drive the real outcome
            }
        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(2_000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void deleteRecursivelyQuietly(Path directory) {
        if (directory == null) {
            return;
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best-effort cleanup of a throwaway temp directory
                }
            });
        } catch (IOException ignored) {
            // best-effort cleanup of a throwaway temp directory
        }
    }

    private static Path defaultScriptPath() {
        var override = System.getProperty("lodestone.goalOrchestrator.scriptPath");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        // Set for every Gradle Test task (see the root build script) but not for a live game client,
        // whose working directory is normally its own run/ folder rather than the repo root.
        var rootDir = System.getProperty("lodestone.rootDir");
        if (rootDir != null && !rootDir.isBlank()) {
            return Path.of(rootDir, "verification", "goal-orchestrator-milestone1.py");
        }
        // Defensive fallback for a live client: walk up from the working directory looking for the
        // repo-relative script. Production wiring of the live host-side spawn point should prefer
        // passing an explicit -Dlodestone.goalOrchestrator.scriptPath instead of relying on this.
        var candidate = Path.of("").toAbsolutePath();
        for (var depth = 0; depth < 8 && candidate != null; depth++, candidate = candidate.getParent()) {
            var script = candidate.resolve("verification").resolve("goal-orchestrator-milestone1.py");
            if (Files.isRegularFile(script)) {
                return script;
            }
        }
        return Path.of("verification", "goal-orchestrator-milestone1.py");
    }

    private static String defaultPythonExecutable() {
        var override = System.getenv("LODESTONE_PYTHON_EXECUTABLE");
        if (override != null && !override.isBlank()) {
            return override;
        }
        return WINDOWS ? "python" : "python3";
    }
}
