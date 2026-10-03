package com.github.minecraft_ta.totalDebugCompanion.ui.categories.content;

import com.github.minecraft_ta.totalDebugCompanion.catalog.CatalogFixtures;
import com.github.minecraft_ta.totalDebugCompanion.catalog.CatalogIndex;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ModResources;
import com.github.minecraft_ta.totalDebugCompanion.catalog.PackCatalogService;
import com.github.minecraft_ta.totalDebugCompanion.catalog.RegistryIds;
import com.github.minecraft_ta.totalDebugCompanion.inspection.ItemIconService;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeSourceCatalog;
import com.github.minecraft_ta.totalDebugCompanion.testui.UiTest;
import com.github.minecraft_ta.totalDebugCompanion.testui.UiTestScope;
import com.github.minecraft_ta.totalDebugCompanion.ui.categories.CategoryTestSupport;
import com.github.minecraft_ta.totalDebugCompanion.ui.categories.CatalogIcons;
import com.github.minecraft_ta.totalDebugCompanion.ui.categories.CatalogMessages;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.inspection.SubjectPanel;
import com.github.minecraft_ta.totalDebugCompanion.util.Signal;
import com.github.minecraft_ta.totaldebug.protocol.execution.Fact;
import com.github.minecraft_ta.totaldebug.protocol.execution.FactLink;
import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectRef;
import com.github.minecraft_ta.totaldebug.storage.InstancePaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@UiTest
class DefinitionDetailsTest {
    @TempDir Path directory;

    @Test
    void aDefinitionNamesItsClassAndRelatedEntries() throws Exception {
        PackCatalogService catalog = CategoryTestSupport.readyCatalog(this.directory);
        try (ItemIconService icons = new ItemIconService()) {
            DefinitionDetails.Services services = new DefinitionDetails.Services(catalog, RuntimeSourceCatalog::empty,
                    icons, target -> { }, new Signal());
            CategoryTestSupport.onEdt(() -> {
                DefinitionDetails block = new DefinitionDetails(
                        new SubjectRef.Definition(RegistryIds.BLOCK, "testmod:widget_block"), services, new JPanel(), () -> { });
                try {
                    assertEquals("Widget Block", block.title());
                    assertEquals("Test Mod", block.modName());
                    assertEquals(Optional.of(Fact.text("Class", "WidgetBlock").withLink(FactLink.toClass("testmod.WidgetBlock"))),
                            block.classFact());
                    assertEquals(List.of(
                            Fact.text("Item", "Widget Block").withLink(FactLink.toSubject(
                                    new SubjectRef.Definition(RegistryIds.ITEM, "testmod:widget_block"))),
                            Fact.text("Block entity type", "testmod:widget_entity")
                    ), block.related(), "a link into a registry that was not captured names its id");
                } finally {
                    CategoryTestSupport.settle(block.resourceLoad());
                    block.dispose();
                }
                DefinitionDetails fluid = new DefinitionDetails(new SubjectRef.Definition(RegistryIds.FLUID, "testmod:goo"),
                        services, new JPanel(), () -> { });
                try {
                    assertEquals("Goo", fluid.title());
                    assertEquals(Optional.of(Fact.text("Class", "GooFluid").withLink(FactLink.toClass("testmod.GooFluid"))),
                            fluid.classFact());
                } finally {
                    CategoryTestSupport.settle(fluid.resourceLoad());
                    fluid.dispose();
                }
            });
        }
    }

    @Test
    void aHiddenDefinitionPageNamesItsTabFromTheCatalogCapturedSince() throws Exception {
        InstancePaths paths = new InstancePaths(this.directory.resolve("total-debug"));
        PackCatalogService catalog = new PackCatalogService(paths);
        AtomicInteger reads = new AtomicInteger();
        try (ItemIconService icons = new ItemIconService()) {
            DefinitionDetails.Services services = new DefinitionDetails.Services(catalog, RuntimeSourceCatalog::empty,
                    icons, target -> { }, new Signal());
            DefinitionDetails[] details = new DefinitionDetails[1];
            CategoryTestSupport.onEdt(() -> details[0] = new DefinitionDetails(new SubjectRef.Definition(RegistryIds.BLOCK, "testmod:widget_block"),
                    services, new JPanel(), reads::incrementAndGet));
            try {
                CategoryTestSupport.onEdt(() -> assertEquals("testmod:widget_block", details[0].title(), "no catalog yet: the id"));

                CatalogFixtures.catalog(CatalogFixtures.modJar(this.directory)).write(paths.catalog());
                catalog.accept(CatalogFixtures.INVENTORY, paths.catalog(), Runnable::run);
                SwingUtilities.invokeAndWait(() -> { });
                CategoryTestSupport.onEdt(() -> {
                    assertEquals("Widget Block", details[0].title(), "the tab names it from the catalog captured since");
                    assertEquals(0, reads.get(), "the hidden page itself reads it once it is shown");
                });
            } finally {
                CategoryTestSupport.onEdt(() -> {
                    CategoryTestSupport.settle(details[0].resourceLoad());
                    details[0].dispose();
                });
            }
        }
    }

    @Test
    void aDefinitionOutsideTheCatalogExplainsWhy() throws Exception {
        PackCatalogService empty = new PackCatalogService(new InstancePaths(this.directory.resolve("none")));
        try (ItemIconService icons = new ItemIconService()) {
            CategoryTestSupport.onEdt(() -> {
                SubjectPanel panel = SubjectPanel.definition(new SubjectRef.Definition(RegistryIds.ITEM, "testmod:widget"),
                        new DefinitionDetails.Services(empty, RuntimeSourceCatalog::empty, icons, target -> { }, new Signal()));
                try {
                    assertTrue(CategoryTestSupport.labels(panel).contains(CatalogMessages.unavailable(new PackCatalogService.None())),
                            CategoryTestSupport.labels(panel)::toString);
                } finally {
                    panel.dispose();
                }
            });
        }
    }

    @Test
    void definitionResourcesMatchTheRegistryPath() throws Exception {
        List<ModResources.Resource> resources = ModResources.list(CatalogFixtures.modJar(this.directory));

        assertEquals(List.of("assets/testmod/models/item/widget.json", "assets/testmod/textures/item/widget.png",
                        "data/testmod/recipe/widget.json"),
                DefinitionDetails.matching(resources, "testmod", "widget").stream().map(ModResources.Resource::path).toList());
    }

    @Test
    void aContentKindNotListedLeavesAllForTheNextListing() throws Exception {
        PackCatalogService catalog = CategoryTestSupport.readyCatalog(this.directory);
        Map<String, List<CatalogIndex.Entry>> all = catalog.index().orElseThrow().content("testmod");
        assertTrue(all.containsKey(RegistryIds.FLUID) && all.containsKey(RegistryIds.ITEM), all.keySet()::toString);
        Map<String, List<CatalogIndex.Entry>> items = new LinkedHashMap<>();
        items.put(RegistryIds.ITEM, all.get(RegistryIds.ITEM));
        try (ItemIconService icons = new ItemIconService()) {
            String selected = UiTestScope.onEdt(() -> {
                ContentBrowser browser = new ContentBrowser(new CatalogIcons(icons, 16), entry -> null, target -> { }, null);
                browser.setContent(items);
                browser.select(RegistryIds.FLUID);
                browser.setContent(all);
                return browser.selectedKind();
            });
            assertEquals("", selected, "All stays, as the user saw it chosen");
        }
    }
}
