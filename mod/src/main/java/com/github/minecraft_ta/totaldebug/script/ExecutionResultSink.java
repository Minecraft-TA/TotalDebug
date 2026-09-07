package com.github.minecraft_ta.totaldebug.script;

import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionResult;

@FunctionalInterface
public interface ExecutionResultSink {
    void send(int scriptId, ExecutionResult result);
}
