package com.github.minecraft_ta.totaldebug.client.catalog;

import com.github.minecraft_ta.totaldebug.storage.PackCatalog;
import com.github.minecraft_ta.totaldebug.storage.RuntimeInventory.ModuleKind;
import com.github.minecraft_ta.totaldebug.storage.RuntimeInventory.RuntimeModule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PackCatalogEntriesTest {
    @Test
    void mapsEveryModIdToItsInventoryModule() {
        Map<String, String> modules = PackCatalogEntries.moduleByModId(List.of(
                new RuntimeModule("minecraft+neoforge", "Minecraft + NeoForge", ModuleKind.PLATFORM),
                new RuntimeModule("create+flywheel", "Create + Flywheel", ModuleKind.MOD),
                new RuntimeModule("guava", "guava", ModuleKind.LIBRARY)));

        assertEquals(Map.of("minecraft", "minecraft+neoforge", "neoforge", "minecraft+neoforge",
                "create", "create+flywheel", "flywheel", "create+flywheel"), modules);
    }

    @Test
    void omitsConventionalItemModels() {
        assertEquals("", PackCatalogEntries.model("mekanism:energy_tablet", "mekanism:item/energy_tablet"));
        assertEquals("minecraft:item/trident_in_hand",
                PackCatalogEntries.model("minecraft:trident", "minecraft:item/trident_in_hand"));
    }

    @Test
    void normalizesAuthorsAndDependencies() {
        assertEquals(List.of("Aidan", "Thiakil"), PackCatalogEntries.authors(List.of(" Aidan", "Thiakil ", "")));
        assertEquals(List.of("A, B"), PackCatalogEntries.authors("A, B"));
        assertEquals(List.of(), PackCatalogEntries.authors(null));
        assertEquals(Optional.of(new PackCatalog.Dependency("neoforge", PackCatalog.DependencyType.REQUIRED, "", PackCatalog.Side.BOTH)),
                PackCatalogEntries.dependency("neoforge", "REQUIRED", " ", "BOTH"));
        assertEquals(Optional.empty(), PackCatalogEntries.dependency("Bad-Id", "OPTIONAL", "[1,)", "CLIENT"));
        assertEquals(PackCatalog.ConfigType.SERVER, PackCatalogEntries.configType("server"));
    }

    @Test
    void dropsTheValueLinesNeoForgeAddsToComments() {
        assertEquals("Energy a machine stores\nPer tick", PackCatalogEntries.configComment(
                " Energy a machine stores\n Per tick\n Default: 40000\n Range: 1 ~ 2147483647"));
        assertEquals("", PackCatalogEntries.configComment("Allowed Values: FAST, SLOW"));
        assertEquals("", PackCatalogEntries.configComment(null));
    }
}
