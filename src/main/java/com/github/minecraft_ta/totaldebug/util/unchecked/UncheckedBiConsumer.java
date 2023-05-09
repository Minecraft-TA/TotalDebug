package com.github.minecraft_ta.totaldebug.util.unchecked;

import java.util.function.BiConsumer;

@FunctionalInterface
public interface UncheckedBiConsumer<T, U> extends BiConsumer<T, U> {

    void uncheckedAccept(T t, U u) throws Throwable;

    @Override
    default void accept(T t, U u) {
        try {
            uncheckedAccept(t, u);
        } catch (Throwable throwable) {
            Unchecked.propagate(throwable);
        }
    }
}
