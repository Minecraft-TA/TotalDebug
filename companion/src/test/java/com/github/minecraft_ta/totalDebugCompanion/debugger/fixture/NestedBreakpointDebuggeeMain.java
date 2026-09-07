package com.github.minecraft_ta.totalDebugCompanion.debugger.fixture;

public final class NestedBreakpointDebuggeeMain {
    private NestedBreakpointDebuggeeMain() {
    }

    public static void main(String[] args) {
        new Worker().run();
    }

    private static final class Worker {
        private void run() {
            int value = 41;
            System.out.println(value + 1); // DEBUG_NESTED_BREAKPOINT
        }
    }
}
