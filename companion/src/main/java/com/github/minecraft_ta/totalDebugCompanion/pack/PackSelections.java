package com.github.minecraft_ta.totalDebugCompanion.pack;

import com.github.minecraft_ta.totalDebugCompanion.catalog.ConfigChanges;
import com.github.minecraft_ta.totalDebugCompanion.catalog.LevelDat;
import com.github.minecraft_ta.totalDebugCompanion.catalog.PackFolders;
import com.github.minecraft_ta.totalDebugCompanion.catalog.Worlds;
import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;
import com.github.minecraft_ta.totaldebug.protocol.message.PackStackPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.SetPacksPayload;
import com.github.minecraft_ta.totaldebug.protocol.nbt.NbtData;
import com.github.minecraft_ta.totaldebug.storage.AtomicFiles;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Enables and orders the resource packs or a world's datapacks, by the rules of {@code docs/RESOURCE_EDITING.md}: in the
 * connected game, which reloads what that needs, or in {@code options.txt} or the world's {@code level.dat} where no game
 * would write them over. Each change is recorded as a {@link ChangeRecord.PackSelection} and can be reverted.
 */
public final class PackSelections {
    /** Where a selection was written, and when the game uses it. */
    public record Applied(ConfigChanges.Effect effect) {
    }

    private final Path workspace;
    private final ChangeRecord record;
    private final ResourceEdits edits;
    private final BooleanSupplier gameRunning;
    private final Executor writes;

    /**
     * {@code workspace} is the game directory, {@code edits} talks to the connected game, {@code gameRunning} tells
     * whether a game runs in the instance, connected or not, and {@code writes} is the project's write queue.
     */
    public PackSelections(Path workspace, ChangeRecord record, ResourceEdits edits, BooleanSupplier gameRunning, Executor writes) {
        this.workspace = Objects.requireNonNull(workspace, "workspace").toAbsolutePath().normalize();
        this.record = Objects.requireNonNull(record, "record");
        this.edits = Objects.requireNonNull(edits, "edits");
        this.gameRunning = Objects.requireNonNull(gameRunning, "gameRunning");
        this.writes = Objects.requireNonNull(writes, "writes");
    }

    /** The record's target for the resource packs, or for {@code world}'s datapacks. */
    public ChangeRecord.PackSelection target(SetPacksPayload.Side side, Path world) {
        return new ChangeRecord.PackSelection(side, side == SetPacksPayload.Side.RESOURCES ? options() : Objects.requireNonNull(world, "world"));
    }

    /**
     * Enables exactly {@code enabled}, lowest first, among the resource packs, or among {@code world}'s datapacks for the
     * data side, and records the change.
     */
    public CompletableFuture<Applied> set(SetPacksPayload.Side side, Path world, List<String> enabled) {
        ChangeRecord.PackSelection target = target(side, world);
        return apply(target, List.copyOf(enabled), null);
    }

    /**
     * Enables what the selection held before Companion first changed it. A selection changed since outside Companion,
     * such as in the game's pack screen, is left alone, unless it is the original again, which ends the change.
     */
    public CompletableFuture<Applied> revert(ChangeRecord.Change change) {
        if (!(change.target() instanceof ChangeRecord.PackSelection target)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Not a pack selection"));
        }
        return apply(target, parse(change.original()), change);
    }

    /** Whether the selection is still what Companion last enabled for {@code change}. Blocking. */
    public boolean holds(ChangeRecord.Change change) {
        if (!(change.target() instanceof ChangeRecord.PackSelection target)) return true;
        try {
            return comparable(current(target)).equals(comparable(parse(change.current())));
        } catch (IOException unreadable) {
            return true;
        }
    }

