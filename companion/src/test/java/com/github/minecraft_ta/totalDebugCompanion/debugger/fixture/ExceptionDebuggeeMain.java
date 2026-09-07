package com.github.minecraft_ta.totalDebugCompanion.debugger.fixture;

public final class ExceptionDebuggeeMain {
    private ExceptionDebuggeeMain() {
    }

    public static void main(String[] args) {
        throw new IllegalStateException("debugger proof"); // DEBUG_EXCEPTION_LOCATION
    }
}
