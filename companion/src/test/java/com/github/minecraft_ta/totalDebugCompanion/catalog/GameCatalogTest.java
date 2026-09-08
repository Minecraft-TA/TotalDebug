package com.github.minecraft_ta.totalDebugCompanion.catalog;

import com.github.minecraft_ta.totalDebugCompanion.search.everywhere.SearchEverywhereSearch;
import com.github.minecraft_ta.totaldebug.storage.GameCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class GameCatalogTest {
    @TempDir Path directory;
    private static final Map<String, Integer> LEAF_COLORS = Map.of(
            "minecraft:acacia_leaves", 0xFF48B518, "minecraft:birch_leaves", 0xFF80A755, "example:violet_leaves", 0xFFAD55CC);

    @Test void resolvesInheritedModelsAtlasAliasesOverridesAndExportBytesOffline() throws Exception {
        var paths = CatalogFixtures.create(directory);
        var snapshot = new GameCatalogService().load(paths);
        var inspection = snapshot.inspect(snapshot.catalog().entries().getFirst());
        assertNotNull(inspection.preview(), inspection.notes().toString());
        assertTrue(inspection.resources().contains("assets/example/models/item/base.json"));
        assertTrue(inspection.resources().contains("assets/example/textures/item/tool.png"));
        assertTrue(inspection.resources().contains("assets/example/textures/item/tool.png.mcmeta"));
        assertFalse(inspection.resources().contains("assets/example/textures/tool_sprite.png"));
        assertEquals(0xffff0033, ImageIO.read(new ByteArrayInputStream(snapshot.read("assets/example/textures/item/tool.png"))).getRGB(0, 0));
        assertEquals(0xffff0033, inspection.preview().getRGB(128, 128));
        assertTrue(snapshot.inspect(snapshot.catalog().entries().get(1)).resources().contains("assets/example/blockstates/tool.json"));
    }

    @Test void searchesRegistryIdsLocalizedNamesAndSharedModulesWithoutAClassIndex() throws Exception {
        var snapshot = new GameCatalogService().load(CatalogFixtures.create(directory));
        var search = new SearchEverywhereSearch();
        assertEquals(2, search.search(null, "example:tool", SearchEverywhereSearch.Category.ALL, 10, null, snapshot, null).size());
        assertEquals(1, search.search(null, "tool", SearchEverywhereSearch.Category.ITEMS, 10, null, snapshot, Set.of("example+library")).size());
        assertTrue(search.search(null, "tool", SearchEverywhereSearch.Category.ALL, 10, null, snapshot, Set.of("other")).isEmpty());
        var localized = search.search(null, "Rötlicher", SearchEverywhereSearch.Category.ALL, 10, null, snapshot, null);
        assertEquals("other:gem", ((SearchEverywhereSearch.GameResult) localized.getFirst()).entry().id());
    }

    @Test void rejectsMismatchedRuntimeAndChangedSnapshotInsteadOfMixingCaptures() throws Exception {
        var paths = CatalogFixtures.create(directory);
        var service = new GameCatalogService();
        var snapshot = service.load(paths);
        Files.writeString(paths.inventory(), Files.readString(paths.inventory()).replace("fixture-runtime", "another-runtime"));
        assertThrows(IOException.class, () -> service.load(paths));
        Files.write(paths.gameCatalog(), new byte[]{1, 2, 3});
        assertThrows(IOException.class, () -> snapshot.read("assets/example/textures/item/tool.png"));
    }

    @Test void rejectsResourceTraversal() {
        assertThrows(IllegalArgumentException.class, () -> GameCatalog.validateResourcePath("assets/example/../../secret"));
        assertThrows(IllegalArgumentException.class, () -> GameCatalog.validateResourcePath("assets/C:/secret"));
    }

    @Test void usesCapturedLeafColorsForItemAndBlockPreviews() throws Exception {
        var snapshot = leafCapture();
        for (var entry : snapshot.catalog().entries()) {
            var image = snapshot.inspect(entry).preview();
            assertNotNull(image);
            int expected = LEAF_COLORS.get(entry.id());
            assertEquals(expected, image.getRGB(64, 128), entry.kind() + " " + entry.id());
            assertEquals(0xFFFFFFFF, image.getRGB(192, 128), "Faces without a tint index must keep their texture color");
        }
    }

    private CatalogSnapshot leafCapture() throws Exception {
        var entries = new java.util.ArrayList<GameCatalog.Entry>();
        for (String id : LEAF_COLORS.keySet()) {
            for (var kind : GameCatalog.Kind.values()) {
                // Deserialize the captured wire shape so this regression exercises persistence too.
                entries.add(GameCatalog.GSON.fromJson("""
                        {"kind":"%s","id":"%s","name":"Leaves","modName":"Test","modVersion":"1",
                         "className":"TestLeaves","counterpart":"%s","model":"example:item/leaves",
                         "properties":{},"tintColors":{"0":%d}}
                        """.formatted(kind, id, id, LEAF_COLORS.get(id)), GameCatalog.Entry.class));
            }
        }
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("assets/example/models/item/leaves.json", "{\"parent\":\"example:block/leaves\"}".getBytes());
        files.put("assets/example/models/block/leaves.json", """
                {"gui_light":"front","textures":{"all":"example:block/leaves"},"elements":[
                  {"from":[0,0,8],"to":[8,16,8],"faces":{"south":{"texture":"#all","tintindex":0}}},
                  {"from":[8,0,8],"to":[16,16,8],"faces":{"south":{"texture":"#all"}}}
                ]}
                """.getBytes());
        var texture = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        texture.setRGB(0, 0, 0xFFFFFFFF);
        var png = new java.io.ByteArrayOutputStream();
        ImageIO.write(texture, "png", png);
        files.put("assets/example/textures/block/leaves.png", png.toByteArray());
        Map<String, List<String>> resources = new LinkedHashMap<>();
        files.keySet().forEach(path -> resources.put(path, List.of("fixture")));
        var catalog = new GameCatalog(GameCatalog.CURRENT_FORMAT, "fixture", "now", "en_us", entries, resources, List.of());
        Path archive = directory.resolve("leaves.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (var file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry("layers/0/" + file.getKey()));
                zip.write(file.getValue());
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry(GameCatalog.MANIFEST));
            zip.write(GameCatalog.GSON.toJson(catalog).getBytes());
            zip.closeEntry();
        }
        var attributes = Files.readAttributes(archive, BasicFileAttributes.class);
        return new CatalogSnapshot(archive, GameCatalog.read(archive), attributes.size(), attributes.lastModifiedTime());
    }
}
