package com.github.minecraft_ta.totalDebugCompanion.pack;

import com.github.minecraft_ta.totalDebugCompanion.catalog.LevelDat;
import com.github.minecraft_ta.totalDebugCompanion.catalog.PackFolders;
import com.github.minecraft_ta.totalDebugCompanion.change.ChangeCategory;
import com.github.minecraft_ta.totalDebugCompanion.game.Access;
import com.github.minecraft_ta.totalDebugCompanion.game.GameState;
import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;
import com.github.minecraft_ta.totaldebug.protocol.nbt.NbtData;

import java.io.IOException;
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
 * A world's datapack selection as a category of the change pipeline: the server of the world the game plays enables and
 * orders the datapacks and reloads its data, reached through the relay, and a closed world's {@code level.dat} is
 * written. Its value is the enabled packs, lowest first, as {@code level.dat} and the server keep them: without the hidden
 * parts of the mods' pack, while a mod's own datapack is a pack like any other.
 */
final class DatapackSelection implements ChangeCategory<ChangeRecord.PackSelection, List<String>> {
    /** The one target the server's change table knows, the selection of the world it runs. */
    static final String GAME_TARGET = "datapacks";


    @Override
    public String id() {
        return "datapacks";
    }

    @Override
    public String name(ChangeRecord.PackSelection target) {
        return "The datapacks of " + GameState.worldName(target.location());
    }

    @Override
    public String gameTarget(ChangeRecord.PackSelection target) {
        return GAME_TARGET;
    }

    @Override
    public Path world(ChangeRecord.PackSelection target) {
        return target.location();
    }

    /** The server answers once its data reloaded with the new selection. */
    @Override
    public Duration answerWait() {
        return Duration.ofMinutes(10);
    }

    @Override
    public String changedSince(ChangeRecord.PackSelection target) {
        return name(target) + " changed outside Companion since, and reverting would replace that";
    }

    /** As the world's state says; the server decides whether the player may change its world, and says why not. */
    @Override
    public Access access(GameState game, ChangeRecord.PackSelection target) {
        return game.world(target.location(), "change its datapacks");
    }

    @Override
    public String text(List<String> enabled) {
        return PackSelections.json(enabled);
    }

    @Override
    public Map<ChangeRecord.PackSelection, String> readFile(Collection<ChangeRecord.PackSelection> targets) throws IOException {
        Map<ChangeRecord.PackSelection, String> values = new HashMap<>();
        for (ChangeRecord.PackSelection target : targets) values.put(target, text(saved(target.location())));
        return values;
    }

    /**
     * Writes the selection into the world's {@code level.dat}, with every other pack of the world disabled, so the game
     * does not enable it again as a new one.
     */
    @Override
    public void writeFile(List<Write<ChangeRecord.PackSelection, List<String>>> writes, Consumer<ChangeRecord.PackSelection> landed)
            throws IOException {
        for (Write<ChangeRecord.PackSelection, List<String>> write : writes) {
            Path world = write.target().location();
            List<String> enabled = write.value();
            LevelDat.update(world, root -> {
                if (!(root.tag().entries().get("Data") instanceof NbtData.CompoundTag data)) {
                    throw new IOException("The level.dat of " + world.getFileName() + " holds no world data");
                }
                NbtData.CompoundTag packs = dataPacks(root.tag());
                Set<String> disabled = new LinkedHashSet<>(strings(packs, "Enabled"));
                disabled.addAll(strings(packs, "Disabled"));
                disabled.addAll(PackFolders.list(world.resolve("datapacks")).keySet());
                enabled.forEach(disabled::remove);
                NbtData.CompoundTag written = LevelDat.with(LevelDat.with(packs == null ? new NbtData.CompoundTag(Map.of()) : packs,
                        "Enabled", list(enabled)), "Disabled", list(List.copyOf(disabled)));
                return new LevelDat.Root(root.name(), LevelDat.with(root.tag(), "Data", LevelDat.with(data, "DataPacks", written)));
            });
            landed.accept(write.target());
        }
    }

    /**
     * Enables {@code id} at the top of {@code world}'s datapacks where its {@code level.dat} disables it or enables it
     * lower, as the data reload of a live save does, and tells whether it changed them; a pack named in neither list the
     * game enables itself on top when it loads the world. Blocking.
     */
    static boolean enableOnTop(Path world, String id) throws IOException {
        NbtData.CompoundTag named = dataPacks(LevelDat.read(LevelDat.file(world)).tag());
        List<String> enabledNow = strings(named, "Enabled");
        boolean disabledNow = strings(named, "Disabled").contains(id);
        if (!disabledNow && (!enabledNow.contains(id) || enabledNow.getLast().equals(id))) return false;
        LevelDat.update(world, root -> {
            if (!(root.tag().entries().get("Data") instanceof NbtData.CompoundTag data)) {
                throw new IOException("The level.dat of " + world.getFileName() + " holds no world data");
            }
            NbtData.CompoundTag packs = dataPacks(root.tag());
            List<String> enabled = new ArrayList<>(strings(packs, "Enabled"));
            List<String> disabled = new ArrayList<>(strings(packs, "Disabled"));
            enabled.remove(id);
            enabled.add(id);
            disabled.remove(id);
            NbtData.CompoundTag written = LevelDat.with(LevelDat.with(packs, "Enabled", list(enabled)), "Disabled", list(disabled));
            return new LevelDat.Root(root.name(), LevelDat.with(root.tag(), "Data", LevelDat.with(data, "DataPacks", written)));
        });
        return true;
    }

    /** Whether {@code world}'s {@code level.dat} lists {@code id} as disabled, which the player did. Blocking. */
    static boolean disables(Path world, String id) throws IOException {
        return strings(dataPacks(LevelDat.read(LevelDat.file(world)).tag()), "Disabled").contains(id);
    }

    /** The datapacks {@code world}'s {@code level.dat} enables, lowest first. Blocking. */
    static List<String> saved(Path world) throws IOException {
        return strings(dataPacks(LevelDat.read(LevelDat.file(world)).tag()), "Enabled");
    }

    private static NbtData.CompoundTag dataPacks(NbtData.CompoundTag root) {
        return root.entries().get("Data") instanceof NbtData.CompoundTag data
                && data.entries().get("DataPacks") instanceof NbtData.CompoundTag packs ? packs : null;
    }

    private static List<String> strings(NbtData.CompoundTag parent, String key) {
        List<String> values = new ArrayList<>();
        if (parent != null && parent.entries().get(key) instanceof NbtData.ListTag list) {
            for (NbtData.Tag item : list.items()) {
                if (item instanceof NbtData.StringTag text) values.add(text.value());
            }
        }
        return values;
    }

    private static NbtData.ListTag list(List<String> values) {
        return new NbtData.ListTag(values.stream().<NbtData.Tag>map(NbtData.StringTag::new).toList());
    }
}
