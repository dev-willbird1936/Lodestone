// SPDX-License-Identifier: MIT
package dev.lodestone.gateway;

import dev.lodestone.goal.GoalControls;
import dev.lodestone.goal.GoalIntelligence;
import dev.lodestone.goal.GoalMode;
import dev.lodestone.goal.GoalSafety;
import dev.lodestone.goal.GoalSpec;
import dev.lodestone.goal.GoalStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link GoalOrchestratorLauncher#reportFromEvidence} directly against fake
 * {@code summary-*.json} files - no subprocess, no live client - covering every status the real
 * orchestrator's own {@code main()} can write (SUCCEEDED, GOAL_NOT_CONFIRMED, BLOCKED, ERROR) plus
 * the missing-evidence case (subprocess exited or was killed before ever writing a summary file) and
 * an unrecognized status string.
 */
class GoalOrchestratorLauncherTest {
    private static final GoalSpec SPEC = new GoalSpec("test goal", GoalMode.REALTIME, null, 10, 5_000, false, null,
            false, GoalIntelligence.GUARDED_V1, GoalSafety.BALANCED, GoalControls.defaults());

    @Test
    void succeededSummaryMapsToSucceededAndCarriesRunIdAndModel(@TempDir Path evidenceDir) throws IOException {
        writeSummary(evidenceDir, "{\"status\":\"SUCCEEDED\",\"runId\":\"abc123\",\"model\":\"claude-sonnet-5\"}");

        var report = GoalOrchestratorLauncher.reportFromEvidence(SPEC, 42L, evidenceDir, 0, "");

        assertEquals(GoalStatus.SUCCEEDED, report.status());
        assertEquals("abc123", report.runId());
        assertEquals("claude-sonnet-5", report.selectedModel());
        assertEquals(42L, report.elapsedMs());
    }

    @Test
    void goalNotConfirmedSummaryMapsToFailed(@TempDir Path evidenceDir) throws IOException {
        writeSummary(evidenceDir, "{\"status\":\"GOAL_NOT_CONFIRMED\",\"runId\":\"def456\"}");

        var report = GoalOrchestratorLauncher.reportFromEvidence(SPEC, 10L, evidenceDir, 0, "");

        assertEquals(GoalStatus.FAILED, report.status());
        assertTrue(report.message().contains("GOAL_NOT_CONFIRMED"), report.message());
        assertTrue(report.message().contains("cause=orchestrator:unconfirmed"), report.message());
    }

    @Test
    void blockedSummaryMapsToFailedAndCarriesBlockerReason(@TempDir Path evidenceDir) throws IOException {
        writeSummary(evidenceDir, "{\"status\":\"BLOCKED\",\"blockerReason\":\"ModelCallFailed: no auth\"}");

        var report = GoalOrchestratorLauncher.reportFromEvidence(SPEC, 10L, evidenceDir, 0, "");

        assertEquals(GoalStatus.FAILED, report.status());
        assertTrue(report.message().contains("cause=orchestrator:blocked"), report.message());
        assertTrue(report.message().contains("ModelCallFailed: no auth"), report.message());
    }

    @Test
    void errorSummaryMapsToFailedAndCarriesExceptionDetail(@TempDir Path evidenceDir) throws IOException {
        writeSummary(evidenceDir,
                "{\"status\":\"ERROR\",\"exceptionType\":\"ConnectionError\",\"exceptionMessage\":\"refused\"}");

        var report = GoalOrchestratorLauncher.reportFromEvidence(SPEC, 10L, evidenceDir, 1, "");

        assertEquals(GoalStatus.FAILED, report.status());
        assertTrue(report.message().contains("ConnectionError"), report.message());
        assertTrue(report.message().contains("refused"), report.message());
    }

    @Test
    void missingSummaryFileMapsToIndeterminateAndCarriesStderrTail(@TempDir Path evidenceDir) {
        // evidenceDir intentionally left empty: the subprocess exited (or was killed) before ever
        // writing a summary file, so nothing about the goal's actual outcome is knowable.
        var report = GoalOrchestratorLauncher.reportFromEvidence(SPEC, 10L, evidenceDir, 137, "stderr detail");

        assertEquals(GoalStatus.INDETERMINATE, report.status());
        assertTrue(report.message().contains("cause=orchestrator:no-evidence"), report.message());
        assertTrue(report.message().contains("code=137"), report.message());
        assertTrue(report.message().contains("stderr detail"), report.message());
    }

    @Test
    void unrecognizedStatusStringMapsToIndeterminate(@TempDir Path evidenceDir) throws IOException {
        writeSummary(evidenceDir, "{\"status\":\"SOMETHING_NEW\"}");

        var report = GoalOrchestratorLauncher.reportFromEvidence(SPEC, 10L, evidenceDir, 0, "");

        assertEquals(GoalStatus.INDETERMINATE, report.status());
        assertTrue(report.message().contains("cause=orchestrator:unknown-status"), report.message());
    }

    @Test
    void unparseableSummaryFileMapsToIndeterminateRatherThanThrowing(@TempDir Path evidenceDir) throws IOException {
        writeSummary(evidenceDir, "{not valid json");

        var report = GoalOrchestratorLauncher.reportFromEvidence(SPEC, 10L, evidenceDir, 0, "");

        assertEquals(GoalStatus.INDETERMINATE, report.status());
        assertTrue(report.message().contains("cause=orchestrator:evidence-unreadable"), report.message());
    }

    private static void writeSummary(Path evidenceDir, String json) throws IOException {
        Files.writeString(evidenceDir.resolve("summary-testrun.json"), json, StandardCharsets.UTF_8);
    }
}
