package com.github.minecraft_ta.totalDebugCompanion.source;

import java.util.Arrays;

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
