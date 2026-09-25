package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.catalog.CatalogFixtures;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ModResources;
import com.github.minecraft_ta.totalDebugCompanion.catalog.PackCatalogService;
import com.github.minecraft_ta.totalDebugCompanion.inspection.ItemIconService;
import com.github.minecraft_ta.totalDebugCompanion.navigation.ModTab;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeSourceCatalog;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.subject.SubjectHeader;
import com.github.minecraft_ta.totaldebug.protocol.execution.Fact;
import com.github.minecraft_ta.totaldebug.protocol.execution.FactLink;
import com.github.minecraft_ta.totaldebug.protocol.execution.FactSection;
import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectRef;
import com.github.minecraft_ta.totaldebug.storage.InstancePaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import javax.swing.Icon;
import javax.imageio.ImageIO;
import java.awt.Component;
import java.awt.Container;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class CatalogPanelsTest {
    @TempDir Path directory;

    @Test
    void wideModLogosStayReadableAndFitInsideTheirHeader() throws Exception {
        BufferedImage banner = new BufferedImage(600, 240, BufferedImage.TYPE_INT_ARGB);
        var graphics = banner.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, 600, 240);
        } finally {
            graphics.dispose();
        }
        ImageIO.write(banner, "png", this.directory.resolve("logo.png").toFile());
        Icon logo = ModPanel.readLogo(this.directory, "logo.png");
        assertNotNull(logo);
        assertTrue(logo.getIconWidth() > logo.getIconHeight() * 2, "A banner must keep its wide aspect ratio");
        onEdt(() -> {
            SubjectHeader header = new SubjectHeader();
            header.setIcon(logo);
            header.setTitle("Mekanism");
            header.setSize(header.getPreferredSize());
            layout(header);
            JLabel label = iconLabel(header, logo);
            assertNotNull(label);
            assertTrue(label.getWidth() >= logo.getIconWidth());
            assertTrue(label.getHeight() >= logo.getIconHeight());
        });
    }

    private static void layout(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) if (child instanceof Container nested) layout(nested);
    }

    private static JLabel iconLabel(Container container, Icon icon) {
        for (Component child : container.getComponents()) {
            if (child instanceof JLabel label && label.getIcon() == icon) return label;
            if (child instanceof Container nested) {
                JLabel found = iconLabel(nested, icon);
                if (found != null) return found;
            }
        }
        return null;
    }

    @Test
    void aModPageListsWhatTheModRegisteredAndOpensDefinitions() throws Exception {
        PackCatalogService catalog = readyCatalog();
        List<NavigationTarget> opened = new ArrayList<>();
        try (ItemIconService icons = new ItemIconService()) {
            onEdt(() -> {
                ModPanel panel = new ModPanel("testmod", catalog, RuntimeSourceCatalog::empty, icons, this.directory, opened::add);
                try {
                    assertEquals("Test Mod", panel.title());
                    assertTrue(labels(panel).contains("1.2.3"), labels(panel)::toString);
                    assertEquals("Items 1", panel.tabs().getTitleAt(ModTab.ITEMS.ordinal()));
                    assertEquals("Entities 1", panel.tabs().getTitleAt(ModTab.ENTITIES.ordinal()));
                    assertEquals("Configuration 1", panel.tabs().getTitleAt(ModTab.CONFIGURATION.ordinal()));

                    panel.show(new NavigationTarget.ModPage("testmod", ModTab.ITEMS, ""));
                    assertEquals(new NavigationTarget.ModPage("testmod", ModTab.ITEMS, ""), panel.target());
                    CatalogEntryTable items = panel.entryTable(ModTab.ITEMS);
                    items.setFilter("block");
                    assertEquals(0, items.rowCount());
                    items.setFilter("widget");
                    assertEquals(1, items.rowCount());
                    assertEquals("testmod:widget", items.entryAt(0).id());
                } finally {
                    settle(panel.resourceLoad());
                    panel.dispose();
                }
            });
        }
    }

    @Test
    void aModOverviewLinksInstalledDependencies() throws Exception {
        PackCatalogService catalog = readyCatalog();
        try (ItemIconService icons = new ItemIconService()) {
            onEdt(() -> {
                ModPanel panel = new ModPanel("testmod", catalog, RuntimeSourceCatalog::empty, icons, this.directory, target -> { });
                try {
                    List<FactSection> sections = panel.sections(catalog.index().orElseThrow().mod("testmod").orElseThrow());
                    assertEquals(List.of("Mod", "Dependencies"), sections.stream().map(FactSection::title).toList());
                    assertEquals(List.of(
                            Fact.text("NeoForge", "Required, 21 or newer").withLink(FactLink.toSubject(new SubjectRef.Mod("neoforge"))),
                            Fact.text("jei", "Optional, client only, not installed")
                    ), sections.get(1).facts());
                } finally {
                    settle(panel.resourceLoad());
                    panel.dispose();
                }
            });
        }
    }

    @Test
    void anUnknownModSaysSo() throws Exception {
        PackCatalogService catalog = readyCatalog();
        try (ItemIconService icons = new ItemIconService()) {
            onEdt(() -> {
                ModPanel panel = new ModPanel("absent", catalog, RuntimeSourceCatalog::empty, icons, this.directory, target -> { });
                try {
                    assertTrue(labels(panel).contains("absent is not an installed mod"), labels(panel)::toString);
                    assertEquals(1, panel.tabs().getTabCount(), "Only the Overview has something to show");
                } finally {
                    settle(panel.resourceLoad());
                    panel.dispose();
                }
            });
        }
    }

    @Test
    void aDefinitionPageLinksItsModClassAndCounterpart() throws Exception {
        PackCatalogService catalog = readyCatalog();
        try (ItemIconService icons = new ItemIconService()) {
            onEdt(() -> {
                DefinitionPanel panel = new DefinitionPanel(
                        new SubjectRef.Definition(SubjectRef.DefinitionKind.BLOCK, "testmod:widget_block"),
                        catalog, RuntimeSourceCatalog::empty, icons, target -> { });
                try {
                    assertEquals("Widget Block", panel.title());
                    assertEquals(List.of(
                            Fact.text("ID", "testmod:widget_block"),
                            Fact.text("Mod", "Test Mod").withLink(FactLink.toSubject(new SubjectRef.Mod("testmod"))),
                            Fact.text("Class", "WidgetBlock").withLink(FactLink.toClass("testmod.WidgetBlock")),
                            Fact.text("Item", "Widget Block").withLink(FactLink.toSubject(
                                    new SubjectRef.Definition(SubjectRef.DefinitionKind.ITEM, "testmod:widget_block"))),
                            Fact.text("Block entity type", "testmod:widget_entity")
                    ), panel.sections().getFirst().facts());
                    assertTrue(labels(panel).contains("Block type"), labels(panel)::toString);
                } finally {
                    settle(panel.resourceLoad());
                    panel.dispose();
                }
            });
        }
    }

    @Test
    void aDefinitionOutsideTheCatalogExplainsWhy() throws Exception {
        PackCatalogService empty = new PackCatalogService(new InstancePaths(this.directory.resolve("none")));
        try (ItemIconService icons = new ItemIconService()) {
            onEdt(() -> {
                DefinitionPanel panel = new DefinitionPanel(
                        new SubjectRef.Definition(SubjectRef.DefinitionKind.ITEM, "testmod:widget"),
                        empty, RuntimeSourceCatalog::empty, icons, target -> { });
                try {
                    assertTrue(labels(panel).contains(CatalogMessages.unavailable(new PackCatalogService.None())),
                            labels(panel)::toString);
                } finally {
                    settle(panel.resourceLoad());
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
                DefinitionPanel.matching(resources, "testmod", "widget").stream().map(ModResources.Resource::path).toList());
    }

    private PackCatalogService readyCatalog() throws Exception {
        InstancePaths paths = new InstancePaths(this.directory.resolve("total-debug"));
        CatalogFixtures.catalog(CatalogFixtures.modJar(this.directory)).write(paths.catalog());
        PackCatalogService catalog = new PackCatalogService(paths);
        catalog.accept(CatalogFixtures.INVENTORY, paths.catalog(), Runnable::run);
        return catalog;
    }

    /** Waits for a page's resource listing, which keeps the test's mod file open while it runs. */
    private static void settle(CompletableFuture<?> loading) {
        try {
            loading.get(10, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // A failed listing has already closed the file.
        }
    }

    private static void onEdt(Runnable test) throws Exception {
        Throwable[] failure = new Throwable[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                test.run();
            } catch (Throwable throwable) {
                failure[0] = throwable;
            }
        });
        if (failure[0] instanceof Error error) throw error;
        if (failure[0] instanceof Exception exception) throw exception;
    }

    private static List<String> labels(Container container) {
        List<String> labels = new ArrayList<>();
        for (Component child : container.getComponents()) {
            if (child instanceof JLabel label && label.isVisible() && label.getText() != null) {
                labels.add(label.getText());
            }
            if (child instanceof Container nested) labels.addAll(labels(nested));
        }
        return labels;
    }
}
