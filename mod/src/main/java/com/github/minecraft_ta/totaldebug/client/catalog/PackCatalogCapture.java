package com.github.minecraft_ta.totaldebug.client.catalog;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.client.inspection.ItemIcons;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.fml.ModList;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.config.ModConfigs;
import net.neoforged.neoforgespi.language.IModInfo;

import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Reads installed mods and the block, item and entity type registries into a {@link PackCatalog}. Registries are
 * frozen once the game has loaded, so one capture describes a runtime inventory. Work runs on the client thread in
 * slices, so a large pack does not stall a frame.
 */
public final class PackCatalogCapture {
    private static final long SLICE_NANOS = 5_000_000L;
    private static final int TINT_PROBES = 4;

    private final String inventoryId;
    private final String language;
    private final Map<String, String> moduleByModId;
    private final CompletableFuture<PackCatalog> result = new CompletableFuture<>();
    private final List<PackCatalog.Mod> mods = new ArrayList<>();
    private final List<PackCatalog.BlockEntry> blocks = new ArrayList<>();
    private final List<PackCatalog.ItemEntry> items = new ArrayList<>();
    private final List<PackCatalog.EntityTypeEntry> entityTypes = new ArrayList<>();
    private final Map<Block, String> blockEntityTypes = new HashMap<>();
    private Iterator<Block> blockCursor;
    private Iterator<Item> itemCursor;
    private Iterator<EntityType<?>> entityCursor;
    private long startedAt;

    public PackCatalogCapture(String inventoryId, String language, Map<String, String> moduleByModId) {
        this.inventoryId = inventoryId;
        this.language = language;
        this.moduleByModId = Map.copyOf(moduleByModId);
    }

    public CompletableFuture<PackCatalog> result() {
        return this.result;
    }

