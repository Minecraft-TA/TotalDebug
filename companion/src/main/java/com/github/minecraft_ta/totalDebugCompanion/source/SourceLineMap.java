package com.github.minecraft_ta.totalDebugCompanion.source;

import java.util.Arrays;
import java.util.OptionalInt;

/** Immutable line pairs connecting class-file lines with displayed source lines. */
public final class SourceLineMap {
    private static final SourceLineMap EMPTY = new SourceLineMap(new int[0], new int[0]);

    private final int[] originalToDisplayed;
    private final int[] displayedToOriginal;

    private SourceLineMap(int[] originalToDisplayed, int[] displayedToOriginal) {
        this.originalToDisplayed = originalToDisplayed;
        this.displayedToOriginal = displayedToOriginal;
    }

    public static SourceLineMap empty() {
        return EMPTY;
    }

    /**
     * Creates a map from alternating {@code originalLine, displayedLine} pairs, which is the
     * format emitted by Vineflower.
     */
    public static SourceLineMap fromOriginalToDisplayed(int[] pairs) {
        if (pairs == null || pairs.length == 0) {
            return EMPTY;
        }
        if (pairs.length % 2 != 0) {
            throw new IllegalArgumentException("Source line mapping must contain complete line pairs");
        }

        int pairCount = pairs.length / 2;
        int[][] normalized = new int[pairCount][2];
        for (int i = 0; i < pairCount; i++) {
            int originalLine = pairs[i * 2];
            int displayedLine = pairs[i * 2 + 1];
            if (originalLine < 1 || displayedLine < 1) {
                throw new IllegalArgumentException("Source line mapping lines must be positive");
            }
            normalized[i][0] = originalLine;
            normalized[i][1] = displayedLine;
        }

        Arrays.sort(normalized, SourceLineMap::comparePair);
        int[] originalToDisplayed = flatten(normalized);
        for (int[] pair : normalized) {
            int originalLine = pair[0];
            pair[0] = pair[1];
            pair[1] = originalLine;
        }
        Arrays.sort(normalized, SourceLineMap::comparePair);
        return new SourceLineMap(originalToDisplayed, flatten(normalized));
    }

    public boolean isEmpty() {
        return this.originalToDisplayed.length == 0;
    }

    public int[] originalToDisplayed() {
        return this.originalToDisplayed.clone();
    }

    public int[] displayedToOriginal() {
        return this.displayedToOriginal.clone();
    }

    /** Returns whether Vineflower mapped the displayed source line to class-file code. */
    public boolean containsDisplayedLine(int displayedLine) {
        if (displayedLine < 1) {
            return false;
        }
        int low = 0;
        int high = this.displayedToOriginal.length / 2 - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            int candidate = this.displayedToOriginal[middle * 2];
            if (candidate < displayedLine) {
                low = middle + 1;
            } else if (candidate > displayedLine) {
                high = middle - 1;
            } else {
                return true;
            }
        }
        return false;
    }

    /** Returns the first mapped displayed line within the inclusive source range. */
    public OptionalInt firstMappedDisplayedLine(int firstLine, int lastLine) {
        if (firstLine < 1 || lastLine < firstLine) {
            throw new IllegalArgumentException("Displayed line range is invalid");
        }
        int low = 0;
        int high = this.displayedToOriginal.length / 2;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (this.displayedToOriginal[middle * 2] < firstLine) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        if (low >= this.displayedToOriginal.length / 2) {
            return OptionalInt.empty();
        }
        int displayedLine = this.displayedToOriginal[low * 2];
        return displayedLine <= lastLine ? OptionalInt.of(displayedLine) : OptionalInt.empty();
    }

    private static int comparePair(int[] left, int[] right) {
        int keyComparison = Integer.compare(left[0], right[0]);
        return keyComparison != 0 ? keyComparison : Integer.compare(left[1], right[1]);
    }

    private static int[] flatten(int[][] pairs) {
        int[] flattened = new int[pairs.length * 2];
        for (int i = 0; i < pairs.length; i++) {
            flattened[i * 2] = pairs[i][0];
            flattened[i * 2 + 1] = pairs[i][1];
        }
        return flattened;
    }
}
