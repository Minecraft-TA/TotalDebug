package com.github.minecraft_ta.totalDebugCompanion.navigation;

/** Editor-local state restored when revisiting a semantic destination. */
public record NavigationViewState(int caretOffset, int viewportX, int viewportY) {
    public static final NavigationViewState EMPTY = new NavigationViewState(-1, 0, 0);

    public NavigationViewState {
        if (caretOffset < -1) {
            throw new IllegalArgumentException("caretOffset must be -1 or greater");
        }
        if (viewportX < 0 || viewportY < 0) {
            throw new IllegalArgumentException("viewport coordinates must not be negative");
        }
    }

    public boolean hasCaret() {
        return this.caretOffset >= 0;
    }
}
