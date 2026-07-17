// SPDX-License-Identifier: MIT
package dev.lodestone.neoforge;

import java.util.HashSet;

/** Detects action loops from position, inventory, stage, and observed geometry, never view rotation. */
final class NeoForgeLivenessWatchdog {
    static final int STALL_TICKS = 90;
    static final int MAX_RECOVERIES = 3;
    private static final double EPOCH_ESCAPE_DISTANCE_SQUARED = 36.0;

    private String stage = "";
    private double progressX;
    private double progressY;
    private double progressZ;
    private double epochX;
    private double epochY;
    private double epochZ;
    private int inventoryRevision;
    private int ticksSinceMeaningfulProgress;
    private int recoveryCount;
    private boolean initialized;
    private final HashSet<Long> seenWorldRevisions = new HashSet<>();
    private final HashSet<Long> seenPoseRegions = new HashSet<>();

    static String activityKey(String stage) {
        for (var prefix : new String[]{"FIND_", "NAVIGATE_", "MINE_", "COLLECT_"}) {
            if (stage.startsWith(prefix) && stage.length() > prefix.length()) {
                return stage.substring(prefix.length());
            }
        }
        return stage;
    }

    TickResult tick(Sample sample) {
        if (!sample.goalMovementContext()) {
            reset(sample);
            return result(Action.NONE, sample);
        }
        if (!initialized || !stage.equals(sample.stage()) || inventoryRevision != sample.inventoryRevision()) {
            reset(sample);
            return result(Action.NONE, sample);
        }

        var epochDisplacement = squaredDistance(sample.x(), sample.y(), sample.z(), epochX, epochY, epochZ);
        var novelWorld = seenWorldRevisions.add(sample.worldRevision());
        var novelPoseRegion = seenPoseRegions.add(poseRegion(sample.x(), sample.y(), sample.z()));
        if (novelPoseRegion || novelWorld) {
            progressX = sample.x();
            progressY = sample.y();
            progressZ = sample.z();
            ticksSinceMeaningfulProgress = 0;
            if (epochDisplacement >= EPOCH_ESCAPE_DISTANCE_SQUARED && novelPoseRegion) {
                recoveryCount = 0;
                epochX = sample.x();
                epochY = sample.y();
                epochZ = sample.z();
            }
            return result(Action.NONE, sample);
        }

        ticksSinceMeaningfulProgress++;
        if (ticksSinceMeaningfulProgress < STALL_TICKS) return result(Action.NONE, sample);
        ticksSinceMeaningfulProgress = 0;
        if (recoveryCount >= MAX_RECOVERIES) return result(Action.LIVENESS_EXHAUSTED, sample);
        recoveryCount++;
        return result(Action.RECOVER, sample);
    }

    void meaningfulFrontierProgress(Sample sample) {
        progressX = sample.x();
        progressY = sample.y();
        progressZ = sample.z();
        ticksSinceMeaningfulProgress = 0;
        seenWorldRevisions.add(sample.worldRevision());
    }

    void restartAfterFrontier(Sample sample) {
        reset(sample);
    }

    int ticksSinceMeaningfulProgress() {
        return ticksSinceMeaningfulProgress;
    }

    int recoveryCount() {
        return recoveryCount;
    }

    double displacementFromProgress(double x, double y, double z) {
        return Math.sqrt(squaredDistance(x, y, z, progressX, progressY, progressZ));
    }

    private void reset(Sample sample) {
        initialized = true;
        stage = sample.stage();
        progressX = epochX = sample.x();
        progressY = epochY = sample.y();
        progressZ = epochZ = sample.z();
        inventoryRevision = sample.inventoryRevision();
        ticksSinceMeaningfulProgress = 0;
        recoveryCount = 0;
        seenWorldRevisions.clear();
        seenWorldRevisions.add(sample.worldRevision());
        seenPoseRegions.clear();
        seenPoseRegions.add(poseRegion(sample.x(), sample.y(), sample.z()));
    }

    private TickResult result(Action action, Sample sample) {
        return new TickResult(action, ticksSinceMeaningfulProgress, recoveryCount,
                displacementFromProgress(sample.x(), sample.y(), sample.z()), sample.worldRevision());
    }

    private static double squaredDistance(double ax, double ay, double az, double bx, double by, double bz) {
        var dx = ax - bx;
        var dy = ay - by;
        var dz = az - bz;
        return dx * dx + dy * dy + dz * dz;
    }

    private static long poseRegion(double x, double y, double z) {
        var regionX = Math.floorDiv((int) Math.floor(x), 4);
        var regionY = Math.floorDiv((int) Math.floor(y), 3);
        var regionZ = Math.floorDiv((int) Math.floor(z), 4);
        return ((long) regionX & 0x3FFFFFFL) << 38
                | ((long) regionZ & 0x3FFFFFFL) << 12
                | (long) regionY & 0xFFFL;
    }

    enum Action {
        NONE,
        RECOVER,
        LIVENESS_EXHAUSTED
    }

    record Sample(String stage, double x, double y, double z, int inventoryRevision, long worldRevision,
                  boolean goalMovementContext) {
    }

    record TickResult(Action action, int ticksSinceMeaningfulProgress, int recoveryCount, double displacement,
                      long worldRevision) {
    }
}
