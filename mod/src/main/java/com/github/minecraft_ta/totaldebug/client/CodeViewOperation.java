package com.github.minecraft_ta.totaldebug.client;

import com.github.minecraft_ta.totaldebug.client.input.Selection;
import java.util.Objects;
import java.util.Optional;

/** Applies the shared F6 rule: inspect a world subject or open an item's class, otherwise focus Companion. */
final class CodeViewOperation {
    private final Actions actions;

    CodeViewOperation(Actions actions) {
        this.actions = Objects.requireNonNull(actions, "actions");
    }

    void inspectOrFocus(Optional<Selection> subject) {
        Objects.requireNonNull(subject, "subject");
        subject.ifPresentOrElse(this.actions::inspect, this.actions::focusCompanion);
    }

    interface Actions {
        void inspect(Selection subject);

        void focusCompanion();
    }
}
