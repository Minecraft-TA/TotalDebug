package com.github.minecraft_ta.totalDebugCompanion.pack;

import com.github.minecraft_ta.totalDebugCompanion.change.ChangeCategory;
import com.github.minecraft_ta.totalDebugCompanion.game.Access;
import com.github.minecraft_ta.totalDebugCompanion.game.GameState;
import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;
import com.github.minecraft_ta.totaldebug.storage.AtomicFiles;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The resource pack selection as a category of the change pipeline: the connected game client enables and orders the
 * packs and reloads its resources, and a closed game's {@code options.txt} is written. Its value is the enabled packs,
 * lowest first, as the game keeps them: with the packs it requires, which it adds where {@code options.txt} leaves them
 * out. The hidden parts of the mods' pack are in neither, while a mod's pack of its own, {@code mod/<id>}, is a pack like
 * any other.
 */
final class ResourcePackSelection implements ChangeCategory<ChangeRecord.PackSelection, List<String>> {
    /** The one target the game client's change table knows, the selection itself. */
    static final String GAME_TARGET = "resourcePacks";

    private final Path options;

    ResourcePackSelection(Path options) {
        this.options = options;
    }

    @Override
    public String id() {
        return "resourcePacks";
    }

    @Override
    public String name(ChangeRecord.PackSelection target) {
        return "The resource packs";
    }

    @Override
    public String gameTarget(ChangeRecord.PackSelection target) {
        return GAME_TARGET;
    }

    /** The game answers once its resources reloaded with the new selection. */
    @Override
    public Duration answerWait() {
        return Duration.ofMinutes(10);
    }

    @Override
    public String changedSince(ChangeRecord.PackSelection target) {
        return "The resource packs changed outside Companion since, and reverting would replace that";
    }

    @Override
    public Access access(GameState game, ChangeRecord.PackSelection target) {
        return game.client("change its resource packs");
    }

    @Override
    public String text(List<String> enabled) {
        return PackSelections.json(asTheGameKeepsIt(enabled));
    }

    @Override
    public Map<ChangeRecord.PackSelection, String> readFile(Collection<ChangeRecord.PackSelection> targets) throws IOException {
        String value = text(PackResources.enabledInOptions(this.options));
        Map<ChangeRecord.PackSelection, String> values = new HashMap<>();
        for (ChangeRecord.PackSelection target : targets) values.put(target, value);
        return values;
    }

    /**
     * {@code enabled} as the game keeps the selection: with Minecraft's pack at the bottom and the mods' resources at the
     * top where it leaves them out, as the game adds them when it reads {@code options.txt}.
     */
    static List<String> asTheGameKeepsIt(List<String> enabled) {
        List<String> ids = new ArrayList<>(enabled);
        if (!ids.contains(PackResources.VANILLA)) ids.addFirst(PackResources.VANILLA);
        if (!ids.contains(PackResources.MOD_RESOURCES)) ids.add(PackResources.MOD_RESOURCES);
        return ids;
    }

    /**
     * Writes the selection as {@code options.txt}'s resource packs. A pack enabled here is also listed as incompatible,
     * which the game drops again for a compatible pack, so it keeps a pack made for another version instead of removing it.
     */
    @Override
    public void writeFile(List<Write<ChangeRecord.PackSelection, List<String>>> writes, Consumer<ChangeRecord.PackSelection> landed)
            throws IOException {
        for (Write<ChangeRecord.PackSelection, List<String>> write : writes) {
            List<String> previous = PackResources.enabledInOptions(this.options);
            List<String> enabled = write.value();
            List<String> lines = Files.isRegularFile(this.options)
                    ? new ArrayList<>(Files.readAllLines(this.options, StandardCharsets.UTF_8)) : new ArrayList<>();
            Set<String> incompatible = new LinkedHashSet<>(listed(lines, "incompatibleResourcePacks:"));
            incompatible.retainAll(enabled);
            for (String id : enabled) {
                if (!previous.contains(id) && (id.startsWith("file/") || id.startsWith("mod/"))) incompatible.add(id);
            }
            put(lines, "resourcePacks:", enabled);
            put(lines, "incompatibleResourcePacks:", List.copyOf(incompatible));
            AtomicFiles.writeString(this.options, String.join("\n", lines) + "\n");
            landed.accept(write.target());
        }
    }

    /** The ids of an {@code options.txt} line such as {@code resourcePacks:["vanilla"]}, or none without it. */
    private static List<String> listed(List<String> lines, String prefix) throws IOException {
        for (String line : lines) {
            if (!line.startsWith(prefix)) continue;
            try {
                List<String> ids = new ArrayList<>();
                for (JsonElement id : JsonParser.parseString(line.substring(prefix.length())).getAsJsonArray()) ids.add(id.getAsString());
                return ids;
            } catch (RuntimeException invalid) {
                throw new IOException("options.txt lists " + prefix + " in a form that cannot be read: " + invalid.getMessage(), invalid);
            }
        }
        return List.of();
    }

    private static void put(List<String> lines, String prefix, List<String> ids) {
        String line = prefix + PackSelections.json(ids);
        for (int index = 0; index < lines.size(); index++) {
            if (lines.get(index).startsWith(prefix)) {
                lines.set(index, line);
                return;
            }
        }
        lines.add(line);
    }
}
