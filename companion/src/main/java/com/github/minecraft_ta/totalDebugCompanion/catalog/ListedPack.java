package com.github.minecraft_ta.totalDebugCompanion.catalog;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * A pack as a pack list shows it: its id as the game names it, such as {@code file/Tweaks}, whether the game applies
 * it, its folder or zip file when it has one of its own, or null, the title the running game gives it, such as
 * {@code Programmer Art}, or empty, and what the game allows for it.
 */
public record ListedPack(String id, State state, Path file, String title, Set<Rule> rules) {
    public ListedPack {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(title, "title");
        rules = rules.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(rules));
    }

    public ListedPack(String id, State state, Path file, String title) {
        this(id, state, file, title, Set.of());
    }

    public ListedPack(String id, State state, Path file) {
        this(id, state, file, "", Set.of());
    }

    public boolean is(Rule rule) {
        return this.rules.contains(rule);
    }

    /** Whether the game applies a pack. */
    public enum State {
        ENABLED,
        DISABLED,
        /** In a world's {@code datapacks} folder but in neither list: the game enables it when it loads the world next. */
        NEW
    }

    /** What the game allows for a pack. */
    public enum Rule {
        /** The game keeps it enabled, such as Minecraft's resources or the mods' data. */
        REQUIRED,
        /** It keeps its place in the order. */
        FIXED,
        /** It is part of the mods' pack, which the game orders as one. */
        PART_OF_MODS,
        /** Made for another version of the game; the game warns before enabling it. */
        INCOMPATIBLE,
        /** It requests features the world does not have, so the world cannot enable it. */
        MISSING_FEATURES
    }
}
