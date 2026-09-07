package com.github.minecraft_ta.totalDebugCompanion.debugger.fixture;

public final class FrameNavigationImplementation implements FrameNavigationTarget {
    @Override
    public int stopHere(int value) {
        return value + 1; // DEBUG_CALLEE_FRAME
    }
}
