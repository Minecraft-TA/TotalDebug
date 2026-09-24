package com.github.minecraft_ta.totalDebugCompanion.search.everywhere;

import com.github.minecraft_ta.totalDebugCompanion.catalog.CatalogFixtures;
import com.github.minecraft_ta.totalDebugCompanion.catalog.CatalogIndex;
import com.github.minecraft_ta.totalDebugCompanion.search.everywhere.SearchEverywhereSearch.Category;
import com.github.minecraft_ta.totalDebugCompanion.search.everywhere.SearchEverywhereSearch.DefinitionResult;
import com.github.minecraft_ta.totalDebugCompanion.search.everywhere.SearchEverywhereSearch.ModResult;
import com.github.minecraft_ta.totalDebugCompanion.search.everywhere.SearchEverywhereSearch.ResourceResult;
import com.github.minecraft_ta.totalDebugCompanion.search.everywhere.SearchEverywhereSearch.Result;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogSearchTest {
    @TempDir Path directory;

    @Test
    void findsModsAndContentWithoutAClassIndex() throws Exception {
        CatalogSearch catalog = catalog();
        SearchEverywhereSearch search = new SearchEverywhereSearch();

        List<Result> all = search.search(null, catalog, "widget", Category.ALL, 20, null, null);

        assertEquals(List.of("Widget", "Widget Block"), all.stream().map(Result::searchableName).toList());
        assertEquals("Item", kind(all.get(0)));
        assertEquals("Block", kind(all.get(1)));
        assertEquals("Test Mod", assertInstanceOf(DefinitionResult.class, all.getFirst()).owner());

        List<Result> mods = search.search(null, catalog, "test", Category.MODS, 20, null, null);
        assertEquals("testmod", assertInstanceOf(ModResult.class, mods.getFirst()).modId());
        assertEquals(1, search.search(null, catalog, "gremlin", Category.ENTITIES, 20, null, null).size());
        assertTrue(search.search(null, catalog, "gremlin", Category.ITEMS, 20, null, null).isEmpty());
    }

    @Test
    void matchesRegistryIdsAndFiltersByModule() throws Exception {
        CatalogSearch catalog = catalog();
        SearchEverywhereSearch search = new SearchEverywhereSearch();

        assertEquals(1, search.search(null, catalog, "shared_dust", Category.ITEMS, 20, null, null).size());
        assertTrue(search.search(null, catalog, "widget", Category.ITEMS, 20, null, Set.of("minecraft+neoforge")).isEmpty());
        assertEquals(1, search.search(null, catalog, "widget", Category.ITEMS, 20, null, Set.of("testmod")).size());
    }

    @Test
    void searchesResourcePathsOnlyInTheirCategory() throws Exception {
        CatalogSearch catalog = catalog();
        SearchEverywhereSearch search = new SearchEverywhereSearch();

        List<Result> resources = search.search(null, catalog, "textures/item", Category.RESOURCES, 20, null, null);

        assertEquals("assets/testmod/textures/item/widget.png",
                assertInstanceOf(ResourceResult.class, resources.getFirst()).resource().path());
        assertTrue(search.search(null, catalog, "widget.png", Category.ALL, 20, null, null).isEmpty());
    }

    @Test
    void theBestMatchesAreKeptWhenMoreMatchThanTheLimit() throws Exception {
        List<PackCatalog.ItemEntry> items = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            items.add(new PackCatalog.ItemEntry("pack:raw_ingot_" + i, "Raw Ingot " + i, "", "", "", Map.of()));
        }
        items.add(new PackCatalog.ItemEntry("pack:ingot", "Ingot", "", "", "", Map.of()));
        CatalogSearch catalog = new CatalogSearch(new CatalogIndex(new PackCatalog("inventory", "en_us", List.of(),
                List.of(), items, List.of())));

        List<Result> results = new SearchEverywhereSearch().search(null, catalog, "ingot", Category.ITEMS, 5, null, null);

        assertEquals(5, results.size());
        assertEquals("Ingot", results.getFirst().searchableName());
    }

    private CatalogSearch catalog() throws Exception {
        return new CatalogSearch(new CatalogIndex(CatalogFixtures.catalog(CatalogFixtures.modJar(this.directory))));
    }

    private static String kind(Result result) {
        return switch (assertInstanceOf(DefinitionResult.class, result).entry().kind()) {
            case ITEM -> "Item";
            case BLOCK -> "Block";
            case ENTITY_TYPE -> "Entity";
        };
    }
}