    /** Continues the capture for one slice. Client thread only; returns true once the result is complete. */
    public boolean step() {
        if (this.result.isDone()) {
            return true;
        }
        try {
            long deadline = System.nanoTime() + SLICE_NANOS;
            if (this.blockCursor == null) {
                this.startedAt = System.nanoTime();
                captureMods();
                for (BlockEntityType<?> type : BuiltInRegistries.BLOCK_ENTITY_TYPE) {
                    String id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type).toString();
                    for (Block block : type.getValidBlocks()) this.blockEntityTypes.putIfAbsent(block, id);
                }
                this.blockCursor = BuiltInRegistries.BLOCK.iterator();
                this.itemCursor = BuiltInRegistries.ITEM.iterator();
                this.entityCursor = BuiltInRegistries.ENTITY_TYPE.iterator();
            }
            while (System.nanoTime() < deadline) {
                if (this.blockCursor.hasNext()) {
                    block(this.blockCursor.next());
                } else if (this.itemCursor.hasNext()) {
                    item(this.itemCursor.next());
                } else if (this.entityCursor.hasNext()) {
                    entityType(this.entityCursor.next());
                } else {
                    PackCatalog catalog = new PackCatalog(this.inventoryId, this.language, this.mods, this.blocks,
                            this.items, this.entityTypes);
                    TotalDebug.LOGGER.info("Captured the pack catalog in {} ms: {} mods, {} blocks, {} items, {} entity types",
                            (System.nanoTime() - this.startedAt) / 1_000_000, this.mods.size(), this.blocks.size(),
                            this.items.size(), this.entityTypes.size());
                    this.result.complete(catalog);
                    return true;
                }
            }
            return false;
        } catch (RuntimeException exception) {
            this.result.completeExceptionally(exception);
            return true;
        }
    }

    private void captureMods() {
        ModList.get().forEachModFile(file -> {
            for (IModInfo info : file.getModInfos()) {
                try {
                    this.mods.add(mod(info, file.getFilePath()));
                } catch (RuntimeException exception) {
                    TotalDebug.LOGGER.warn("Leaving mod {} out of the pack catalog", info.getModId(), exception);
                }
            }
        });
    }

    private PackCatalog.Mod mod(IModInfo info, Path file) {
        Map<String, String> urls = new LinkedHashMap<>();
        info.getModURL().map(URL::toString).ifPresent(url -> urls.put("display", url));
        info.getOwningFile().getConfig().getConfigElement("issueTrackerURL")
                .ifPresent(url -> urls.put("issues", String.valueOf(url)));
        List<PackCatalog.Dependency> dependencies = new ArrayList<>();
        for (IModInfo.ModVersion dependency : info.getDependencies()) {
            PackCatalogEntries.dependency(dependency.getModId(), dependency.getType().name(),
                    dependency.getVersionRange().toString(), dependency.getSide().name()).ifPresent(dependencies::add);
        }
        List<PackCatalog.ConfigFile> configs = new ArrayList<>();
        for (ModConfig config : ModConfigs.getModConfigs(info.getModId())) {
            configs.add(new PackCatalog.ConfigFile(config.getFileName(),
                    PackCatalogEntries.configType(config.getType().name()), loadedPath(config)));
        }
        return new PackCatalog.Mod(
                info.getModId(),
                info.getDisplayName(),
                info.getVersion().toString(),
                info.getDescription().strip(),
                PackCatalogEntries.authors(info.getConfig().getConfigElement("authors").orElse(null)),
                info.getOwningFile().getLicense(),
                urls,
                info.getLogoFile().orElse(""),
                this.moduleByModId.getOrDefault(info.getModId(), info.getModId()),
                file.toAbsolutePath().normalize().toUri(),
                dependencies,
                configs);
    }

    private void block(Block block) {
        String id = key(BuiltInRegistries.BLOCK, block);
        String name = "";
        String item = "";
        try {
            name = block.getName().getString();
            Item blockItem = block.asItem();
            if (blockItem != Items.AIR) item = key(BuiltInRegistries.ITEM, blockItem);
        } catch (RuntimeException exception) {
            TotalDebug.LOGGER.debug("Incomplete pack catalog entry for block {}", id, exception);
        }
        this.blocks.add(new PackCatalog.BlockEntry(id, name, block.getClass().getName(), item,
                this.blockEntityTypes.getOrDefault(block, "")));
    }

    private void item(Item item) {
        String id = key(BuiltInRegistries.ITEM, item);
        String name = "";
        String model = "";
        Map<Integer, Integer> tints = Map.of();
        try {
            ItemStack stack = new ItemStack(item);
            name = item.getName(stack).getString();
            model = ItemIcons.model(stack).map(found -> PackCatalogEntries.model(id, found)).orElse("");
            if (tinted(stack)) {
                tints = ItemIcons.of(stack).map(ItemIcons.Icon::tints).orElse(Map.of());
            }
        } catch (RuntimeException exception) {
            TotalDebug.LOGGER.debug("Incomplete pack catalog entry for item {}", id, exception);
        }
        String block = item instanceof BlockItem blockItem ? key(BuiltInRegistries.BLOCK, blockItem.getBlock()) : "";
        this.items.add(new PackCatalog.ItemEntry(id, name, item.getClass().getName(), block, model, tints));
    }

    private void entityType(EntityType<?> type) {
        String id = key(BuiltInRegistries.ENTITY_TYPE, type);
        String name = "";
        String egg = "";
        try {
            name = type.getDescription().getString();
            SpawnEggItem spawnEgg = SpawnEggItem.byId(type);
            if (spawnEgg != null) egg = key(BuiltInRegistries.ITEM, spawnEgg);
        } catch (RuntimeException exception) {
            TotalDebug.LOGGER.debug("Incomplete pack catalog entry for entity type {}", id, exception);
        }
        this.entityTypes.add(new PackCatalog.EntityTypeEntry(id, name, type.getCategory().getName(), egg));
    }

    /** A configuration has a file only while it is loaded; a server configuration outside a world has none. */
    private static Path loadedPath(ModConfig config) {
        if (config.getLoadedConfig() == null) {
            return null;
        }
        try {
            return config.getFullPath().toAbsolutePath().normalize();
        } catch (IllegalStateException notAFile) {
            return null;
        }
    }

    /** Most items have no color handler; only those need their model walked for tint indices. */
    private static boolean tinted(ItemStack stack) {
        var colors = Minecraft.getInstance().getItemColors();
        for (int index = 0; index < TINT_PROBES; index++) {
            if (colors.getColor(stack, index) != -1) return true;
        }
        return false;
    }

    private static <T> String key(Registry<T> registry, T value) {
        ResourceLocation key = registry.getKey(value);
        return key.toString();
    }
}
