package com.github.minecraft_ta.totalDebugCompanion.pack;

import com.github.minecraft_ta.totalDebugCompanion.catalog.CatalogIndex;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ModResources;
import com.github.minecraft_ta.totaldebug.protocol.message.PackStackPayload;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Every resource of the pack joined as the game uses it: for each pack path, the copy of the highest pack that supplies
 * it, and the lower packs it hides. Assets follow the resource pack stack and data the datapack stack, lowest first.
 * With a game connected both come from the stacks it named; without one, assets follow {@code options.txt} and data
 * comes from Minecraft and the mods alone, since datapacks belong to a world. Mods stack as NeoForge stacks them: one
 * pack per mod file, in the order the game loaded them, which the catalog keeps. A pack Companion cannot read, such as
 * one a mod builds in memory or a mod inside another mod's file, adds nothing.
 */
public final class PackResources {
    private static final String VANILLA = "vanilla";
    private static final String MOD_RESOURCES = "mod_resources";

    /** A pack as the game stacks it: its id, the title shown, and the files it reads from, none when Companion cannot read them. */
    public record Source(String id, String title, List<Path> files) {
        public Source {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(title, "title");
            files = List.copyOf(files);
        }
    }

    /**
     * The joined resources, by pack path: each path's winning copy, the title of the pack it comes from, and the titles
     * of the lower packs that supply it too, the nearest first.
     */
    public record Joined(List<ModResources.Resource> resources, Map<String, String> from, Map<String, List<String>> hidden) {
        public Joined {
            resources = List.copyOf(resources);
            from = Map.copyOf(from);
            hidden = Map.copyOf(hidden);
        }
    }

    private PackResources() {
    }

    /** The packs assets come from, lowest first. {@code stack} is what the running game named, or null. Blocking. */
    public static List<Source> assets(PackStackPayload stack, CatalogIndex index, Path workspace) throws IOException {
        if (stack != null) return stack.resourcePacks().stream().map(pack -> source(pack, index)).toList();
        List<String> enabled = new ArrayList<>(enabledInOptions(workspace.resolve("options.txt")));
        // Both are required: the game adds vanilla at the bottom and the mods' resources at the top when options.txt
        // does not list them.
        if (!enabled.contains(VANILLA)) enabled.addFirst(VANILLA);
        if (!enabled.contains(MOD_RESOURCES)) enabled.add(MOD_RESOURCES);
        List<Source> sources = new ArrayList<>();
        for (String id : enabled) {
            switch (id) {
                case VANILLA -> sources.add(vanilla(index));
                case MOD_RESOURCES -> sources.addAll(mods(index));
                default -> {
                    Path file = id.startsWith("file/") ? workspace.resolve("resourcepacks").resolve(id.substring("file/".length())) : null;
                    sources.add(new Source(id, title(id), file != null && Files.exists(file) ? List.of(file) : List.of()));
                }
            }
        }
        return sources;
    }

    /** The packs data comes from, lowest first. {@code stack} is what the running game named, or null. */
    public static List<Source> data(PackStackPayload stack, CatalogIndex index) {
        if (stack != null && !stack.dataPacks().isEmpty()) return stack.dataPacks().stream().map(pack -> source(pack, index)).toList();
        List<Source> sources = new ArrayList<>();
        sources.add(vanilla(index));
        sources.addAll(mods(index));
        return sources;
    }

    /** Joins {@code assets} and {@code data}, each lowest first, into the copies the game uses. Blocking. */
    public static Joined join(List<Source> assets, List<Source> data) throws IOException {
        Map<String, ModResources.Resource> winners = new LinkedHashMap<>();
        Map<String, String> from = new HashMap<>();
        Map<String, List<String>> hidden = new HashMap<>();
        stack(assets, "assets/", winners, from, hidden);
        stack(data, "data/", winners, from, hidden);
        List<ModResources.Resource> resources = new ArrayList<>(winners.values());
        resources.sort(Comparator.comparing(ModResources.Resource::path));
        return new Joined(resources, from, hidden);
    }

