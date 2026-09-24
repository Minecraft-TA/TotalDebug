package com.github.minecraft_ta.totalDebugCompanion.catalog;

import com.github.minecraft_ta.totaldebug.storage.PackCatalog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** A small pack: one mod with a block, its item and an entity, plus content in a namespace without a mod. */
public final class CatalogFixtures {
    public static final String INVENTORY = "inventory-1";

    private CatalogFixtures() {
    }

    public static Path modJar(Path directory) throws IOException {
        Path jar = directory.resolve("testmod.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (String entry : List.of(
                    "META-INF/neoforge.mods.toml",
                    "testmod/Widget.class",
                    "assets/testmod/lang/en_us.json",
                    "assets/testmod/models/item/widget.json",
                    "assets/testmod/textures/item/widget.png",
                    "assets/testmod/textures/block/widget_block.png",
                    "assets/testmod/sounds.json",
                    "data/testmod/recipe/widget.json",
                    "data/testmod/loot_table/blocks/widget_block.json")) {
                zip.putNextEntry(new ZipEntry(entry));
                zip.write(entry.getBytes());
                zip.closeEntry();
            }
        }
        return jar;
    }

    public static PackCatalog catalog(Path jar) {
        return new PackCatalog(INVENTORY, "en_us",
                List.of(new PackCatalog.Mod("testmod", "Test Mod", "1.2.3", "Widgets for tests", List.of("Tester"),
                                "MIT", Map.of("display", "https://example.invalid"), "", "testmod", jar.toUri(),
                                List.of(new PackCatalog.Dependency("neoforge", PackCatalog.DependencyType.REQUIRED, "[21,)",
                                                PackCatalog.Side.BOTH),
                                        new PackCatalog.Dependency("jei", PackCatalog.DependencyType.OPTIONAL, "",
                                                PackCatalog.Side.CLIENT)),
                                List.of(new PackCatalog.ConfigFile("testmod-common.toml", PackCatalog.ConfigType.COMMON,
                                        jar.resolveSibling("testmod-common.toml")))),
                        new PackCatalog.Mod("neoforge", "NeoForge", "21.1.250", "", List.of(), "LGPL", Map.of(), "",
                                "minecraft+neoforge", jar.resolveSibling("neoforge.jar").toUri(), List.of(), List.of())),
                List.of(new PackCatalog.BlockEntry("testmod:widget_block", "Widget Block", "testmod.WidgetBlock",
                        "testmod:widget_block", "testmod:widget_entity")),
                List.of(new PackCatalog.ItemEntry("testmod:widget", "Widget", "testmod.Widget", "", "", Map.of()),
                        new PackCatalog.ItemEntry("testmod:widget_block", "Widget Block", "net.minecraft.world.item.BlockItem",
                                "testmod:widget_block", "", Map.of()),
                        new PackCatalog.ItemEntry("c:shared_dust", "Shared Dust", "c.Dust", "", "c:item/dust", Map.of(0, 0xFF0000))),
                List.of(new PackCatalog.EntityTypeEntry("testmod:gremlin", "Gremlin", "monster", "")));
    }
}
