package com.github.minecraft_ta.totalDebugCompanion.ui.components;

import javax.swing.CellRendererPane;
import javax.swing.Icon;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Icons that take time to draw, such as rendered items, texture previews and mod logos, for lists with thousands of
 * rows. Only painted rows ask for an icon, a few load at a time, and the list or tree painting the icon repaints when
 * one finishes, which asks for the next visible icons. Swing thread only; the loads themselves run elsewhere.
 */
public final class IconLoader<K> {
    private final int maxInFlight;
    private final Function<K, CompletableFuture<Optional<Icon>>> load;
    private final Map<K, Optional<Icon>> cache;
    private final Set<K> inFlight = new HashSet<>();
    private long generation;

    public IconLoader(int maxCached, int maxInFlight, Function<K, CompletableFuture<Optional<Icon>>> load) {
        this.maxInFlight = maxInFlight;
        this.load = Objects.requireNonNull(load, "load");
        this.cache = new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, Optional<Icon>> eldest) {
                return size() > maxCached;
            }
        };
    }

    /** The icon for {@code key}, or null while it loads or when it cannot be drawn. */
    public Icon icon(K key, Component painting) {
        Optional<Icon> cached = this.cache.get(key);
        if (cached != null) return cached.orElse(null);
        if (this.inFlight.size() < this.maxInFlight && this.inFlight.add(key)) {
            long requested = this.generation;
            Component repaint = repaintTarget(painting);
            this.load.apply(key).whenComplete((icon, failure) -> SwingUtilities.invokeLater(() -> {
                if (requested != this.generation) return;
                this.inFlight.remove(key);
                this.cache.put(key, failure == null ? icon : Optional.empty());
                repaint.repaint();
            }));
        }
        return null;
    }

    /** Forgets loaded icons and ignores loads still running, for example after newer resources arrived. */
    public void clear() {
        this.generation++;
        this.cache.clear();
        this.inFlight.clear();
    }

    /** A renderer paints through a cell renderer pane; the list, table or tree that owns it is what must repaint. */
    private static Component repaintTarget(Component painting) {
        Component pane = SwingUtilities.getAncestorOfClass(CellRendererPane.class, painting);
        return pane != null && pane.getParent() != null ? pane.getParent() : painting;
    }
}
