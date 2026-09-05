package com.github.minecraft_ta.totalDebugCompanion.debugger;

/** Keeps an evaluation result inspectable until released or its originating pause ends. */
@FunctionalInterface
public interface DebuggerValueLease extends AutoCloseable {
    DebuggerValueLease NONE = () -> { };

    @Override void close();
}
