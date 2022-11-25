package com.github.minecraft_ta.totaldebug.util.mappings;

import java.util.function.Supplier;

interface UncheckedSupplier<T> extends Supplier<T> {

    T uncheckedGet() throws Throwable;

    @Override
    default T get() {
        try {
            return uncheckedGet();
        } catch (Throwable e) {
            propagate(e);
            throw new IllegalStateException("unreachable");
        }
    }

    static <T extends Throwable> void propagate(Throwable e) throws T {
        throw (T) e;
    }
}
