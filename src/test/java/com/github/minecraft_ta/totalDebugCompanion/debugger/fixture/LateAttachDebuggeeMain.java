package com.github.minecraft_ta.totalDebugCompanion.debugger.fixture;

public final class LateAttachDebuggeeMain {
    private LateAttachDebuggeeMain() {
    }

    public static void main(String[] args) throws Exception {
        int sentinel = 87;
        System.out.println("ready");
        System.in.read();
        sentinel++; // DEBUG_LATE_ATTACH
        System.out.println(sentinel);
    }
}
