package com.github.minecraft_ta.totalDebugCompanion.debugger.fixture;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

public final class MethodHandleDebuggeeMain {
    public static void main(String[] args) throws Throwable {
        var target = MethodHandles.lookup().findStatic(
                MethodHandleDebuggeeMain.class, "inspect", MethodType.methodType(void.class, int.class));
        System.out.println("ready");
        System.in.read();
        target.invokeExact(42);
    }

    private static void inspect(int value) {
        System.out.println(value); // HIDDEN_FRAME_BREAK
    }
}
