package com.github.minecraft_ta.totalDebugCompanion.ui.categories.mods;

import com.github.minecraft_ta.totalDebugCompanion.catalog.ConfigSettingsFixture;
import com.github.minecraft_ta.totalDebugCompanion.catalog.KeyBindingControl;
import com.github.minecraft_ta.totalDebugCompanion.catalog.PackCatalogService;
import com.github.minecraft_ta.totalDebugCompanion.catalog.RegistryIds;
import com.github.minecraft_ta.totalDebugCompanion.change.ChangePipeline;
import com.github.minecraft_ta.totalDebugCompanion.game.GameLocations;
import com.github.minecraft_ta.totalDebugCompanion.inspection.ItemIconService;
import com.github.minecraft_ta.totalDebugCompanion.navigation.ModTab;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeSourceCatalog;
import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;
import com.github.minecraft_ta.totalDebugCompanion.ui.categories.CategoryTestSupport;
import com.github.minecraft_ta.totalDebugCompanion.ui.categories.content.CatalogEntryTable;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.subject.SubjectHeader;
import com.github.minecraft_ta.totalDebugCompanion.util.Signal;
import com.github.minecraft_ta.totaldebug.protocol.execution.Fact;
import com.github.minecraft_ta.totaldebug.protocol.execution.FactLink;
import com.github.minecraft_ta.totaldebug.protocol.execution.FactSection;
import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import javax.swing.Icon;
import javax.swing.JLabel;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModPanelTest {
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
        Icon logo = ModPanel.readLogo(List.of(new ModLogoIcons.Source(this.directory.toUri(), "logo.png")));
        assertNotNull(logo);
        assertTrue(logo.getIconWidth() > logo.getIconHeight() * 2, "A banner must keep its wide aspect ratio");
        CategoryTestSupport.onEdt(() -> {
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
        PackCatalogService catalog = CategoryTestSupport.readyCatalog(this.directory);
        List<NavigationTarget> opened = new ArrayList<>();
        try (ItemIconService icons = new ItemIconService()) {
            CategoryTestSupport.onEdt(() -> {
                ModPanel panel = new ModPanel("testmod", catalog, RuntimeSourceCatalog::empty, icons, this.directory, ConfigSettingsFixture.of(GameLocations.of(this.directory, false), ChangeRecord.inMemory()),
                        new KeyBindingControl(new ChangePipeline(GameLocations.of(this.directory, false), ChangeRecord.inMemory(), Runnable::run)), opened::add, new Signal());
                try {
                    assertEquals("Test Mod", panel.title());
                    assertTrue(CategoryTestSupport.labels(panel).contains("1.2.3"), CategoryTestSupport.labels(panel)::toString);
                    assertEquals("Content 4", panel.tabs().getTitleAt(ModTab.CONTENT.ordinal()),
                            "a block, an item, an entity type and a fluid; the block's item is listed as the block");
                    assertEquals("Configuration 1", panel.tabs().getTitleAt(ModTab.CONFIGURATION.ordinal()));

                    panel.show(new NavigationTarget.ModPage("testmod", ModTab.CONTENT, RegistryIds.ITEM));
                    assertEquals(new NavigationTarget.ModPage("testmod", ModTab.CONTENT, RegistryIds.ITEM), panel.target());
                    CatalogEntryTable table = panel.contentBrowser().table();
                    table.setFilter("block");
                    assertEquals(0, table.rowCount());
                    table.setFilter("widget");
                    assertEquals(1, table.rowCount());
                    assertEquals("testmod:widget", table.entryAt(0).id());
                    assertEquals(3, table.table().getColumnCount(), "one kind needs no Kind column");

                    panel.show(new NavigationTarget.ModPage("testmod", ModTab.CONTENT, ""));
                    assertEquals(2, table.rowCount(), "All lists the widget block and the widget item");
                    assertEquals("Kind", table.table().getColumnName(3));
                    assertEquals(new NavigationTarget.ModPage("testmod", ModTab.CONTENT, ""), panel.target());
                } finally {
                    CategoryTestSupport.settle(panel.resourceLoad());
                    panel.dispose();
                }
            });
        }
    }

    @Test
    void aModOverviewLinksInstalledDependencies() throws Exception {
        PackCatalogService catalog = CategoryTestSupport.readyCatalog(this.directory);
        try (ItemIconService icons = new ItemIconService()) {
            CategoryTestSupport.onEdt(() -> {
                ModPanel panel = new ModPanel("testmod", catalog, RuntimeSourceCatalog::empty, icons, this.directory, ConfigSettingsFixture.of(GameLocations.of(this.directory, false), ChangeRecord.inMemory()),
                        new KeyBindingControl(new ChangePipeline(GameLocations.of(this.directory, false), ChangeRecord.inMemory(), Runnable::run)), target -> { }, new Signal());
                try {
                    List<FactSection> sections = panel.sections(catalog.index().orElseThrow().mod("testmod").orElseThrow());
                    assertEquals(List.of("Mod", "Dependencies"), sections.stream().map(FactSection::title).toList());
                    assertEquals(List.of(
                            Fact.text("NeoForge", "Required, 21 or newer").withLink(FactLink.toSubject(new SubjectRef.Mod("neoforge")))
                    ), sections.get(1).facts(), "Dependencies on mods that are not installed are left out");
                } finally {
                    CategoryTestSupport.settle(panel.resourceLoad());
                    panel.dispose();
                }
            });
        }
    }

    @Test
    void anUnknownModSaysSo() throws Exception {
        PackCatalogService catalog = CategoryTestSupport.readyCatalog(this.directory);
        try (ItemIconService icons = new ItemIconService()) {
            CategoryTestSupport.onEdt(() -> {
                ModPanel panel = new ModPanel("absent", catalog, RuntimeSourceCatalog::empty, icons, this.directory, ConfigSettingsFixture.of(GameLocations.of(this.directory, false), ChangeRecord.inMemory()),
                        new KeyBindingControl(new ChangePipeline(GameLocations.of(this.directory, false), ChangeRecord.inMemory(), Runnable::run)), target -> { }, new Signal());
                try {
                    assertTrue(CategoryTestSupport.labels(panel).contains("absent is not an installed mod"), CategoryTestSupport.labels(panel)::toString);
                    assertEquals(1, panel.tabs().getTabCount(), "Only the Overview has something to show");
                } finally {
                    CategoryTestSupport.settle(panel.resourceLoad());
                    panel.dispose();
                }
            });
        }
    }
}
