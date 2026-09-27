package com.github.minecraft_ta.totalDebugCompanion.catalog;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What was last read of the current world, so the views that show it follow one another: the World page reads the
 * world whenever it is shown, and the Project tree loads its World rows again when that read found something other than
 * what the tree shows.
 */
public final class WorldReadings {
    /** What the tree shows of a world: its folder and how many rules and datapacks it has. */
    public record Summary(Path directory, int gameRules, int datapacks) {
        /** No world, or one that could not be read. */
        public static final Summary NONE = new Summary(null, 0, 0);

        public static Summary of(CurrentWorld.Saved saved) {
            return saved == null ? NONE : new Summary(saved.directory(), saved.gameRules().size(), saved.datapacks().size());
        }
    }

    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private Summary last;

    /**
     * Records what the World page read; the listeners hear of it when it differs from the read before, or when it is the
     * first, since the tree may be reading the world at the same time. Any thread.
     */
    public void read(Summary summary) {
        Summary before;
        synchronized (this) {
            before = this.last;
            this.last = summary;
        }
        if (!summary.equals(before)) this.listeners.forEach(Runnable::run);
    }

    /** Records what the Project tree shows, so a read that finds the same asks for nothing; the listeners hear nothing. */
    public synchronized void shown(Summary summary) {
        this.last = summary;
    }

    /** Adds a listener, called on the thread that recorded the read; returns what removes it. */
    public Runnable addListener(Runnable listener) {
        this.listeners.add(listener);
        return () -> this.listeners.remove(listener);
    }
}
