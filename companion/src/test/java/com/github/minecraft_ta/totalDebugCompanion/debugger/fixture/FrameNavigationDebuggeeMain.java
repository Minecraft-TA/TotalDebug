package com.github.minecraft_ta.totalDebugCompanion.debugger.fixture;

public final class FrameNavigationDebuggeeMain {
    private FrameNavigationDebuggeeMain() {
    }

    public static void main(String[] args) {
        FrameNavigationTarget target = new FrameNavigationImplementation();
        int result = target.stopHere(41); // DEBUG_CALLER_FRAME
        System.out.println(result);
    }
}
