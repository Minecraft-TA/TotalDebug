package com.github.minecraft_ta.totalDebugCompanion.pack;

import com.github.minecraft_ta.totalDebugCompanion.catalog.ConfigChanges;
import com.github.minecraft_ta.totalDebugCompanion.catalog.LevelDat;
import com.github.minecraft_ta.totalDebugCompanion.catalog.PackFolders;
import com.github.minecraft_ta.totalDebugCompanion.change.ChangePipeline;
import com.github.minecraft_ta.totalDebugCompanion.game.Access;
import com.github.minecraft_ta.totalDebugCompanion.game.GameLocation;
import com.github.minecraft_ta.totalDebugCompanion.game.GameState;
import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;
import com.github.minecraft_ta.totaldebug.protocol.message.PackStackPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.SetPacksPayload;
import com.github.minecraft_ta.totaldebug.protocol.nbt.NbtData;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.IOException;
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
import java.util.function.Supplier;

/**
 * Enables and orders the resource packs or a world's datapacks, by the rules of {@code docs/RESOURCE_EDITING.md}: in the
 * connected game, which reloads what that needs, or in {@code options.txt} or the world's {@code level.dat} where no game
 * would write them over. Each change is recorded as a {@link ChangeRecord.PackSelection} and can be reverted. The resource
 * packs are a category of the change pipeline ({@link ResourcePackSelection}).
 */
public final class PackSelections {
    /** Where a selection was written, and when the game uses it. */
    public record Applied(ConfigChanges.Effect effect) {
    }

    private final GameLocation location;
    private final Path workspace;
    private final ChangeRecord record;
    private final ResourceEdits edits;
    private final Executor writes;
    private final ChangePipeline pipeline;
    private final ResourcePackSelection resourcePacks;

    /** {@code edits} talks to the connected game of its location, and {@code writes} is the project's write queue. */
    public PackSelections(ChangeRecord record, ResourceEdits edits, Executor writes) {
        this.location = edits.location();
        this.workspace = this.location.workspace();
        this.record = Objects.requireNonNull(record, "record");
        this.edits = Objects.requireNonNull(edits, "edits");
        this.writes = Objects.requireNonNull(writes, "writes");
        this.pipeline = edits.pipeline();
        this.resourcePacks = new ResourcePackSelection(options());
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
        // Enabling what the view shows replaces whatever the game or the file holds, as the game's pack screen does.
        if (side == SetPacksPayload.Side.RESOURCES) return resourcePacks(new ChangePipeline.Edit<>(target, null, List.copyOf(enabled)));
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
        if (target.side() == SetPacksPayload.Side.RESOURCES) {
            return resourcePacks(new ChangePipeline.Edit<>(target, change.current(), parse(change.original())));
        }
        return apply(target, parse(change.original()), change);
    }

    /**
     * Changes the resource packs through the pipeline: the connected game answers once its resources reloaded with them, a
     * closed game uses them when it next starts.
     */
    private CompletableFuture<Applied> resourcePacks(ChangePipeline.Edit<ChangeRecord.PackSelection, List<String>> edit) {
        return this.pipeline.change(this.resourcePacks, List.of(edit)).thenApply(outcome ->
                new Applied(outcome.live() ? ConfigChanges.Effect.NOW : ConfigChanges.Effect.GAME_STARTS));
    }

    /** Whether the selection is still what Companion last enabled for {@code change}. Blocking. */
    public boolean holds(ChangeRecord.Change change) {
        if (!(change.target() instanceof ChangeRecord.PackSelection target)) return true;
        try {
            List<String> current = current(this.location.read(), target);
            List<String> recorded = parse(change.current());
            return target.side() == SetPacksPayload.Side.RESOURCES ? current.equals(recorded) : comparable(current).equals(comparable(recorded));
        } catch (IOException unreadable) {
            return true;
        }
    }

    /** What a change reads before it writes: the selection before it, and the connection that applies it, or null. */
    private record Read(List<String> previous, GameLocation.Connection live) {
    }

