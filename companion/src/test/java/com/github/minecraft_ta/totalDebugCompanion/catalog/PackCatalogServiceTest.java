package com.github.minecraft_ta.totalDebugCompanion.catalog;

import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectRef;
import com.github.minecraft_ta.totaldebug.storage.InstancePaths;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog;
import com.github.minecraft_ta.totaldebug.storage.RuntimeInventory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackCatalogServiceTest {
    @TempDir Path directory;

    @Test
    void restoresTheSavedCatalogOfTheSavedRuntime() throws Exception {
        InstancePaths paths = new InstancePaths(this.directory.resolve("total-debug"));
        Path jar = CatalogFixtures.modJar(this.directory);
        inventory(paths, CatalogFixtures.INVENTORY, jar);
        CatalogFixtures.catalog(jar).write(paths.catalog());
        PackCatalogService service = new PackCatalogService(paths);
        AtomicInteger changes = new AtomicInteger();
        service.addListener(changes::incrementAndGet);

        service.restore();
        SwingUtilities.invokeAndWait(() -> { });

        CatalogIndex index = assertInstanceOf(PackCatalogService.Ready.class, service.state()).index();
        assertEquals(List.of("NeoForge", "Test Mod"), index.mods().stream().map(PackCatalog.Mod::title).toList());
        assertEquals(1, changes.get());
    }

    @Test
    void aCatalogOfAnotherRuntimeIsStale() throws Exception {
        InstancePaths paths = new InstancePaths(this.directory.resolve("total-debug"));
        Path jar = CatalogFixtures.modJar(this.directory);
        inventory(paths, "inventory-2", jar);
        CatalogFixtures.catalog(jar).write(paths.catalog());
        PackCatalogService service = new PackCatalogService(paths);

        service.restore();

        assertInstanceOf(PackCatalogService.Stale.class, service.state());
        assertTrue(service.index().isEmpty());
    }

    @Test
    void withoutASavedCatalogNothingIsCaptured() {
        PackCatalogService service = new PackCatalogService(new InstancePaths(this.directory));

        service.restore();

        assertInstanceOf(PackCatalogService.None.class, service.state());
    }

    @Test
    void acceptsOnlyTheAnnouncedCatalogOfThisProject() throws Exception {
        InstancePaths paths = new InstancePaths(this.directory.resolve("total-debug"));
        Path jar = CatalogFixtures.modJar(this.directory);
        CatalogFixtures.catalog(jar).write(paths.catalog());
        PackCatalogService service = new PackCatalogService(paths);

        service.accept("inventory-9", paths.catalog(), Runnable::run);
        assertTrue(assertInstanceOf(PackCatalogService.Failed.class, service.state()).detail().contains("not the announced inventory-9"));

        Path elsewhere = this.directory.resolve("elsewhere.json");
        Files.copy(paths.catalog(), elsewhere);
        service.accept(CatalogFixtures.INVENTORY, elsewhere, Runnable::run);
        assertTrue(assertInstanceOf(PackCatalogService.Failed.class, service.state()).detail().contains("outside this project"));

        service.accept(CatalogFixtures.INVENTORY, paths.catalog(), Runnable::run);
        assertInstanceOf(PackCatalogService.Ready.class, service.state());

        service.inventoryAnnounced(CatalogFixtures.INVENTORY);
        assertInstanceOf(PackCatalogService.Ready.class, service.state());
        service.inventoryAnnounced("inventory-2");
        assertInstanceOf(PackCatalogService.Stale.class, service.state());
    }

    @Test
    void aStateReportedWhileAnAnnouncedCatalogLoadsWins() throws Exception {
        InstancePaths paths = new InstancePaths(this.directory.resolve("total-debug"));
        CatalogFixtures.catalog(CatalogFixtures.modJar(this.directory)).write(paths.catalog());
        PackCatalogService service = new PackCatalogService(paths);
        List<Runnable> loads = new ArrayList<>();

        service.accept(CatalogFixtures.INVENTORY, paths.catalog(), loads::add);
        service.capturing();
        loads.forEach(Runnable::run);

        assertInstanceOf(PackCatalogService.Capturing.class, service.state());
    }

    @Test
    void theIndexAssignsContentToModsByNamespace() throws Exception {
        CatalogIndex index = new CatalogIndex(CatalogFixtures.catalog(CatalogFixtures.modJar(this.directory)));

        assertEquals(List.of("c"), index.otherNamespaces());
        assertEquals(List.of("Widget"),
                index.entries("testmod", SubjectRef.DefinitionKind.ITEM).stream().map(CatalogIndex.Entry::title).toList());
        assertEquals("Widget Block", index.entry(new SubjectRef.Definition(SubjectRef.DefinitionKind.ITEM,
                "testmod:widget_block")).orElseThrow().title(), "Block item definitions remain available through explicit links");
        assertEquals("testmod:widget_block", index.entry(new SubjectRef.Definition(SubjectRef.DefinitionKind.BLOCK,
                "testmod:widget_block")).orElseThrow().iconItem());
        assertEquals(new CatalogIndex.ItemIcon("testmod:item/widget", Map.of()), index.itemIcon("testmod:widget").orElseThrow());
        assertEquals("c:item/dust", index.itemIcon("c:shared_dust").orElseThrow().model());
        assertEquals("Test Mod", index.ownerName("testmod"));
        assertEquals("c", index.ownerName("c"));
    }

    private static void inventory(InstancePaths paths, String id, Path jar) throws Exception {
        new RuntimeInventory(id, "21", System.getProperty("java.home"), true,
                List.of(new RuntimeInventory.Source(RuntimeInventory.SourceKind.ARCHIVE, jar, jar.toUri().toString(),
                        new RuntimeInventory.RuntimeModule("testmod", "Test Mod", RuntimeInventory.ModuleKind.MOD))))
                .write(paths.inventory());
    }
}
