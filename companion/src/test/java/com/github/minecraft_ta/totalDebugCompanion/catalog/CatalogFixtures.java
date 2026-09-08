package com.github.minecraft_ta.totalDebugCompanion.catalog;

import com.github.minecraft_ta.totaldebug.storage.GameCatalog;
import com.github.minecraft_ta.totaldebug.storage.InstancePaths;
import com.github.minecraft_ta.totaldebug.storage.RuntimeInventory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class CatalogFixtures {
    public static InstancePaths create(Path directory) throws Exception {
        var paths = new InstancePaths(directory);
        Files.createDirectories(paths.runtime());
        new RuntimeInventory("fixture-runtime", "21", directory.toString(), false, List.of(new RuntimeInventory.Source(
                RuntimeInventory.SourceKind.DIRECTORY, directory, "fixture", new RuntimeInventory.RuntimeModule(
                "example", "Example Mod", RuntimeInventory.ModuleKind.MOD)))).write(paths.inventory());
        var item = new GameCatalog.Entry(GameCatalog.Kind.ITEM, "example:tool", "Ruby Tool", "Example Mod", "1.0",
                "example.ToolItem", "example:tool", "example:item/tool", Map.of("Maximum stack size", "1", "Maximum durability", "500"));
        var block = new GameCatalog.Entry(GameCatalog.Kind.BLOCK, "example:tool", "Ruby Tool Block", "Example Mod", "1.0",
                "example.ToolBlock", "example:tool", "example:item/tool", Map.of("Default hardness", "3.0"));
        var localized = new GameCatalog.Entry(GameCatalog.Kind.ITEM, "other:gem", "Rötlicher Kristall", "Other Mod", "2.0",
                "other.Gem", "", "other:item/gem", Map.of());
        Map<String, byte[]> files = new LinkedHashMap<>();
        add(files, "layers/0/assets/minecraft/models/item/generated.json", "{\"parent\":\"builtin/generated\",\"gui_light\":\"front\"}");
        add(files, "layers/0/assets/example/models/item/tool.json", "{\"parent\":\"example:item/base\"}");
        add(files, "layers/0/assets/example/models/item/base.json", "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"example:tool_sprite\"}}");
        add(files, "layers/0/assets/minecraft/atlases/blocks.json", "{\"sources\":[{\"type\":\"minecraft:single\",\"resource\":\"example:item/tool\",\"sprite\":\"example:tool_sprite\"}]}");
        add(files, "layers/0/assets/example/blockstates/tool.json", "{\"variants\":{\"\":{\"model\":\"example:item/tool\"}}}");
        files.put("layers/0/assets/example/textures/item/tool.png", png(0xff0000ff));
        files.put("layers/1/assets/example/textures/item/tool.png", png(0xffff0033));
        add(files, "layers/0/assets/example/textures/item/tool.png.mcmeta", "{\"animation\":{\"frametime\":2}}");
        Map<String, List<String>> resources = new LinkedHashMap<>();
        for (String file : files.keySet()) resources.put(file.substring("layers/0/".length()), List.of("Example Mod"));
        resources.put("assets/example/textures/item/tool.png", List.of("Example Mod", "Ruby resource pack"));
        var catalog = new GameCatalog(1, "fixture-runtime", "2026-09-08T12:00:00Z", "en_us", List.of(item, block, localized), resources, List.of());
        add(files, GameCatalog.MANIFEST, GameCatalog.GSON.toJson(catalog));
        try (var zip = new ZipOutputStream(Files.newOutputStream(paths.gameCatalog()))) {
            for (var file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue());
                zip.closeEntry();
            }
        }
        return paths;
    }

    private static void add(Map<String, byte[]> files, String path, String text) {
        files.put(path, text.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] png(int color) throws Exception {
        var image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) image.setRGB(x, y, color);
        var output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