    private CompletableFuture<Applied> apply(ChangeRecord.PackSelection target, List<String> enabled, ChangeRecord.Change reverting) {
        // Where the game is and the selection before are read in the write queue, after every change queued before this
        // one, and never on the Swing thread.
        CompletableFuture<Read> before = write(() -> {
            try {
                GameState game = this.location.read();
                GameLocation.Connection live = live(game, target);
                List<String> previous = current(game, target);
                if (reverting != null && !comparable(previous).equals(comparable(parse(reverting.current())))
                        && !comparable(previous).equals(comparable(enabled))) {
                    throw new IOException(label(target) + " changed outside Companion since, and reverting would replace that");
                }
                if (live == null) {
                    writeLevel(target.location(), enabled);
                    this.record.changed(target, json(previous), json(enabled));
                }
                return new Read(previous, live);
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        });
        return before.thenCompose(read -> {
            if (read.live() == null) return CompletableFuture.completedFuture(new Applied(ConfigChanges.Effect.WORLD_OPENS));
            return this.edits.select(read.live(), target.location(), enabled).thenApply(result -> {
                if (!result.error().isEmpty()) throw new CompletionException(new IOException(result.error()));
                this.record.changed(target, json(read.previous()), json(enabled));
                return new Applied(ConfigChanges.Effect.NOW);
            });
        });
    }

    /**
     * The connection of the game that applies a datapack selection, or null where {@code level.dat} is written; fails
     * where a running game would write it over: the world open in a game Companion is not connected to or in another
     * program. Blocking.
     */
    private static GameLocation.Connection live(GameState game, ChangeRecord.PackSelection target) throws IOException {
        return switch (game.world(target.location(), "change its datapacks")) {
            case Access.Live live -> live.connection();
            case Access.Files ignored -> null;
            case Access.Refused refused -> throw new IOException(refused.reason());
        };
    }

    /** The enabled packs, lowest first: as the connected game names them, or as the file keeps them. Blocking. */
    List<String> current(GameState game, ChangeRecord.PackSelection target) throws IOException {
        if (target.side() == SetPacksPayload.Side.RESOURCES) {
            PackStackPayload stack = this.edits.packs().resourcePacks();
            if (stack != null) {
                // As options.txt keeps them: without the parts of another pack or the packs fixed in place.
                return stack.enabled().stream().filter(pack -> !pack.is(PackStackPayload.HIDDEN) && !pack.is(PackStackPayload.FIXED))
                        .map(PackStackPayload.Pack::id).toList();
            }
            return ResourcePackSelection.asTheGameKeepsIt(PackResources.enabledInOptions(options()));
        }
        PackStackPayload datapacks = this.edits.packs().datapacks();
        if (datapacks != null && game.plays(target.location())) {
            return datapacks.enabled().stream().filter(pack -> !pack.is(PackStackPayload.HIDDEN)).map(PackStackPayload.Pack::id).toList();
        }
        NbtData.CompoundTag packs = dataPacks(LevelDat.read(LevelDat.file(target.location())).tag());
        return strings(packs, "Enabled");
    }

    /**
     * Writes {@code enabled} as the world's datapacks into its {@code level.dat}, with every other pack of the world
     * disabled, so the game does not enable it again as a new one.
     */
    private static void writeLevel(Path world, List<String> enabled) throws IOException {
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

    /**
     * The ids a comparison of two datapack selections looks at: the parts of the mods' pack come and go with it, and the
     * game keeps them in {@code level.dat}.
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
        return "The datapacks of " + target.location().getFileName();
    }

    private <T> CompletableFuture<T> write(Supplier<T> write) {
        try {
            return CompletableFuture.supplyAsync(write, this.writes);
        } catch (RejectedExecutionException closed) {
            return CompletableFuture.failedFuture(new IOException("The project is closing; the change was not written"));
        }
    }
}
