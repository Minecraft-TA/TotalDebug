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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GameCatalogTest {
    @TempDir Path directory;

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
}
