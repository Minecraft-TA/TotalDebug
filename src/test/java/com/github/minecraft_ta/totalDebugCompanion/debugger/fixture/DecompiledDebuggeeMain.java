package com.github.minecraft_ta.totalDebugCompanion.debugger.fixture;

public final class DecompiledDebuggeeMain {
    private DecompiledDebuggeeMain() {
    }

    public static void main(String[] args) {
        int value = 10;








        value++; // DEBUG_DECOMPILED_BREAKPOINT
        System.out.println(value);
    }
}
