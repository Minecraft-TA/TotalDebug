package com.github.minecraft_ta.totaldebug.client.companion;

import com.github.minecraft_ta.totaldebug.storage.AtomicFiles;
import com.github.minecraft_ta.totaldebug.storage.GameCatalog;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.neoforged.fml.ModList;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Registry reads run on the client thread; resource copying runs on the inventory worker. */
final class GameCatalogPublisher {
    record RegistryCapture(List<GameCatalog.Entry> entries, String language, List<String> warnings) { }

    static void publish(Path target, String inventoryId) throws IOException {
        Minecraft minecraft = Minecraft.getInstance();
        RegistryCapture capture;
        try {
            capture = minecraft.submit(() -> captureRegistries(minecraft)).get(30, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Game catalog capture interrupted", exception);
        } catch (Exception exception) {
            throw new IOException("Unable to capture item and block registries", exception);
        }
        writeResources(target, inventoryId, capture, minecraft.getResourceManager());
    }

    static void writeResources(Path target, String inventoryId, RegistryCapture capture,
                               net.minecraft.server.packs.resources.ResourceManager manager) throws IOException {
        var packs = manager.listPacks().toList();
        var resources = new java.util.TreeMap<String, List<String>>();
        AtomicFiles.replace(target, staged -> {
            try (var zip = new ZipOutputStream(Files.newOutputStream(staged))) {
                zip.setLevel(java.util.zip.Deflater.BEST_SPEED);
                // Retain each visible layer. Atlas definitions are additive, unlike ordinary models/textures.
                long total = 0;
                for (String directory : List.of("models", "blockstates", "textures", "atlases")) {
                    for (var resource : manager.listResourceStacks(directory, id -> true).entrySet()) {
                        String path = "assets/" + resource.getKey().getNamespace() + "/" + resource.getKey().getPath();
                        GameCatalog.validateResourcePath(path);
                        var providers = new ArrayList<String>();
                        for (var layer : resource.getValue()) {
                            if (Thread.currentThread().isInterrupted()) throw new IOException("Capture interrupted");
                            zip.putNextEntry(new ZipEntry("layers/" + providers.size() + "/" + path));
                            try (var input = layer.open()) {
                                byte[] buffer = new byte[16384];
                                long size = 0;
                                int count;
                                while ((count = input.read(buffer)) != -1) {
                                    size += count;
                                    total += count;
                                    if (size > 64L * 1024 * 1024 || total > 2L * 1024 * 1024 * 1024)
                                        throw new IOException("Game resources exceed capture size limit: " + path);
                                    zip.write(buffer, 0, count);
                                }
                            }
                            zip.closeEntry();
                            providers.add(layer.sourcePackId());
                        }
                        resources.put(path, providers);
                        // Minecraft omits .mcmeta from listings. Metadata may be supplied by a pack
                        // above the selected texture, but never inherited from a lower texture layer.
                        var metadataId = resource.getKey().withPath(resource.getKey().getPath() + ".mcmeta");
                        var metadata = manager.getResource(metadataId);
                        if (metadata.isPresent() && packs.indexOf(metadata.get().source())
                                >= packs.indexOf(resource.getValue().getLast().source())) {
                            String metadataPath = path + ".mcmeta";
                            zip.putNextEntry(new ZipEntry("layers/0/" + metadataPath));
                            try (var input = metadata.get().open()) {
                                byte[] bytes = input.readNBytes(4 * 1024 * 1024 + 1);
                                if (bytes.length > 4 * 1024 * 1024) throw new IOException("Oversized metadata: " + metadataPath);
                                total += bytes.length;
                                if (total > 2L * 1024 * 1024 * 1024) throw new IOException("Game resources exceed capture size limit");
                                zip.write(bytes);
                            }
                            zip.closeEntry();
                            resources.put(metadataPath, List.of(metadata.get().sourcePackId()));
                        }
                    }
                }
                if (!packs.equals(manager.listPacks().toList()))
                    throw new IOException("Resource packs changed during capture; retry after resource reload finishes");
                GameCatalog catalog = new GameCatalog(1, inventoryId, Instant.now().toString(), capture.language(),
                        capture.entries(), resources, capture.warnings());
                zip.putNextEntry(new ZipEntry(GameCatalog.MANIFEST));
                zip.write(GameCatalog.GSON.toJson(catalog).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        });
    }

    private static RegistryCapture captureRegistries(Minecraft minecraft) {
        var entries = new ArrayList<GameCatalog.Entry>();
        var warnings = new ArrayList<String>();
        for (var item : BuiltInRegistries.ITEM) {
            String id = BuiltInRegistries.ITEM.getKey(item).toString();
            try {
                var stack = item.getDefaultInstance();
                var properties = new LinkedHashMap<String, String>();
                properties.put("Maximum stack size", Integer.toString(stack.getMaxStackSize()));
                properties.put("Maximum durability", Integer.toString(stack.getMaxDamage()));
                var food = stack.get(net.minecraft.core.component.DataComponents.FOOD);
                if (food != null) {
                    properties.put("Food nutrition", Integer.toString(food.nutrition()));
                    properties.put("Food saturation", Float.toString(food.saturation()));
                }
                String counterpart = item instanceof BlockItem blockItem
                        ? BuiltInRegistries.BLOCK.getKey(blockItem.getBlock()).toString() : "";
                entries.add(entry(GameCatalog.Kind.ITEM, id, stack.getHoverName().getString(), item.getClass(),
                        counterpart, model(minecraft, item), properties));
            } catch (RuntimeException failure) {
                warnings.add("Item " + id + ": " + failure.getMessage());
                entries.add(entry(GameCatalog.Kind.ITEM, id, id, item.getClass(), "", "",
                        Map.of("Capture error", failure.toString())));
            }
        }
        for (var block : BuiltInRegistries.BLOCK) {
            String id = BuiltInRegistries.BLOCK.getKey(block).toString();
            try {
                var state = block.defaultBlockState();
                var properties = new LinkedHashMap<String, String>();
                properties.put("Default state", state.toString());
                properties.put("Possible states", Integer.toString(block.getStateDefinition().getPossibleStates().size()));
                properties.put("Block entity", Boolean.toString(state.hasBlockEntity()));
                properties.put("Requires correct tool", Boolean.toString(state.requiresCorrectToolForDrops()));
                properties.put("Default hardness", Float.toString(block.defaultDestroyTime()));
                for (var property : state.getProperties())
                    properties.put("State: " + property.getName(), property.getPossibleValues().toString());
                Item item = block.asItem();
                entries.add(entry(GameCatalog.Kind.BLOCK, id, block.getName().getString(), block.getClass(),
                        item == Items.AIR ? "" : BuiltInRegistries.ITEM.getKey(item).toString(),
                        item == Items.AIR ? "" : model(minecraft, item), properties));
            } catch (RuntimeException failure) {
                warnings.add("Block " + id + ": " + failure.getMessage());
                entries.add(entry(GameCatalog.Kind.BLOCK, id, id, block.getClass(), "", "",
                        Map.of("Capture error", failure.toString())));
            }
        }
        return new RegistryCapture(entries, minecraft.options.languageCode, warnings);
    }

    private static String model(Minecraft minecraft, Item item) {
        var shaper = (net.neoforged.neoforge.client.model.RegistryAwareItemModelShaper)
                minecraft.getItemRenderer().getItemModelShaper();
        var location = shaper.getLocation(item.getDefaultInstance());
        if (location == null || !location.variant().equals("inventory")) return "";
        return location.id().getNamespace() + ":item/" + location.id().getPath();
    }

    private static GameCatalog.Entry entry(GameCatalog.Kind kind, String id, String name, Class<?> type,
                                           String counterpart, String model, Map<String, String> properties) {
        String namespace = id.substring(0, id.indexOf(':'));
        var mod = ModList.get().getModContainerById(namespace).map(container -> container.getModInfo());
        return new GameCatalog.Entry(kind, id, name, mod.map(info -> info.getDisplayName()).orElse(namespace),
                mod.map(info -> info.getVersion().toString()).orElse(""), type.getName(), counterpart, model, properties);
    }
}
