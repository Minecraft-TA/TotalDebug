package com.github.minecraft_ta.totalDebugCompanion.ui.categories.resources;

import com.github.minecraft_ta.totalDebugCompanion.catalog.ModResources;
import com.github.minecraft_ta.totalDebugCompanion.testui.UiTest;
import com.github.minecraft_ta.totalDebugCompanion.testui.UiTestScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A category asked for settles against the listing shown, or the first one. */
@UiTest
class ResourceCategoriesTest {
    @TempDir Path directory;

    @Test
    void aResourceCategoryAskedForAfterAnEmptyListingIsNotAppliedByALaterOne() throws Exception {
        ResourceBrowser.Prepared textures = prepared(true);
        String selected = UiTestScope.onEdt(() -> {
            ResourceBrowser browser = new ResourceBrowser(target -> { });
            // As a mod page whose mod has no resources: its Resources tab is gone, and a navigation asks anyway.
            browser.setResources(ResourceBrowser.Prepared.NONE);
            browser.selectCategory("assets/textures");
            browser.setResources(textures);
            return browser.selectedCategory();
        });
        assertEquals("", selected);
    }

    @Test
    void aResourceCategoryAskedForBeforeTheFirstListingIsSelectedByIt() throws Exception {
        ResourceBrowser.Prepared textures = prepared(true);
        String selected = UiTestScope.onEdt(() -> {
            ResourceBrowser browser = new ResourceBrowser(target -> { });
            browser.selectCategory("assets/textures");
            browser.setResources(textures);
            return browser.selectedCategory();
        });
        assertEquals("assets/textures", selected);
    }

    @Test
    void aResourceCategoryNotListedLeavesAllForTheNextListing() throws Exception {
        ResourceBrowser.Prepared models = prepared(false);
        ResourceBrowser.Prepared textures = prepared(true);
        String selected = UiTestScope.onEdt(() -> {
            ResourceBrowser browser = new ResourceBrowser(target -> { });
            browser.setResources(models);
            // Asked for while a listing without it shows, as during a refresh that has not finished.
            browser.selectCategory("assets/textures");
            browser.setResources(textures);
            return browser.selectedCategory();
        });
        assertEquals("", selected, "All stays, as the user saw it chosen");
    }

    /** A mod's resources, prepared off the Swing thread: its models, and its textures too where asked. */
    private ResourceBrowser.Prepared prepared(boolean withTextures) throws Exception {
        Path mod = this.directory.resolve(withTextures ? "with-textures" : "models-only");
        Files.createDirectories(mod.resolve("assets/testmod/models/item"));
        Files.writeString(mod.resolve("assets/testmod/models/item/widget.json"), "{}");
        if (withTextures) {
            Files.createDirectories(mod.resolve("assets/testmod/textures/item"));
            Files.write(mod.resolve("assets/testmod/textures/item/widget.png"), new byte[0]);
        }
        return ResourceBrowser.prepare(ModResources.list(mod), Map.of(), Map.of());
    }
}
