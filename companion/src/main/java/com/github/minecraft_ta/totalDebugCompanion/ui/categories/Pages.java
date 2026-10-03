package com.github.minecraft_ta.totalDebugCompanion.ui.categories;

import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.navigation.Page;
import com.github.minecraft_ta.totalDebugCompanion.ui.categories.changes.ChangesPage;
import com.github.minecraft_ta.totalDebugCompanion.ui.categories.configuration.PackConfigurationPage;
import com.github.minecraft_ta.totalDebugCompanion.ui.categories.content.ContentPage;
import com.github.minecraft_ta.totalDebugCompanion.ui.categories.keybindings.KeyBindingsPage;
import com.github.minecraft_ta.totalDebugCompanion.ui.categories.logs.LogsPage;
import com.github.minecraft_ta.totalDebugCompanion.ui.categories.mods.ModsPage;
import com.github.minecraft_ta.totalDebugCompanion.ui.categories.resources.PackPage;
import com.github.minecraft_ta.totalDebugCompanion.ui.categories.resources.PackResourcesPage;
import com.github.minecraft_ta.totalDebugCompanion.ui.categories.world.WorldPage;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Every category's page. A new category adds its page here. */
public final class Pages {
    private final Map<Class<?>, Page<?>> byTarget = new HashMap<>();

    public Pages() {
        this(List.of(new ModsPage(), new ContentPage(), new PackConfigurationPage(), new KeyBindingsPage(),
                new PackResourcesPage(), new PackPage(), new WorldPage(), new LogsPage(), new ChangesPage()));
    }

    Pages(List<Page<?>> pages) {
        for (Page<?> page : pages) {
            if (this.byTarget.put(page.target(), page) != null) {
                throw new IllegalStateException("Two pages open " + page.target().getSimpleName());
            }
        }
    }

    /** The page that opens {@code target}; fails for a category target no page opens. */
    @SuppressWarnings("unchecked")
    public <T extends NavigationTarget.CategoryTarget> Page<T> of(T target) {
        Page<?> page = this.byTarget.get(target.getClass());
        if (page == null) throw new IllegalStateException("No page opens " + target.getClass().getSimpleName());
        return (Page<T>) page;
    }
}
