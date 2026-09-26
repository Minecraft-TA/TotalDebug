package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.catalog.CatalogIndex;
import com.github.minecraft_ta.totalDebugCompanion.inspection.ItemIconService;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.IconLoader;

import javax.swing.Icon;
import javax.swing.ImageIcon;
import java.awt.Component;
import java.util.Objects;

/** Rendered item icons for catalog lists; they are drawn again when the game publishes newer resources. */
public final class CatalogIcons {
    private final int size;
    private final IconLoader<CatalogIndex.ItemIcon> icons;
    private final Runnable removeListener;

    public CatalogIcons(ItemIconService service, int size) {
        Objects.requireNonNull(service, "service");
        this.size = size;
        this.icons = new IconLoader<>(4_096, 8, item -> service.render(item.model(), item.tints(), size)
                .thenApply(image -> image.<Icon>map(ImageIcon::new)));
        this.removeListener = service.addListener(this.icons::clear);
    }

    public int size() {
        return this.size;
    }

    /** The icon of an item, or null while it is being drawn or when it cannot be drawn. */
    public Icon icon(CatalogIndex.ItemIcon item, Component painting) {
        return item == null ? null : this.icons.icon(item, painting);
    }

    /** Forgets drawn icons, for example after the catalog changed. */
    public void clear() {
        this.icons.clear();
    }

    public void dispose() {
        this.removeListener.run();
        this.icons.clear();
    }
}
