package com.github.minecraft_ta.totalDebugCompanion.catalog;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A pack as a pack list shows it: its id as the game names it, such as {@code file/Tweaks}, whether the game applies
 * it, its folder or zip file when it has one of its own, or null, and the title the running game gives it, such as
 * {@code Programmer Art}, or empty.
 */
public record ListedPack(String id, State state, Path file, String title) {
    public ListedPack {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(title, "title");
    }

    public ListedPack(String id, State state, Path file) {
        this(id, state, file, "");
    }

    /** Whether the game applies a pack. */
    public enum State {
        ENABLED,
        DISABLED,
        /** In a world's {@code datapacks} folder but in neither list: the game enables it when it loads the world next. */
        NEW
    }
}
