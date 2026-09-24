package com.github.minecraft_ta.totalDebugCompanion.debugger.fixture.z;

public final class Blocks {
    // Keep this non-constant so accessing it loads the class in the debuggee.
    public static final String CORRECT = new String("correct");

    private Blocks() {
    }
}
