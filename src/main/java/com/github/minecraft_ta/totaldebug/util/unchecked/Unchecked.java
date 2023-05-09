package com.github.minecraft_ta.totaldebug.util.unchecked;

public class Unchecked {

    private Unchecked() {}

    public static <T extends Throwable> void propagate(Throwable e) throws T {
        throw (T) e;
    }

    public static void propagate(UncheckedRunnable r) {
        r.run();
    }
}
