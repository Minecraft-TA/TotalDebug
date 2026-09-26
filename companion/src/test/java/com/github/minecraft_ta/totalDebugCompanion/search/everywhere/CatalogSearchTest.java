package com.github.minecraft_ta.totalDebugCompanion.search.everywhere;

import com.github.minecraft_ta.totalDebugCompanion.catalog.CatalogFixtures;
import com.github.minecraft_ta.totalDebugCompanion.catalog.CatalogIndex;
import com.github.minecraft_ta.totalDebugCompanion.catalog.RegistryIds;
import com.github.minecraft_ta.totalDebugCompanion.search.everywhere.SearchEverywhereSearch.Category;
import com.github.minecraft_ta.totalDebugCompanion.search.everywhere.SearchEverywhereSearch.DefinitionResult;
import com.github.minecraft_ta.totalDebugCompanion.search.everywhere.SearchEverywhereSearch.ModResult;
import com.github.minecraft_ta.totalDebugCompanion.search.everywhere.SearchEverywhereSearch.ResourceResult;
import com.github.minecraft_ta.totalDebugCompanion.search.everywhere.SearchEverywhereSearch.Result;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
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

        assertEquals(List.of("Widget", "Widget Block", "Spin widgets", "Peek into widgets"),
                all.stream().map(Result::searchableName).toList(), "key bindings come after registered content");
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
    void findsKeyBindingsByActionAndByTheirKey() throws Exception {
        Files.writeString(this.directory.resolve("options.txt"), "key_key.testmod.spin:key.keyboard.g:CONTROL\n");
        CatalogSearch catalog = catalog();
        SearchEverywhereSearch search = new SearchEverywhereSearch();

        List<Result> byAction = search.search(null, catalog, "spin", Category.KEY_BINDINGS, 20, null, null);
        List<Result> byKey = search.search(null, catalog, "ctrl+g", Category.KEY_BINDINGS, 20, null, null);

        assertEquals(List.of(new SearchEverywhereSearch.KeyBindingResult("key.testmod.spin", "Spin widgets", "Ctrl + G", "Test Mod")),
                byAction);
        assertEquals(byAction, byKey);
        assertEquals("Drop Selected Item", search.search(null, catalog, "q", Category.KEY_BINDINGS, 20, null, null)
                .getFirst().searchableName());
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
        List<PackCatalog.RegistryEntry> items = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            items.add(new PackCatalog.RegistryEntry("pack:raw_ingot_" + i, "Raw Ingot " + i, "", "", List.of(), Map.of()));
        }
        items.add(new PackCatalog.RegistryEntry("pack:ingot", "Ingot", "", "", List.of(), Map.of()));
        CatalogSearch catalog = new CatalogSearch(new CatalogIndex(new PackCatalog("inventory", "en_us", List.of(),
                List.of(new PackCatalog.Registry(RegistryIds.ITEM, items)), Map.of(), List.of(), List.of(), Map.of())), null);

        List<Result> results = new SearchEverywhereSearch().search(null, catalog, "ingot", Category.ITEMS, 5, null, null);

        assertEquals(5, results.size());
        assertEquals("Ingot", results.getFirst().searchableName());
    }

    private CatalogSearch catalog() throws Exception {
        return new CatalogSearch(new CatalogIndex(CatalogFixtures.catalog(CatalogFixtures.modJar(this.directory))),
                this.directory.resolve("options.txt"));
    }

    private static String kind(Result result) {
        return switch (assertInstanceOf(DefinitionResult.class, result).entry().registry()) {
            case RegistryIds.ITEM -> "Item";
            case RegistryIds.BLOCK -> "Block";
            case RegistryIds.ENTITY_TYPE -> "Entity";
            default -> "Other";
        };
    }
}
