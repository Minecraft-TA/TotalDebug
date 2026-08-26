package com.github.minecraft_ta.totalDebugCompanion.debugger.fixture;

public final class FrameNavigationTarget {
    private FrameNavigationTarget() {
    }

    static int stopHere(int value) {
        return value + 1; // DEBUG_CALLEE_FRAME
    }
}
