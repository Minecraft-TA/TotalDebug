package com.github.minecraft_ta.totalDebugCompanion.pack;

import com.github.minecraft_ta.totalDebugCompanion.catalog.CatalogIndex;
import com.github.minecraft_ta.totalDebugCompanion.catalog.CurrentWorld;
import com.github.minecraft_ta.totalDebugCompanion.game.GameLocation;
import com.github.minecraft_ta.totalDebugCompanion.game.GameState;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ListedPack;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ModResources;
import com.github.minecraft_ta.totalDebugCompanion.catalog.PackFolders;
import com.github.minecraft_ta.totaldebug.protocol.message.PackStackPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.PlayingPayload;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Every resource of the pack joined as the game uses it: for each pack path, the copy of the highest pack that supplies
 * it, and the lower packs it hides. Assets follow the resource pack stack and data the datapack stack, lowest first.
 * With a game connected both come from the stacks it named; without one, assets follow {@code options.txt} and data
 * comes from Minecraft and the mods alone, since datapacks belong to a world. Mods stack as NeoForge stacks them: one
 * pack per mod file, in the order the game loaded them, which the catalog keeps. A pack Companion cannot read, such as
 * one a mod builds in memory or a mod inside another mod's file, adds nothing.
 */
public final class PackResources {
    static final String VANILLA = "vanilla";
    static final String MOD_RESOURCES = "mod_resources";
    private static final String MOD_DATA = "mod_data";

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

