package com.github.minecraft_ta.totalDebugCompanion.catalog;

import java.nio.file.Path;

/**
 * A pack as a pack list shows it: its id as the game names it, such as {@code file/Tweaks}, whether the game applies
 * it, and its folder or zip file when it has one of its own, or null.
 */
public record ListedPack(String id, State state, Path file) {
    /** Whether the game applies a pack. */
    public enum State {
        ENABLED,
        DISABLED,
        /** In a world's {@code datapacks} folder but in neither list: the game enables it when it loads the world next. */
        NEW
    }
}
