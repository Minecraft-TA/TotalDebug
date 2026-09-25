package com.github.minecraft_ta.totaldebug.client.catalog;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.client.inspection.ItemIcons;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.Fluid;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The registries the pack catalog lists, and what an entry of each records beyond its id: the name the game shows,
 * the class of the registered object, an item that draws it, related entries and further facts. Listing another kind
 * of content is one more {@link Capture} in {@link #captures()}; Companion lists any registry it finds in the catalog.
 * Created and used on the client thread once registries and resources have loaded.
 */
final class CatalogRegistries {
    private static final int TINT_PROBES = 4;

    /** Fills in what an entry records about one registered value. */
    interface Description<T> {
        void describe(T value, Entry entry);
    }

    /** One registry to capture: which of its values are listed, and how each is described. */
    record Capture<T>(Registry<T> registry, Predicate<T> listed, Description<T> description) {
        Cursor<T> start() {
            return new Cursor<>(this);
        }
    }

    /** Walks one registry a value at a time, so the capture can pause between slices. */
    static final class Cursor<T> {
        private final Capture<T> capture;
        private final Iterator<T> values;
        private final List<PackCatalog.RegistryEntry> entries = new ArrayList<>();

        private Cursor(Capture<T> capture) {
            this.capture = capture;
            this.values = capture.registry().iterator();
        }

        boolean hasNext() {
            return this.values.hasNext();
        }

        /** Describes the next value; a description that fails keeps what it recorded before failing. */
        void next() {
            T value = this.values.next();
            if (!this.capture.listed().test(value)) return;
            Entry entry = new Entry(this.capture.registry().getKey(value).toString());
            try {
                this.capture.description().describe(value, entry);
            } catch (RuntimeException exception) {
                TotalDebug.LOGGER.debug("Incomplete pack catalog entry {}", entry.id, exception);
            }
            this.entries.add(entry.build());
        }

        PackCatalog.Registry result() {
            return new PackCatalog.Registry(id(this.capture.registry()), this.entries);
        }
    }

    /** What is known about one entry while it is described. */
    static final class Entry {
        private final String id;
        private final List<PackCatalog.Link> links = new ArrayList<>();
        private final Map<String, String> facts = new LinkedHashMap<>();
        private String name = "";
        private String className = "";
        private String icon = "";

        private Entry(String id) {
            this.id = id;
        }

        Entry name(Component name) {
            this.name = name.getString();
            return this;
        }

        Entry className(Object value) {
            this.className = value.getClass().getName();
            return this;
        }

        Entry icon(Item item) {
            this.icon = BuiltInRegistries.ITEM.getKey(item).toString();
            return this;
        }

        <T> Entry link(String relation, Registry<T> registry, T value) {
            this.links.add(new PackCatalog.Link(relation, id(registry), registry.getKey(value).toString()));
            return this;
        }

        Entry fact(String key, String value) {
            if (!value.isEmpty()) this.facts.put(key, value);
            return this;
        }

        private PackCatalog.RegistryEntry build() {
            return new PackCatalog.RegistryEntry(this.id, this.name, this.className, this.icon, this.links, this.facts);
        }
    }

    private final Map<Block, BlockEntityType<?>> blockEntityTypes = new HashMap<>();
    private final Map<String, PackCatalog.ItemAppearance> itemAppearances = new HashMap<>();

    CatalogRegistries() {
        for (BlockEntityType<?> type : BuiltInRegistries.BLOCK_ENTITY_TYPE) {
            for (Block block : type.getValidBlocks()) this.blockEntityTypes.putIfAbsent(block, type);
        }
    }

    /** The registries to list, in the order they are captured. */
    List<Capture<?>> captures() {
        return List.of(
                new Capture<>(BuiltInRegistries.BLOCK, block -> true, this::block),
                new Capture<>(BuiltInRegistries.ITEM, item -> true, this::item),
                new Capture<>(BuiltInRegistries.ENTITY_TYPE, type -> true, CatalogRegistries::entityType),
                new Capture<>(BuiltInRegistries.FLUID, fluid -> fluid.isSource(fluid.defaultFluidState()), CatalogRegistries::fluid),
                new Capture<>(BuiltInRegistries.SOUND_EVENT, sound -> true, CatalogRegistries::sound));
    }

    /** How the captured items are drawn, where that is not their conventional model. */
    Map<String, PackCatalog.ItemAppearance> itemAppearances() {
        return this.itemAppearances;
    }

    private void block(Block block, Entry entry) {
        entry.className(block);
        Item item = block.asItem();
        if (item != Items.AIR) entry.icon(item).link("item", BuiltInRegistries.ITEM, item);
        BlockEntityType<?> type = this.blockEntityTypes.get(block);
        if (type != null) entry.link("block_entity_type", BuiltInRegistries.BLOCK_ENTITY_TYPE, type);
        entry.name(block.getName());
    }

    private void item(Item item, Entry entry) {
        entry.className(item).icon(item);
        if (item instanceof BlockItem blockItem) entry.link("block", BuiltInRegistries.BLOCK, blockItem.getBlock());
        ItemStack stack = new ItemStack(item);
        entry.name(item.getName(stack));
        String id = BuiltInRegistries.ITEM.getKey(item).toString();
        String model = ItemIcons.model(stack).map(found -> PackCatalogEntries.model(id, found)).orElse("");
        Map<Integer, Integer> tints = tinted(stack) ? ItemIcons.of(stack).map(ItemIcons.Icon::tints).orElse(Map.of()) : Map.of();
        if (!model.isEmpty() || !tints.isEmpty()) this.itemAppearances.put(id, new PackCatalog.ItemAppearance(model, tints));
    }

    private static void entityType(EntityType<?> type, Entry entry) {
        entry.fact("category", type.getCategory().getName());
        SpawnEggItem egg = SpawnEggItem.byId(type);
        if (egg != null) entry.icon(egg).link("spawn_egg", BuiltInRegistries.ITEM, egg);
        entry.name(type.getDescription());
    }

    /** Only source fluids are listed; the flowing form of a fluid is part of it. */
    private static void fluid(Fluid fluid, Entry entry) {
        entry.className(fluid);
        Item bucket = fluid.getBucket();
        if (bucket != Items.AIR) entry.icon(bucket).link("bucket", BuiltInRegistries.ITEM, bucket);
        entry.name(fluid.getFluidType().getDescription());
    }

    /** Named by the subtitle the game shows for it; many block and ambient sounds have none. */
    private static void sound(SoundEvent sound, Entry entry) {
        WeighedSoundEvents sounds = Minecraft.getInstance().getSoundManager().getSoundEvent(sound.getLocation());
        if (sounds != null && sounds.getSubtitle() != null) entry.name(sounds.getSubtitle());
    }

    /** Most items have no color handler; only those need their model walked for tint indices. */
    private static boolean tinted(ItemStack stack) {
        var colors = Minecraft.getInstance().getItemColors();
        for (int index = 0; index < TINT_PROBES; index++) {
            if (colors.getColor(stack, index) != -1) return true;
        }
        return false;
    }

    private static String id(Registry<?> registry) {
        return registry.key().location().toString();
    }
}
