package com.github.minecraft_ta.totaldebug.script;

@FunctionalInterface
public interface ExecutionResultSink {
    void send(int scriptId, ExecutionResult result);
}
