package com.github.minecraft_ta.totalDebugCompanion.itemrender;

import java.awt.image.BufferedImage;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** Results from a non-fail-fast item render batch. */
public record ItemRenderBatchResult(List<Entry> entries, long elapsedNanos) {

    public ItemRenderBatchResult {
        entries = List.copyOf(entries);
        if (elapsedNanos < 0) {
            throw new IllegalArgumentException("elapsedNanos must be non-negative");
        }
    }

    public long succeededCount() {
        return this.entries.stream().filter(Entry::succeeded).count();
    }

    public long failedCount() {
        return this.entries.size() - succeededCount();
    }

    /** Successful requests whose image contains at least one nontransparent pixel. */
    public long visibleCount() {
        return this.entries.stream().filter(entry -> entry.visiblePixels() > 0).count();
    }

    public Duration elapsed() {
        return Duration.ofNanos(this.elapsedNanos);
    }

    /** A successful entry has no failure and owns an image when the batch retained images. */
    public record Entry(
            ItemRenderRequest request,
            BufferedImage image,
            Exception failure,
            int visiblePixels,
            long elapsedNanos
    ) {

        public Entry {
            Objects.requireNonNull(request, "request");
            if (image != null && failure != null) {
                throw new IllegalArgumentException("A failed entry cannot contain an image");
            }
            if (visiblePixels < 0 || visiblePixels > request.size() * request.size()
                    || (failure != null && visiblePixels != 0)) {
                throw new IllegalArgumentException("Invalid visible pixel count: " + visiblePixels);
            }
            if (elapsedNanos < 0) {
                throw new IllegalArgumentException("elapsedNanos must be non-negative");
            }
        }

        public boolean succeeded() {
            return this.failure == null;
        }

        public Duration elapsed() {
            return Duration.ofNanos(this.elapsedNanos);
        }
    }
}
