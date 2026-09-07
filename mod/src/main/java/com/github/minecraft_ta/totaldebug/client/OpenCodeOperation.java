package com.github.minecraft_ta.totaldebug.client;

import java.util.Objects;
import java.util.Optional;

/** Applies the shared F6 rule: open a resolved class, otherwise focus Companion. */
final class OpenCodeOperation {
    private final Actions actions;

    OpenCodeOperation(Actions actions) {
        this.actions = Objects.requireNonNull(actions, "actions");
    }

    void openOrFocus(Optional<Class<?>> targetClass) {
        Objects.requireNonNull(targetClass, "targetClass");
        if (targetClass.isPresent()) {
            this.actions.openClass(targetClass.get());
            return;
        }

        this.actions.focusCompanion();
    }

    interface Actions {
        void openClass(Class<?> targetClass);

        void focusCompanion();
    }
}
