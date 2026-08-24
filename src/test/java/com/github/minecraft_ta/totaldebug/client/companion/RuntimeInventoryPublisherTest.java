package com.github.minecraft_ta.totaldebug.client.companion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
                        classes, new RuntimeInventory.RuntimeModule("total_debug", "TotalDebug"),
                        archive, new RuntimeInventory.RuntimeModule("example", "Example Mod")
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
    }

    @Test
    void namesGradleOutputsAfterTheirOwningProject() {
        Path classes = this.temporaryDirectory.resolve("TotalDebug/build/classes/java/main");

        RuntimeInventory.RuntimeModule module = RuntimeInventoryPublisher.fallbackModule(classes);

        assertEquals("TotalDebug", module.id());
        assertEquals("TotalDebug", module.displayName());
    }
}
