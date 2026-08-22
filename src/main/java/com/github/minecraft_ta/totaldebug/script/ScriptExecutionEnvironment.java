package com.github.minecraft_ta.totaldebug.script;

public enum ScriptExecutionEnvironment {
    THREAD,
    PRE_TICK,
    POST_TICK;

    public static ScriptExecutionEnvironment fromWireName(String name) {
        try {
            return valueOf(name);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IllegalArgumentException("Unknown script execution environment: " + name, exception);
        }
    }
}
