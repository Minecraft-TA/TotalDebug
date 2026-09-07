package com.github.minecraft_ta.totaldebug.client.companion;

import java.util.Objects;

public record CompanionStartupProgress(
        Stage stage,
        String detail,
        long completedBytes,
        long totalBytes
) {
    static final long UNKNOWN_TOTAL = -1L;

    public CompanionStartupProgress {
        Objects.requireNonNull(stage, "stage");
        detail = Objects.requireNonNull(detail, "detail");
        if (completedBytes < 0L) {
            throw new IllegalArgumentException("completedBytes must not be negative");
        }
        if (totalBytes < UNKNOWN_TOTAL) {
            throw new IllegalArgumentException("totalBytes must be non-negative or UNKNOWN_TOTAL");
        }
    }

    static CompanionStartupProgress downloading(String version, long completedBytes, long totalBytes) {
        return new CompanionStartupProgress(Stage.DOWNLOADING, version, completedBytes, totalBytes);
    }

    static CompanionStartupProgress starting() {
        return stage(Stage.STARTING);
    }

    static CompanionStartupProgress connecting() {
        return stage(Stage.CONNECTING);
    }

    static CompanionStartupProgress ready() {
        return stage(Stage.READY);
    }

    static CompanionStartupProgress failed(String detail) {
        return new CompanionStartupProgress(Stage.FAILED, detail == null ? "" : detail, 0L, UNKNOWN_TOTAL);
    }

    public boolean hasDeterminateProgress() {
        return this.stage == Stage.DOWNLOADING && this.totalBytes > 0L;
    }

    public float fraction() {
        if (!hasDeterminateProgress()) {
            return 0.0F;
        }
        float fraction = (float) this.completedBytes / (float) this.totalBytes;
        return Math.clamp(fraction, 0.0F, 1.0F);
    }

    public int percentage() {
        if (!hasDeterminateProgress()) {
            return 0;
        }
        return (int) Math.min(100L, (long) (this.completedBytes * 100.0D / this.totalBytes));
    }

    private static CompanionStartupProgress stage(Stage stage) {
        return new CompanionStartupProgress(stage, "", 0L, UNKNOWN_TOTAL);
    }

    public enum Stage {
        DOWNLOADING,
        STARTING,
        CONNECTING,
        READY,
        FAILED
    }
}
