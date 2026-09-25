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
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
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
        List<EntityTypeEntry> entityTypes,
        List<KeyBinding> keyBindings,
        List<KeyContext> keyContexts,
        Map<String, String> keyNames
) {
    public static final int FORMAT_VERSION = 3;
    public static final long MAX_FILE_BYTES = 64L * 1024 * 1024;

    private static final Pattern MOD_ID = Pattern.compile("[a-z][a-z0-9_]{1,63}");
    private static final Pattern REGISTRY_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");

    public enum ConfigType { COMMON, CLIENT, SERVER, STARTUP }
    /** What must restart before a changed setting takes effect. */
    public enum Restart { NONE, WORLD, GAME }
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

    /**
     * A mod's configuration file. {@code path} is null for a configuration that is not loaded, such as a server
     * configuration outside a world. {@code sections} and {@code settings} come from its specification and are empty
     * when the mod declares the file without a NeoForge specification.
     */
    public record ConfigFile(String fileName, ConfigType type, Path path, List<ConfigSection> sections,
                             List<ConfigSetting> settings) {
        public ConfigFile {
            requireText(fileName, "config file name");
            Objects.requireNonNull(type, "config type");
            sections = List.copyOf(sections);
            settings = List.copyOf(settings);
            unique(settings.stream().map(ConfigSetting::path).toList(), "setting");
        }
    }

    /** A group of settings, such as {@code general}, with the comment its specification gives it. */
    public record ConfigSection(String path, String comment) {
        public ConfigSection {
            requireText(path, "config section path");
            comment = text(comment);
        }
    }

    /**
     * One setting as its specification declares it. {@code path} is its dotted key in the file and
     * {@code defaultValue} its default in {@link #display(Object)} form. {@code range} is the accepted range, such as
     * {@code 1 ~ 64}, and {@code allowed} lists the accepted values of a choice; both are empty when unrestricted.
     */
    public record ConfigSetting(String path, String comment, String defaultValue, String range, List<String> allowed,
                                Restart restart) {
        public ConfigSetting {
            requireText(path, "config setting path");
            comment = text(comment);
            defaultValue = text(defaultValue);
            range = text(range);
            allowed = List.copyOf(allowed);
            Objects.requireNonNull(restart, "restart of " + path);
        }

        /** The last part of the path, such as {@code maxEnergy} for {@code machines.maxEnergy}. */
        public String name() {
            return this.path.substring(this.path.lastIndexOf('.') + 1);
        }

        /**
         * The text of a configuration value, the same for a captured default and a value read from the file. A list
         * reads as a TOML array, with its strings quoted.
         */
        public static String display(Object value) {
            if (value == null) return "";
            if (value instanceof Enum<?> constant) return constant.name();
            if (value instanceof Collection<?> values) {
                StringJoiner joined = new StringJoiner(", ", "[", "]");
                for (Object element : values) {
                    joined.add(element instanceof CharSequence || element instanceof Enum<?> ? quoted(display(element)) : display(element));
                }
                return joined.toString();
            }
            return String.valueOf(value);
        }

        private static String quoted(String text) {
            return '"' + text.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
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

    /**
     * A key binding as the game registered it. {@code name} is its key in {@code options.txt}, {@code modId} the mod
     * that registered it, or empty when no mod's registration added it. {@code defaultKey} is a Minecraft key name
     * such as {@code key.keyboard.g}, {@code defaultModifier} one of {@code NONE}, {@code SHIFT}, {@code CONTROL} and
     * {@code ALT}, and {@code context} the id of its {@link KeyContext}.
     */
    public record KeyBinding(String name, String displayName, String category, String categoryName, String modId,
                             String defaultKey, String defaultModifier, String context) {
        public KeyBinding {
            requireText(name, "key binding name");
            displayName = text(displayName);
            category = text(category);
            categoryName = text(categoryName);
            modId = text(modId);
            requireText(defaultKey, "default key of " + name);
            requireText(defaultModifier, "default modifier of " + name);
            requireText(context, "context of " + name);
        }
    }

    /**
     * Where a key binding is active, such as in the game or in screens. {@code conflicts} lists the contexts this one
     * says it conflicts with; two bindings on one key collide when either context names the other.
     */
    public record KeyContext(String id, String name, List<String> conflicts) {
        public KeyContext {
            requireText(id, "key context id");
            name = text(name);
            conflicts = List.copyOf(conflicts);
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
        keyBindings = List.copyOf(keyBindings);
        keyContexts = List.copyOf(keyContexts);
        keyNames = Map.copyOf(keyNames);
        unique(mods.stream().map(Mod::id).toList(), "mod");
        unique(blocks.stream().map(BlockEntry::id).toList(), "block");
        unique(items.stream().map(ItemEntry::id).toList(), "item");
        unique(entityTypes.stream().map(EntityTypeEntry::id).toList(), "entity type");
        unique(keyBindings.stream().map(KeyBinding::name).toList(), "key binding");
        unique(keyContexts.stream().map(KeyContext::id).toList(), "key context");
        Set<String> contexts = new HashSet<>(keyContexts.stream().map(KeyContext::id).toList());
        for (KeyBinding binding : keyBindings) {
            if (!contexts.contains(binding.context())) {
                throw new IllegalArgumentException("Key binding " + binding.name() + " has an unknown context " + binding.context());
            }
        }
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
            List<KeyBinding> keyBindings = List.of();
            List<KeyContext> keyContexts = List.of();
            Map<String, String> keyNames = Map.of();
            while (reader.hasNext()) {
                switch (reader.nextName()) {
                    case "mods" -> mods = entries(reader, PackCatalog::mod);
                    case "blocks" -> blocks = entries(reader, PackCatalog::block);
                    case "items" -> items = entries(reader, PackCatalog::item);
                    case "entityTypes" -> entityTypes = entries(reader, PackCatalog::entityType);
                    case "keyBindings" -> keyBindings = entries(reader, PackCatalog::keyBinding);
                    case "keyContexts" -> keyContexts = entries(reader, PackCatalog::keyContext);
                    case "keyNames" -> keyNames = strings(reader);
                    default -> reader.skipValue();
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new IllegalArgumentException("Trailing content");
            }
            return new PackCatalog(header.inventoryId(), header.language(), mods, blocks, items, entityTypes,
                    keyBindings, keyContexts, keyNames);
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
                    if (!config.sections().isEmpty()) {
                        writer.name("sections").beginArray();
                        for (ConfigSection section : config.sections()) {
                            writer.beginObject();
                            writer.name("path").value(section.path());
                            optional(writer, "comment", section.comment());
                            writer.endObject();
                        }
                        writer.endArray();
                    }
                    if (!config.settings().isEmpty()) {
                        writer.name("settings").beginArray();
                        for (ConfigSetting setting : config.settings()) writeSetting(writer, setting);
                        writer.endArray();
                    }
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
        writer.name("keyBindings").beginArray();
        for (KeyBinding binding : keyBindings) {
            writer.beginObject();
            writer.name("name").value(binding.name());
            optional(writer, "displayName", binding.displayName());
            optional(writer, "category", binding.category());
            optional(writer, "categoryName", binding.categoryName());
            optional(writer, "modId", binding.modId());
            writer.name("defaultKey").value(binding.defaultKey());
            writer.name("defaultModifier").value(binding.defaultModifier());
            writer.name("context").value(binding.context());
            writer.endObject();
        }
        writer.endArray();
        writer.name("keyContexts").beginArray();
        for (KeyContext context : keyContexts) {
            writer.beginObject();
            writer.name("id").value(context.id());
            optional(writer, "name", context.name());
            writer.name("conflicts").beginArray();
            for (String conflict : context.conflicts()) writer.value(conflict);
            writer.endArray();
            writer.endObject();
        }
        writer.endArray();
        writer.name("keyNames").beginObject();
        for (var name : new TreeMap<>(keyNames).entrySet()) writer.name(name.getKey()).value(name.getValue());
        writer.endObject();
        writer.endObject();
    }

    private static Map<String, String> strings(JsonReader reader) throws IOException {
        Map<String, String> strings = new LinkedHashMap<>();
        reader.beginObject();
        while (reader.hasNext()) strings.put(reader.nextName(), reader.nextString());
        reader.endObject();
        return strings;
    }

    private static KeyBinding keyBinding(JsonObject json) {
        return new KeyBinding(JsonFiles.string(json, "name"), optional(json, "displayName"), optional(json, "category"),
                optional(json, "categoryName"), optional(json, "modId"), JsonFiles.string(json, "defaultKey"),
                JsonFiles.string(json, "defaultModifier"), JsonFiles.string(json, "context"));
    }

    private static KeyContext keyContext(JsonObject json) {
        List<String> conflicts = new ArrayList<>();
        for (JsonElement conflict : JsonFiles.array(json, "conflicts")) conflicts.add(conflict.getAsString());
        return new KeyContext(JsonFiles.string(json, "id"), optional(json, "name"), conflicts);
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
                List<ConfigSection> sections = new ArrayList<>();
                if (config.has("sections")) {
                    for (JsonElement section : JsonFiles.array(config, "sections")) {
                        JsonObject entry = section.getAsJsonObject();
                        sections.add(new ConfigSection(JsonFiles.string(entry, "path"), optional(entry, "comment")));
                    }
                }
                List<ConfigSetting> settings = new ArrayList<>();
                if (config.has("settings")) {
                    for (JsonElement setting : JsonFiles.array(config, "settings")) settings.add(setting(setting.getAsJsonObject()));
                }
                configs.add(new ConfigFile(JsonFiles.string(config, "fileName"),
                        ConfigType.valueOf(JsonFiles.string(config, "type")),
                        path.isEmpty() ? null : fileUri(path), sections, settings));
            }
        }
        return new Mod(JsonFiles.string(json, "id"), optional(json, "name"), optional(json, "version"),
                optional(json, "description"), authors, optional(json, "license"), urls, optional(json, "logo"),
                JsonFiles.string(json, "module"), URI.create(JsonFiles.string(json, "file")), dependencies, configs);
    }

    private static void writeSetting(JsonWriter writer, ConfigSetting setting) throws IOException {
        writer.beginObject();
        writer.name("path").value(setting.path());
        optional(writer, "comment", setting.comment());
        writer.name("default").value(setting.defaultValue());
        optional(writer, "range", setting.range());
        if (!setting.allowed().isEmpty()) {
            writer.name("allowed").beginArray();
            for (String value : setting.allowed()) writer.value(value);
            writer.endArray();
        }
        if (setting.restart() != Restart.NONE) writer.name("restart").value(setting.restart().name());
        writer.endObject();
    }

    private static ConfigSetting setting(JsonObject json) {
        List<String> allowed = new ArrayList<>();
        if (json.has("allowed")) {
            for (JsonElement value : JsonFiles.array(json, "allowed")) allowed.add(value.getAsString());
        }
        String restart = optional(json, "restart");
        return new ConfigSetting(JsonFiles.string(json, "path"), optional(json, "comment"), optional(json, "default"),
                optional(json, "range"), allowed, restart.isEmpty() ? Restart.NONE : Restart.valueOf(restart));
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
