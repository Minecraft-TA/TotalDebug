package com.github.minecraft_ta.totaldebug.resource;

import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackCompatibility;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.world.flag.FeatureFlagSet;
import net.neoforged.neoforge.event.AddPackFindersEvent;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resources Companion tries in the running game without writing a file: a pack held in memory, always enabled and
 * above every other pack, for client assets and for the singleplayer server's data. Its content lasts until the game
 * closes or Companion disconnects; the game uses a change after its next reload.
 */
public final class GameOverlay {
    /** The pack's id in both pack stacks. */
    public static final String ID = "totaldebug_game";

    private static final Map<String, byte[]> ENTRIES = new ConcurrentHashMap<>();

    private GameOverlay() {
    }

    /** Puts {@code content} at a pack path such as {@code assets/ns/lang/en_us.json}, or removes it for null. */
    public static void set(String path, byte[] content) {
        if (content == null) ENTRIES.remove(path);
        else ENTRIES.put(path, content.clone());
    }

    /** Removes every resource; returns the paths that were held. */
    public static Set<String> clear() {
        Set<String> paths = new TreeSet<>(ENTRIES.keySet());
        ENTRIES.clear();
        return paths;
    }

    /** Adds the pack to each pack stack the game builds. Registered on the mod event bus. */
    public static void addPackFinders(AddPackFindersEvent event) {
        PackType type = event.getPackType();
        PackLocationInfo location = new PackLocationInfo(ID, Component.literal("TotalDebug, in the game only"),
                PackSource.BUILT_IN, Optional.empty());
        PackResources resources = new Resources(location);
        Pack.ResourcesSupplier supplier = new Pack.ResourcesSupplier() {
            @Override
            public PackResources openPrimary(PackLocationInfo info) {
                return resources;
            }

            @Override
            public PackResources openFull(PackLocationInfo info, Pack.Metadata metadata) {
                return resources;
            }
        };
        Pack.Metadata metadata = new Pack.Metadata(Component.literal("Changes tried from TotalDebug Companion"),
                PackCompatibility.COMPATIBLE, FeatureFlagSet.of(), List.of());
        Pack pack = new Pack(location, supplier, metadata, new PackSelectionConfig(true, Pack.Position.TOP, true));
        event.addRepositorySource(consumer -> consumer.accept(pack));
    }

    private static String root(PackType type) {
        return type == PackType.CLIENT_RESOURCES ? "assets/" : "data/";
    }

    /** The pack's view of the entries; it holds nothing of its own, so closing it does nothing. */
    record Resources(PackLocationInfo location) implements PackResources {
        @Override
        public IoSupplier<InputStream> getRootResource(String... elements) {
            return null;
        }

        @Override
        public IoSupplier<InputStream> getResource(PackType type, ResourceLocation location) {
            byte[] content = ENTRIES.get(root(type) + location.getNamespace() + "/" + location.getPath());
            return content == null ? null : () -> new ByteArrayInputStream(content);
        }

        @Override
        public void listResources(PackType type, String namespace, String path, ResourceOutput output) {
            String namespaceRoot = root(type) + namespace + "/";
            String prefix = path.isEmpty() ? namespaceRoot : namespaceRoot + path + "/";
            for (Map.Entry<String, byte[]> entry : ENTRIES.entrySet()) {
                if (!entry.getKey().startsWith(prefix)) continue;
                ResourceLocation location = ResourceLocation.tryBuild(namespace, entry.getKey().substring(namespaceRoot.length()));
                byte[] content = entry.getValue();
                if (location != null) output.accept(location, () -> new ByteArrayInputStream(content));
            }
        }

        @Override
        public Set<String> getNamespaces(PackType type) {
            Set<String> namespaces = new TreeSet<>();
            String root = root(type);
            for (String path : ENTRIES.keySet()) {
                if (!path.startsWith(root)) continue;
                int end = path.indexOf('/', root.length());
                if (end > root.length()) namespaces.add(path.substring(root.length(), end));
            }
            return namespaces;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T getMetadataSection(MetadataSectionSerializer<T> deserializer) {
            if (deserializer != PackMetadataSection.TYPE) return null;
            return (T) new PackMetadataSection(Component.literal("Changes tried from TotalDebug Companion"),
                    SharedConstants.getCurrentVersion().getPackVersion(PackType.CLIENT_RESOURCES), Optional.empty());
        }

        @Override
        public void close() {
        }
    }
}
