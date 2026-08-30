package com.github.minecraft_ta.totaldebug.client.companion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RuntimeInventoryPublisherTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void preservesDefaultFilesystemSourcesWithoutCopyingThem() throws Exception {
        Path classes = Files.createDirectories(this.temporaryDirectory.resolve("classes"));
        Files.write(classes.resolve("Example.class"), new byte[]{1});
        Path archive = Files.write(this.temporaryDirectory.resolve("runtime.jar"), new byte[]{2});
        Path staged = Files.createDirectories(this.temporaryDirectory.resolve("staged"));
        Path published = this.temporaryDirectory.resolve("published");

        List<RuntimeInventory.Source> sources = RuntimeInventoryPublisher.prepareSources(
                List.of(classes, archive),
                Map.of(
                        classes, new RuntimeInventory.RuntimeModule(
                                "total_debug",
                                "TotalDebug",
                                RuntimeInventory.ModuleKind.MOD
                        ),
                        archive, new RuntimeInventory.RuntimeModule(
                                "example",
                                "Example Mod",
                                RuntimeInventory.ModuleKind.MOD
                        )
                ),
                staged,
                published
        );

        assertEquals(classes, sources.get(0).path());
        assertEquals(archive, sources.get(1).path());
        assertEquals(RuntimeInventory.SourceKind.DIRECTORY, sources.get(0).kind());
        assertEquals(RuntimeInventory.SourceKind.ARCHIVE, sources.get(1).kind());
        assertEquals("TotalDebug", sources.get(0).module().displayName());
        assertEquals("example", sources.get(1).module().id());
        assertEquals(RuntimeInventory.ModuleKind.MOD, sources.get(1).module().kind());
    }

    @Test
    void namesGradleOutputsAfterTheirOwningProject() {
        Path classes = this.temporaryDirectory.resolve("TotalDebug/build/classes/java/main");

        RuntimeInventory.RuntimeModule module = RuntimeInventoryPublisher.fallbackModule(classes);

        assertEquals("TotalDebug", module.id());
        assertEquals("TotalDebug", module.displayName());
        assertEquals(RuntimeInventory.ModuleKind.LIBRARY, module.kind());
    }

    @Test
    void classifiesPlatformModsRealModsAndMetadataFreeLibraries() {
        RuntimeInventory.RuntimeModule minecraft = RuntimeInventoryPublisher.describeModule(
                List.of("minecraft"),
                List.of("Minecraft"),
                "minecraft"
        );
        RuntimeInventory.RuntimeModule neoforge = RuntimeInventoryPublisher.describeModule(
                List.of("neoforge"),
                List.of("NeoForge"),
                "neoforge"
        );
        assertEquals("minecraft", minecraft.id());
        assertEquals("Minecraft", minecraft.displayName());
        assertEquals(RuntimeInventory.ModuleKind.PLATFORM, minecraft.kind());
        assertEquals("neoforge", neoforge.id());
        assertEquals("NeoForge", neoforge.displayName());
        assertEquals(RuntimeInventory.ModuleKind.PLATFORM, neoforge.kind());
        assertEquals(
                RuntimeInventory.ModuleKind.MOD,
                RuntimeInventoryPublisher.describeModule(
                        List.of("example"),
                        List.of("Example Mod"),
                        "example"
                ).kind()
        );
        assertEquals(
                RuntimeInventory.ModuleKind.LIBRARY,
                RuntimeInventoryPublisher.describeModule(List.of(), List.of(), "asm").kind()
        );
        assertEquals(
                "minecraft+neoforge",
                RuntimeInventoryPublisher.describeModule(
                        List.of("minecraft", "neoforge"),
                        List.of("Minecraft", "NeoForge"),
                        "minecraft-neoforge"
                ).id()
        );
    }

    @Test
    void mergesPlatformOwnershipOnlyWhenMinecraftLivesInTheNeoForgeArtifact(@TempDir Path directory) {
        Path mergedSource = directory.resolve("merged.jar");
        Path loaderSource = directory.resolve("loader.jar");
        Map<Path, RuntimeInventory.RuntimeModule> development = new LinkedHashMap<>();
        development.put(
                mergedSource.toAbsolutePath().normalize(),
                RuntimeInventoryPublisher.describeModule(List.of("neoforge"), List.of("NeoForge"), "neoforge")
        );

        RuntimeInventoryPublisher.assignPlatformModules(development, mergedSource, loaderSource);

        assertEquals(development.get(mergedSource.toAbsolutePath().normalize()),
                development.get(loaderSource.toAbsolutePath().normalize()));
        assertEquals("minecraft+neoforge", development.get(mergedSource.toAbsolutePath().normalize()).id());

        Path minecraftSource = directory.resolve("minecraft.jar");
        Path neoforgeSource = directory.resolve("neoforge.jar");
        Map<Path, RuntimeInventory.RuntimeModule> production = new LinkedHashMap<>();
        production.put(
                neoforgeSource.toAbsolutePath().normalize(),
                RuntimeInventoryPublisher.describeModule(List.of("neoforge"), List.of("NeoForge"), "neoforge")
        );

        RuntimeInventoryPublisher.assignPlatformModules(production, minecraftSource, neoforgeSource);

        assertEquals("minecraft", production.get(minecraftSource.toAbsolutePath().normalize()).id());
        assertEquals("neoforge", production.get(neoforgeSource.toAbsolutePath().normalize()).id());

        Path combinedSource = directory.resolve("minecraft-neoforge.jar");
        Path supportingLoaderSource = directory.resolve("neoforge-loader.jar");
        Map<Path, RuntimeInventory.RuntimeModule> combined = new LinkedHashMap<>();
        combined.put(
                combinedSource.toAbsolutePath().normalize(),
                RuntimeInventoryPublisher.describeModule(
                        List.of("minecraft", "neoforge"),
                        List.of("Minecraft", "NeoForge"),
                        "minecraft-neoforge"
                )
        );

        RuntimeInventoryPublisher.assignPlatformModules(combined, combinedSource, supportingLoaderSource);

        assertEquals("minecraft+neoforge", combined.get(combinedSource.toAbsolutePath().normalize()).id());
        assertEquals(combined.get(combinedSource.toAbsolutePath().normalize()),
                combined.get(supportingLoaderSource.toAbsolutePath().normalize()));
    }

    @Test
    void treatsAnUnsupportedGeneratedInventoryAsStale(@TempDir Path directory) throws Exception {
        Path inventory = directory.resolve(RuntimeInventory.FILE_NAME);
        Files.writeString(inventory, """
                format=2
                inventory.id=old
                """);

        assertFalse(RuntimeInventoryPublisher.matchesPublishedInventory(inventory, "current"));
    }
}