    /** The packs assets come from, lowest first. {@code resourcePacks} are what the running game named, or null. Blocking. */
    public static List<Source> assets(PackStackPayload resourcePacks, CatalogIndex index, Path workspace) throws IOException {
        if (resourcePacks != null) return resourcePacks.enabled().stream().map(pack -> source(pack, index)).toList();
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
                    if (id.startsWith("mod/")) {
                        // A mod that shows its resources as a pack of its own: its file, as when the game names it.
                        sources.add(source(new PackStackPayload.Pack(id, title(id), ""), index));
                    } else {
                        Path file = id.startsWith("file/") ? workspace.resolve("resourcepacks").resolve(id.substring("file/".length())) : null;
                        sources.add(new Source(id, title(id), file != null && Files.exists(file) ? List.of(file) : List.of()));
                    }
                }
            }
        }
        return sources;
    }

    /**
     * The packs data comes from, lowest first. {@code datapacks} are what the server of the world the game plays named,
     * or null. A game at its menu or on a server has no data of its own loaded, so none are listed then. Without a game,
     * and while the server of the singleplayer world the game plays has not named them yet, the current world's
     * {@code level.dat} names them; a pack the game builds in memory, such as one a mod generates, adds nothing Companion
     * can read. Blocking.
     */
    public static List<Source> data(PackStackPayload datapacks, CatalogIndex index, GameLocation location) throws IOException {
        GameState game = location.read();
        // A server's datapacks are on the server, which names them by its own files.
        if (game.game() instanceof GameState.Game.Connected connected
                && (connected.playing() instanceof PlayingPayload.Menu || connected.playing() instanceof PlayingPayload.Multiplayer)) {
            return List.of();
        }
        if (datapacks != null) return datapacks.enabled().stream().map(pack -> source(pack, index)).toList();
        // The game enables a new pack of the world's folder above the others when it loads the world.
        List<ListedPack> listed = worldDatapacks(game);
        List<ListedPack> enabled = new ArrayList<>(listed.stream().filter(pack -> pack.state() == ListedPack.State.ENABLED).toList().reversed());
        enabled.addAll(listed.stream().filter(pack -> pack.state() == ListedPack.State.NEW).toList());
        List<Source> sources = new ArrayList<>();
        for (ListedPack pack : enabled) {
            switch (pack.id()) {
                case VANILLA -> sources.add(vanilla(index));
                case MOD_DATA -> sources.addAll(mods(index));
                default -> {
                    if (pack.file() != null) sources.add(new Source(pack.id(), PackFolders.title(pack.file()), List.of(pack.file())));
                }
            }
        }
        // The game adds a required pack the world does not list where it goes by default: Minecraft's at the bottom, the
        // mods' data at the top.
        List<String> ids = enabled.stream().map(ListedPack::id).toList();
        if (!ids.contains(VANILLA)) sources.addFirst(vanilla(index));
        if (!ids.contains(MOD_DATA)) sources.addAll(mods(index));
        return sources;
    }

    /**
     * The current world's datapacks, or none without a world. A {@code level.dat} that cannot be read fails, rather than
     * leaving the world's packs out of what the game uses. Blocking.
     */
    private static List<ListedPack> worldDatapacks(GameState game) throws IOException {
        Optional<Path> world = CurrentWorld.directory(game);
        if (world.isEmpty()) return List.of();
        try {
            return CurrentWorld.read(game, world.get()).datapacks();
        } catch (RuntimeException malformed) {
            throw new IOException("The level.dat of " + world.get().getFileName() + " is malformed: " + malformed.getMessage(), malformed);
        }
    }

    /**
     * The resource packs as the game's pack screen lists them: enabled ones with the highest first, then those in
     * {@code resourcepacks/} that are not. With a game connected they follow the stack it named, where each mod file is
     * a pack of its own, shown together as the mods' resources as the game shows them; without one, {@code options.txt}.
     * Blocking.
     */
    public static List<ListedPack> resourcePacks(PackStackPayload stack, Path workspace) throws IOException {
        Map<String, Path> files = PackFolders.list(workspace.resolve("resourcepacks"));
        // The enabled packs, lowest first, with the titles the running game gives them and what it allows for each.
        LinkedHashMap<String, PackStackPayload.Pack> enabled = new LinkedHashMap<>();
        List<ListedPack> packs = new ArrayList<>();
        if (stack == null) {
            // The game drops a pack of the folder that is gone when it starts, and adds the required ones as assets() does.
            List<String> ids = enabledInOptions(workspace.resolve("options.txt"));
            if (!ids.contains(VANILLA)) enabled.put(VANILLA, required(VANILLA));
            for (String id : ids) {
                if (id.equals(VANILLA) || id.equals(MOD_RESOURCES)) enabled.put(id, required(id));
                else if (!id.startsWith("file/") || files.containsKey(id)) enabled.put(id, new PackStackPayload.Pack(id, "", ""));
            }
            if (!ids.contains(MOD_RESOURCES)) enabled.put(MOD_RESOURCES, required(MOD_RESOURCES));
        } else {
            for (PackStackPayload.Pack pack : stack.enabled()) {
                // The mods whose resources are not shown on their own are parts of the mods' pack, listed as that one pack.
                if (!pack.is(PackStackPayload.HIDDEN)) enabled.putIfAbsent(pack.id(), pack);
            }
        }
        for (PackStackPayload.Pack pack : enabled.sequencedValues().reversed()) {
            packs.add(new ListedPack(pack.id(), ListedPack.State.ENABLED, files.remove(pack.id()), pack.title(), rules(pack)));
        }
        if (stack != null) {
            for (PackStackPayload.Pack pack : stack.others()) {
                packs.add(new ListedPack(pack.id(), ListedPack.State.DISABLED, files.remove(pack.id()), pack.title(), rules(pack)));
            }
        }
        files.forEach((id, file) -> packs.add(new ListedPack(id, ListedPack.State.DISABLED, file)));
        return packs;
    }

    /**
     * The current world's datapacks as the game's pack screen lists them. While the connected game has the world open,
     * as its server names them; otherwise as its {@code level.dat} saved them.
     */
    public static List<ListedPack> worldDatapacks(PackStackPayload datapacks, CurrentWorld.Saved saved) throws IOException {
        if (datapacks == null || !saved.open() || datapacks.enabled().isEmpty()) return saved.datapacks();
        Map<String, Path> files = PackFolders.list(saved.directory().resolve("datapacks"));
        List<ListedPack> packs = new ArrayList<>();
        for (PackStackPayload.Pack pack : datapacks.enabled().reversed()) {
            if (pack.is(PackStackPayload.HIDDEN)) continue;
            packs.add(new ListedPack(pack.id(), ListedPack.State.ENABLED, files.remove(pack.id()), pack.title(), rules(pack)));
        }
        for (PackStackPayload.Pack pack : datapacks.others()) {
            packs.add(new ListedPack(pack.id(), ListedPack.State.DISABLED, files.remove(pack.id()), pack.title(), rules(pack)));
        }
        files.forEach((id, file) -> packs.add(new ListedPack(id, ListedPack.State.DISABLED, file)));
        return packs;
    }

    private static PackStackPayload.Pack required(String id) {
        return new PackStackPayload.Pack(id, "", "", PackStackPayload.REQUIRED);
    }

    /** What the game allows for a pack it named. */
    private static Set<ListedPack.Rule> rules(PackStackPayload.Pack pack) {
        Set<ListedPack.Rule> rules = EnumSet.noneOf(ListedPack.Rule.class);
        if (pack.is(PackStackPayload.REQUIRED)) rules.add(ListedPack.Rule.REQUIRED);
        if (pack.is(PackStackPayload.FIXED)) rules.add(ListedPack.Rule.FIXED);
        if (pack.is(PackStackPayload.INCOMPATIBLE)) rules.add(ListedPack.Rule.INCOMPATIBLE);
        if (pack.is(PackStackPayload.MISSING_FEATURES)) rules.add(ListedPack.Rule.MISSING_FEATURES);
        return rules;
    }

    /** Joins {@code assets} and {@code data}, each lowest first, into the copies the game uses. Blocking. */
    public static Joined join(List<Source> assets, List<Source> data) {
        Map<String, ModResources.Resource> winners = new LinkedHashMap<>();
        Map<String, String> from = new HashMap<>();
        Map<String, List<String>> hidden = new HashMap<>();
        Map<String, String> sources = new HashMap<>();
        stack(assets, "assets/", winners, from, hidden, sources);
        stack(data, "data/", winners, from, hidden, sources);
        List<ModResources.Resource> resources = new ArrayList<>(winners.values());
        resources.sort(Comparator.comparing(ModResources.Resource::path));
        return new Joined(resources, from, hidden);
    }

    /** Stacks {@code sources} over {@code winners}; {@code ids} holds the id of each path's source, as two can share a title. */
    private static void stack(List<Source> sources, String root, Map<String, ModResources.Resource> winners,
                              Map<String, String> from, Map<String, List<String>> hidden, Map<String, String> ids) {
        for (Source source : sources) {
            List<ModResources.Resource> listed;
            try {
                listed = ModResources.list(source.files());
            } catch (IOException unreadable) {
                // A file that cannot be read, such as a removed or broken archive, adds nothing; the rest still count.
                continue;
            }
            for (ModResources.Resource resource : listed) {
                if (!resource.path().startsWith(root)) continue;
                String lower = from.put(resource.path(), source.title());
                String lowerId = ids.put(resource.path(), source.id());
                // Another file of the same pack, such as a second jar of one mod, hides nothing.
                if (lower != null && !lowerId.equals(source.id())) {
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
    public static List<String> enabledInOptions(Path options) throws IOException {
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

    /** A title for a pack the running game has not named: its folder or file name, as the game titles it. */
    static String title(String id) {
        return id.startsWith("file/") ? id.substring("file/".length()) : id;
    }
}