    private CompletableFuture<Applied> apply(ChangeRecord.PackSelection target, List<String> enabled, ChangeRecord.Change reverting) {
        // The selection before is read in the write queue, after every change queued before this one, and so is whether a
        // world is open, which reads its lock: never on the Swing thread.
        CompletableFuture<Read> before = write(() -> {
            try {
                boolean live = live(target);
                List<String> previous = current(target);
                if (reverting != null && !comparable(previous).equals(comparable(parse(reverting.current())))
                        && !comparable(previous).equals(comparable(enabled))) {
                    throw new IOException(label(target) + " changed outside Companion since, and reverting would replace that");
                }
                if (!live) {
                    if (target.side() == SetPacksPayload.Side.RESOURCES) writeOptions(previous, enabled);
                    else writeLevel(target.location(), enabled);
                    this.record.changed(target, json(previous), json(enabled));
                }
                return new Read(previous, live);
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        });
        return before.thenCompose(read -> {
            if (!read.live()) {
                return CompletableFuture.completedFuture(new Applied(target.side() == SetPacksPayload.Side.RESOURCES
                        ? ConfigChanges.Effect.GAME_STARTS : ConfigChanges.Effect.WORLD_OPENS));
            }
            return this.edits.select(target.side(), enabled).thenApply(result -> {
                if (!result.error().isEmpty()) throw new CompletionException(new IOException(result.error()));
                this.record.changed(target, json(read.previous()), json(enabled));
                return new Applied(ConfigChanges.Effect.NOW);
            });
        });
    }

    /** What a change reads before it writes: the selection, and whether the connected game applies it. */
    private record Read(List<String> previous, boolean live) {
    }

    /**
     * Whether the connected game applies the selection; fails where a running game would write it over: a game running
     * without a connection, or a world open in a game Companion is not connected to.
     */
    private boolean live(ChangeRecord.PackSelection target) throws IOException {
        PackStackPayload stack = this.edits.packStack();
        if (target.side() == SetPacksPayload.Side.RESOURCES) {
            if (stack != null) return true;
            if (this.gameRunning.getAsBoolean()) {
                throw new IOException("The game is running but not connected to Companion; connect it, or close it, to change its resource packs");
            }
            return false;
        }
        if (!Worlds.isOpen(target.location())) return false;
        if (stack != null && !stack.dataPacks().isEmpty()) return true;
        throw new IOException("The world " + target.location().getFileName()
                + " is open in a game that is not connected to Companion; connect it, or close the world, to change its datapacks");
    }

    /** The enabled packs, lowest first: as the connected game names them, or as the file keeps them. Blocking. */
    List<String> current(ChangeRecord.PackSelection target) throws IOException {
        PackStackPayload stack = this.edits.packStack();
        if (target.side() == SetPacksPayload.Side.RESOURCES) {
            if (stack != null) {
                // As options.txt keeps them: without the parts of another pack or the packs fixed in place.
                return stack.resourcePacks().stream().filter(pack -> !pack.is(PackStackPayload.HIDDEN) && !pack.is(PackStackPayload.FIXED))
                        .map(PackStackPayload.Pack::id).toList();
            }
            return PackResources.enabledInOptions(options());
        }
        if (Worlds.isOpen(target.location()) && stack != null && !stack.dataPacks().isEmpty()) {
            return stack.dataPacks().stream().filter(pack -> !pack.is(PackStackPayload.HIDDEN)).map(PackStackPayload.Pack::id).toList();
        }
        NbtData.CompoundTag packs = dataPacks(LevelDat.read(LevelDat.file(target.location())).tag());
        return strings(packs, "Enabled");
    }

    /**
     * Writes {@code enabled} as {@code options.txt}'s resource packs. A pack enabled here is also listed as incompatible,
     * which the game drops again for a compatible pack, so it keeps a pack made for another version instead of removing it.
     */
    private void writeOptions(List<String> previous, List<String> enabled) throws IOException {
        Path options = options();
        List<String> lines = Files.isRegularFile(options) ? new ArrayList<>(Files.readAllLines(options, StandardCharsets.UTF_8)) : new ArrayList<>();
        Set<String> incompatible = new LinkedHashSet<>(listed(lines, "incompatibleResourcePacks:"));
        incompatible.retainAll(enabled);
        for (String id : enabled) {
            if (!previous.contains(id) && id.startsWith("file/")) incompatible.add(id);
        }
        List<String> written = enabled.stream().filter(id -> !id.startsWith("mod/")).toList();
        put(lines, "resourcePacks:", written);
        put(lines, "incompatibleResourcePacks:", List.copyOf(incompatible));
        AtomicFiles.writeString(options, String.join("\n", lines) + "\n");
    }

    /**
     * Writes {@code enabled} as the world's datapacks into its {@code level.dat}, with every other pack of the world
     * disabled, so the game does not enable it again as a new one.
     */
    private static void writeLevel(Path world, List<String> enabled) throws IOException {
        LevelDat.update(world, root -> written(world, root, enabled));
    }

    /** {@code root} with {@code enabled} as its world's datapacks. */
    private static LevelDat.Root written(Path world, LevelDat.Root root, List<String> enabled) throws IOException {
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
        String line = prefix + json(ids);
        for (int index = 0; index < lines.size(); index++) {
            if (lines.get(index).startsWith(prefix)) {
                lines.set(index, line);
                return;
            }
        }
        lines.add(line);
    }

    /**
     * The ids a comparison of two selections looks at: the parts of the mods' pack come and go with it, and the game
     * keeps them in {@code level.dat} but not in {@code options.txt}.
     */
    private static List<String> comparable(List<String> ids) {
        return ids.stream().filter(id -> !id.startsWith("mod/")).toList();
    }

    static String json(List<String> ids) {
        JsonArray array = new JsonArray();
        ids.forEach(array::add);
        return array.toString();
    }

    /** The ids of a recorded selection value, lowest first. */
    public static List<String> parse(String json) {
        List<String> ids = new ArrayList<>();
        for (JsonElement id : JsonParser.parseString(json).getAsJsonArray()) ids.add(id.getAsString());
        return ids;
    }

    private Path options() {
        return this.workspace.resolve("options.txt");
    }

    private static String label(ChangeRecord.PackSelection target) {
        return target.side() == SetPacksPayload.Side.RESOURCES ? "The resource packs"
                : "The datapacks of " + target.location().getFileName();
    }

    private <T> CompletableFuture<T> write(Supplier<T> write) {
        try {
            return CompletableFuture.supplyAsync(write, this.writes);
        } catch (RejectedExecutionException closed) {
            return CompletableFuture.failedFuture(new IOException("The project is closing; the change was not written"));
        }
    }
}
