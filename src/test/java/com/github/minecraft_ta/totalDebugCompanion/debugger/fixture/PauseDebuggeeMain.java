package com.github.minecraft_ta.totalDebugCompanion.debugger.fixture;

public final class PauseDebuggeeMain {
    private PauseDebuggeeMain() {
    }

    public static void main(String[] args) throws Exception {
        int sentinel = 73;
        System.out.println("ready");
        System.in.read(); // DEBUG_PAUSE_LOCATION
        System.out.println(sentinel);
    }
}
