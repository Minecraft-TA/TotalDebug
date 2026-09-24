package com.github.minecraft_ta.totalDebugCompanion.debugger.fixture.a;

public final class Blocks {
    // Keep this non-constant so accessing it loads the class in the debuggee.
    public static final String WRONG = new String("wrong");

    private Blocks() {
    }
}