    private static void stack(List<Source> sources, String root, Map<String, ModResources.Resource> winners,
                              Map<String, String> from, Map<String, List<String>> hidden) throws IOException {
        for (Source source : sources) {
            for (ModResources.Resource resource : ModResources.list(source.files())) {
                if (!resource.path().startsWith(root)) continue;
                String lower = from.put(resource.path(), source.title());
                if (lower != null && !lower.equals(source.title())) {
                    hidden.computeIfAbsent(resource.path(), ignored -> new ArrayList<>()).addFirst(lower);
                }
                winners.put(resource.path(), resource);
            }
        }
    }

    /**
     * A pack the running game named. Minecraft's and the mods' are read from their files in the catalog; a mod file's
     * pack is named by all the mods in it, as {@code mod/a,b}.
     */
    private static Source source(PackStackPayload.Pack pack, CatalogIndex index) {
        if (pack.id().equals(VANILLA)) return vanilla(index);
        if (pack.id().startsWith("mod/")) {
            List<String> ids = List.of(pack.id().substring("mod/".length()).split(","));
            List<Path> files = ids.stream().map(index::resourceFiles).filter(found -> !found.isEmpty()).findFirst().orElse(List.of());
            List<String> titles = ids.stream().map(id -> index.mod(id).map(PackCatalog.Mod::title).orElse(id)).toList();
            return new Source(pack.id(), String.join(", ", titles), files);
        }
        return new Source(pack.id(), pack.title(), pack.source().isEmpty() ? List.of() : List.of(Path.of(pack.source())));
    }

    private static Source vanilla(CatalogIndex index) {
        return new Source(VANILLA, "Minecraft", index.resourceFiles("minecraft"));
    }

    /** The mods' own packs as NeoForge makes them: one per mod file, in the order the game loaded the mods. */
    private static List<Source> mods(CatalogIndex index) {
        Map<List<Path>, List<PackCatalog.Mod>> byFile = new LinkedHashMap<>();
        for (PackCatalog.Mod mod : index.catalog().mods()) {
            if (mod.id().equals("minecraft")) continue;
            List<Path> files = index.resourceFiles(mod.id());
            // A mod inside another mod's file has no file Companion can read.
            if (!files.isEmpty()) byFile.computeIfAbsent(files, ignored -> new ArrayList<>()).add(mod);
        }
        List<Source> sources = new ArrayList<>();
        byFile.forEach((files, mods) -> sources.add(new Source(
                "mod/" + String.join(",", mods.stream().map(PackCatalog.Mod::id).toList()),
                String.join(", ", mods.stream().map(PackCatalog.Mod::title).toList()), files)));
        return sources;
    }

    /** The resource packs {@code options.txt} enables, lowest first; the game's defaults when it lists none. */
    static List<String> enabledInOptions(Path options) throws IOException {
        if (!Files.isRegularFile(options)) return List.of(VANILLA, MOD_RESOURCES);
        for (String line : Files.readAllLines(options, StandardCharsets.UTF_8)) {
            if (!line.startsWith("resourcePacks:")) continue;
            List<String> ids = new ArrayList<>();
            try {
                for (JsonElement id : JsonParser.parseString(line.substring("resourcePacks:".length())).getAsJsonArray()) {
                    ids.add(id.getAsString());
                }
            } catch (RuntimeException invalid) {
                throw new IOException("options.txt lists its resource packs in a form that cannot be read: " + invalid.getMessage(), invalid);
            }
            return ids;
        }
        return List.of(VANILLA, MOD_RESOURCES);
    }

    /** A title for a pack the running game has not named: its folder or file name. */
    static String title(String id) {
        String name = id.startsWith("file/") ? id.substring("file/".length()) : id;
        return name.toLowerCase(Locale.ROOT).endsWith(".zip") ? name.substring(0, name.length() - 4) : name;
    }
}
