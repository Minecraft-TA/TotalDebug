package com.github.minecraft_ta.totaldebug.util.unchecked;

import java.util.function.Supplier;

@FunctionalInterface
public interface UncheckedSupplier<T> extends Supplier<T> {

    T uncheckedGet() throws Throwable;

    @Override
    default T get() {
        try {
            return uncheckedGet();
        } catch (Throwable e) {
            Unchecked.propagate(e);
            throw new IllegalStateException("unreachable");
        }
    }
}
