package com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.RuntimeSnapshotBytecodeSource;
import com.github.minecraft_ta.totalDebugCompanion.catalog.CatalogFixtures;
import com.github.minecraft_ta.totalDebugCompanion.catalog.CatalogIndex;
import com.github.minecraft_ta.totalDebugCompanion.catalog.PackCatalogService;
import com.github.minecraft_ta.totalDebugCompanion.catalog.RegistryIds;
import com.github.minecraft_ta.totalDebugCompanion.navigation.ModTab;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeSourceCatalog;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree.DirectoryTreeItem;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree.TreeItem;
import com.github.minecraft_ta.totaldebug.storage.RuntimeInventory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.zip.ZipOutputStream;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModTreeItemsTest {
    @TempDir Path directory;

    @Test
    void capturedModsGroupTheirContentWithoutListingIt() throws Exception {
        Path jar = CatalogFixtures.modJar(this.directory);
        var snapshot = new ModTreeItems.Snapshot(
                new PackCatalogService.Ready(new CatalogIndex(CatalogFixtures.catalog(jar))), sources(jar), 0);

        List<TreeItem> mods = ModTreeItems.children(snapshot);

        List<TreeItem> pack = ModTreeItems.packChildren(snapshot);
        assertEquals(List.of(ModTreeItems.MODS, ModTreeItems.CONTENT, ModTreeItems.CONFIGURATION, ModTreeItems.KEY_BINDINGS),
                pack.stream().map(TreeItem::getName).toList());
        assertEquals("3", pack.get(3).getPresentation().secondary());
        assertEquals("2", pack.getFirst().getPresentation().secondary());
        assertEquals(new NavigationTarget.PackConfiguration(), ((NavigableTreeItem) pack.get(2)).navigationTarget());
        List<TreeItem> content = ((DirectoryTreeItem) pack.get(1)).loadChildren();
        assertEquals(List.of(RegistryIds.BLOCK, RegistryIds.ITEM, RegistryIds.ENTITY_TYPE, RegistryIds.FLUID),
                content.stream().map(TreeItem::getName).toList());
        assertEquals("Fluids", content.getLast().getPresentation().primary());
        assertEquals(new NavigationTarget.Content(RegistryIds.ITEM), ((NavigableTreeItem) content.get(1)).navigationTarget());
        List<TreeItem> changed = ModTreeItems.packChildren(new ModTreeItems.Snapshot(snapshot.state(), snapshot.sources(), 3));
        assertEquals(ModTreeItems.CHANGES, changed.getLast().getName(), "Changes appears once Companion changed something");
        assertEquals("3", changed.getLast().getPresentation().secondary());
        assertEquals(List.of("neoforge", "testmod", ModTreeItems.OTHER_NAMESPACES), mods.stream().map(TreeItem::getName).toList());
        TreeItem testmod = mods.get(1);
        assertTrue(testmod.isActivatable());
        assertEquals(new NavigationTarget.ModPage("testmod"), ((NavigableTreeItem) testmod).navigationTarget());
        List<TreeItem> groups = ((DirectoryTreeItem) testmod).loadChildren();
        assertEquals(List.of("content", "configuration", "key_bindings", "resources"),
                groups.stream().map(TreeItem::getName).toList());
        List<TreeItem> kinds = ((DirectoryTreeItem) groups.getFirst()).loadChildren();
        assertEquals(4, kinds.size());
        assertEquals("1", kinds.get(1).getPresentation().secondary());
        assertEquals(new NavigationTarget.ModPage("testmod", ModTab.CONTENT, RegistryIds.ITEM),
                ((NavigableTreeItem) kinds.get(1)).navigationTarget());

        TreeItem resources = groups.getLast();
        assertFalse(resources.isDirectory(), "Resources opens its tab, which lists the categories");
        assertEquals("7", resources.getPresentation().secondary());
        assertEquals(new NavigationTarget.ModPage("testmod", ModTab.RESOURCES, ""),
                ((NavigableTreeItem) resources).navigationTarget());

        List<TreeItem> neoforge = ((DirectoryTreeItem) mods.getFirst()).loadChildren();
        assertTrue(neoforge.isEmpty(), "a mod without content or an existing file has no groups");
    }

    @Test
    void beforeTheFirstCaptureModsComeFromTheRuntimeWithTheirResources() throws Exception {
        Path jar = CatalogFixtures.modJar(this.directory);
        var snapshot = new ModTreeItems.Snapshot(new PackCatalogService.None(), sources(jar), 0);

        List<TreeItem> mods = ModTreeItems.children(snapshot);

        assertEquals(List.of("othermod"), mods.stream().map(TreeItem::getName).toList());
        List<TreeItem> groups = ((DirectoryTreeItem) mods.getFirst()).loadChildren();
        assertEquals(List.of("resources"), groups.stream().map(TreeItem::getName).toList());
        assertInstanceOf(NavigableTreeItem.class, groups.getFirst());
        ModTreeItems.Root root = new ModTreeItems.Root(() -> snapshot);
        assertEquals("not captured", root.getPresentation().secondary());
        assertEquals(List.of(ModTreeItems.MODS), root.loadChildren().stream().map(TreeItem::getName).toList(),
                "Configuration waits for the catalog that describes the settings");
        assertFalse(root.isActivatable());
    }

    @Test
    void aModOfCodeAloneHasNoResourcesToExpand() throws Exception {
        Path jar = this.directory.resolve("codeonly.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry("dev/architectury/Architectury.class"));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("icon.png"));
            zip.closeEntry();
        }
        var snapshot = new ModTreeItems.Snapshot(new PackCatalogService.None(), sources(jar), 0);

        DirectoryTreeItem mod = (DirectoryTreeItem) ModTreeItems.children(snapshot).getFirst();

        assertTrue(mod.loadChildren().isEmpty(), "a JAR without assets or data has no Resources");
    }

    private static RuntimeSourceCatalog sources(Path jar) {
        return new RuntimeSourceCatalog(List.of(new RuntimeSnapshotBytecodeSource.Source(0, jar, jar.toUri().toString(),
                new RuntimeInventory.RuntimeModule("othermod", "Other Mod", RuntimeInventory.ModuleKind.MOD))));
    }
}
