package com.github.minecraft_ta.totaldebug.client.companion;

import com.github.minecraft_ta.totaldebug.storage.GameCatalog;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class GameCatalogPublisherTest {
    @TempDir Path directory;

    @Test void capturesPackOverridesAdditiveAtlasesAndMetadataWithoutInheritingDiscardedMetadata() throws Exception {
        Path base = directory.resolve("base"), override = directory.resolve("override"), metadata = directory.resolve("metadata");
        String texture = "assets/example/textures/item/tool.png";
        put(base, texture, "base");
        put(base, texture + ".mcmeta", "{\"animation\":{\"frametime\":2}}");
        put(override, texture, "override");
        put(base, "assets/minecraft/atlases/blocks.json", "{\"sources\":[]}");
        put(override, "assets/minecraft/atlases/blocks.json", "{\"sources\":[]}");
        Files.createDirectories(metadata);
        Path archive = directory.resolve("capture.zip");
        try (var manager = manager(base, override, metadata)) {
            GameCatalogPublisher.writeResources(archive, "runtime", capture(), manager);
        }
        var catalog = GameCatalog.read(archive);
        assertEquals(List.of("base", "override"), catalog.resources().get(texture));
        assertEquals(List.of("base", "override"), catalog.resources().get("assets/minecraft/atlases/blocks.json"));
        assertFalse(catalog.resources().containsKey(texture + ".mcmeta"), "Lower texture metadata must not leak into an override");
        try (var zip = new ZipFile(archive.toFile())) {
            assertEquals("override", new String(zip.getInputStream(zip.getEntry(catalog.effectiveEntry(texture))).readAllBytes()));
        }
        put(metadata, texture + ".mcmeta", "{\"animation\":{\"frametime\":4}}");
        try (var manager = manager(base, override, metadata)) {
            GameCatalogPublisher.writeResources(archive, "runtime", capture(), manager);
        }
        assertEquals(List.of("metadata"), GameCatalog.read(archive).resources().get(texture + ".mcmeta"));
    }

    private static GameCatalogPublisher.RegistryCapture capture() {
        return new GameCatalogPublisher.RegistryCapture(List.of(), "en_us", List.of());
    }

    private static MultiPackResourceManager manager(Path... roots) {
        return new MultiPackResourceManager(PackType.CLIENT_RESOURCES, java.util.Arrays.stream(roots)
                .map(root -> (net.minecraft.server.packs.PackResources) new PathPackResources(
                        new PackLocationInfo(root.getFileName().toString(), Component.literal("Test"), PackSource.DEFAULT, Optional.empty()), root)).toList());
    }

    private static void put(Path root, String path, String content) throws Exception {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
