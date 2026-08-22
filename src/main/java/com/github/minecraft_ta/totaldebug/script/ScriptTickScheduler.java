package com.github.minecraft_ta.totaldebug.script;

import com.github.minecraft_ta.totaldebug.tick.TickPhase;

@FunctionalInterface
public interface ScriptTickScheduler {
    void submit(TickPhase phase, Runnable task);
}
