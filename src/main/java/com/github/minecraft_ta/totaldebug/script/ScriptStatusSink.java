package com.github.minecraft_ta.totaldebug.script;

@FunctionalInterface
public interface ScriptStatusSink {
    void send(int scriptId, ScriptStatus status);
}
