package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.catalog.CatalogIndex;
import com.github.minecraft_ta.totalDebugCompanion.inspection.ItemIconService;

import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Item icons for lists with thousands of rows. Only rows that are painted ask for an icon, a few renders run at a time,
 * and the component is repainted when one finishes, which asks for the next visible icons. Swing thread only.
 */
public final class CatalogIcons {
    static final int MAX_CACHED = 4_096;
    static final int MAX_IN_FLIGHT = 8;

    private record Key(String model, Map<Integer, Integer> tints) {
    }

    private final ItemIconService service;
    private final int size;
    private final Runnable removeListener;
    private final Map<Key, Optional<Icon>> cache = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, Optional<Icon>> eldest) {
            return size() > MAX_CACHED;
        }
    };
    private final Set<Key> inFlight = new HashSet<>();
    private long generation;

    public CatalogIcons(ItemIconService service, int size) {
        this.service = Objects.requireNonNull(service, "service");
        this.size = size;
        this.removeListener = service.addListener(this::clear);
    }

    public int size() {
        return this.size;
    }

    /** The icon of an item, or null while it is being drawn or when it cannot be drawn. */
    public Icon icon(CatalogIndex.ItemIcon item, Component repaint) {
        if (item == null) return null;
        Key key = new Key(item.model(), item.tints());
        Optional<Icon> cached = this.cache.get(key);
        if (cached != null) return cached.orElse(null);
        if (this.inFlight.size() < MAX_IN_FLIGHT && this.inFlight.add(key)) {
            long requested = this.generation;
            this.service.render(key.model(), key.tints(), this.size).whenComplete((image, failure) ->
                    SwingUtilities.invokeLater(() -> {
                        if (requested != this.generation) return;
                        this.inFlight.remove(key);
                        this.cache.put(key, failure == null ? image.map(ImageIcon::new) : Optional.empty());
                        repaint.repaint();
                    }));
        }
        return null;
    }

    /** Forgets drawn icons, for example after the game published newer resources. */
    public void clear() {
        this.generation++;
        this.cache.clear();
        this.inFlight.clear();
    }

    public void dispose() {
        this.removeListener.run();
        clear();
    }
}
