package com.github.minecraft_ta.totaldebug.server.script;

import com.github.minecraft_ta.totaldebug.evaluation.InMemoryJavaCompiler;
import com.github.minecraft_ta.totaldebug.evaluation.ServerManifest;
import com.github.minecraft_ta.totaldebug.runtime.RuntimeSourceInventory;
import com.github.minecraft_ta.totaldebug.runtime.RuntimeSourceMaterializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ServerScriptManifestTest {
    @TempDir Path directory;

    @Test
    void detailsReusePreparedSourcesAndCachedBytesButStillCheckRuntimeIdentity() throws Exception {
        Path input = directory.resolve("input.zip");
        Path cache = directory.resolve("cache/sources");
        ServerScriptService.Manifest manifest;
        try (var compiler = new InMemoryJavaCompiler();
             var archive = FileSystems.newFileSystem(input, Map.of("create", "true"))) {
            Files.write(archive.getPath("/Api.class"), compiler.compile("public class Api {}", "Api", "").get("Api"));
            var sources = RuntimeSourceMaterializer.prepare(
                    List.of(new RuntimeSourceInventory.Source(archive.getPath("/"), "fixture")), cache);
            manifest = sources.withCurrentSources(() ->
                    new ServerScriptService.Manifest(sources, new ServerManifest.Catalog(sources.paths())));
        }
        // Repreparing would now fail: the original filesystem is closed and its archive is gone.
        Files.delete(input);
        byte[] details = manifest.details(0);
        assertTrue(ServerManifest.decodeDetails(details).containsKey("Api"));
        Files.delete(manifest.sources().paths().getFirst());
        assertSame(details, manifest.details(0), "A later player reuses the encoded response without reading classes");
        Files.writeString(cache.resolve("manifest.json"), "{\"id\":\"replaced\"}");
        var failure = assertThrows(IOException.class, () -> manifest.details(0));
        assertTrue(failure.getMessage().contains("Runtime cache has changed"));
    }
}
