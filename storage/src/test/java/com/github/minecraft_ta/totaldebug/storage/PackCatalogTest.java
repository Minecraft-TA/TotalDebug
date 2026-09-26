package com.github.minecraft_ta.totaldebug.storage;

import com.github.minecraft_ta.totaldebug.storage.PackCatalog.ConfigFile;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog.ConfigSection;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog.ConfigSetting;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog.ConfigType;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog.Dependency;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog.DependencyType;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog.ItemAppearance;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog.Link;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog.Mod;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog.Registry;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog.RegistryEntry;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog.Restart;
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
                        List.of(new ConfigFile("mekanism-common.toml", ConfigType.COMMON, config,
                                        List.of(new ConfigSection("machines", "Machine settings")),
                                        List.of(new ConfigSetting("machines.maxEnergy", "Energy a machine stores",
                                                        "40000", "1 ~ 2147483647", List.of(), Restart.WORLD),
                                                new ConfigSetting("machines.mode", "", "FAST", "",
                                                        List.of("FAST", "SLOW"), Restart.NONE))),
                                new ConfigFile("mekanism-world.toml", ConfigType.SERVER, null, List.of(), List.of())))),
                List.of(new Registry("minecraft:block", List.of(new RegistryEntry("mekanism:metallurgic_infuser",
                                "Metallurgic Infuser", "mekanism.common.block.BlockMachine", "mekanism:metallurgic_infuser",
                                List.of(new Link("item", "minecraft:item", "mekanism:metallurgic_infuser"),
                                        new Link("block_entity_type", "minecraft:block_entity_type", "mekanism:metallurgic_infuser")),
                                Map.of()))),
                        new Registry("minecraft:item", List.of(
                                new RegistryEntry("mekanism:energy_tablet", "Energy Tablet", "mekanism.common.item.ItemEnergized",
                                        "mekanism:energy_tablet", List.of(), Map.of()),
                                new RegistryEntry("minecraft:leather_helmet", "Leather Cap", "net.minecraft.world.item.ArmorItem",
                                        "minecraft:leather_helmet", List.of(), Map.of()))),
                        new Registry("minecraft:entity_type", List.of(new RegistryEntry("minecraft:zombie", "Zombie", "",
                                "minecraft:zombie_spawn_egg", List.of(new Link("spawn_egg", "minecraft:item", "minecraft:zombie_spawn_egg")),
                                Map.of("category", "monster")))),
                        new Registry("minecraft:sound_event", List.of(new RegistryEntry("minecraft:block.anvil.land", "", "", "",
                                List.of(), Map.of())))),
                Map.of("minecraft:leather_helmet", new ItemAppearance("minecraft:item/leather_helmet_custom", Map.of(0, 0xA06540))),
                List.of(new PackCatalog.KeyBinding("key.mekanism.mode", "Mode Switch", "constants.mekanism.mod_name",
                        "Mekanism", "mekanism", "key.keyboard.n", "SHIFT", "mekanism.Context#0")),
                List.of(new PackCatalog.KeyContext("mekanism.Context#0", "Context", List.of("mekanism.Context#0"))),
                Map.of("key.keyboard.n", "N", "key.keyboard.z", "Y"));
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
        Files.writeString(file, "{\"format\":1,\"inventoryId\":\"a\",\"language\":\"en_us\"}");

        IOException failure = assertThrows(IOException.class, () -> PackCatalog.readHeader(file));
        assertTrue(failure.getMessage().contains("Unsupported pack catalog format 1"), failure.getMessage());
        assertThrows(IOException.class, () -> PackCatalog.read(file));
    }

    @Test
    void rejectsInvalidContent() throws Exception {
        assertInvalid("{\"format\":" + PackCatalog.FORMAT_VERSION + ",\"inventoryId\":\" \",\"language\":\"en_us\"}",
                "Blank inventory id");
        assertInvalid(catalogJson(registry("minecraft:item", "{\"id\":\"a:b\"},{\"id\":\"a:b\"}")),
                "Duplicate minecraft:item entry id a:b");
        assertInvalid(catalogJson(registry("minecraft:block", "{\"id\":\"Not An Id\"}")), "Invalid entry id");
        assertInvalid(catalogJson("\"registries\":[{\"id\":\"a:b\",\"entries\":[]},{\"id\":\"a:b\",\"entries\":[]}]"),
                "Duplicate registry id a:b");
        assertInvalid(catalogJson("\"mods\":[{\"id\":\"M\",\"module\":\"m\",\"file\":\"file:///m.jar\"}]"), "Invalid mod id");
        assertInvalid(catalogJson("\"mods\":[{\"id\":\"mod\",\"module\":\"m\",\"file\":\"file:///m.jar\","
                + "\"configs\":[{\"fileName\":\"a.toml\",\"type\":\"WORLD\"}]}]"), "WORLD");
        assertInvalid(catalogJson(registry("minecraft:item",
                "{\"id\":\"a:b\",\"links\":[{\"relation\":\"block\",\"registry\":\"minecraft:block\",\"id\":\"no id\"}]}")),
                "Invalid linked id");
        assertInvalid(catalogJson(registry("minecraft:item", "{\"id\":\"a:b\",\"facts\":{\"Bad Key\":\"x\"}}")),
                "Invalid fact of a:b");
        assertInvalid(catalogJson("\"registries\":[]} trailing"), "");
        assertInvalid(catalogJson("\"keyBindings\":[{\"name\":\"key.jump\",\"defaultKey\":\"key.keyboard.space\","
                + "\"defaultModifier\":\"NONE\",\"context\":\"missing\"}]"), "unknown context missing");
    }

    @Test
    void readsLargeCatalogs() throws Exception {
        List<RegistryEntry> items = new ArrayList<>();
        List<RegistryEntry> blocks = new ArrayList<>();
        for (int i = 0; i < 30_000; i++) {
            items.add(new RegistryEntry("pack:item_" + i, "Item " + i, "pack.Item", "pack:item_" + i, List.of(), Map.of()));
        }
        for (int i = 0; i < 20_000; i++) {
            blocks.add(new RegistryEntry("pack:block_" + i, "Block " + i, "pack.Block", "pack:item_" + i,
                    List.of(new Link("item", "minecraft:item", "pack:item_" + i)), Map.of()));
        }
        PackCatalog catalog = new PackCatalog("inventory", "en_us", List.of(),
                List.of(new Registry("minecraft:block", blocks), new Registry("minecraft:item", items)), Map.of(),
                List.of(), List.of(), Map.of());
        Path file = this.directory.resolve("catalog.json");

        catalog.write(file);

        assertTrue(Files.size(file) < 8L * 1024 * 1024, "catalog is " + Files.size(file) + " bytes");
        assertEquals(catalog, PackCatalog.read(file));
    }

    @Test
    void displaysDefaultsAndFileValuesAlike() {
        assertEquals("FAST", ConfigSetting.display("FAST"));
        assertEquals("GAME", ConfigSetting.display(Restart.GAME));
        assertEquals("[\"a\", 3, true, \"GAME\"]", ConfigSetting.display(List.of("a", 3, true, Restart.GAME)));
        assertEquals("0.5", ConfigSetting.display(0.5));
        assertEquals("", ConfigSetting.display(null));
    }

    private static String registry(String id, String entries) {
        return "\"registries\":[{\"id\":\"" + id + "\",\"entries\":[" + entries + "]}]";
    }

    private static String catalogJson(String content) {
        return "{\"format\":" + PackCatalog.FORMAT_VERSION + ",\"inventoryId\":\"inventory\",\"language\":\"en_us\"," + content + "}";
    }

    private void assertInvalid(String json, String message) throws IOException {
        Path file = this.directory.resolve("invalid.json");
        Files.writeString(file, json);
        IOException failure = assertThrows(IOException.class, () -> PackCatalog.read(file));
        assertTrue(failure.getMessage().contains(message), failure.getMessage());
    }
}
