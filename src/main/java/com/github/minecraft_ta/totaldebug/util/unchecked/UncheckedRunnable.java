package com.github.minecraft_ta.totaldebug.util.unchecked;

@FunctionalInterface
public interface UncheckedRunnable extends Runnable {

    void uncheckedRun() throws Throwable;

    @Override
    default void run() {
        try {
            uncheckedRun();
        } catch (Throwable e) {
            Unchecked.propagate(e);
            throw new IllegalStateException("unreachable");
        }
    }
}
