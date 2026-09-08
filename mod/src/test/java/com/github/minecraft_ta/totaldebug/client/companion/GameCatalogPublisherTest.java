package com.github.minecraft_ta.totaldebug.client.companion;

import com.github.minecraft_ta.totaldebug.storage.GameCatalog;
import net.minecraft.network.chat.Component;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
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
import java.util.Map;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class GameCatalogPublisherTest {
    @TempDir Path directory;

    @Test void capturesTintedFacesAcrossRenderPassesOncePerIndexWithTheInventorySeed() {
        var queriedFaces = new java.util.ArrayList<Direction>();
        var queriedColors = new java.util.ArrayList<Integer>();
        BakedModel first = tintModel(queriedFaces, 0);
        BakedModel second = tintModel(queriedFaces, 7);
        var colors = GameCatalogPublisher.captureTints(List.of(first, second), index -> {
            queriedColors.add(index);
            return index == 0 ? 0xFF80A755 : 0x80112233;
        });
        assertEquals(Map.of(0, 0xFF80A755, 7, 0x80112233), colors);
        assertEquals(List.of(0, 7), queriedColors, "Duplicate faces must not repeat item-color callbacks");
        assertEquals(14, queriedFaces.size());
        assertEquals(2, queriedFaces.stream().filter(java.util.Objects::isNull).count());
        for (var direction : Direction.values())
            assertEquals(2, queriedFaces.stream().filter(face -> face == direction).count());
    }

    private static BakedModel tintModel(List<Direction> queriedFaces, int tint) {
        return (BakedModel) java.lang.reflect.Proxy.newProxyInstance(BakedModel.class.getClassLoader(),
                new Class<?>[]{BakedModel.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("getQuads")) throw new AssertionError(method);
                    assertNull(args[0], "Inventory quads have no placed block state");
                    queriedFaces.add((Direction) args[1]);
                    assertEquals(RandomSource.create(42).nextInt(), ((RandomSource) args[2]).nextInt());
                    return List.of(new BakedQuad(new int[32], tint, Direction.SOUTH, null, true),
                            new BakedQuad(new int[32], -1, Direction.SOUTH, null, true));
                });
    }

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
        assertEquals(Map.of(0, 0xFF80A755), catalog.entries().getFirst().tintColors());
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
        var entry = new GameCatalog.Entry(GameCatalog.Kind.ITEM, "minecraft:birch_leaves", "Birch Leaves", "Minecraft", "1.21.1",
                "net.minecraft.world.item.BlockItem", "minecraft:birch_leaves", "minecraft:item/birch_leaves", Map.of(), Map.of(0, 0xFF80A755));
        return new GameCatalogPublisher.RegistryCapture(List.of(entry), "en_us", List.of());
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
