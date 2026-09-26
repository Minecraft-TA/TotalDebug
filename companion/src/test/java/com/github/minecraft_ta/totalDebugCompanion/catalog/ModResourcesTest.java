package com.github.minecraft_ta.totalDebugCompanion.catalog;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.RuntimeSnapshotBytecodeSource;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeSourceCatalog;
import com.github.minecraft_ta.totaldebug.storage.RuntimeInventory;
import com.github.minecraft_ta.totaldebug.storage.InstancePaths;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModResourcesTest {
    @TempDir Path directory;

    @Test
    void vanillaResourcesComeFromTheAssetArchiveNotOnlyTheTransformedClassArchive() throws Exception {
        Path classes = this.directory.resolve("transformed-game.jar");
        Path assets = this.directory.resolve("vanilla-assets.jar");
        Path unrelated = CatalogFixtures.modJar(this.directory);
        archive(classes, List.of("net/minecraft/SomeClass.class"));
        archive(assets, List.of("assets/.mcassetsroot", "data/.mcassetsroot",
                "assets/minecraft/textures/block/stone.png", "data/minecraft/recipe/stone.json"));
        InstancePaths paths = new InstancePaths(this.directory.resolve("total-debug"));
        var minecraft = new RuntimeInventory.RuntimeModule("minecraft", "Minecraft", RuntimeInventory.ModuleKind.PLATFORM);
        var library = new RuntimeInventory.RuntimeModule("vanilla-assets", "Vanilla assets", RuntimeInventory.ModuleKind.LIBRARY);
        new RuntimeInventory("inventory", "21", System.getProperty("java.home"), true, List.of(
                new RuntimeInventory.Source(RuntimeInventory.SourceKind.ARCHIVE, classes, classes.toUri().toString(), minecraft),
                new RuntimeInventory.Source(RuntimeInventory.SourceKind.ARCHIVE, assets, assets.toUri().toString(), library),
                new RuntimeInventory.Source(RuntimeInventory.SourceKind.ARCHIVE, unrelated, unrelated.toUri().toString(), library)))
                .write(paths.inventory());
        new PackCatalog("inventory", "en_us", List.of(new PackCatalog.Mod("minecraft", "Minecraft", "1.21.1",
                "", List.of(), "", Map.of(), "", "minecraft", classes.toUri(), List.of(), List.of())),
                List.of(), Map.of(), List.of(), List.of(), Map.of()).write(paths.catalog());
        var service = new PackCatalogService(paths);
        service.accept("inventory", paths.catalog(), Runnable::run);
        var summary = ModSummary.resolve("minecraft", service.index().orElseThrow(), RuntimeSourceCatalog.empty()).orElseThrow();
        assertEquals(List.of("assets/minecraft/textures/block/stone.png", "data/minecraft/recipe/stone.json"),
                ModResources.list(summary.files()).stream().map(ModResources.Resource::path).toList());
        assertEquals(new NavigationTarget.ArchiveEntry(assets, "assets/minecraft/textures/block/stone.png"),
                ModResources.list(summary.files()).getFirst().target());
    }

    private static void archive(Path path, List<String> names) throws Exception {
        try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
            for (String name : names) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write("fixture".getBytes());
                zip.closeEntry();
            }
        }
    }

    @Test
    void groupsArchiveResourcesByTheirFirstFolder() throws Exception {
        Path jar = CatalogFixtures.modJar(this.directory);

        List<ModResources.Resource> resources = ModResources.list(jar);

        assertEquals(7, resources.size(), resources::toString);
        assertEquals(List.of("assets/lang", "assets/models", "assets/sounds", "assets/textures", "data/loot_table", "data/recipe"),
                ModResources.categories(resources).stream().map(ModResources.Category::key).toList());
        ModResources.Resource texture = resources.stream()
                .filter(resource -> resource.path().equals("assets/testmod/textures/item/widget.png")).findFirst().orElseThrow();
        assertEquals("widget", texture.stem());
        assertEquals(new NavigationTarget.ArchiveEntry(jar, "assets/testmod/textures/item/widget.png"), texture.target());
    }

    @Test
    void listsDirectoryModsAndIgnoresOtherFiles() throws Exception {
        Path mod = Files.createDirectories(this.directory.resolve("devmod"));
        Files.createDirectories(mod.resolve("assets/devmod/textures/block"));
        Files.writeString(mod.resolve("assets/devmod/textures/block/stone.png"), "png");
        Files.createDirectories(mod.resolve("devmod"));
        Files.writeString(mod.resolve("devmod/Main.class"), "class");

        List<ModResources.Resource> resources = ModResources.list(mod);

        assertEquals(1, resources.size());
        assertEquals(new NavigationTarget.LocalFile(mod.resolve("assets/devmod/textures/block/stone.png")),
                resources.getFirst().target());
    }

    @Test
    void resourcesKnowTheirPathInsideTheirCategory() {
        ModResources.Resource texture = new ModResources.Resource(this.directory, false,
                "assets/framedblocks/textures/block/framed_slab.png", new ModResources.Category("assets", "textures"));
        ModResources.Resource sounds = new ModResources.Resource(this.directory, false,
                "assets/framedblocks/sounds.json", new ModResources.Category("assets", "sounds"));

        assertEquals("framedblocks", texture.namespace());
        assertEquals("block/framed_slab.png", texture.relativePath());
        assertEquals("block", texture.folder());
        assertEquals("sounds.json", sounds.relativePath());
        assertEquals("", sounds.folder());
    }

    @Test
    void categoriesComeFromTheNamespacesFirstFolder() {
        assertEquals(new ModResources.Category("assets", "textures"), ModResources.category("assets/ns/textures/a.png"));
        assertEquals(new ModResources.Category("assets", "sounds"), ModResources.category("assets/ns/sounds.json"));
        assertNull(ModResources.category("META-INF/mods.toml"));
        assertNull(ModResources.category("assets/pack.png"));
        assertEquals("data/recipe", ModResources.Category.parse("data/recipe").key());
    }

    @Test
    void summariesDescribeCapturedModsNamespacesAndUncapturedModules() throws Exception {
        Path jar = CatalogFixtures.modJar(this.directory);
        CatalogIndex index = new CatalogIndex(CatalogFixtures.catalog(jar));
        RuntimeInventory.RuntimeModule module = new RuntimeInventory.RuntimeModule("othermod", "Other Mod",
                RuntimeInventory.ModuleKind.MOD);
        RuntimeSourceCatalog sources = new RuntimeSourceCatalog(List.of(
                new RuntimeSnapshotBytecodeSource.Source(0, jar, jar.toUri().toString(), module)));

        ModSummary captured = ModSummary.resolve("testmod", index, sources).orElseThrow();
        assertEquals(List.of(jar), captured.files());
        assertTrue(captured.captured());
        ModSummary namespace = ModSummary.resolve("c", index, sources).orElseThrow();
        assertTrue(namespace.captured());
        assertTrue(namespace.files().isEmpty());
        ModSummary uncaptured = ModSummary.resolve("othermod", null, sources).orElseThrow();
        assertFalse(uncaptured.captured());
        assertEquals("Other Mod", uncaptured.title());
        assertEquals(List.of(jar), uncaptured.files());
        assertTrue(ModSummary.resolve("missing", index, sources).isEmpty());
    }
}
