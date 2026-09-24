package com.github.minecraft_ta.totaldebug.storage;

import com.github.minecraft_ta.totaldebug.storage.PackCatalog.BlockEntry;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog.ConfigFile;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog.ConfigType;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog.Dependency;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog.DependencyType;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog.EntityTypeEntry;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog.ItemEntry;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog.Mod;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog.Side;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackCatalogTest {
    @TempDir Path directory;

    @Test
    void roundTripsEveryField() throws Exception {
        Path config = this.directory.resolve("config/mekanism-common.toml");
        PackCatalog catalog = new PackCatalog("inventory-1", "en_us",
                List.of(new Mod("mekanism", "Mekanism", "10.7.0", "Machines", List.of("Aidan", "Thiakil"), "MIT",
                        Map.of("display", "https://example.invalid", "issues", "https://example.invalid/issues"),
                        "logo.png", "mekanism", this.directory.resolve("Mekanism.jar").toUri(),
                        List.of(new Dependency("neoforge", DependencyType.REQUIRED, "[21.1,)", Side.BOTH)),
                        List.of(new ConfigFile("mekanism-common.toml", ConfigType.COMMON, config),
                                new ConfigFile("mekanism-world.toml", ConfigType.SERVER, null)))),
                List.of(new BlockEntry("mekanism:metallurgic_infuser", "Metallurgic Infuser",
                        "mekanism.common.block.BlockMachine", "mekanism:metallurgic_infuser", "mekanism:metallurgic_infuser")),
                List.of(new ItemEntry("mekanism:energy_tablet", "Energy Tablet", "mekanism.common.item.ItemEnergized",
                        "", "", Map.of()),
                        new ItemEntry("minecraft:leather_helmet", "Leather Cap", "net.minecraft.world.item.ArmorItem",
                                "", "minecraft:item/leather_helmet_custom", Map.of(0, 0xA06540))),
                List.of(new EntityTypeEntry("minecraft:zombie", "Zombie", "monster", "minecraft:zombie_spawn_egg")));
        Path file = this.directory.resolve("catalog.json");

        catalog.write(file);

        assertEquals(catalog, PackCatalog.read(file));
        PackCatalog.Header header = PackCatalog.readHeader(file);
        assertTrue(header.matches("inventory-1", "en_us"));
        assertFalse(header.matches("inventory-2", "en_us"));
        assertFalse(header.matches("inventory-1", "de_de"));
    }

    @Test
    void rejectsAnotherFormat() throws Exception {
        Path file = this.directory.resolve("catalog.json");
        Files.writeString(file, "{\"format\":2,\"inventoryId\":\"a\",\"language\":\"en_us\"}");

        IOException failure = assertThrows(IOException.class, () -> PackCatalog.readHeader(file));
        assertTrue(failure.getMessage().contains("Unsupported pack catalog format 2"), failure.getMessage());
        assertThrows(IOException.class, () -> PackCatalog.read(file));
    }

    @Test
    void rejectsInvalidContent() throws Exception {
        assertInvalid("{\"format\":1,\"inventoryId\":\" \",\"language\":\"en_us\"}", "Blank inventory id");
        assertInvalid(catalogJson("\"items\":[{\"id\":\"a:b\"},{\"id\":\"a:b\"}]"), "Duplicate item id a:b");
        assertInvalid(catalogJson("\"blocks\":[{\"id\":\"Not An Id\"}]"), "Invalid block id");
        assertInvalid(catalogJson("\"mods\":[{\"id\":\"M\",\"module\":\"m\",\"file\":\"file:///m.jar\"}]"), "Invalid mod id");
        assertInvalid(catalogJson("\"mods\":[{\"id\":\"mod\",\"module\":\"m\",\"file\":\"file:///m.jar\","
                + "\"configs\":[{\"fileName\":\"a.toml\",\"type\":\"WORLD\"}]}]"), "WORLD");
        assertInvalid(catalogJson("\"items\":[{\"id\":\"a:b\",\"block\":\"no id\"}]"), "Invalid block of a:b");
        assertInvalid(catalogJson("\"items\":[]} trailing"), "");
    }

    @Test
    void readsLargeCatalogs() throws Exception {
        List<ItemEntry> items = new ArrayList<>();
        List<BlockEntry> blocks = new ArrayList<>();
        for (int i = 0; i < 30_000; i++) {
            items.add(new ItemEntry("pack:item_" + i, "Item " + i, "pack.Item", "", "", Map.of()));
        }
        for (int i = 0; i < 20_000; i++) {
            blocks.add(new BlockEntry("pack:block_" + i, "Block " + i, "pack.Block", "pack:item_" + i, ""));
        }
        PackCatalog catalog = new PackCatalog("inventory", "en_us", List.of(), blocks, items, List.of());
        Path file = this.directory.resolve("catalog.json");

        catalog.write(file);

        assertTrue(Files.size(file) < 8L * 1024 * 1024, "catalog is " + Files.size(file) + " bytes");
        assertEquals(catalog, PackCatalog.read(file));
    }

    private static String catalogJson(String content) {
        return "{\"format\":1,\"inventoryId\":\"inventory\",\"language\":\"en_us\"," + content + "}";
    }

    private void assertInvalid(String json, String message) throws IOException {
        Path file = this.directory.resolve("invalid.json");
        Files.writeString(file, json);
        IOException failure = assertThrows(IOException.class, () -> PackCatalog.read(file));
        assertTrue(failure.getMessage().contains(message), failure.getMessage());
    }
}
