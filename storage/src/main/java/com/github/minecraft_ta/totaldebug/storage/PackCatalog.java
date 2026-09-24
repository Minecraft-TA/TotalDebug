package com.github.minecraft_ta.totaldebug.storage;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The game-owned description of installed mods and their registered content. Minecraft writes it next to the
 * runtime inventory it belongs to; Companion only reads it, including while no game is connected.
 */
public record PackCatalog(
        String inventoryId,
        String language,
        List<Mod> mods,
        List<BlockEntry> blocks,
        List<ItemEntry> items,
        List<EntityTypeEntry> entityTypes
) {
    public static final int FORMAT_VERSION = 1;
    public static final long MAX_FILE_BYTES = 64L * 1024 * 1024;

    private static final Pattern MOD_ID = Pattern.compile("[a-z][a-z0-9_]{1,63}");
    private static final Pattern REGISTRY_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");

    public enum ConfigType { COMMON, CLIENT, SERVER, STARTUP }
    public enum DependencyType { REQUIRED, OPTIONAL, INCOMPATIBLE, DISCOURAGED }
    public enum Side { CLIENT, SERVER, BOTH }

    /**
     * One installed mod. {@code module} is the runtime inventory module that contains it and {@code file} is its
     * original mod file, matching {@link RuntimeInventory.Source#logicalUri()}.
     */
    public record Mod(String id, String name, String version, String description, List<String> authors,
                      String license, Map<String, String> urls, String logo, String module, URI file,
                      List<Dependency> dependencies, List<ConfigFile> configs) {
        public Mod {
            requireMatch(MOD_ID, id, "mod id");
            name = text(name);
            version = text(version);
            description = text(description);
            authors = List.copyOf(authors);
            license = text(license);
            urls = Map.copyOf(urls);
            logo = text(logo);
            requireText(module, "module of " + id);
            Objects.requireNonNull(file, "file of " + id);
            dependencies = List.copyOf(dependencies);
            configs = List.copyOf(configs);
        }

        public String title() { return name.isEmpty() ? id : name; }
    }

    public record Dependency(String modId, DependencyType type, String versionRange, Side side) {
        public Dependency {
            requireMatch(MOD_ID, modId, "dependency id");
            Objects.requireNonNull(type, "dependency type");
            versionRange = text(versionRange);
            Objects.requireNonNull(side, "dependency side");
        }
    }

    /** {@code path} is null for a configuration that is not loaded, such as a server configuration outside a world. */
    public record ConfigFile(String fileName, ConfigType type, Path path) {
        public ConfigFile {
            requireText(fileName, "config file name");
            Objects.requireNonNull(type, "config type");
        }
    }

    public record BlockEntry(String id, String name, String className, String item, String blockEntityType) {
        public BlockEntry {
            requireMatch(REGISTRY_ID, id, "block id");
            name = text(name);
            className = text(className);
            item = optionalId(item, "block item of " + id);
            blockEntityType = optionalId(blockEntityType, "block entity type of " + id);
        }
    }

    /** {@code model} is empty when the item uses its conventional {@code ns:item/path} model. */
    public record ItemEntry(String id, String name, String className, String block, String model,
                            Map<Integer, Integer> tints) {
        public ItemEntry {
            requireMatch(REGISTRY_ID, id, "item id");
            name = text(name);
            className = text(className);
            block = optionalId(block, "block of " + id);
            model = text(model);
            tints = Map.copyOf(tints);
        }
    }

    public record EntityTypeEntry(String id, String name, String category, String spawnEgg) {
        public EntityTypeEntry {
            requireMatch(REGISTRY_ID, id, "entity type id");
            name = text(name);
            category = text(category);
            spawnEgg = optionalId(spawnEgg, "spawn egg of " + id);
        }
    }

    public record Header(int format, String inventoryId, String language) {
        public boolean matches(String inventoryId, String language) {
            return format == FORMAT_VERSION && this.inventoryId.equals(inventoryId) && this.language.equals(language);
        }
    }

    /** Whether {@code value} is a mod id as NeoForge accepts it. */
    public static boolean isModId(String value) {
        return value != null && MOD_ID.matcher(value).matches();
    }

    public PackCatalog {
        requireText(inventoryId, "inventory id");
        requireText(language, "language");
        mods = List.copyOf(mods);
        blocks = List.copyOf(blocks);
        items = List.copyOf(items);
        entityTypes = List.copyOf(entityTypes);
        unique(mods.stream().map(Mod::id).toList(), "mod");
        unique(blocks.stream().map(BlockEntry::id).toList(), "block");
        unique(items.stream().map(ItemEntry::id).toList(), "item");
        unique(entityTypes.stream().map(EntityTypeEntry::id).toList(), "entity type");
    }

    public void write(Path path) throws IOException {
        AtomicFiles.replace(path, staged -> {
            try (JsonWriter writer = new JsonWriter(Files.newBufferedWriter(staged, StandardCharsets.UTF_8))) {
                writer.setHtmlSafe(false);
                writeTo(writer);
            }
            read(staged);
        });
    }

    public static PackCatalog read(Path file) throws IOException {
        requireSize(file);
        try (JsonReader reader = new JsonReader(Files.newBufferedReader(file, StandardCharsets.UTF_8))) {
            Header header = header(reader, file);
            List<Mod> mods = List.of();
            List<BlockEntry> blocks = List.of();
            List<ItemEntry> items = List.of();
            List<EntityTypeEntry> entityTypes = List.of();
            while (reader.hasNext()) {
                switch (reader.nextName()) {
                    case "mods" -> mods = entries(reader, PackCatalog::mod);
                    case "blocks" -> blocks = entries(reader, PackCatalog::block);
                    case "items" -> items = entries(reader, PackCatalog::item);
                    case "entityTypes" -> entityTypes = entries(reader, PackCatalog::entityType);
                    default -> reader.skipValue();
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new IllegalArgumentException("Trailing content");
            }
            return new PackCatalog(header.inventoryId(), header.language(), mods, blocks, items, entityTypes);
        } catch (RuntimeException exception) {
            throw new IOException("Invalid pack catalog " + file + ": " + exception.getMessage(), exception);
        }
    }

    /** Reads only the leading identity fields, so a matching catalog can be reused without parsing its content. */
    public static Header readHeader(Path file) throws IOException {
        requireSize(file);
        try (JsonReader reader = new JsonReader(Files.newBufferedReader(file, StandardCharsets.UTF_8))) {
            return header(reader, file);
        } catch (RuntimeException exception) {
            throw new IOException("Invalid pack catalog " + file + ": " + exception.getMessage(), exception);
        }
    }

    private void writeTo(JsonWriter writer) throws IOException {
        writer.beginObject();
        writer.name("format").value(FORMAT_VERSION);
        writer.name("inventoryId").value(inventoryId);
        writer.name("language").value(language);
        writer.name("mods").beginArray();
        for (Mod mod : mods) {
            writer.beginObject();
            writer.name("id").value(mod.id());
            optional(writer, "name", mod.name());
            optional(writer, "version", mod.version());
            optional(writer, "description", mod.description());
            if (!mod.authors().isEmpty()) {
                writer.name("authors").beginArray();
                for (String author : mod.authors()) writer.value(author);
                writer.endArray();
            }
            optional(writer, "license", mod.license());
            if (!mod.urls().isEmpty()) {
                writer.name("urls").beginObject();
                for (var url : new TreeMap<>(mod.urls()).entrySet()) writer.name(url.getKey()).value(url.getValue());
                writer.endObject();
            }
            optional(writer, "logo", mod.logo());
            writer.name("module").value(mod.module());
            writer.name("file").value(mod.file().toASCIIString());
            if (!mod.dependencies().isEmpty()) {
                writer.name("dependencies").beginArray();
                for (Dependency dependency : mod.dependencies()) {
                    writer.beginObject();
                    writer.name("id").value(dependency.modId());
                    writer.name("type").value(dependency.type().name());
                    optional(writer, "versionRange", dependency.versionRange());
                    writer.name("side").value(dependency.side().name());
                    writer.endObject();
                }
                writer.endArray();
            }
            if (!mod.configs().isEmpty()) {
                writer.name("configs").beginArray();
                for (ConfigFile config : mod.configs()) {
                    writer.beginObject();
                    writer.name("fileName").value(config.fileName());
                    writer.name("type").value(config.type().name());
                    if (config.path() != null) writer.name("path").value(config.path().toUri().toASCIIString());
                    writer.endObject();
                }
                writer.endArray();
            }
            writer.endObject();
        }
        writer.endArray();
        writer.name("blocks").beginArray();
        for (BlockEntry block : blocks) {
            writer.beginObject();
            writer.name("id").value(block.id());
            optional(writer, "name", block.name());
            optional(writer, "class", block.className());
            optional(writer, "item", block.item());
            optional(writer, "blockEntityType", block.blockEntityType());
            writer.endObject();
        }
        writer.endArray();
        writer.name("items").beginArray();
        for (ItemEntry item : items) {
            writer.beginObject();
            writer.name("id").value(item.id());
            optional(writer, "name", item.name());
            optional(writer, "class", item.className());
            optional(writer, "block", item.block());
            optional(writer, "model", item.model());
            if (!item.tints().isEmpty()) {
                writer.name("tints").beginObject();
                for (var tint : new TreeMap<>(item.tints()).entrySet()) {
                    writer.name(Integer.toString(tint.getKey())).value(tint.getValue());
                }
                writer.endObject();
            }
            writer.endObject();
        }
        writer.endArray();
        writer.name("entityTypes").beginArray();
        for (EntityTypeEntry entityType : entityTypes) {
            writer.beginObject();
            writer.name("id").value(entityType.id());
            optional(writer, "name", entityType.name());
            optional(writer, "category", entityType.category());
            optional(writer, "spawnEgg", entityType.spawnEgg());
            writer.endObject();
        }
        writer.endArray();
        writer.endObject();
    }

    private static Header header(JsonReader reader, Path file) throws IOException {
        reader.beginObject();
        int format = reader.nextName().equals("format") ? reader.nextInt() : -1;
        if (format != FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported pack catalog format " + format + "; required "
                    + FORMAT_VERSION + ". Use matching TotalDebug and Companion builds.");
        }
        String inventoryId = reader.nextName().equals("inventoryId") ? reader.nextString() : "";
        String language = reader.nextName().equals("language") ? reader.nextString() : "";
        requireText(inventoryId, "inventory id");
        requireText(language, "language");
        return new Header(format, inventoryId, language);
    }

    private interface EntryReader<T> {
        T read(JsonObject json);
    }

    private static <T> List<T> entries(JsonReader reader, EntryReader<T> entryReader) throws IOException {
        List<T> entries = new ArrayList<>();
        reader.beginArray();
        while (reader.hasNext()) {
            JsonElement value = JsonParser.parseReader(reader);
            if (!value.isJsonObject()) {
                throw new IllegalArgumentException("Expected an object entry");
            }
            entries.add(entryReader.read(value.getAsJsonObject()));
        }
        reader.endArray();
        return entries;
    }

    private static Mod mod(JsonObject json) {
        List<String> authors = new ArrayList<>();
        if (json.has("authors")) {
            for (JsonElement author : JsonFiles.array(json, "authors")) authors.add(author.getAsString());
        }
        Map<String, String> urls = new LinkedHashMap<>();
        if (json.has("urls")) {
            for (var url : JsonFiles.object(json, "urls").entrySet()) urls.put(url.getKey(), url.getValue().getAsString());
        }
        List<Dependency> dependencies = new ArrayList<>();
        if (json.has("dependencies")) {
            for (JsonElement value : JsonFiles.array(json, "dependencies")) {
                JsonObject dependency = value.getAsJsonObject();
                dependencies.add(new Dependency(JsonFiles.string(dependency, "id"),
                        DependencyType.valueOf(JsonFiles.string(dependency, "type")),
                        optional(dependency, "versionRange"),
                        Side.valueOf(JsonFiles.string(dependency, "side"))));
            }
        }
        List<ConfigFile> configs = new ArrayList<>();
        if (json.has("configs")) {
            for (JsonElement value : JsonFiles.array(json, "configs")) {
                JsonObject config = value.getAsJsonObject();
                String path = optional(config, "path");
                configs.add(new ConfigFile(JsonFiles.string(config, "fileName"),
                        ConfigType.valueOf(JsonFiles.string(config, "type")),
                        path.isEmpty() ? null : fileUri(path)));
            }
        }
        return new Mod(JsonFiles.string(json, "id"), optional(json, "name"), optional(json, "version"),
                optional(json, "description"), authors, optional(json, "license"), urls, optional(json, "logo"),
                JsonFiles.string(json, "module"), URI.create(JsonFiles.string(json, "file")), dependencies, configs);
    }

    private static BlockEntry block(JsonObject json) {
        return new BlockEntry(JsonFiles.string(json, "id"), optional(json, "name"), optional(json, "class"),
                optional(json, "item"), optional(json, "blockEntityType"));
    }

    private static ItemEntry item(JsonObject json) {
        Map<Integer, Integer> tints = new LinkedHashMap<>();
        if (json.has("tints")) {
            for (var tint : JsonFiles.object(json, "tints").entrySet()) {
                tints.put(Integer.parseInt(tint.getKey()), tint.getValue().getAsInt());
            }
        }
        return new ItemEntry(JsonFiles.string(json, "id"), optional(json, "name"), optional(json, "class"),
                optional(json, "block"), optional(json, "model"), tints);
    }

    private static EntityTypeEntry entityType(JsonObject json) {
        return new EntityTypeEntry(JsonFiles.string(json, "id"), optional(json, "name"),
                optional(json, "category"), optional(json, "spawnEgg"));
    }

    private static Path fileUri(String value) {
        URI uri = URI.create(value);
        if (!"file".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Config path is not a file URI: " + uri);
        }
        return Path.of(uri);
    }

    private static void optional(JsonWriter writer, String name, String value) throws IOException {
        if (!value.isEmpty()) writer.name(name).value(value);
    }

    private static String optional(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (value == null || value.isJsonNull()) return "";
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Expected string: " + key);
        }
        return value.getAsString();
    }

    private static void requireSize(Path file) throws IOException {
        long size = Files.size(file);
        if (size > MAX_FILE_BYTES) {
            throw new IOException("Pack catalog " + file + " has " + size + " bytes; the limit is " + MAX_FILE_BYTES);
        }
    }

    private static void unique(List<String> ids, String kind) {
        Set<String> seen = new HashSet<>();
        for (String id : ids) {
            if (!seen.add(id)) throw new IllegalArgumentException("Duplicate " + kind + " id " + id);
        }
    }

    private static String optionalId(String value, String name) {
        String id = text(value);
        if (!id.isEmpty()) requireMatch(REGISTRY_ID, id, name);
        return id;
    }

    private static void requireMatch(Pattern pattern, String value, String name) {
        requireText(value, name);
        if (!pattern.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid " + name + ": " + value);
        }
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static void requireText(String value, String name) {
        if (Objects.requireNonNull(value, name).isBlank()) {
            throw new IllegalArgumentException("Blank " + name);
        }
    }
}
