package com.github.minecraft_ta.totalDebugCompanion.debugger.fixture;

public final class ConditionalDebuggeeMain {
    private ConditionalDebuggeeMain() {
    }

    private static boolean isTarget(int iteration) {
        return iteration == 2;
    }

    public static void main(String[] args) {
        new ConditionalDebuggeeMain().run();
    }

    private void run() {
        for (int iteration = 0; iteration < 3; iteration++) {
            int snapshot = iteration; // DEBUG_CONDITIONAL_BREAKPOINT
            System.out.println(snapshot);
        }
    }
}
